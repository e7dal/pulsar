/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.service.persistent;


import com.google.common.base.MoreObjects;
import org.apache.pulsar.broker.service.BrokerService;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.policies.data.Policies;
import org.apache.pulsar.common.policies.data.SubscribeRate;
import org.apache.pulsar.common.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.pulsar.broker.cache.ConfigurationCacheService.POLICIES;
import static org.apache.pulsar.broker.web.PulsarWebResource.path;
import static org.apache.pulsar.zookeeper.ZooKeeperCache.cacheTimeOutInSec;

public class SubscribeRateLimiter {

    private final String topicName;
    private final BrokerService brokerService;
    private ConcurrentHashMap<ConsumerIdentifier, RateLimiter> subscribeRateLimiter;
    private final ScheduledExecutorService executorService;
    private ScheduledFuture<?> resetTask;
    private SubscribeRate subscribeRate;

    public SubscribeRateLimiter(PersistentTopic topic) {
        this.topicName = topic.getName();
        this.brokerService = topic.getBrokerService();
        subscribeRateLimiter = new ConcurrentHashMap<>();
        this.executorService = brokerService.pulsar().getExecutor();
        this.subscribeRate = getPoliciesSubscribeRate();
        if (this.subscribeRate == null) {
            this.subscribeRate = new SubscribeRate(brokerService.pulsar().getConfiguration().getSubscribeThrottlingRatePerConsumer(),
                    brokerService.pulsar().getConfiguration().getSubscribeRatePeriodPerConsumerInSecond());

        }
        if (isSubscribeRateEnabled(this.subscribeRate)) {
            resetTask = createTask();
            log.info("[{}] [{}] configured subscribe-dispatch rate at broker {}", this.topicName, subscribeRate);
        }
    }

    /**
     * returns available subscribes if subscribe-throttling is enabled else it returns -1
     *
     * @return
     */
    public long getAvailableSubscribeRateLimit(ConsumerIdentifier consumerIdentifier) {
        return subscribeRateLimiter.get(consumerIdentifier) == null ? -1 : subscribeRateLimiter.get(consumerIdentifier).getAvailablePermits();
    }

    /**
     * It acquires subscribe from subscribe-limiter and returns if acquired permits succeed.
     *
     * @return
     */
    public synchronized boolean tryAcquire(ConsumerIdentifier consumerIdentifier) {
        addSubscribeLimiterIfAbsent(consumerIdentifier);
        return subscribeRateLimiter.get(consumerIdentifier) == null || subscribeRateLimiter.get(consumerIdentifier).tryAcquire();
    }

    /**
     * checks if subscribe-rate limit is configured and if it's configured then check if subscribe are available or not.
     *
     * @return
     */
    public boolean subscribeAvailable(ConsumerIdentifier consumerIdentifier) {
        return (subscribeRateLimiter.get(consumerIdentifier) == null|| subscribeRateLimiter.get(consumerIdentifier).getAvailablePermits() > 0);
    }

    /**
     * Update subscribe-throttling-rate. gives first priority to namespace-policy configured subscribe rate else applies
     * default broker subscribe-throttling-rate
     */
    private synchronized void addSubscribeLimiterIfAbsent(ConsumerIdentifier consumerIdentifier) {
        if (subscribeRateLimiter.get(consumerIdentifier) != null) {
            return;
        }
        updateSubscribeRate(consumerIdentifier, this.subscribeRate);
    }

    private synchronized void removeSubscribeLimiter(ConsumerIdentifier consumerIdentifier) {
        if (this.subscribeRateLimiter.get(consumerIdentifier) != null) {
            this.subscribeRateLimiter.get(consumerIdentifier).close();
            this.subscribeRateLimiter.remove(consumerIdentifier);
        }
    }

    /**
     * Update subscribe rate by updating rate-limiter. If subscribe-rate is configured < 0 then it closes
     * the rate-limiter and disables appropriate rate-limiter.
     *
     * @param subscribeRate
     */
    private synchronized void updateSubscribeRate(ConsumerIdentifier consumerIdentifier, SubscribeRate subscribeRate) {

        long ratePerConsumer = subscribeRate.subscribeThrottlingRatePerConsumer;
        long ratePeriod = subscribeRate.ratePeriodInSecond;

        // update subscribe-rateLimiter
        if (ratePerConsumer > 0) {
            if (this.subscribeRateLimiter.get(consumerIdentifier) == null) {
                this.subscribeRateLimiter.put(consumerIdentifier, new RateLimiter(brokerService.pulsar().getExecutor(), ratePerConsumer,
                        ratePeriod, TimeUnit.SECONDS));
            } else {
                this.subscribeRateLimiter.get(consumerIdentifier).setRate(ratePerConsumer, ratePeriod, TimeUnit.SECONDS);
            }
        } else {
            // subscribe-rate should be disable and close
            removeSubscribeLimiter(consumerIdentifier);
        }
    }

    public void onPoliciesUpdate(Policies data) {

        String cluster = brokerService.pulsar().getConfiguration().getClusterName();

        SubscribeRate subscribeRate = data.clusterSubscribeRate.get(cluster);

        // update dispatch-rate only if it's configured in policies else ignore
        if (subscribeRate != null) {
            final SubscribeRate newSubscribeRate = new SubscribeRate(
                    brokerService.pulsar().getConfiguration().getSubscribeThrottlingRatePerConsumer(),
                    brokerService.pulsar().getConfiguration().getSubscribeRatePeriodPerConsumerInSecond()
                    );
            // if policy-throttling rate is disabled and cluster-throttling is enabled then apply
            // cluster-throttling rate
            if (!isSubscribeRateEnabled(subscribeRate) && isSubscribeRateEnabled(newSubscribeRate)) {
                subscribeRate = newSubscribeRate;
            }
            this.subscribeRate = subscribeRate;
            stopResetTask();
            for (ConsumerIdentifier consumerIdentifier : this.subscribeRateLimiter.keySet()) {
                updateSubscribeRate(consumerIdentifier, subscribeRate);
            }
            if (isSubscribeRateEnabled(this.subscribeRate)) {
                this.resetTask = createTask();
                log.info("[{}] [{}] configured subscribe-dispatch rate at broker {}", this.topicName, subscribeRate);
            }
        }
    }

    /**
     * Gets configured subscribe-rate from namespace policies. Returns null if subscribe-rate is not configured
     *
     * @return
     */
    public SubscribeRate getPoliciesSubscribeRate() {
        final NamespaceName namespace = TopicName.get(this.topicName).getNamespaceObject();
        final String cluster = brokerService.pulsar().getConfiguration().getClusterName();
        final String path = path(POLICIES, namespace.toString());
        Optional<Policies> policies = Optional.empty();
        try {
            policies = brokerService.pulsar().getConfigurationCache().policiesCache().getAsync(path)
                    .get(cacheTimeOutInSec, SECONDS);
        } catch (Exception e) {
            log.warn("Failed to get subscribe-rate for {}", this.topicName, e);
        }
        // return policy-subscribe rate only if it's enabled in policies
        return policies.map(p -> {
            if (p.clusterSubscribeRate != null) {
                SubscribeRate subscribeRate = p.clusterSubscribeRate.get(cluster);
                return isSubscribeRateEnabled(subscribeRate) ? subscribeRate : null;
            } else {
                return null;
            }

        }).orElse(null);
    }

    /**
     * Get configured msg subscribe-throttling rate. Returns -1 if not configured
     *
     * @return
     */
    public long getSubscribeRatePerConsumer(ConsumerIdentifier consumerIdentifier) {
        return subscribeRateLimiter.get(consumerIdentifier) != null ? subscribeRateLimiter.get(consumerIdentifier).getRate() : -1;
    }

    private boolean isSubscribeRateEnabled(SubscribeRate subscribeRate) {
        return subscribeRate != null && (subscribeRate.subscribeThrottlingRatePerConsumer > 0);
    }

    public void close() {
        closeAndClearRateLimiters();
        stopResetTask();
    }

    private ScheduledFuture<?> createTask() {
        return executorService.scheduleAtFixedRate(this::closeAndClearRateLimiters,
                this.subscribeRate.ratePeriodInSecond,
                this.subscribeRate.ratePeriodInSecond,
                TimeUnit.SECONDS);
    }

    private void stopResetTask() {
        if (this.resetTask != null) {
            this.resetTask.cancel(false);
        }
    }

    private synchronized void closeAndClearRateLimiters() {
        // close rate-limiter
        this.subscribeRateLimiter.values().forEach(rateLimiter -> {
            if (rateLimiter != null) {
                rateLimiter.close();
            }
        });
        this.subscribeRateLimiter.clear();
    }

    public SubscribeRate getSubscribeRate() {
        return subscribeRate;
    }

    public static class ConsumerIdentifier {

        private String host;

        private String consumerName;

        private long consumerId;

        public ConsumerIdentifier(String host, String consumerName, long consumerId) {
            this.host = host;
            this.consumerName = consumerName;
            this.consumerId = consumerId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(host, consumerName, consumerId);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof ConsumerIdentifier) {
                ConsumerIdentifier consumer = (ConsumerIdentifier) obj;
                return Objects.equals(host, consumer.host)
                        && Objects.equals(consumerName, consumer.consumerName)
                        && Objects.equals(consumerId, consumer.consumerId);
            }
            return false;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("host", host)
                    .add("consumerName", consumerName)
                    .add("consumerId", consumerId).toString();
        }
    }

    private static final Logger log = LoggerFactory.getLogger(SubscribeRateLimiter.class);
}

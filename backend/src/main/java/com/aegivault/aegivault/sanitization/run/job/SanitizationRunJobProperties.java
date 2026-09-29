package com.aegivault.aegivault.sanitization.run.job;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bounded worker-pool settings for asynchronous sanitization runs:
 * {@code aegivault.sanitization.jobs.pool-size} and
 * {@code aegivault.sanitization.jobs.queue-capacity}, with
 * {@code aegivault.sanitization.jobs.max-pool-size} bounding the pool when it
 * grows.
 *
 * <p><strong>Every bound is explicit and has a safe local default.</strong> The
 * launcher must never be able to grow without limit, so the queue is finite and
 * the pool is finite: a submission beyond either bound is refused rather than
 * buffered, and the refusal is reported to the caller instead of being run on
 * the caller's thread. Defaults are deliberately small — local development
 * should not reserve a large pool for background CSV work.
 *
 * <p>These values are read in exactly one place (the job configuration) so
 * they are never hard-coded twice, and a non-positive value fails while this
 * properties bean is bound rather than producing a silently broken pool.
 */
@Component
@ConfigurationProperties(prefix = "aegivault.sanitization.jobs")
public class SanitizationRunJobProperties {

    /** Concurrent workers when demand is steady. */
    private int poolSize = 2;

    /** Upper bound on workers, used when the queue is already full. */
    private int maxPoolSize = 4;

    /** Run submissions that may wait for a free worker. */
    private int queueCapacity = 50;

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }
}

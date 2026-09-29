package com.aegivault.aegivault.sanitization.run.job;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Wires the single bounded {@link TaskExecutor} asynchronous sanitization runs
 * are submitted to, from {@link SanitizationRunJobProperties}.
 *
 * <p><strong>Bounded on purpose.</strong> Both the worker count and the
 * submission queue have explicit finite bounds, so a burst of run submissions
 * cannot create unbounded threads or unbounded buffered work. Everything else
 * — queueing semantics, thread naming, pool lifetime — comes from Spring's
 * own {@link ThreadPoolTaskExecutor}, not from hand-rolled threading: there is
 * no {@code new Thread(...)}, no thread per request, and no use of the common
 * fork-join pool.
 *
 * <p><strong>Refusal is the back-pressure signal.</strong> The pool keeps
 * Spring's default abort policy, so a submission past the bounds is rejected
 * and reported to the caller as {@link SanitizationRunJobLaunchException}. It
 * is deliberately <em>not</em> a caller-runs policy: silently executing the
 * work on the calling (HTTP) thread would make "asynchronous" untrue and would
 * turn back-pressure into request latency.
 *
 * <p>The bean is named and typed specifically, and the launcher injects it by
 * name, so background sanitization can never accidentally run on a different
 * executor that happens to be in the context.
 */
@Configuration(proxyBeanMethods = false)
public class SanitizationRunJobConfiguration {

    /** Prefixes worker thread names so a stack dump is self-describing. */
    static final String THREAD_NAME_PREFIX = "sanitization-run-";

    @Bean(name = "sanitizationRunJobExecutor")
    TaskExecutor sanitizationRunJobExecutor(SanitizationRunJobProperties properties) {
        int poolSize = requirePositive(properties.getPoolSize(), "pool-size");
        int maxPoolSize = requirePositive(properties.getMaxPoolSize(), "max-pool-size");
        int queueCapacity = requirePositive(properties.getQueueCapacity(), "queue-capacity");
        if (maxPoolSize < poolSize) {
            throw new IllegalStateException(
                    "aegivault.sanitization.jobs.max-pool-size must be at least pool-size");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
        // Fail fast on startup if the pool cannot be created, rather than
        // discovering it on the first run submission.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    private static int requirePositive(int value, String property) {
        if (value <= 0) {
            throw new IllegalStateException(
                    "aegivault.sanitization.jobs." + property + " must be a positive number");
        }
        return value;
    }
}

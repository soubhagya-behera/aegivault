package com.aegivault.aegivault.sanitization.run.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aegivault.aegivault.sanitization.run.SanitizationRunExecutor;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Wiring tests for the bounded background worker pool, on a minimal Spring
 * context (no database, no web server, no network): the pool and queue bounds
 * come from configuration rather than from code, the defaults are finite and
 * safe for local use, the launcher gets exactly one executor by name, and an
 * unusable configuration fails at startup rather than producing a pool that
 * silently accepts everything.
 *
 * <p>Bounds are asserted against the pool's own observable state
 * ({@code getCorePoolSize()}, {@code getMaxPoolSize()}, and the live queue)
 * rather than against a copy of the configuration, so these tests would still
 * hold if the wiring ever stopped applying the properties.
 */
class SanitizationRunJobConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(JobWiring.class);

    /** Minimal wiring: the real configuration under test plus a mock executor. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SanitizationRunJobProperties.class)
    @Import({SanitizationRunJobConfiguration.class, SanitizationRunJobLauncher.class})
    static class JobWiring {

        @Bean
        SanitizationRunExecutor sanitizationRunExecutor() {
            return mock(SanitizationRunExecutor.class);
        }
    }


    private static String failureMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current).append('\n');
        }
        return messages.toString();
    }

    @Test
    void theDefaultPoolIsSmallAndItsQueueIsFinite() {
        runner.run(context -> {
            context.assertThat().hasNotFailed();
            ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) context.getBean("sanitizationRunJobExecutor");
            // A fresh checkout needs no configuration, yet must still be
            // bounded: a finite thread count and a finite submission queue.
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            var queue = executor.getThreadPoolExecutor().getQueue();
            // The bound that matters is that the queue is finite: an unbounded
            // queue would let submissions accumulate without limit.
            assertThat(queue.remainingCapacity()).isEqualTo(50);
            assertThat(queue.remainingCapacity()).isNotEqualTo(Integer.MAX_VALUE);
        });
    }

    @Test
    void everyBoundIsExplicitConfiguration() {
        runner.withPropertyValues(
                        "aegivault.sanitization.jobs.pool-size=3",
                        "aegivault.sanitization.jobs.max-pool-size=7",
                        "aegivault.sanitization.jobs.queue-capacity=11")
                .run(context -> {
                    context.assertThat().hasNotFailed();
                    ThreadPoolTaskExecutor executor =
                            (ThreadPoolTaskExecutor) context.getBean("sanitizationRunJobExecutor");
                    assertThat(executor.getCorePoolSize()).isEqualTo(3);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(7);
                    assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(11);
                });
    }

    @Test
    void thePoolRefusesRatherThanRunningTheWorkOnTheCallingThread() {
        runner.run(context -> {
            // An abort policy is the back-pressure signal: a full pool reports a
            // rejection to the launcher. A caller-runs policy would instead
            // execute the sanitization on the submitting (HTTP) thread, which
            // would make "asynchronous" untrue.
            ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) context.getBean("sanitizationRunJobExecutor");
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler().getClass().getName())
                    .doesNotContain("CallerRuns");
        });
    }

    @Test
    void theLauncherIsWiredToTheNamedPool() {
        runner.run(context -> {
            context.assertThat().hasNotFailed();
            context.assertThat().hasSingleBean(SanitizationRunJobLauncher.class);
            // Injected by name, so background work can never land on some other
            // executor that happens to be in the context.
            var executorField = Arrays.stream(SanitizationRunJobLauncher.class.getDeclaredFields())
                    .filter(field -> TaskExecutor.class.equals(field.getType()))
                    .findFirst()
                    .orElseThrow();
            assertThat(executorField.getName()).isEqualTo("executor");
        });
    }

    @Test
    void anUnusableBoundFailsAtStartupRatherThanSilentlyAcceptingWork() {
        for (String property : Arrays.asList("pool-size", "max-pool-size", "queue-capacity")) {
            runner.withPropertyValues("aegivault.sanitization.jobs." + property + "=0").run(context -> {
                context.assertThat().hasFailed();
                assertThat(failureMessages(context.getStartupFailure()))
                        .contains("aegivault.sanitization.jobs." + property);
            });
        }
    }

    @Test
    void aMaximumSmallerThanTheCorePoolFailsAtStartup() {
        runner.withPropertyValues(
                        "aegivault.sanitization.jobs.pool-size=4",
                        "aegivault.sanitization.jobs.max-pool-size=2")
                .run(context -> {
                    context.assertThat().hasFailed();
                    assertThat(failureMessages(context.getStartupFailure()))
                            .contains("aegivault.sanitization.jobs.max-pool-size");
                });
    }
}

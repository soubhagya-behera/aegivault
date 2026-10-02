package com.aegivault.aegivault.dataset.postgres.sanitization;

import static org.assertj.core.api.Assertions.assertThat;

import com.aegivault.aegivault.dataset.DatasetInputSource;
import com.aegivault.aegivault.dataset.DatasetRepository;
import com.aegivault.aegivault.dataset.csv.CsvSanitizationService;
import com.aegivault.aegivault.dataset.postgres.PostgresDataSource;
import com.aegivault.aegivault.dataset.postgres.PostgresSchemaDiscoveryService;
import com.aegivault.aegivault.dataset.postgres.PostgresSourceConfiguration;
import com.aegivault.aegivault.dataset.postgres.PostgresSourceProperties;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingRepository;
import com.aegivault.aegivault.dataset.postgres.binding.PostgresDatasetBindingService;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.sanitization.DataSanitizationService;
import com.aegivault.aegivault.sanitization.artifact.SanitizationArtifactStore;
import com.aegivault.aegivault.sanitization.run.SanitizationRunExecutor;
import com.aegivault.aegivault.sanitization.run.SanitizationRunService;
import com.aegivault.aegivault.sanitization.run.SanitizationRunSourceDispatcher;
import com.aegivault.aegivault.sanitization.run.SanitizationSourceProvider;
import com.aegivault.aegivault.sanitization.run.SanitizationSourceType;
import com.aegivault.aegivault.sanitization.strategy.TransformationRegistry;
import com.aegivault.aegivault.sanitization.run.CsvSanitizationSources;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Production bean-graph proof for the PostgreSQL run path: executor -&gt; source
 * provider -&gt; {@link PostgresSanitizationRunService} -&gt;
 * {@link PostgresDatasetSanitizationService}.
 *
 * <p><strong>Why this is separate from the async integration test.</strong> That
 * test proves execution behaviour, but it assembles the executor itself because
 * the suite's application context has no PostgreSQL source configured. This test
 * proves what that assembly hides: that the <em>real production beans</em> wire
 * into each other, and specifically that the executor and the PostgreSQL service
 * — which mutually need each other — start without a circular-bean failure.
 * A manual assembly cannot demonstrate that, because it bypasses the cycle.
 *
 * <p><strong>No database is contacted.</strong> The source is configured with
 * synthetic properties and nothing connects: {@code PostgresDataSource} opens a
 * connection only when discovery or a row read asks it to, so this proves the
 * graph, not I/O.
 *
 * <p>A minimal context, not a new {@code @SpringBootTest}: collaborators that
 * would need a database are supplied as mocks, so this stays fast and cannot be
 * mistaken for an integration test.
 */
class PostgresSanitizationRunWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RunWiring.class)
            .withPropertyValues(
                    "aegivault.dataset.postgres.host=postgres.invalid",
                    "aegivault.dataset.postgres.port=5432",
                    "aegivault.dataset.postgres.database=aegivault",
                    "aegivault.dataset.postgres.username=aegivault_readonly");

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PostgresSourceProperties.class)
    @Import({
            PostgresSourceConfiguration.class,
            // The real run-path components, imported explicitly because this
            // minimal context does not component-scan.
            SanitizationRunExecutor.class,
            SanitizationRunSourceDispatcher.class,
            CsvSanitizationSources.class,
            PostgresSanitizationRunService.class,
            PostgresDatasetSanitizationService.class,
            Collaborators.class
    })
    static class RunWiring {

        @Bean
        PostgresSchemaDiscoveryService discovery() {
            return new PostgresSchemaDiscoveryService();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    /**
     * Database-dependent collaborators, replaced with mocks so this context needs
     * no database. The beans under test — the source configuration, the run
     * service, the sanitization service, the binding service, the dispatcher, and
     * the executor — are the real production classes.
     */
    @Configuration(proxyBeanMethods = false)
    static class Collaborators {

        @Bean
        SanitizationRunService runs() {
            return org.mockito.Mockito.mock(SanitizationRunService.class);
        }

        @Bean
        CsvSanitizationService csv() {
            return org.mockito.Mockito.mock(CsvSanitizationService.class);
        }

        @Bean
        DatasetInputSource inputs() {
            return org.mockito.Mockito.mock(DatasetInputSource.class);
        }

        @Bean
        SanitizationArtifactStore artifacts() {
            return org.mockito.Mockito.mock(SanitizationArtifactStore.class);
        }

        @Bean
        com.aegivault.aegivault.audit.AuditLedgerService audit() {
            return org.mockito.Mockito.mock(com.aegivault.aegivault.audit.AuditLedgerService.class);
        }

        @Bean
        PiiDetectorRegistry detectors() {
            return new PiiDetectorRegistry(List.of());
        }

        /**
         * The in-memory PII profiler the configured source's profiler bean builds
         * on. The application gets this by component scanning; this minimal
         * context does not scan, so it is declared explicitly.
         */
        @Bean
        com.aegivault.aegivault.pii.profile.PiiColumnProfiler piiColumnProfiler(
                PiiDetectorRegistry detectors) {
            return new com.aegivault.aegivault.pii.profile.PiiColumnProfiler(detectors);
        }

        @Bean
        TransformationRegistry transformationRegistry() {
            return new TransformationRegistry(List.of());
        }

        @Bean
        DataSanitizationService sanitizationEngine(TransformationRegistry registry) {
            return new DataSanitizationService(registry);
        }

        @Bean
        PostgresDatasetBindingService postgresDatasetBindingService(
                PostgresSchemaDiscoveryService discovery) {
            PostgresDataSource source = org.mockito.Mockito.mock(PostgresDataSource.class);
            org.mockito.Mockito.when(source.schemaName()).thenReturn("public");
            return new PostgresDatasetBindingService(
                    org.mockito.Mockito.mock(PostgresDatasetBindingRepository.class),
                    org.mockito.Mockito.mock(DatasetRepository.class),
                    discovery,
                    providerOf(source));
        }
    }

    @Test
    void theProductionExecutorWiresToThePostgresProviderWithoutACircularBeanFailure() {
        runner.run(context -> {
            // The whole point: the real executor and the real PostgreSQL service
            // mutually reference each other, and this context starts. A
            // BeanCurrentlyInCreationException would fail this assertion.
            context.assertThat().hasNotFailed();
            context.assertThat().hasSingleBean(SanitizationRunExecutor.class);
            context.assertThat().hasSingleBean(SanitizationRunSourceDispatcher.class);
            context.assertThat().hasSingleBean(PostgresDatasetSanitizationService.class);
        });
    }

    @Test
    void thePostgresProviderIsRegisteredInTheContextsProviderCollection() {
        runner.run(context -> {
            // Exactly the beans the dispatcher was constructed from, read the way
            // Spring itself sees them rather than through a test-only accessor.
            List<SanitizationSourceProvider> providers =
                    context.getBeansOfType(SanitizationSourceProvider.class)
                            .values().stream().toList();

            assertThat(providers).hasSize(1);
            SanitizationSourceProvider provider = providers.get(0);
            // The only entry is the real PostgreSQL run service, and it declares
            // the PostgreSQL source kind.
            assertThat(provider).isInstanceOf(PostgresSanitizationRunService.class);
            assertThat(provider.sourceType()).isEqualTo(SanitizationSourceType.POSTGRESQL);
        });
    }

    @Test
    void thePostgresProviderResolvesTheDeferredSanitizationService() {
        runner.run(context -> {
            PostgresSanitizationRunService provider =
                    context.getBean(PostgresSanitizationRunService.class);

            // The executor and the PostgreSQL service mutually need each other, so
            // the provider holds an ObjectProvider and resolves on demand. Calling
            // the real entry point the executor calls proves the deferred
            // reference resolves to the real service rather than failing
            // eagerly at construction. Building the content source touches no
            // database: the binding is resolved only when the source is invoked.
            assertThat(provider.contentFor(
                            "owner-1", java.util.UUID.randomUUID(), plan()))
                    .isNotNull();
        });
    }

    private static com.aegivault.aegivault.sanitization.TransformationPlan plan() {
        return com.aegivault.aegivault.sanitization.TransformationPlan.of(
                new com.aegivault.aegivault.sanitization.TransformationRule(
                        com.aegivault.aegivault.pii.PiiType.EMAIL,
                        com.aegivault.aegivault.sanitization.TransformationStrategy.REDACT));
    }

    @Test
    void thePostgresSourceIsWiredSoTheProviderCanActuallyRun() {
        runner.run(context -> {
            // The provider's content source resolves the real configured source;
            // only the object graph is exercised, no row is read.
            context.assertThat().hasSingleBean(PostgresDataSource.class);
            assertThat(context.getBean(PostgresDataSource.class).schemaName()).isEqualTo("public");
        });
    }
}
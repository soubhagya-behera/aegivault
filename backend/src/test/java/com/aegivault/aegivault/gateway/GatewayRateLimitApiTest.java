package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.audit.AuditLedgerEntryRepository;
import com.aegivault.aegivault.auth.RegisterRequest;
import com.aegivault.aegivault.gateway.provider.DefaultLlmProviderSelector;
import com.aegivault.aegivault.gateway.provider.LlmProvider;
import com.aegivault.aegivault.gateway.provider.LlmProviderSelector;
import com.aegivault.aegivault.gateway.provider.LlmRequest;
import com.aegivault.aegivault.gateway.provider.LlmResponse;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Rate limiting on {@code POST /api/gateway/complete} through the real
 * application path: the quota is enforced per JWT subject before
 * inspection, audit, provider selection, or provider invocation, and a
 * rejected request answers HTTP 429 with the safe message only. Time
 * comes from a mutable test clock (never {@code Thread.sleep}), and
 * call-counting wrappers prove what a 429 request never reaches.
 */
@SpringBootTest(properties = "aegivault.gateway.provider=MOCK")
@AutoConfigureMockMvc
class GatewayRateLimitApiTest {

    private static final String CLEAN = "summarize quarterly revenue trends for the board.";

    private static final String EMAIL = "alice@example.com";

    private static final Instant BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    /** Minimal mutable clock so window expiry is deterministic. */
    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void setInstant(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }
    }

    /** Counts request inspections while delegating to the real service. */
    static final class CountingInspections extends SecurityInspectionService {

        final AtomicInteger calls = new AtomicInteger();

        CountingInspections(PiiDetectorRegistry pii, SecretDetector secrets) {
            super(pii, secrets);
        }

        @Override
        public SecurityInspectionResult inspect(GatewayInspectionRequest request, GatewaySecurityPolicy policy) {
            calls.incrementAndGet();
            return super.inspect(request, policy);
        }
    }

    /** Counts provider selections while delegating to the real selector. */
    static final class CountingSelector implements LlmProviderSelector {

        final AtomicInteger calls = new AtomicInteger();

        private final LlmProviderSelector delegate;

        CountingSelector(LlmProviderSelector delegate) {
            this.delegate = delegate;
        }

        @Override
        public LlmProvider select(String model) {
            calls.incrementAndGet();
            return delegate.select(model);
        }
    }

    /** Counts provider invocations and answers a clean completion. */
    static final class CountingProvider implements LlmProvider {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public LlmResponse complete(LlmRequest request) {
            calls.incrementAndGet();
            return new LlmResponse(request.model(), "fake-completion for " + request.model());
        }
    }

    @TestConfiguration
    static class RateLimitTestConfig {

        @Bean
        MutableClock mutableClock() {
            return new MutableClock(BASE_INSTANT);
        }

        @Bean
        @Primary
        InMemoryGatewayRateLimiter testRateLimiter(MutableClock clock) {
            return new InMemoryGatewayRateLimiter(clock);
        }

        @Bean
        @Primary
        CountingInspections countingInspections(PiiDetectorRegistry pii, SecretDetector secrets) {
            return new CountingInspections(pii, secrets);
        }

        @Bean
        @Primary
        CountingSelector countingSelector(DefaultLlmProviderSelector delegate) {
            return new CountingSelector(delegate);
        }

        @Bean
        @Primary
        CountingProvider countingProvider() {
            return new CountingProvider();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Autowired
    private CountingInspections inspections;

    @Autowired
    private CountingSelector selector;

    @Autowired
    private CountingProvider provider;

    @Autowired
    private AuditLedgerEntryRepository ledger;

    @BeforeEach
    void resetClockAndCounters() {
        clock.setInstant(BASE_INSTANT);
        inspections.calls.set(0);
        selector.calls.set(0);
        provider.calls.set(0);
    }

    private String register() throws Exception {
        String body = objectMapper.writeValueAsString(
                new RegisterRequest("rate-limit-" + UUID.randomUUID() + "@example.com", "gateway-pass-1", null));
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private MvcResult complete(String token, String content) throws Exception {
        return mvc.perform(post("/api/gateway/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GatewayCompleteRequest("local-test-model", content))))
                .andReturn();
    }

    private void exhaustQuota(String token) throws Exception {
        for (int i = 0; i < InMemoryGatewayRateLimiter.MAX_REQUESTS; i++) {
            MvcResult result = complete(token, CLEAN);
            assertThat(result.getResponse().getStatus()).as("request %d", i + 1).isEqualTo(200);
        }
    }

    @Test
    void requestsBelowTheLimitBehaveAsBefore() throws Exception {
        String token = register();
        long ledgerBefore = ledger.count();

        for (int i = 0; i < 3; i++) {
            MvcResult result = complete(token, CLEAN);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("verdict").asText())
                    .isEqualTo("ALLOW");
        }

        assertThat(provider.calls.get()).isEqualTo(3);
        assertThat(selector.calls.get()).isEqualTo(3);
        assertThat(inspections.calls.get()).isEqualTo(3);
        assertThat(ledger.count()).isEqualTo(ledgerBefore + 3);
    }

    @Test
    void twentiethRequestIsAllowedAndTwentyFirstIsRejected() throws Exception {
        String token = register();

        exhaustQuota(token);

        MvcResult rejected = complete(token, CLEAN);
        assertThat(rejected.getResponse().getStatus()).isEqualTo(429);
        assertThat(objectMapper.readTree(rejected.getResponse().getContentAsString()).get("message").asText())
                .isEqualTo(GatewayRateLimitExceededException.MESSAGE);
        assertThat(provider.calls.get()).isEqualTo(InMemoryGatewayRateLimiter.MAX_REQUESTS);
    }

    @Test
    void rejectedActorStaysRejectedWithoutReachingAnything() throws Exception {
        String token = register();
        exhaustQuota(token);
        long ledgerBefore = ledger.count();
        int inspectionsBefore = inspections.calls.get();
        int selectionsBefore = selector.calls.get();
        int providerBefore = provider.calls.get();

        for (int i = 0; i < 3; i++) {
            MvcResult rejected = complete(token, CLEAN);
            assertThat(rejected.getResponse().getStatus()).as("extra request %d", i + 1).isEqualTo(429);
        }

        assertThat(inspections.calls.get()).isEqualTo(inspectionsBefore);
        assertThat(selector.calls.get()).isEqualTo(selectionsBefore);
        assertThat(provider.calls.get()).isEqualTo(providerBefore);
        assertThat(ledger.count()).isEqualTo(ledgerBefore);
    }

    @Test
    void rejectedResponseContainsOnlyTheSafeMessage() throws Exception {
        String token = register();
        exhaustQuota(token);

        MvcResult rejected = complete(token, CLEAN);

        assertThat(rejected.getResponse().getStatus()).isEqualTo(429);
        String body = rejected.getResponse().getContentAsString();
        Set<String> fields = new java.util.HashSet<>();
        objectMapper.readTree(body).propertyNames().forEach(fields::add);
        assertThat(fields).containsExactly("message");
        assertThat(objectMapper.readTree(body).get("message").asText())
                .isEqualTo("Gateway rate limit exceeded.");
        assertThat(body).doesNotContain(
                "actorSubject", "remaining", "counter", "quota", "retry", "provider", CLEAN, "local-test-model");
    }

    @Test
    void separateActorHasAnIndependentQuota() throws Exception {
        String exhausted = register();
        exhaustQuota(exhausted);
        assertThat(complete(exhausted, CLEAN).getResponse().getStatus()).isEqualTo(429);

        String fresh = register();

        MvcResult result = complete(fresh, CLEAN);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("verdict").asText())
                .isEqualTo("ALLOW");
    }

    @Test
    void quotaResetsAfterTheWindowExpires() throws Exception {
        String token = register();
        exhaustQuota(token);
        assertThat(complete(token, CLEAN).getResponse().getStatus()).isEqualTo(429);

        clock.advance(InMemoryGatewayRateLimiter.WINDOW.plusSeconds(1));

        MvcResult result = complete(token, CLEAN);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("verdict").asText())
                .isEqualTo("ALLOW");
    }

    @Test
    void securityBlockStillReturns200UnderTheLimit() throws Exception {
        String token = register();
        long ledgerBefore = ledger.count();

        MvcResult result = complete(token, "contact " + EMAIL + " for access.");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).get("verdict").asText()).isEqualTo("BLOCK");
        assertThat(objectMapper.readTree(body).has("provider")).isFalse();
        assertThat(provider.calls.get()).isEqualTo(0);
        assertThat(selector.calls.get()).isEqualTo(0);
        assertThat(ledger.count()).isEqualTo(ledgerBefore + 1);
    }

    @Test
    void unauthenticatedRequestStillReturns401() throws Exception {
        mvc.perform(post("/api/gateway/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GatewayCompleteRequest("local-test-model", CLEAN))))
                .andExpect(status().isUnauthorized());

        assertThat(inspections.calls.get()).isEqualTo(0);
        assertThat(provider.calls.get()).isEqualTo(0);
    }

    @Test
    void concurrentRejectedRequestsAllStayRejected() throws Exception {
        String token = register();
        exhaustQuota(token);
        long ledgerBefore = ledger.count();
        int inspectionsBefore = inspections.calls.get();
        int providerBefore = provider.calls.get();

        int parallel = 20;
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < parallel; i++) {
                futures.add(pool.submit(() -> complete(token, CLEAN).getResponse().getStatus()));
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

            for (Future<Integer> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(429);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(inspections.calls.get()).isEqualTo(inspectionsBefore);
        assertThat(provider.calls.get()).isEqualTo(providerBefore);
        assertThat(ledger.count()).isEqualTo(ledgerBefore);
    }
}

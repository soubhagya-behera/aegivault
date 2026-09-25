package com.aegivault.aegivault.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aegivault.aegivault.audit.AuditLedgerEntryRepository;
import com.aegivault.aegivault.auth.RegisterRequest;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * REDIS limiter mode through the real application path with a mocked
 * Redis template — no Redis server, no network. The active
 * {@link GatewayRateLimiter} is the {@link RedisGatewayRateLimiter}
 * (this context also proves the application starts in REDIS mode
 * without a server), and HTTP behavior matches the in-memory mode:
 * quota denial stays the unchanged 429, security BLOCK stays 200
 * data, while a Redis outage fails closed as a generic 500.
 */
@SpringBootTest(
        properties = {
            "aegivault.gateway.provider=MOCK",
            "aegivault.gateway.rate-limiter=REDIS"
        })
@AutoConfigureMockMvc
class RedisRateLimitApiTest {

    private static final String CLEAN = "summarize quarterly revenue trends for the board.";

    private static final String EMAIL = "alice@example.com";

    @TestConfiguration
    static class MockRedisConfig {

        @Bean
        @Primary
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private GatewayRateLimiter rateLimiter;

    @Autowired
    private AuditLedgerEntryRepository ledger;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void redisAllowsByDefault() {
        reset(redis);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);
    }

    private String register() throws Exception {
        String body = objectMapper.writeValueAsString(
                new RegisterRequest("redis-limit-" + UUID.randomUUID() + "@example.com", "gateway-pass-1", null));
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

    @Test
    void redisModeWiresExactlyOneLimiterAndItIsRedis() {
        assertThat(rateLimiter).isInstanceOf(RedisGatewayRateLimiter.class);
    }

    @Test
    void redisDenialReturnsTheUnchanged429() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
        String token = register();
        long ledgerBefore = ledger.count();

        MvcResult rejected = complete(token, CLEAN);

        assertThat(rejected.getResponse().getStatus()).isEqualTo(429);
        String body = rejected.getResponse().getContentAsString();
        Set<String> fields = StreamSupport.stream(
                        objectMapper.readTree(body).propertyNames().spliterator(), false)
                .collect(Collectors.toSet());
        assertThat(fields).containsExactly("message");
        assertThat(objectMapper.readTree(body).get("message").asText())
                .isEqualTo("Gateway rate limit exceeded.");
        assertThat(ledger.count()).isEqualTo(ledgerBefore);
    }

    @Test
    void redisAllowKeepsSecurityBlockUnchanged() throws Exception {
        String token = register();
        long ledgerBefore = ledger.count();

        MvcResult result = complete(token, "contact " + EMAIL + " for access.");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).get("verdict").asText()).isEqualTo("BLOCK");
        assertThat(objectMapper.readTree(body).has("provider")).isFalse();
        assertThat(ledger.count()).isEqualTo(ledgerBefore + 1);
    }

    @Test
    void redisOutageFailsClosedWithAGeneric500() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new RuntimeException("redis-connection-refused-9z"));
        String token = register();
        long ledgerBefore = ledger.count();

        MvcResult result = complete(token, CLEAN);

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        String body = result.getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).get("message").asText())
                .isEqualTo("Unable to check gateway rate limit.");
        assertThat(body).doesNotContain("redis-connection-refused-9z", "localhost", "6379", CLEAN);
        assertThat(ledger.count()).isEqualTo(ledgerBefore);
    }
}

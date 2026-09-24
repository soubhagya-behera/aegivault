package com.aegivault.aegivault.gateway;

import com.aegivault.aegivault.gateway.provider.LlmResponse;
import com.aegivault.aegivault.pii.PiiDetection;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.pii.PiiType;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * Deterministic security inspection for provider responses: the second,
 * separate security decision in the gateway completion flow.
 *
 * <p>Request inspection ({@link SecurityInspectionService}) decides
 * whether a prompt may reach the provider; this service decides whether
 * an already-produced provider response may reach the client. It reuses
 * the same deterministic mechanisms — the shared
 * {@link PiiDetectorRegistry} applied per token plus {@link SecretDetector}
 * for obvious secrets — so no second detector system exists. Findings
 * are type names and reason codes only; matched values never leave this
 * call.
 *
 * <p>Strictly side-effect free: no logging of provider content, no
 * storage, no persistence, no network or audit calls. The same response
 * under the same policy always yields the identical result.
 */
@Service
public class ProviderResponseInspectionService {

    private final PiiDetectorRegistry pii;

    private final SecretDetector secrets;

    public ProviderResponseInspectionService(PiiDetectorRegistry pii, SecretDetector secrets) {
        this.pii = Objects.requireNonNull(pii, "pii must not be null");
        this.secrets = Objects.requireNonNull(secrets, "secrets must not be null");
    }

    /**
     * Inspects one provider response under one policy.
     *
     * @param response provider response to inspect, never null (null or
     *        blank content inspects {@code ALLOW} with no findings —
     *        there is nothing to leak)
     * @param policy blocking policy, never null
     * @return ALLOW or BLOCK with safe reason codes and detected PII type
     *         names; never provider content or matched values
     */
    public ProviderResponseInspectionResult inspect(LlmResponse response, GatewaySecurityPolicy policy) {
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Set<PiiType> detected = detectPiiTypes(response.content());
        boolean secret = secrets.containsSecret(response.content());

        Set<BlockReason> reasons = new TreeSet<>(Comparator.comparing(BlockReason::name));
        if (!detected.isEmpty() && policy.blockOnPii()) {
            reasons.add(BlockReason.PII_DETECTED);
        }
        if (secret && policy.blockOnSecrets()) {
            reasons.add(BlockReason.SECRET_DETECTED);
        }
        SecurityVerdict verdict = reasons.isEmpty() ? SecurityVerdict.ALLOW : SecurityVerdict.BLOCK;
        return new ProviderResponseInspectionResult(verdict, reasons, detected);
    }

    private Set<PiiType> detectPiiTypes(String content) {
        Set<PiiType> detected = new TreeSet<>(Comparator.comparing(PiiType::name));
        for (String token : GatewayTokens.split(content)) {
            for (PiiDetection detection : pii.detect(token)) {
                detected.add(detection.type());
            }
        }
        return detected;
    }
}

package com.aegivault.aegivault.gateway;

import com.aegivault.aegivault.pii.PiiDetection;
import com.aegivault.aegivault.pii.PiiDetectorRegistry;
import com.aegivault.aegivault.pii.PiiType;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * Deterministic security inspection for outbound AI requests: the single
 * decision point a future gateway will consult before allowing a request
 * to reach an LLM provider.
 *
 * <p>One inspection runs the existing {@link PiiDetectorRegistry} over each
 * token (no second PII detector system exists — every PII finding comes
 * from the shared registry with its usual deterministic ordering), checks
 * {@link SecretDetector} for obvious secrets, and folds both signals
 * through one {@link GatewaySecurityPolicy} into a single
 * {@link SecurityVerdict}. Findings are type names and reason codes only;
 * matched values never leave this call.
 *
 * <p>Strictly side-effect free: no logging of request content, no storage,
 * no persistence, no network or LLM calls. The same request under the same
 * policy always yields the identical result.
 */
@Service
public class SecurityInspectionService {

    private final PiiDetectorRegistry pii;

    private final SecretDetector secrets;

    public SecurityInspectionService(PiiDetectorRegistry pii, SecretDetector secrets) {
        this.pii = Objects.requireNonNull(pii, "pii must not be null");
        this.secrets = Objects.requireNonNull(secrets, "secrets must not be null");
    }

    /**
     * Inspects one request under one policy.
     *
     * @param request request to inspect, never null (null or blank content
     *        inspects {@code ALLOW} with no findings — there is nothing to
     *        leak)
     * @param policy blocking policy, never null
     * @return ALLOW or BLOCK with safe reason codes and detected PII type
     *         names; never request content or matched values
     */
    public SecurityInspectionResult inspect(GatewayInspectionRequest request, GatewaySecurityPolicy policy) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Set<PiiType> detected = detectPiiTypes(request.content());
        boolean secret = secrets.containsSecret(request.content());

        Set<BlockReason> reasons = new TreeSet<>(Comparator.comparing(BlockReason::name));
        if (!detected.isEmpty() && policy.blockOnPii()) {
            reasons.add(BlockReason.PII_DETECTED);
        }
        if (secret && policy.blockOnSecrets()) {
            reasons.add(BlockReason.SECRET_DETECTED);
        }
        SecurityVerdict verdict = reasons.isEmpty() ? SecurityVerdict.ALLOW : SecurityVerdict.BLOCK;
        return new SecurityInspectionResult(verdict, reasons, detected);
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

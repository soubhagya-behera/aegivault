package com.aegivault.aegivault.gateway.policy;

/**
 * The outcome of resolving one actor's effective gateway usage policy.
 *
 * <p>There are exactly two non-error outcomes:
 * <ul>
 *   <li>{@link Resolved} — exactly one enabled policy belongs to the actor;</li>
 *   <li>{@link None} — the actor has no enabled policy, which is a normal
 *       state (an actor simply has not defined one), never an error.</li>
 * </ul>
 *
 * <p>A third outcome — several enabled policies — is deliberately not a
 * value: it is an error
 * ({@link GatewayUsagePolicyAmbiguousException}), because picking any one of
 * them could enforce the wrong limit. The sealed shape makes the difference
 * explicit at the type level, so a caller cannot forget to handle it.
 *
 * <p>This type resolves a policy only. It never enforces one, and nothing
 * in the gateway traffic path consults it yet.
 */
public sealed interface GatewayUsagePolicyResolution {

    /** Exactly one enabled policy was found for the actor. */
    record Resolved(GatewayUsagePolicy policy) implements GatewayUsagePolicyResolution {

        public Resolved {
            if (policy == null) {
                throw new IllegalArgumentException("policy must not be null");
            }
        }
    }

    /** The actor has no enabled policy. A normal, non-error outcome. */
    record None() implements GatewayUsagePolicyResolution {

        static None instance() {
            return new None();
        }
    }

    /** Wraps the one enabled policy found for an actor. */
    static Resolved resolved(GatewayUsagePolicy policy) {
        return new Resolved(policy);
    }

    /** The no-policy outcome. */
    static None none() {
        return None.instance();
    }

    /** Whether an enabled policy was resolved. */
    default boolean isPresent() {
        return this instanceof Resolved;
    }

    /**
     * The resolved policy, or empty when the actor has none. Ambiguity is
     * never reported here — it throws before this point.
     */
    default java.util.Optional<GatewayUsagePolicy> effectivePolicy() {
        return this instanceof Resolved resolved
                ? java.util.Optional.of(resolved.policy())
                : java.util.Optional.empty();
    }
}
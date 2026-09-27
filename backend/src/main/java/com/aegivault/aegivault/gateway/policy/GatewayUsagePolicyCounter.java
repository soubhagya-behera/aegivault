package com.aegivault.aegivault.gateway.policy;

/**
 * Atomically admits one gateway request against an actor's usage-policy
 * request limit for one fixed UTC window.
 *
 * <p>This is the enforcement <em>primitive</em>, not policy enforcement. It
 * knows an actor, a window, and a numeric limit — and nothing else. It never
 * sees {@link GatewayUsagePolicy}, never resolves one, and never decides
 * whether a policy exists, is enabled, is ambiguous, or is satisfied.
 * Interpreting a policy into a call to this interface (which limits apply,
 * which window, what to do when a limit is absent, and what a rejection
 * should mean for the caller) is a later service's job.
 *
 * <p><strong>Why this exists at all.</strong> The read-only
 * {@link GatewayUsagePolicyUsageSnapshotProvider} counts
 * <em>persisted</em> usage, which is inherently after the fact: two requests
 * arriving at the same moment can both read the same historical count and
 * both conclude there is room. Deciding from that snapshot and then admitting
 * the request would let both through. This interface instead performs the
 * count and the decision as <strong>one indivisible operation</strong>, so
 * the count a request is admitted against already includes every request
 * admitted before it — including ones still in flight.
 *
 * <p><strong>Adoption is all-or-nothing.</strong> A {@code true} result means
 * the unit was consumed and the caller may proceed; a {@code false} result
 * means it was not consumed and the caller must not proceed. Rejected
 * attempts deliberately do <em>not</em> increment, so a client hammering a
 * spent limit cannot inflate the count it is already over, and the count
 * stays equal to the number of admitted requests.
 *
 * <p><strong>What must not leak into this type.</strong> No HTTP status, no
 * controller, no audit entry, no provider, no PII detector, no repository, no
 * usage record, and no policy interpretation. It is deliberately independent
 * of all of them.
 */
@FunctionalInterface
public interface GatewayUsagePolicyCounter {

    /**
     * Atomically consumes one request unit for {@code actorSubject} in
     * {@code window} and reports whether the consume stayed within
     * {@code limit}.
     *
     * <p>The comparison is exactly {@code currentCount + 1 <= limit}, where
     * {@code currentCount} counts the units already consumed in the current
     * window — including units consumed by concurrent requests that have not
     * returned yet. So a limit is the highest permitted value: the first
     * request under a limit of 1 is admitted, the second is rejected, and
     * with a limit of {@code n} exactly {@code n} requests are admitted.
     *
     * <p>Implementations must perform the read, the increment, and the limit
     * comparison as one indivisible operation. Separate
     * read-then-write steps are not an acceptable approximation: they are
     * exactly the race this abstraction exists to close.
     *
     * @param actorSubject verified JWT subject, never blank; trimmed exactly
     *        like the rest of the policy package trims it
     * @param window the fixed UTC window to count in, never null
     * @param limit the highest number of requests permitted in that window,
     *        strictly positive
     * @return {@code true} when the unit was consumed and the caller may
     *         proceed, {@code false} when the limit is already spent
     * @throws IllegalArgumentException when the actor is blank, the window is
     *         null, or the limit is not strictly positive
     * @throws GatewayUsagePolicyCounterUnavailableException when the
     *         underlying store could not answer; implementations that can fail
     *         must fail closed rather than admit the request
     */
    boolean tryConsume(String actorSubject, GatewayUsagePolicyCounterWindow window, long limit);
}

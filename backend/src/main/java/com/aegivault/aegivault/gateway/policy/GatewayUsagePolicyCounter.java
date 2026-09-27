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
 *
 * <p>Two operations, deliberately not merged into one: the single-window
 * {@link #tryConsume(String, GatewayUsagePolicyCounterWindow, long)} is the
 * simple case, and {@link #tryConsume(GatewayUsagePolicyCounterRequest)} is
 * the atomic multi-window case that several limits must be admitted by a
 * single indivisible decision. Keeping both means a caller with one limit
 * reads naturally, while a caller with two limits can never half-apply them.
 */
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

    /**
     * Atomically consumes one request unit from <em>every</em> window named by
     * {@code request}, treating them as a single unit of work.
     *
     * <p>This is the operation that removes partial consumption. Consuming the
     * day limit and the minute limit in two separate calls means the day's
     * capacity is already spent when the minute call rejects, so a refused
     * request still costs the actor a unit of one of their limits. Here the
     * whole set is decided together:
     * <ul>
     *   <li>if <strong>every</strong> requested window can admit the request,
     *       <strong>all</strong> of them are incremented and
     *       {@link GatewayUsagePolicyCounterResult.State#ALLOWED} is
     *       returned;</li>
     *   <li>if <strong>any</strong> requested window is already spent,
     *       <strong>none</strong> is incremented and
     *       {@link GatewayUsagePolicyCounterResult.State#REJECTED} is returned
     *       with the exhausted window.</li>
     * </ul>
     *
     * <p>All requested windows are therefore incremented together or not at
     * all. A request that names a single window behaves exactly like
     * {@link #tryConsume(String, GatewayUsagePolicyCounterWindow, long)},
     * including the {@code currentCount + 1 <= limit} boundary.
     *
     * <p>The per-window comparison is exactly the same
     * {@code currentCount + 1 <= limit} as the single-window operation, and
     * the counting remains atomic per window: concurrent attempts for the same
     * actor and window still cannot overshoot, and a multi-window attempt is
     * indivisible with respect to other attempts for that actor.
     *
     * <p>When several requested windows are exhausted, the one reported is the
     * first in {@link GatewayUsagePolicyCounterWindow#evaluationRank()} order
     * (day, then minute) — a fixed order, independent of configured limit
     * values, map iteration order, and timestamps.
     *
     * @param request the actor, the instant, and the per-window limits, never
     *        null
     * @return the attempt outcome, never null
     * @throws GatewayUsagePolicyCounterUnavailableException when the
     *         underlying store could not answer; a failure is never reported
     *         as either {@code ALLOWED} or {@code REJECTED}
     */
    GatewayUsagePolicyCounterResult tryConsume(GatewayUsagePolicyCounterRequest request);
}

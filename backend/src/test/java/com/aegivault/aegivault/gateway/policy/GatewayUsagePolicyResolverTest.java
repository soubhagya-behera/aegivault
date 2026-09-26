package com.aegivault.aegivault.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link GatewayUsagePolicyResolver} (no Spring context,
 * no database). They pin the resolution rule itself: one enabled policy
 * resolves, none is a normal outcome, and several are an explicit failure
 * rather than a silent pick. The resolver's only dependency is asserted so
 * it can never grow a path toward the gateway traffic flow.
 */
class GatewayUsagePolicyResolverTest {

    private final GatewayUsagePolicyRepository repository = mock(GatewayUsagePolicyRepository.class);

    private final GatewayUsagePolicyResolver resolver = new GatewayUsagePolicyResolver(repository);

    private static GatewayUsagePolicy policy(String owner, String name, boolean enabled) {
        return new GatewayUsagePolicy(owner, name, null, 60L, null, null, enabled);
    }

    @Test
    void oneEnabledPolicyIsResolved() {
        GatewayUsagePolicy only = policy("actor-1", "only", true);
        when(repository.findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc("actor-1"))
                .thenReturn(List.of(only));

        var resolution = resolver.resolve("actor-1");

        assertThat(resolution).isInstanceOf(GatewayUsagePolicyResolution.Resolved.class);
        assertThat(resolution.isPresent()).isTrue();
        assertThat(resolution.effectivePolicy()).contains(only);
        verify(repository).findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc("actor-1");
    }

    @Test
    void noEnabledPolicyIsTheNoPolicyOutcomeNotAnError() {
        when(repository.findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc("actor-1"))
                .thenReturn(List.of());

        var resolution = resolver.resolve("actor-1");

        assertThat(resolution).isInstanceOf(GatewayUsagePolicyResolution.None.class);
        assertThat(resolution.isPresent()).isFalse();
        assertThat(resolution.effectivePolicy()).isEmpty();
    }

    @Test
    void multipleEnabledPoliciesFailAsAmbiguous() {
        when(repository.findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc("actor-1"))
                .thenReturn(List.of(
                        policy("actor-1", "newer", true),
                        policy("actor-1", "older", true)));

        assertThatThrownBy(() -> resolver.resolve("actor-1"))
                .isInstanceOf(GatewayUsagePolicyAmbiguousException.class)
                .hasMessage("Multiple enabled gateway usage policies are configured.");
    }

    @Test
    void theAmbiguityMessageLeaksNoDetail() {
        GatewayUsagePolicy first = policy("secret-owner", "confidential-label", true);
        GatewayUsagePolicy second = policy("secret-owner", "another-label", true);
        when(repository.findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc("secret-owner"))
                .thenReturn(List.of(first, second));

        assertThatThrownBy(() -> resolver.resolve("secret-owner"))
                .isInstanceOf(GatewayUsagePolicyAmbiguousException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage())
                        .doesNotContain("secret-owner")
                        .doesNotContain("confidential-label")
                        .doesNotContain("another-label")
                        .doesNotContain("2"));
    }

    @Test
    void aSingleDisabledPolicyNeverReachesTheResolverAsACandidate() {
        // Disabled policies are filtered by the owner-scoped query, not by
        // the resolver: a lone disabled policy is therefore "no policy".
        when(repository.findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc("actor-1"))
                .thenReturn(List.of());

        assertThat(resolver.resolve("actor-1"))
                .isInstanceOf(GatewayUsagePolicyResolution.None.class);
    }

    @Test
    void anotherActorsPoliciesAreNeverCandidates() {
        // The query is owner-scoped, so a foreign actor's enabled policies
        // are invisible and cannot make this actor look ambiguous.
        when(repository.findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc("actor-1"))
                .thenReturn(List.of(policy("actor-1", "mine", true)));

        assertThat(resolver.resolve("actor-1").isPresent()).isTrue();
        verify(repository).findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc("actor-1");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void actorSubjectIsTrimmedBeforeTheQuery() {
        when(repository.findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc("actor-1"))
                .thenReturn(List.of(policy("actor-1", "only", true)));

        assertThat(resolver.resolve("  actor-1  ").isPresent()).isTrue();
        verify(repository).findByOwnerSubjectAndEnabledTrueOrderByCreatedAtDescIdDesc("actor-1");
    }

    @Test
    void blankActorSubjectIsRejectedWithoutAQuery() {
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
        assertThatThrownBy(() -> resolver.resolve("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("actorSubject must not be blank");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void theResolutionModelExposesExactlyTwoNonErrorOutcomes() {
        // Found and none are values; ambiguity is a thrown error, so it can
        // never be mistaken for a policy.
        assertThat(GatewayUsagePolicyResolution.Resolved.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("policy");
        assertThat(GatewayUsagePolicyResolution.None.class.getRecordComponents())
                .isEmpty();
        assertThat(GatewayUsagePolicyResolution.none().isPresent()).isFalse();
        assertThat(GatewayUsagePolicyResolution.none().effectivePolicy()).isEmpty();
        assertThat(GatewayUsagePolicyResolution.resolved(policy("actor-1", "only", true))
                        .effectivePolicy())
                .get()
                .extracting(GatewayUsagePolicy::getName)
                .isEqualTo("only");
    }

    @Test
    void resolverDependsOnlyOnTheRepository() {
        // Guards the dependency direction: the resolver must never acquire a
        // path to the completion service, rate limiter, Redis, providers,
        // PII detectors, the audit ledger, or a controller.
        var dependencies = java.util.Arrays.stream(GatewayUsagePolicyResolver.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getType().getName())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(dependencies).containsExactly(GatewayUsagePolicyRepository.class.getName());
    }
}

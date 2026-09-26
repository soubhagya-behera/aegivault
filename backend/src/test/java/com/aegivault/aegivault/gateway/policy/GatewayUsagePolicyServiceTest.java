package com.aegivault.aegivault.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link GatewayUsagePolicyService} (no Spring context,
 * no database): owner validation and trimming, delegation to the
 * owner-scoped repository reads, and the create/list/get/update/delete
 * contract. Ownership is the invariant under test — a foreign policy id is
 * indistinguishable from a missing one, and no query ever runs without an
 * explicit owner.
 */
class GatewayUsagePolicyServiceTest {

    private final GatewayUsagePolicyRepository repository = mock(GatewayUsagePolicyRepository.class);

    private final GatewayUsagePolicyService service = new GatewayUsagePolicyService(repository);

    private static GatewayUsagePolicy policy(String owner, String name) {
        return new GatewayUsagePolicy(owner, name, null, 60L, null, null, true);
    }

    @Test
    void createDelegatesToAnOwnerScopedSave() {
        when(repository.saveAndFlush(any(GatewayUsagePolicy.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.create("owner-1", "team", "desc", 60L, 100L, 1000L, true);

        assertThat(created.name()).isEqualTo("team");
        assertThat(created.description()).isEqualTo("desc");
        assertThat(created.requestsPerMinute()).isEqualTo(60L);
        assertThat(created.requestsPerDay()).isEqualTo(100L);
        assertThat(created.tokensPerDay()).isEqualTo(1000L);
        assertThat(created.enabled()).isTrue();
        verify(repository).saveAndFlush(any(GatewayUsagePolicy.class));
    }

    @Test
    void createRejectsABlankOwner() {
        assertThatThrownBy(() -> service.create(null, "n", null, 1L, null, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ownerSubject must not be blank");
        assertThatThrownBy(() -> service.create("   ", "n", null, 1L, null, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ownerSubject must not be blank");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void listIsOwnerScoped() {
        when(repository.findByOwnerSubjectOrderByCreatedAtDescIdDesc("owner-1"))
                .thenReturn(List.of(policy("owner-1", "only")));

        assertThat(service.list("owner-1")).extracting(GatewayUsagePolicyResponse::name).containsExactly("only");
        verify(repository).findByOwnerSubjectOrderByCreatedAtDescIdDesc("owner-1");
    }

    @Test
    void listReturnsEmptyForAnOwnerWithNoPolicies() {
        when(repository.findByOwnerSubjectOrderByCreatedAtDescIdDesc("nobody")).thenReturn(List.of());

        assertThat(service.list("nobody")).isEmpty();
    }

    @Test
    void listRejectsABlankOwner() {
        assertThatThrownBy(() -> service.list("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ownerSubject must not be blank");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void getDelegatesToAnOwnerScopedLookup() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndOwnerSubject(id, "owner-1"))
                .thenReturn(Optional.of(policy("owner-1", "mine")));

        assertThat(service.get("owner-1", id).name()).isEqualTo("mine");
        verify(repository).findByIdAndOwnerSubject(id, "owner-1");
    }

    @Test
    void missingPolicyBehavesAsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndOwnerSubject(id, "owner-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("owner-1", id))
                .isInstanceOf(GatewayUsagePolicyNotFoundException.class)
                .hasMessage("Usage policy not found.");
    }

    @Test
    void foreignPolicyBehavesExactlyLikeAMissingOne() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndOwnerSubject(id, "owner-2")).thenReturn(Optional.empty());

        // The owner-scoped query simply never finds another owner's row.
        assertThatThrownBy(() -> service.get("owner-2", id))
                .isInstanceOf(GatewayUsagePolicyNotFoundException.class)
                .hasMessage("Usage policy not found.");
        verify(repository).findByIdAndOwnerSubject(id, "owner-2");
    }

    @Test
    void updateReplacesThePolicyInPlace() {
        UUID id = UUID.randomUUID();
        GatewayUsagePolicy existing = policy("owner-1", "before");
        when(repository.findByIdAndOwnerSubject(id, "owner-1")).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(existing)).thenReturn(existing);

        var updated = service.update("owner-1", id, "after", "changed", null, 500L, null, false);

        assertThat(updated.id()).isEqualTo(existing.getId());
        assertThat(updated.name()).isEqualTo("after");
        assertThat(updated.requestsPerMinute()).isNull();
        assertThat(updated.requestsPerDay()).isEqualTo(500L);
        assertThat(updated.enabled()).isFalse();
        verify(repository).saveAndFlush(existing);
    }

    @Test
    void updateRejectsABlankOwner() {
        assertThatThrownBy(() -> service.update("  ", UUID.randomUUID(), "n", null, 1L, null, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ownerSubject must not be blank");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void updateOfAForeignPolicyIsRejected() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndOwnerSubject(id, "owner-2")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update("owner-2", id, "hijack", null, 1L, null, null, true))
                .isInstanceOf(GatewayUsagePolicyNotFoundException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void deleteRemovesOnlyTheCallersPolicy() {
        UUID id = UUID.randomUUID();
        GatewayUsagePolicy existing = policy("owner-1", "doomed");
        when(repository.findByIdAndOwnerSubject(id, "owner-1")).thenReturn(Optional.of(existing));

        service.delete("owner-1", id);

        verify(repository).delete(existing);
    }

    @Test
    void deleteOfAForeignPolicyIsRejected() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndOwnerSubject(id, "owner-2")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("owner-2", id))
                .isInstanceOf(GatewayUsagePolicyNotFoundException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void deleteRejectsABlankOwner() {
        assertThatThrownBy(() -> service.delete(null, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ownerSubject must not be blank");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void noCrossOwnerRepositoryQueryExists() {
        // The repository declares no global lookup: every find* method it
        // defines takes the owner explicitly, so no caller can widen scope.
        assertThat(GatewayUsagePolicyRepository.class.getDeclaredMethods())
                .filteredOn(method -> method.getName().startsWith("find"))
                .allSatisfy(method ->
                        assertThat(method.getParameterTypes())
                                .as(method.getName())
                                .anyMatch(type -> type == String.class));
    }

    @Test
    void serviceDependsOnlyOnTheRepository() {
        var dependencies = java.util.Arrays.stream(GatewayUsagePolicyService.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getType().getName())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(dependencies).containsExactly(GatewayUsagePolicyRepository.class.getName());
    }

    @Test
    void responseExcludesTheOwnerAndCarriesTimestamps() {
        // The view's shape is fixed: the owner never appears, and both
        // timestamps are part of it (set on persist, proven in the
        // repository test).
        var keys = java.util.stream.Stream.of(GatewayUsagePolicyResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(keys).containsExactlyInAnyOrder(
                "id", "name", "description", "requestsPerMinute", "requestsPerDay",
                "tokensPerDay", "enabled", "createdAt", "updatedAt");
        assertThat(keys).doesNotContain("ownerSubject");
        assertThat(java.util.stream.Stream.of(GatewayUsagePolicyResponse.class.getRecordComponents())
                        .filter(component -> component.getType().equals(Instant.class))
                        .count())
                .isEqualTo(2L);
    }
}

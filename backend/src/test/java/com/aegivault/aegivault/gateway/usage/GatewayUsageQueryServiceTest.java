package com.aegivault.aegivault.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link GatewayUsageQueryService} (no Spring
 * context, no database): actor validation and trimming, delegation to
 * the repository's actor-scoped reads, and strict read-only behavior —
 * history and aggregate calls never save, flush, or delete.
 */
class GatewayUsageQueryServiceTest {

    private final GatewayUsageRepository repository = mock(GatewayUsageRepository.class);

    private final GatewayUsageQueryService service = new GatewayUsageQueryService(repository);

    @Test
    void blankActorIsRejected() {
        assertThatThrownBy(() -> service.historyFor(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.historyFor("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.aggregateFor(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.aggregateFor("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actorIsTrimmedBeforeTheRepositoryCall() {
        when(repository.findByActorSubjectOrderByCreatedAtDescIdDesc("analyst")).thenReturn(List.of());
        when(repository.aggregateByActorSubject("analyst"))
                .thenReturn(new GatewayUsageAggregate(0L, null, null, null));

        assertThat(service.historyFor("  analyst  ")).isEmpty();
        assertThat(service.aggregateFor("  analyst  "))
                .isEqualTo(new GatewayUsageAggregate(0L, null, null, null));
        verify(repository).findByActorSubjectOrderByCreatedAtDescIdDesc("analyst");
        verify(repository).aggregateByActorSubject("analyst");
    }

    @Test
    void historyDelegatesToTheNewestFirstRepositoryRead() {
        GatewayUsageRecord record = mock(GatewayUsageRecord.class);
        when(repository.findByActorSubjectOrderByCreatedAtDescIdDesc("analyst"))
                .thenReturn(List.of(record));

        assertThat(service.historyFor("analyst")).containsExactly(record);
    }

    @Test
    void aggregateDelegatesToTheDatabaseAggregate() {
        GatewayUsageAggregate aggregate = new GatewayUsageAggregate(2L, 15L, 25L, 40L);
        when(repository.aggregateByActorSubject("analyst")).thenReturn(aggregate);

        assertThat(service.aggregateFor("analyst")).isEqualTo(aggregate);
    }

    @Test
    void serviceIsStrictlyReadOnly() {
        when(repository.findByActorSubjectOrderByCreatedAtDescIdDesc(anyString())).thenReturn(List.of());
        when(repository.aggregateByActorSubject(anyString()))
                .thenReturn(new GatewayUsageAggregate(0L, null, null, null));

        service.historyFor("analyst");
        service.aggregateFor("analyst");

        verify(repository).findByActorSubjectOrderByCreatedAtDescIdDesc("analyst");
        verify(repository).aggregateByActorSubject("analyst");
        verify(repository, never()).save(any(GatewayUsageRecord.class));
        verify(repository, never()).saveAll(any());
        verify(repository, never()).saveAndFlush(any(GatewayUsageRecord.class));
        verify(repository, never()).delete(any(GatewayUsageRecord.class));
        verify(repository, never()).deleteAll();
        verifyNoMoreInteractions(repository);
    }

    @Test
    void dependsOnlyOnTheRepository() {
        var dependencies = java.util.Arrays.stream(GatewayUsageQueryService.class.getDeclaredFields())
                .map(field -> field.getType().getName())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(dependencies).containsExactly(GatewayUsageRepository.class.getName());
    }
}

package com.aegivault.aegivault.dataset;

import java.time.Instant;
import java.util.UUID;

/**
 * API view of a dataset. {@code ownerSubject} is deliberately excluded: it is
 * an internal authorization field and every endpoint here is already scoped
 * to the authenticated user.
 */
public record DatasetResponse(
        UUID id,
        String name,
        String sourceType,
        String originalFilename,
        Long rowCount,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    static DatasetResponse from(Dataset dataset) {
        return new DatasetResponse(
                dataset.getId(),
                dataset.getName(),
                dataset.getSourceType(),
                dataset.getOriginalFilename(),
                dataset.getRowCount(),
                dataset.getStatus(),
                dataset.getCreatedAt(),
                dataset.getUpdatedAt());
    }
}

package com.aegivault.aegivault.dataset;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Creation payload. Only user-supplied metadata at this stage. Ownership,
 * source type, status, and row counts are server-controlled and therefore
 * absent here; an attempted {@code ownerSubject} property is not bound and
 * never used (Spring Boot ignores unknown JSON properties).
 */
public record CreateDatasetRequest(
        @NotBlank @Size(min = 1, max = 255) String name,
        @Size(max = 1024) String originalFilename) {}

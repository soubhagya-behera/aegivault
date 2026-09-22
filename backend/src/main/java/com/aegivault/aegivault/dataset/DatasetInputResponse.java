package com.aegivault.aegivault.dataset;

import java.util.UUID;

/**
 * Confirmation that input was stored for a dataset. Carries the dataset
 * identity only — never bytes, never owner identity.
 */
public record DatasetInputResponse(UUID datasetId) {}

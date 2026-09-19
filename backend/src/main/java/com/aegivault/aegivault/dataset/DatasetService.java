package com.aegivault.aegivault.dataset;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-scoped dataset operations. The invariant is
 * {@code JWT sub -> ownerSubject -> owner-scoped repository query}.
 * USER and ADMIN behave identically; no cross-user access exists here.
 */
@Service
@RequiredArgsConstructor
public class DatasetService {

    private final DatasetRepository datasets;

    @Transactional
    public DatasetResponse create(String ownerSubject, String name, String originalFilename) {
        Dataset dataset = new Dataset(name.trim(), ownerSubject);
        dataset.setSourceType("CSV");
        dataset.setStatus("UPLOADED");
        if (originalFilename != null && !originalFilename.isBlank()) {
            dataset.setOriginalFilename(originalFilename);
        }
        return DatasetResponse.from(datasets.save(dataset));
    }

    @Transactional(readOnly = true)
    public List<DatasetResponse> list(String ownerSubject) {
        return datasets.findByOwnerSubject(ownerSubject).stream()
                .map(DatasetResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DatasetResponse get(String ownerSubject, UUID id) {
        Dataset dataset = datasets
                .findByIdAndOwnerSubject(id, ownerSubject)
                .orElseThrow(DatasetNotFoundException::new);
        return DatasetResponse.from(dataset);
    }
}

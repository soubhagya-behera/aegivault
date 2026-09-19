package com.aegivault.aegivault.dataset;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Authenticated dataset endpoints. The owner always comes from the verified
 * JWT subject; the client can never supply or override it.
 */
@RestController
@RequestMapping("/api/datasets")
@RequiredArgsConstructor
public class DatasetController {

    private final DatasetService datasetService;

    @PostMapping
    public ResponseEntity<DatasetResponse> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateDatasetRequest request) {
        DatasetResponse response = datasetService.create(
                jwt.getSubject(), request.name(), request.originalFilename());
        return ResponseEntity.created(URI.create("/api/datasets/" + response.id())).body(response);
    }

    @GetMapping
    public List<DatasetResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return datasetService.list(jwt.getSubject());
    }

    @GetMapping("/{id}")
    public DatasetResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return datasetService.get(jwt.getSubject(), id);
    }

    @ExceptionHandler(DatasetNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    DatasetError notFound(DatasetNotFoundException ex) {
        return new DatasetError("Dataset not found.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    DatasetError badUuid(MethodArgumentTypeMismatchException ex) {
        return new DatasetError("Invalid dataset id.");
    }
}

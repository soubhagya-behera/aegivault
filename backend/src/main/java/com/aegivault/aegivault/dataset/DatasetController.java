package com.aegivault.aegivault.dataset;

import com.aegivault.aegivault.dataset.csv.CsvParseException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
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

    /**
     * Stores (or replaces) the raw CSV input of an owned dataset. The body
     * is streamed straight from the servlet request into storage — never
     * pre-buffered by a message converter — so the shared 10 MiB bound is
     * enforced before the full body ever sits in memory. No parsing,
     * detection, or execution happens here.
     */
    @PostMapping(value = "/{id}/input", consumes = "text/csv")
    public DatasetInputResponse storeInput(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest request) {
        try (InputStream input = request.getInputStream()) {
            datasetService.storeInput(jwt.getSubject(), id, input);
        } catch (IOException ex) {
            throw new CsvParseException("Unable to read CSV input.");
        }
        return new DatasetInputResponse(id);
    }

    @ExceptionHandler(DatasetNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    DatasetError notFound(DatasetNotFoundException ex) {
        return new DatasetError("Dataset not found.");
    }

    @ExceptionHandler(DatasetInputTooLargeException.class)
    @ResponseStatus(HttpStatus.CONTENT_TOO_LARGE)
    DatasetError tooLarge(DatasetInputTooLargeException ex) {
        return new DatasetError(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    DatasetError badUuid(MethodArgumentTypeMismatchException ex) {
        return new DatasetError("Invalid dataset id.");
    }
}

package com.aegivault.aegivault.gateway.policy.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link GatewayTokenBudgetReservationRequest} — no Spring
 * context, no Redis, no database, no network, no provider.
 *
 * <p>Most tests assert what the type <em>is</em>: one caller-supplied positive
 * amount, validated once at construction. The last group asserts what it is
 * <strong>not</strong>, by inspecting the class itself: it carries exactly one
 * component, exposes no conversion or derivation, depends on nothing outside
 * {@code java.lang}, and no production type in this package mentions it. That
 * makes "there is no token estimation here" a checked property rather than a
 * claim about code that is not there.
 */
class GatewayTokenBudgetReservationRequestTest {

    @Test
    void aPositiveAmountIsAccepted() {
        GatewayTokenBudgetReservationRequest request =
                GatewayTokenBudgetReservationRequest.of(1_000L);

        assertThat(request.requestedTokens()).isEqualTo(1_000L);
    }

    @Test
    void theSmallestPositiveAmountIsAccepted() {
        // 1 is a real reservation: the boundary is strictly positive, so the
        // smallest legal amount must not be rejected by an off-by-one.
        assertThat(GatewayTokenBudgetReservationRequest.of(1L).requestedTokens())
                .isEqualTo(1L);
    }

    @Test
    void aLargeAmountIsAcceptedUnchanged() {
        // No clamping or normalisation of any kind: the caller's figure is
        // carried through exactly as given.
        long large = Long.MAX_VALUE;

        assertThat(GatewayTokenBudgetReservationRequest.of(large).requestedTokens())
                .isEqualTo(large);
    }

    @Test
    void aZeroAmountIsRejected() {
        assertThatThrownBy(() -> GatewayTokenBudgetReservationRequest.of(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestedTokens must be positive");
    }

    @Test
    void aNegativeAmountIsRejected() {
        assertThatThrownBy(() -> GatewayTokenBudgetReservationRequest.of(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestedTokens must be positive");
        assertThatThrownBy(() -> GatewayTokenBudgetReservationRequest.of(Long.MIN_VALUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestedTokens must be positive");
    }

    @Test
    void aMissingAmountIsRejectedDistinctlyFromZero() {
        // "No amount supplied" and "zero supplied" are different mistakes, so
        // they are different failures rather than both being a null check.
        assertThatThrownBy(() -> new GatewayTokenBudgetReservationRequest(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("requestedTokens must not be null");
    }

    @Test
    void validationIsDeterministic() {
        // The same input always produces the same outcome, and a rejected
        // amount is rejected identically every time it is offered.
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> GatewayTokenBudgetReservationRequest.of(0L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("requestedTokens must be positive");
        }
        assertThat(GatewayTokenBudgetReservationRequest.of(7L).requestedTokens())
                .isEqualTo(7L);
    }

    @Test
    void theRequestCarriesExactlyOneValue() {
        // One component, so there is no room for a second implicit input such
        // as a character count, a model name, or a max-token guess.
        assertThat(List.of(GatewayTokenBudgetReservationRequest.class.getRecordComponents()))
                .extracting(component -> component.getName())
                .containsExactly("requestedTokens");
    }

    @Test
    void theRequestIsImmutable() {
        GatewayTokenBudgetReservationRequest request =
                GatewayTokenBudgetReservationRequest.of(500L);

        // Records are final, so a validated amount cannot be swapped after the
        // fact by subclassing or replacement.
        assertThat(Modifier.isFinal(request.getClass().getModifiers())).isTrue();
        assertThat(request.getClass().getRecordComponents()).hasSize(1);
    }

    @Test
    void equalAmountsAreEqualAndUnequalAmountsAreNot() {
        // Standard record value semantics, and worth pinning because a future
        // edit that cached or derived the amount would break it.
        assertThat(GatewayTokenBudgetReservationRequest.of(300L))
                .isEqualTo(GatewayTokenBudgetReservationRequest.of(300L))
                .hasSameHashCodeAs(GatewayTokenBudgetReservationRequest.of(300L));
        assertThat(GatewayTokenBudgetReservationRequest.of(300L))
                .isNotEqualTo(GatewayTokenBudgetReservationRequest.of(301L));
    }

    @Test
    void theClassDerivesNothingAndConvertsNothing() {
        // A conversion, estimator, tokenizer, or default would have to live in
        // some method, so the absence of any method beyond the factory, the
        // accessor, and object plumbing is the check that nothing computes an
        // amount from another value.
        List<Method> methods = Arrays.stream(
                        GatewayTokenBudgetReservationRequest.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .toList();

        assertThat(methods)
                .as("no derivation, conversion, or estimator method exists")
                .extracting(Method::getName)
                .containsExactlyInAnyOrder(
                        "of", "requestedTokens", "toString", "hashCode", "equals");
    }

    @Test
    void theClassDependsOnNothingOutsideJavaLang() {
        // No Spring, Redis, JPA, provider, controller, or audit type is
        // reachable from the contract's own field types.
        List<String> fieldTypes = Arrays.stream(
                        GatewayTokenBudgetReservationRequest.class.getDeclaredFields())
                .map(field -> field.getType().getName())
                .toList();

        assertThat(fieldTypes)
                .as("the only field type is the boxed amount")
                .containsExactly("java.lang.Long");
    }

    @Test
    void noProductionTypeInThePackageConsumesTheRequest() {
        // The contract must not be wired to anything yet: no budget interface
        // and no implementation may reference it, so it cannot have become a
        // call path by accident.
        Path packageDir = Path.of(
                "src/main/java/com/aegivault/aegivault/gateway/policy/budget");

        List<String> consumers;
        try (Stream<Path> files = Files.walk(packageDir)) {
            consumers = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString()
                            .equals("GatewayTokenBudgetReservationRequest.java"))
                    .filter(path -> {
                        try {
                            return Files.readString(path)
                                    .contains("GatewayTokenBudgetReservationRequest");
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    })
                    .map(path -> path.getFileName().toString())
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }

        assertThat(consumers)
                .as("no other production file in the package references the request type")
                .isEmpty();
    }
}
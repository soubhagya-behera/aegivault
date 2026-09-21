package com.aegivault.aegivault.sanitization.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.aegivault.aegivault.sanitization.TransformationStrategy;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link Sha256HashTransformation} (no Spring, no I/O). */
class Sha256HashTransformationTest {

    private final ValueTransformation transformation = new Sha256HashTransformation();

    @Test
    void reportsItsStrategy() {
        assertThat(transformation.strategy()).isEqualTo(TransformationStrategy.HASH_SHA256);
    }

    @Test
    void matchesTheKnownAbcDigest() {
        assertThat(transformation.apply("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void matchesTheKnownHelloDigest() {
        assertThat(transformation.apply("hello"))
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void outputIsLowercaseHexadecimalOfFixedLength() {
        String hash = transformation.apply("some user identifier");
        assertThat(hash).hasSize(Sha256HashTransformation.HASH_LENGTH);
        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    void outputContainsNoPartOfTheOriginalValue() {
        assertThat(transformation.apply("alice@example.com")).doesNotContain("alice");
        assertThat(transformation.apply("alice@example.com")).doesNotContain("example.com");
    }

    @Test
    void distinctInputsProduceDistinctHashes() {
        assertThat(transformation.apply("user1@example.com")).isNotEqualTo(transformation.apply("user2@example.com"));
    }

    @Test
    void hashesUtf8InputConsistently() {
        assertThat(transformation.apply("café")).isEqualTo(transformation.apply("café"));
        assertThat(transformation.apply("café")).matches("[0-9a-f]{64}");
        assertThat(transformation.apply("café")).isNotEqualTo(transformation.apply("cafe"));
    }

    @Test
    void outputIsDeterministicAcrossInstances() {
        assertThat(new Sha256HashTransformation().apply("alice@example.com"))
                .isEqualTo(new Sha256HashTransformation().apply("alice@example.com"));
    }
}

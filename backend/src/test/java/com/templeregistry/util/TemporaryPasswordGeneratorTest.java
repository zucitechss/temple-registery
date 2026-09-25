package com.templeregistry.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TemporaryPasswordGeneratorTest {

    @Test
    void should_satisfyPasswordPolicy_when_generated() {
        String password = TemporaryPasswordGenerator.generate();

        // Application policy is 8–128 characters (CreateUserRequest / ChangePasswordRequest).
        assertThat(password).hasSizeGreaterThanOrEqualTo(8).hasSizeLessThanOrEqualTo(128);
        assertThat(password).containsPattern("[A-Z]").containsPattern("[a-z]").containsPattern("[0-9]");
    }

    @Test
    void should_excludeAmbiguousCharacters_when_generated() {
        for (int i = 0; i < 200; i++) {
            assertThat(TemporaryPasswordGenerator.generate()).doesNotContainAnyWhitespaces()
                    .doesNotContain("0").doesNotContain("O")
                    .doesNotContain("1").doesNotContain("l").doesNotContain("I");
        }
    }

    @Test
    void should_beUnpredictable_when_generatedRepeatedly() {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            generated.add(TemporaryPasswordGenerator.generate());
        }
        // A predictable or seeded generator would collide; a CSPRNG over 12 chars will not.
        assertThat(generated).hasSize(1_000);
    }

    @Test
    void should_varyGuaranteedCharacterPositions_when_generatedRepeatedly() {
        Set<Character> firstChars = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            firstChars.add(TemporaryPasswordGenerator.generate().charAt(0));
        }
        // Without the shuffle the first character would always be uppercase.
        assertThat(firstChars.stream().anyMatch(Character::isLowerCase)).isTrue();
        assertThat(firstChars.stream().anyMatch(Character::isDigit)).isTrue();
    }
}

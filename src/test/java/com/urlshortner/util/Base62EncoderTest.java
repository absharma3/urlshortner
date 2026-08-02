package com.urlshortner.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class Base62EncoderTest {

    private final Base62Encoder encoder = new Base62Encoder();

    @Nested
    class Encode {

        @ParameterizedTest(name = "encode({0}) == \"{1}\"")
        @CsvSource({
                "0,    0",
                "1,    1",
                "9,    9",
                "10,   A",
                "35,   Z",
                "36,   a",
                "61,   z",
                "62,   10",
                "3843, zz",
                "3844, 100"
        })
        void encodesKnownValues(long input, String expected) {
            assertThat(encoder.encode(input)).isEqualTo(expected);
        }

        @Test
        void rejectsNegativeValues() {
            assertThatThrownBy(() -> encoder.encode(-1L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void encodesLongMaxValueWithoutOverflow() {
            String encoded = encoder.encode(Long.MAX_VALUE);
            assertThat(encoded).isNotBlank();
            assertThat(encoder.decode(encoded)).isEqualTo(Long.MAX_VALUE);
        }
    }

    @Nested
    class Decode {

        @ParameterizedTest(name = "decode(\"{0}\") == {1}")
        @CsvSource({
                "0,   0",
                "1,   1",
                "9,   9",
                "A,   10",
                "Z,   35",
                "a,   36",
                "z,   61",
                "10,  62",
                "zz,  3843",
                "100, 3844"
        })
        void decodesKnownValues(String input, long expected) {
            assertThat(encoder.decode(input)).isEqualTo(expected);
        }

        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> encoder.decode(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void rejectsEmpty() {
            assertThatThrownBy(() -> encoder.decode(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "decode(\"{0}\") rejects invalid input")
        @ValueSource(strings = {"@", "!", "/", " ", "hello!", "abc$def", "-", "_"})
        void rejectsInvalidCharacters(String input) {
            assertThatThrownBy(() -> encoder.decode(input))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Roundtrip {

        @ParameterizedTest(name = "encode-then-decode preserves {0}")
        @ValueSource(longs = {0L, 1L, 61L, 62L, 100L, 999L, 12_345L, 1_000_000L, 999_999_999L, Long.MAX_VALUE})
        void encodeThenDecodePreservesValue(long value) {
            String encoded = encoder.encode(value);
            assertThat(encoder.decode(encoded)).isEqualTo(value);
        }
    }
}

package com.urlshortner.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlHashGeneratorTest {

    private static final int SHORT_CODE_LENGTH = 8;

    private final UrlHashGenerator generator =
            new UrlHashGenerator(new Base62Encoder(), SHORT_CODE_LENGTH);

    @Nested
    class Determinism {

        @ParameterizedTest(name = "same URL yields same code: \"{0}\"")
        @ValueSource(strings = {
                "https://example.com/foo",
                "https://example.com/",
                "http://a.b.c.example.com/deep/path?q=1&r=2",
                "https://example.com/UTF-8-ok-ünicode"
        })
        void sameInputYieldsSameCode(String url) {
            String first = generator.generateShortCode(url, 0);
            String second = generator.generateShortCode(url, 0);
            assertThat(first).isEqualTo(second);
        }

        @Test
        void differentUrlsYieldDifferentCodes() {
            assertThat(generator.generateShortCode("https://example.com/a", 0))
                    .isNotEqualTo(generator.generateShortCode("https://example.com/b", 0));
        }
    }

    @Nested
    class SaltAttempt {

        @Test
        void saltAttemptZeroMatchesUnsaltedInput() {
            // saltAttempt=0 must not append "_salt_0" — otherwise repeat requests wouldn't
            // stabilise on the first attempt.
            String url = "https://example.com/salt-test";
            String zeroSalt = generator.generateShortCode(url, 0);
            String explicitSuffix = generator.generateShortCode(url + "_salt_0", 0);
            assertThat(zeroSalt).isNotEqualTo(explicitSuffix);
        }

        @Test
        void nonZeroSaltProducesDifferentCode() {
            String url = "https://example.com/collision";
            String a = generator.generateShortCode(url, 0);
            String b = generator.generateShortCode(url, 1);
            String c = generator.generateShortCode(url, 2);
            assertThat(a).isNotEqualTo(b);
            assertThat(a).isNotEqualTo(c);
            assertThat(b).isNotEqualTo(c);
        }

        @Test
        void saltIsDeterministicAcrossAttempts() {
            String url = "https://example.com/deterministic-salt";
            String first = generator.generateShortCode(url, 7);
            String second = generator.generateShortCode(url, 7);
            assertThat(first).isEqualTo(second);
        }
    }

    @Nested
    class Shape {

        @ParameterizedTest(name = "output is exactly {0} chars for \"{1}\"")
        @org.junit.jupiter.params.provider.CsvSource({
                "8, https://example.com/",
                "8, https://a.example.com/deep",
                "8, https://example.com/some/very/long/path?with=many&query=parameters"
        })
        void outputIsFixedLength(int expectedLen, String url) {
            String code = generator.generateShortCode(url, 0);
            assertThat(code).hasSize(expectedLen);
        }

        @ParameterizedTest(name = "output is strict alphanumeric for \"{0}\"")
        @ValueSource(strings = {
                "https://example.com/foo",
                "https://example.com/",
                "https://x.example.com/bar/baz"
        })
        void outputIsStrictAlphanumeric(String url) {
            String code = generator.generateShortCode(url, 0);
            assertThat(code).matches("^[a-zA-Z0-9]{8}$");
        }
    }

    @Nested
    class Rejects {

        @Test
        void rejectsNullInput() {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> generator.generateShortCode(null, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNegativeSalt() {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> generator.generateShortCode("https://example.com", -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

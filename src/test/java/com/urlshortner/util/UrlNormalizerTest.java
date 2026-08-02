package com.urlshortner.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class UrlNormalizerTest {

    private final UrlNormalizer normalizer = new UrlNormalizer();

    @Nested
    class SchemeAndHost {

        @ParameterizedTest(name = "normalize(\"{0}\") == \"{1}\"")
        @CsvSource({
                "HTTPS://Example.com,        https://example.com",
                "HTTP://EXAMPLE.COM/path,    http://example.com/path",
                "https://EXAMPLE.com,        https://example.com",
                "HTTPS://SUB.Example.COM/x,  https://sub.example.com/x"
        })
        void lowercasesSchemeAndHost(String input, String expected) {
            assertThat(normalizer.normalize(input)).isEqualTo(expected);
        }

        @Test
        void defaultsToHttpsWhenSchemeMissing() {
            assertThat(normalizer.normalize("example.com/path"))
                    .isEqualTo("https://example.com/path");
        }

        @Test
        void preservesExplicitHttpScheme() {
            assertThat(normalizer.normalize("http://example.com"))
                    .isEqualTo("http://example.com");
        }
    }

    @Nested
    class TrailingSlash {

        @ParameterizedTest(name = "normalize(\"{0}\") strips trailing / → \"{1}\"")
        @CsvSource({
                "https://example.com/,           https://example.com",
                "https://example.com/path/,      https://example.com/path",
                "https://example.com/a/b/c/,     https://example.com/a/b/c",
                "https://example.com/a/b/c///,   https://example.com/a/b/c"
        })
        void stripsTrailingSlashes(String input, String expected) {
            assertThat(normalizer.normalize(input)).isEqualTo(expected);
        }

        @Test
        void preservesQueryAndFragment() {
            assertThat(normalizer.normalize("HTTPS://Example.com/path/?q=1#frag"))
                    .isEqualTo("https://example.com/path?q=1#frag");
        }
    }

    @Nested
    class Whitespace {

        @ParameterizedTest(name = "trims leading/trailing whitespace on \"{0}\"")
        @ValueSource(strings = {
                "  https://example.com/path  ",
                "\thttps://example.com/path\n",
                "https://example.com/path "
        })
        void trimsSurroundingWhitespace(String input) {
            assertThat(normalizer.normalize(input)).isEqualTo("https://example.com/path");
        }
    }

    @Nested
    class Determinism {

        @Test
        void syntacticVariationsCollapseToSameOutput() {
            String a = normalizer.normalize("HTTPS://Example.COM/foo/");
            String b = normalizer.normalize(" https://example.com/foo ");
            String c = normalizer.normalize("Example.com/foo");
            assertThat(a).isEqualTo(b).isEqualTo(c);
        }
    }

    @Nested
    class Rejects {

        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> normalizer.normalize(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsBlank() {
            assertThatThrownBy(() -> normalizer.normalize("   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

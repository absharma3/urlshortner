package com.urlshortner.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlValidatorTest {

    // Real HostResolver hits DNS; that's OK here because the test URLs are IANA-reserved or
    // obviously-blocked address literals. See UrlValidatorDnsBehaviourTest for the mocked path.
    private final UrlValidator validator = new UrlValidator(new HostResolver(1500L));

    @Nested
    class SchemeGuard {

        @ParameterizedTest(name = "rejects scheme in \"{0}\"")
        @ValueSource(strings = {
                "javascript:alert(1)",
                "data:text/html,alert(1)",
                "file:///etc/passwd",
                "ftp://example.com/foo",
                "gopher://example.com/",
                "vbscript:msgbox(1)"
        })
        void rejectsNonHttpSchemes(String url) {
            // Some of these fail URI parsing outright; either way, they must be rejected.
            var result = validator.validate(url);
            assertThat(result.valid()).isFalse();
        }

        @ParameterizedTest(name = "accepts \"{0}\"")
        @ValueSource(strings = {
                // Hostnames that reliably resolve — example.com / www.example.com are IANA-reserved
                // documentation domains with published A records.
                "http://example.com/foo",
                "https://example.com/bar",
                "https://www.example.com/path?q=1#frag",
                "HTTPS://EXAMPLE.COM/mixed-case-scheme"
        })
        void acceptsHttpAndHttps(String url) {
            var result = validator.validate(url);
            assertThat(result.valid())
                    .withFailMessage("expected %s to be accepted (reason=%s)", url, result.reason())
                    .isTrue();
        }
    }

    @Nested
    class SsrfBlocklist {

        @ParameterizedTest(name = "blocks loopback \"{0}\"")
        @ValueSource(strings = {
                "http://127.0.0.1/admin",
                "http://127.1.2.3/",
                "http://localhost/private",
                "http://[::1]/foo"
        })
        void blocksLoopback(String url) {
            var result = validator.validate(url);
            assertThat(result.valid()).isFalse();
        }

        @ParameterizedTest(name = "blocks private/link-local \"{0}\"")
        @ValueSource(strings = {
                "http://10.0.0.1/",
                "http://10.255.255.254/foo",
                "http://172.16.0.1/",
                "http://172.31.255.1/",
                "http://192.168.1.1/",
                "http://169.254.169.254/latest/meta-data/"  // AWS/GCP metadata endpoint
        })
        void blocksPrivateAndLinkLocal(String url) {
            var result = validator.validate(url);
            assertThat(result.valid()).isFalse();
            assertThat(result.reason()).containsIgnoringCase("disallowed");
        }

        @ParameterizedTest(name = "blocks metadata host \"{0}\"")
        @ValueSource(strings = {
                "http://metadata.google.internal/computeMetadata/v1/",
                "http://metadata.goog/"
        })
        void blocksCloudMetadataHostnames(String url) {
            assertThat(validator.validate(url).valid()).isFalse();
        }

        @Test
        void blocksZeroAddress() {
            assertThat(validator.validate("http://0.0.0.0/").valid()).isFalse();
        }
    }

    @Nested
    class Syntactic {

        @Test
        void rejectsBlank() {
            assertThat(validator.validate("   ").valid()).isFalse();
            assertThat(validator.validate("").valid()).isFalse();
            assertThat(validator.validate(null).valid()).isFalse();
        }

        @Test
        void rejectsOverLengthUrl() {
            String url = "https://example.com/" + "a".repeat(2100);
            var result = validator.validate(url);
            assertThat(result.valid()).isFalse();
            assertThat(result.reason()).containsIgnoringCase("2048");
        }

        @Test
        void rejectsMissingHost() {
            assertThat(validator.validate("http:///path").valid()).isFalse();
        }
    }

}

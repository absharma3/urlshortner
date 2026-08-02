package com.urlshortner.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.Optional;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link UrlValidator} against a mocked {@link HostResolver}. The DNS-free surface
 * lets us assert the fail-open vs fail-closed decision without depending on the real network.
 */
class UrlValidatorDnsMockTest {

    @Nested
    class FailOpenOnResolutionFailure {

        @Test
        void unresolvableHostIsAcceptedWithWarning() {
            UrlValidator validator = new UrlValidator(new StubResolver(Optional.empty()));
            var result = validator.validate("https://not-really-a-host.example.invalid/path");
            assertThat(result.valid())
                    .withFailMessage("expected fail-open on DNS miss, got: %s", result.reason())
                    .isTrue();
        }
    }

    @Nested
    class FailClosedOnBlockedRange {

        @Test
        void resolvedLoopbackIsRejected() throws Exception {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            UrlValidator validator = new UrlValidator(new StubResolver(Optional.of(loopback)));
            var result = validator.validate("https://example.com/path");
            assertThat(result.valid()).isFalse();
            assertThat(result.reason()).containsIgnoringCase("disallowed");
        }

        @Test
        void resolvedPrivateRangeIsRejected() throws Exception {
            InetAddress privateAddr = InetAddress.getByName("10.0.0.1");
            UrlValidator validator = new UrlValidator(new StubResolver(Optional.of(privateAddr)));
            var result = validator.validate("https://example.com/path");
            assertThat(result.valid()).isFalse();
        }

        @Test
        void resolvedPublicAddressIsAccepted() throws Exception {
            InetAddress publicAddr = InetAddress.getByName("93.184.216.34"); // example.com's known IP
            UrlValidator validator = new UrlValidator(new StubResolver(Optional.of(publicAddr)));
            var result = validator.validate("https://example.com/path");
            assertThat(result.valid()).isTrue();
        }
    }

    @Nested
    class StaticHostBlocklist {

        /** Static host list is checked before resolution — never reaches the resolver. */
        @Test
        void localhostBlockedRegardlessOfResolver() {
            UrlValidator validator = new UrlValidator(new StubResolver(Optional.empty()));
            var result = validator.validate("http://localhost/path");
            assertThat(result.valid()).isFalse();
            assertThat(result.reason()).containsIgnoringCase("blocked");
        }
    }

    /** Test double for {@link HostResolver} that returns a canned resolution. */
    static final class StubResolver extends HostResolver {

        private final Optional<InetAddress> canned;

        StubResolver(Optional<InetAddress> canned) {
            super(1500L);
            this.canned = canned;
        }

        @Override
        public Optional<InetAddress> resolve(String host) {
            return canned;
        }
    }
}

package com.tieat.security.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8", "192.0.2.0/24"));

    @Test
    void ignoresForwardedHeadersFromAnUntrustedPeer() {
        MockHttpServletRequest request = request("198.51.100.20", "203.0.113.50");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.20");
    }

    @Test
    void walksAForwardedChainFromTheTrustedPeerToTheFirstUntrustedAddress() {
        MockHttpServletRequest request = request("10.0.0.5", "198.51.100.20, 192.0.2.10");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.20");
    }

    @Test
    void fallsBackToTheTrustedPeerWhenTheForwardedChainIsMalformed() {
        MockHttpServletRequest request = request("10.0.0.5", "198.51.100.20, attacker.example");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.5");
    }

    private MockHttpServletRequest request(String remoteAddress, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }
}

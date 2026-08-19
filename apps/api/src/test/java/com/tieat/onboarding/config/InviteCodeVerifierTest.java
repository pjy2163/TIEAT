package com.tieat.onboarding.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InviteCodeVerifierTest {

    @Test
    void failsClosedWhenNoServerInviteSecretIsConfigured() {
        InviteCodeVerifier verifier = new InviteCodeVerifier("");

        assertThat(verifier.matches(null)).isFalse();
        assertThat(verifier.matches("any-code")).isFalse();
    }

    @Test
    void acceptsOnlyTheExactConfiguredServerSecret() {
        InviteCodeVerifier verifier = new InviteCodeVerifier("pilot-code");

        assertThat(verifier.matches("pilot-code")).isTrue();
        assertThat(verifier.matches("pilot-code ")).isFalse();
        assertThat(verifier.matches("wrong-code")).isFalse();
    }
}

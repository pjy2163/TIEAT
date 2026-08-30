package com.tieat.onboarding.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class InviteCodeVerifierTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " \t "})
    void failsClosedWhenNoOrBlankServerInviteSecretIsConfigured(String configuredInviteCode) {
        InviteCodeVerifier verifier = new InviteCodeVerifier(configuredInviteCode);

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

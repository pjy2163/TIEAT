package com.tieat.onboarding.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InviteCodeVerifier {

    private final String configuredInviteCode;

    public InviteCodeVerifier(@Value("${tieat.onboarding.invite-code:}") String configuredInviteCode) {
        this.configuredInviteCode = configuredInviteCode;
    }

    public boolean matches(String suppliedInviteCode) {
        if (configuredInviteCode == null || configuredInviteCode.isBlank() || suppliedInviteCode == null) {
            return false;
        }
        return MessageDigest.isEqual(
            configuredInviteCode.getBytes(StandardCharsets.UTF_8),
            suppliedInviteCode.getBytes(StandardCharsets.UTF_8)
        );
    }
}

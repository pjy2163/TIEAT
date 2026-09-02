package com.tieat.security.web;

import com.tieat.security.application.RateLimiter.Key;
import java.util.List;
import java.util.Locale;
import com.tieat.qr.domain.MealUsageQrContextId;

public final class RateLimitKeys {

    private RateLimitKeys() {
    }

    public static List<Key> login(String ip, String loginId) {
        return keys("LOGIN_IP", ip, "LOGIN_ID", loginId);
    }

    public static Key loginIdentity(String loginId) {
        return new Key("LOGIN_ID", normalizeIdentity(loginId));
    }

    public static List<Key> signup(String ip, String loginId) {
        return keys("SIGNUP_IP", ip, "SIGNUP_ID", loginId);
    }

    public static List<Key> invite(String scope, String ip) {
        return List.of(new Key(scope, ip));
    }

    public static Key publicQrCreateIp(String ip) {
        return new Key("PUBLIC_QR_CREATE_IP", ip);
    }

    public static Key publicQrCreateClient(MealUsageQrContextId contextId, String clientKey) {
        return new Key("PUBLIC_QR_CREATE_CLIENT", contextId.value() + ":" + clientKey);
    }

    private static List<Key> keys(String ipScope, String ip, String identityScope, String identity) {
        if (identity == null || identity.isBlank()) {
            return List.of(new Key(ipScope, ip));
        }
        return List.of(
            new Key(ipScope, ip),
            new Key(identityScope, normalizeIdentity(identity))
        );
    }

    private static String normalizeIdentity(String identity) {
        return identity == null ? null : identity.trim().toLowerCase(Locale.ROOT);
    }
}

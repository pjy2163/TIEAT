package com.tieat.qr.application;

import com.tieat.qr.domain.MealUsageQrContext;
import com.tieat.qr.domain.MealUsageQrContextId;
import com.tieat.qr.domain.MealUsageQrToken;
import com.tieat.store.domain.StoreId;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class MealUsageQrTokenProtector {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Map<Integer, byte[]> keysByVersion;
    private final int activeKeyVersion;

    @Autowired
    public MealUsageQrTokenProtector(
        @Value("${tieat.qr.token-encryption-keys}") String configuredKeys,
        @Value("${tieat.qr.token-encryption-key:}") String configuredKey,
        @Value("${tieat.qr.token-encryption-key-version}") int activeKeyVersion,
        @Value("${tieat.qr.token-encryption-required}") boolean tokenEncryptionRequired
    ) {
        if (activeKeyVersion <= 0) {
            throw new IllegalStateException("QR token encryption key version must be positive");
        }
        this.keysByVersion = parseKeys(configuredKeys, configuredKey, activeKeyVersion);
        if (tokenEncryptionRequired && this.keysByVersion.isEmpty()) {
            throw new IllegalStateException(
                "QR token encryption is required but no valid key or key ring is configured"
            );
        }
        this.activeKeyVersion = activeKeyVersion;
    }

    public MealUsageQrTokenProtector(String configuredKeys, String configuredKey, int activeKeyVersion) {
        this(configuredKeys, configuredKey, activeKeyVersion, false);
    }

    public MealUsageQrTokenProtector(String configuredKey, int activeKeyVersion) {
        this("", configuredKey, activeKeyVersion, false);
    }

    public MealUsageQrContext.ProtectedToken protect(
        String rawToken,
        MealUsageQrContextId contextId,
        StoreId storeId
    ) {
        if (!MealUsageQrToken.isValid(rawToken)) {
            throw new QrTokenProtectionException();
        }
        Objects.requireNonNull(contextId, "QR context id must be supplied");
        Objects.requireNonNull(storeId, "Store id must be supplied");
        try {
            byte[] keyBytes = keysByVersion.get(activeKeyVersion);
            if (keyBytes == null) {
                throw new QrTokenProtectionException();
            }
            byte[] nonce = new byte[NONCE_BYTES];
            SECURE_RANDOM.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, contextId, storeId, keyBytes);
            byte[] ciphertext = cipher.doFinal(rawToken.getBytes(StandardCharsets.UTF_8));
            return new MealUsageQrContext.ProtectedToken(ciphertext, nonce, activeKeyVersion);
        } catch (GeneralSecurityException | RuntimeException exception) {
            if (exception instanceof QrTokenProtectionException protectionException) {
                throw protectionException;
            }
            throw new QrTokenProtectionException(exception);
        }
    }

    public String reveal(MealUsageQrContext context) {
        Objects.requireNonNull(context, "QR context must be supplied");
        MealUsageQrContext.ProtectedToken protectedToken = context.protectedToken()
            .orElseThrow(QrTokenProtectionException::new);
        byte[] keyBytes = keysByVersion.get(protectedToken.keyVersion());
        if (keyBytes == null) {
            throw new QrTokenProtectionException();
        }
        try {
            Cipher cipher = cipher(
                Cipher.DECRYPT_MODE,
                protectedToken.nonce(),
                context.id(),
                context.storeId(),
                keyBytes
            );
            String rawToken = new String(cipher.doFinal(protectedToken.ciphertext()), StandardCharsets.UTF_8);
            if (!MealUsageQrToken.isValid(rawToken)) {
                throw new QrTokenProtectionException();
            }
            return rawToken;
        } catch (GeneralSecurityException | RuntimeException exception) {
            if (exception instanceof QrTokenProtectionException protectionException) {
                throw protectionException;
            }
            throw new QrTokenProtectionException(exception);
        }
    }

    private Cipher cipher(
        int mode,
        byte[] nonce,
        MealUsageQrContextId contextId,
        StoreId storeId,
        byte[] keyBytes
    )
        throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(mode, new SecretKeySpec(keyBytes, KEY_ALGORITHM), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad(contextId, storeId));
        return cipher;
    }

    private byte[] aad(MealUsageQrContextId contextId, StoreId storeId) {
        return ("tieat:meal-usage-qr:" + contextId.value() + ":store:" + storeId.value())
            .getBytes(StandardCharsets.UTF_8);
    }

    private static Map<Integer, byte[]> parseKeys(
        String configuredKeys,
        String configuredKey,
        int activeKeyVersion
    ) {
        if (configuredKeys == null || configuredKeys.isBlank()) {
            byte[] keyBytes = decodeOptionalKey(configuredKey);
            return keyBytes == null ? Map.of() : Map.of(activeKeyVersion, keyBytes);
        }

        Map<Integer, byte[]> parsed = new HashMap<>();
        String[] entries = configuredKeys.split(",", -1);
        for (String rawEntry : entries) {
            String entry = rawEntry.trim();
            String[] parts = entry.split(":", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalStateException("QR token encryption key ring format is invalid");
            }
            int version = parseVersion(parts[0].trim());
            if (parsed.putIfAbsent(version, decodeRequiredKey(parts[1].trim())) != null) {
                throw new IllegalStateException("QR token encryption key ring contains duplicate versions");
            }
        }
        if (!parsed.containsKey(activeKeyVersion)) {
            throw new IllegalStateException("QR token encryption key ring has no active version");
        }
        return Map.copyOf(parsed);
    }

    private static int parseVersion(String value) {
        try {
            int version = Integer.parseInt(value);
            if (version <= 0) {
                throw new IllegalStateException("QR token encryption key version must be positive");
            }
            return version;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("QR token encryption key ring version is invalid", exception);
        }
    }

    private static byte[] decodeOptionalKey(String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            return null;
        }
        return decodeRequiredKey(configuredKey.trim());
    }

    private static byte[] decodeRequiredKey(String configuredKey) {
        try {
            byte[] decoded = Base64.getDecoder().decode(configuredKey.trim());
            if (decoded.length != KEY_BYTES) {
                throw new IllegalStateException("QR token encryption key must decode to 32 bytes");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("QR token encryption key must be base64", exception);
        }
    }
}

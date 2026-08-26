package com.tieat.ledger.domain;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.qr.domain.MealUsageQrContextId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record PublicMealUsageIdempotency(
    MealUsageQrContextId qrContextId,
    UUID idempotencyKey,
    MealContractId mealContractId,
    long amount,
    MealUsageId mealUsageId,
    String requestKeyHash,
    Instant createdAt
) {

    private static final int REQUEST_KEY_BYTES = 32;
    private static final Pattern REQUEST_KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern REQUEST_KEY_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Duration REQUEST_KEY_LIFETIME = Duration.ofMinutes(10);

    public PublicMealUsageIdempotency {
        Objects.requireNonNull(qrContextId, "Meal usage QR context id must be supplied");
        Objects.requireNonNull(idempotencyKey, "Idempotency key must be supplied");
        Objects.requireNonNull(mealContractId, "Meal contract id must be supplied");
        if (amount <= 0) {
            throw new IllegalArgumentException("Usage amount must be positive");
        }
        Objects.requireNonNull(mealUsageId, "Meal usage id must be supplied");
        if (requestKeyHash != null && !REQUEST_KEY_HASH_PATTERN.matcher(requestKeyHash).matches()) {
            throw new IllegalArgumentException("Public request key hash must be a SHA-256 hex digest");
        }
        Objects.requireNonNull(createdAt, "Public request creation time must be supplied");
    }

    public static String hashRequestKey(String rawRequestKey) {
        if (rawRequestKey == null || !REQUEST_KEY_PATTERN.matcher(rawRequestKey).matches()) {
            throw new InvalidPublicRequestKeyException();
        }
        try {
            byte[] keyBytes = Base64.getUrlDecoder().decode(rawRequestKey);
            if (keyBytes.length != REQUEST_KEY_BYTES) {
                throw new InvalidPublicRequestKeyException();
            }
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(keyBytes));
        } catch (IllegalArgumentException exception) {
            throw new InvalidPublicRequestKeyException();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public boolean matchesCreatePayload(MealContractId requestedContractId, long requestedAmount, String requestedKeyHash) {
        return mealContractId.equals(requestedContractId)
            && amount == requestedAmount
            && requestKeyHash != null
            && MessageDigest.isEqual(
                requestKeyHash.getBytes(StandardCharsets.US_ASCII),
                requestedKeyHash.getBytes(StandardCharsets.US_ASCII)
            );
    }

    public boolean matchesRequestKey(String rawRequestKey) {
        if (requestKeyHash == null) {
            return false;
        }
        try {
            return MessageDigest.isEqual(
                requestKeyHash.getBytes(StandardCharsets.US_ASCII),
                hashRequestKey(rawRequestKey).getBytes(StandardCharsets.US_ASCII)
            );
        } catch (InvalidPublicRequestKeyException exception) {
            return false;
        }
    }

    public boolean isRequestKeyActiveAt(Instant instant) {
        Objects.requireNonNull(instant, "Public request key check time must be supplied");
        return requestKeyHash != null && instant.isBefore(createdAt.plus(REQUEST_KEY_LIFETIME));
    }

    public static final class InvalidPublicRequestKeyException extends IllegalArgumentException {

        public InvalidPublicRequestKeyException() {
            super("Public request key must be a 256-bit URL-safe value");
        }
    }
}

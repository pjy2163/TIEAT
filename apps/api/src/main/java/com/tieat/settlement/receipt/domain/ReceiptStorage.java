package com.tieat.settlement.receipt.domain;

import java.util.Set;
import java.util.Objects;

/** Private object-store boundary. Implementations must never expose a public URL or SAS. */
public interface ReceiptStorage {

    void put(String objectKey, byte[] bytes, String contentType);

    byte[] get(String objectKey);

    /** Deletes the active object and any recovery copies (snapshots, versions, or backups). */
    void deleteAllVersions(String objectKey);

    /** Removes provider objects that have no corresponding settlement metadata. */
    void reconcileOrphans(Set<String> knownObjectKeys);

    default void validateKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.startsWith("/") || objectKey.contains("..")) {
            throw new IllegalArgumentException("Receipt object key must be opaque and relative");
        }
    }

    default void validatePayload(String objectKey, byte[] bytes, String contentType) {
        validateKey(objectKey);
        Objects.requireNonNull(bytes, "Receipt bytes must be supplied");
        Objects.requireNonNull(contentType, "Receipt content type must be supplied");
    }
}

package com.tieat.settlement.receipt.application;

import com.tieat.settlement.receipt.adapter.out.persistence.PosSettlementReceiptPersistenceAdapter;
import com.tieat.settlement.receipt.domain.PosSettlementReceipt;
import com.tieat.settlement.receipt.domain.ReceiptStorage;
import com.tieat.store.domain.StoreId;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosSettlementReceiptService {

    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;
    public static final int RETENTION_DAYS = 365;
    private static final List<String> ALLOWED_CONTENT_TYPES = List.of(
        "image/jpeg",
        "image/png",
        "application/pdf"
    );

    private final PosSettlementReceiptPersistenceAdapter repository;
    private final ReceiptStorage storage;
    private final Clock clock;

    public PosSettlementReceiptService(
        PosSettlementReceiptPersistenceAdapter repository,
        ReceiptStorage storage,
        Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.storage = Objects.requireNonNull(storage);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public PosSettlementReceipt upload(
        StoreId storeId,
        UUID posSettlementId,
        String fileName,
        String contentType,
        byte[] bytes
    ) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        Objects.requireNonNull(posSettlementId, "POS settlement id must be supplied");
        validate(fileName, contentType, bytes);
        if (!repository.lockSettlement(posSettlementId, storeId)) {
            throw new PosSettlementReceiptExceptions.NotFound(posSettlementId);
        }
        if (repository.findBySettlementIdAndStoreId(posSettlementId, storeId).isPresent()) {
            throw new PosSettlementReceiptExceptions.AlreadyAttached(posSettlementId);
        }

        Instant uploadedAt = Instant.now(clock);
        String objectKey = UUID.randomUUID().toString();
        try {
            storage.put(objectKey, Arrays.copyOf(bytes, bytes.length), contentType);
            PosSettlementReceipt receipt = new PosSettlementReceipt(
                UUID.randomUUID(),
                posSettlementId,
                storeId,
                objectKey,
                safeFileName(fileName),
                contentType,
                bytes.length,
                uploadedAt,
                uploadedAt.plus(RETENTION_DAYS, ChronoUnit.DAYS),
                PosSettlementReceipt.ScanStatus.CLEAN,
                null
            );
            repository.insert(receipt);
            return receipt;
        } catch (PosSettlementReceiptExceptions.AlreadyAttached | PosSettlementReceiptExceptions.NotFound exception) {
            throw exception;
        } catch (RuntimeException exception) {
            deleteAfterFailedUpload(objectKey, exception);
            if (exception instanceof com.tieat.settlement.receipt.domain.ReceiptStorage.UnsafeReceiptException) {
                throw new PosSettlementReceiptExceptions.Validation(
                    PosSettlementReceiptExceptions.Validation.Reason.UNSAFE_FILE,
                    "Receipt did not pass malware scanning"
                );
            }
            if (exception instanceof PosSettlementReceiptExceptions.Validation validation) {
                throw validation;
            }
            if (exception instanceof PosSettlementReceiptExceptions.StorageFailure storageFailure) {
                throw storageFailure;
            }
            throw new PosSettlementReceiptExceptions.StorageFailure("Receipt storage failed", exception);
        }
    }

    @Transactional(readOnly = true)
    public ReceiptDownload download(StoreId storeId, UUID posSettlementId) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        Objects.requireNonNull(posSettlementId, "POS settlement id must be supplied");
        PosSettlementReceipt receipt = repository.findBySettlementIdAndStoreId(posSettlementId, storeId)
            .filter(candidate -> candidate.deletedAt() == null)
            .orElseThrow(() -> new PosSettlementReceiptExceptions.NotFound(posSettlementId));
        if (receipt.isExpired(Instant.now(clock)) || receipt.scanStatus() != PosSettlementReceipt.ScanStatus.CLEAN) {
            throw new PosSettlementReceiptExceptions.NotFound(posSettlementId);
        }
        try {
            return new ReceiptDownload(receipt, storage.get(receipt.objectKey()));
        } catch (RuntimeException exception) {
            throw new PosSettlementReceiptExceptions.StorageFailure("Receipt download failed", exception);
        }
    }

    @Transactional(readOnly = true)
    public Map<UUID, ReceiptSummary> summariesFor(StoreId storeId, List<UUID> posSettlementIds) {
        Objects.requireNonNull(storeId, "Store id must be supplied");
        Objects.requireNonNull(posSettlementIds, "POS settlement ids must be supplied");
        List<UUID> ids = List.copyOf(posSettlementIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, PosSettlementReceipt> receiptsBySettlementId = repository
            .findBySettlementIdsAndStoreId(ids, storeId)
            .stream()
            .collect(Collectors.toMap(PosSettlementReceipt::posSettlementId, java.util.function.Function.identity()));
        Instant now = Instant.now(clock);
        Map<UUID, ReceiptSummary> summaries = new HashMap<>();
        for (UUID id : ids) {
            PosSettlementReceipt receipt = receiptsBySettlementId.get(id);
            summaries.put(id, receipt == null ? ReceiptSummary.none() : ReceiptSummary.from(receipt, now));
        }
        return Map.copyOf(summaries);
    }

    @Transactional
    public int cleanupExpired(int limit) {
        Instant now = Instant.now(clock);
        int deleted = 0;
        for (PosSettlementReceipt receipt : repository.findExpiredActive(now, limit)) {
            try {
                storage.deleteAllVersions(receipt.objectKey());
                repository.markDeleted(receipt.id(), now);
                deleted++;
            } catch (RuntimeException exception) {
                // Keep metadata active so a later cleanup run can retry the physical deletion.
            }
        }
        return deleted;
    }

    /** Manual/backstop seam for objects left by a process crash before metadata commit. */
    @Transactional(readOnly = true)
    public void reconcileOrphans() {
        storage.reconcileOrphans(repository.findAllObjectKeys());
    }

    private void validate(String fileName, String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new PosSettlementReceiptExceptions.Validation(
                PosSettlementReceiptExceptions.Validation.Reason.EMPTY_FILE,
                "Receipt file must not be empty"
            );
        }
        if (bytes.length > MAX_FILE_SIZE_BYTES) {
            throw new PosSettlementReceiptExceptions.Validation(
                PosSettlementReceiptExceptions.Validation.Reason.FILE_TOO_LARGE,
                "Receipt file must not exceed 10 MiB"
            );
        }
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(java.util.Locale.ROOT))) {
            throw new PosSettlementReceiptExceptions.Validation(
                PosSettlementReceiptExceptions.Validation.Reason.UNSUPPORTED_MEDIA_TYPE,
                "Receipt file must be JPG, PNG, or PDF"
            );
        }
        String detected = detectContentType(bytes);
        if (!contentType.equalsIgnoreCase(detected)) {
            throw new PosSettlementReceiptExceptions.Validation(
                PosSettlementReceiptExceptions.Validation.Reason.CONTENT_SIGNATURE_MISMATCH,
                "Receipt file content does not match its media type"
            );
        }
        if (fileName == null || fileName.isBlank()) {
            throw new PosSettlementReceiptExceptions.Validation(
                PosSettlementReceiptExceptions.Validation.Reason.EMPTY_FILE,
                "Receipt file name must be supplied"
            );
        }
    }

    private String detectContentType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (bytes.length >= 8
            && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
            && (bytes[4] & 0xff) == 0x0d && (bytes[5] & 0xff) == 0x0a && (bytes[6] & 0xff) == 0x1a && bytes[7] == 0x0a) {
            return "image/png";
        }
        if (bytes.length >= 5
            && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-') {
            return "application/pdf";
        }
        return "application/octet-stream";
    }

    private String safeFileName(String fileName) {
        String leaf = fileName.replace('\\', '/');
        leaf = leaf.substring(leaf.lastIndexOf('/') + 1).trim();
        if (leaf.isEmpty() || leaf.equals(".") || leaf.equals("..")) {
            return "receipt";
        }
        return leaf.length() > 180 ? leaf.substring(leaf.length() - 180) : leaf;
    }

    private void deleteAfterFailedUpload(String objectKey, RuntimeException original) {
        try {
            storage.deleteAllVersions(objectKey);
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    public record ReceiptDownload(PosSettlementReceipt receipt, byte[] bytes) {
        public ReceiptDownload {
            Objects.requireNonNull(receipt, "Receipt must be supplied");
            Objects.requireNonNull(bytes, "Receipt bytes must be supplied");
        }
    }

    public record ReceiptSummary(
        ReceiptStatus status,
        String fileName,
        String contentType,
        Long sizeBytes,
        Instant uploadedAt,
        Instant expiresAt
    ) {

        public ReceiptSummary {
            Objects.requireNonNull(status, "Receipt summary status must be supplied");
            if (status == ReceiptStatus.NONE) {
                if (fileName != null || contentType != null || sizeBytes != null || uploadedAt != null || expiresAt != null) {
                    throw new IllegalArgumentException("A missing receipt cannot have metadata");
                }
            } else if (fileName == null || fileName.isBlank() || contentType == null || contentType.isBlank()
                || sizeBytes == null || sizeBytes <= 0 || uploadedAt == null || expiresAt == null) {
                throw new IllegalArgumentException("An attached receipt summary must include metadata");
            }
        }

        static ReceiptSummary none() {
            return new ReceiptSummary(ReceiptStatus.NONE, null, null, null, null, null);
        }

        static ReceiptSummary from(PosSettlementReceipt receipt, Instant now) {
            ReceiptStatus status = receipt.deletedAt() != null || receipt.isExpired(now)
                ? ReceiptStatus.EXPIRED
                : ReceiptStatus.AVAILABLE;
            return new ReceiptSummary(
                status,
                receipt.fileName(),
                receipt.contentType(),
                receipt.sizeBytes(),
                receipt.uploadedAt(),
                receipt.expiresAt()
            );
        }
    }

    public enum ReceiptStatus {
        NONE,
        AVAILABLE,
        EXPIRED
    }
}

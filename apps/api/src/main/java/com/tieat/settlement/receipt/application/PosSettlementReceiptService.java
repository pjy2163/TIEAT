package com.tieat.settlement.receipt.application;

import com.tieat.settlement.receipt.adapter.out.persistence.PosSettlementReceiptPersistenceAdapter;
import com.tieat.settlement.receipt.domain.PosSettlementReceipt;
import com.tieat.settlement.receipt.domain.ReceiptStorage;
import com.tieat.store.domain.StoreId;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosSettlementReceiptService {

    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;
    public static final long MAX_IMAGE_PIXELS = 25_000_000L;
    public static final int RETENTION_DAYS = 365;
    private static final List<String> ALLOWED_CONTENT_TYPES = List.of(
        "image/jpeg",
        "image/png"
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
        ValidatedUpload upload = validate(fileName, contentType, bytes);
        if (!repository.lockSettlement(posSettlementId, storeId)) {
            throw new PosSettlementReceiptExceptions.NotFound(posSettlementId);
        }
        if (repository.findBySettlementIdAndStoreId(posSettlementId, storeId).isPresent()) {
            throw new PosSettlementReceiptExceptions.AlreadyAttached(posSettlementId);
        }

        Instant uploadedAt = Instant.now(clock);
        String objectKey = UUID.randomUUID().toString();
        try {
            storage.put(objectKey, upload.bytes(), upload.contentType());
            PosSettlementReceipt receipt = new PosSettlementReceipt(
                UUID.randomUUID(),
                posSettlementId,
                storeId,
                objectKey,
                upload.fileName(),
                upload.contentType(),
                upload.bytes().length,
                uploadedAt,
                uploadedAt.plus(RETENTION_DAYS, ChronoUnit.DAYS),
                PosSettlementReceipt.ValidationStatus.VALIDATED,
                null
            );
            repository.insert(receipt);
            return receipt;
        } catch (PosSettlementReceiptExceptions.AlreadyAttached | PosSettlementReceiptExceptions.NotFound exception) {
            throw exception;
        } catch (RuntimeException exception) {
            deleteAfterFailedUpload(objectKey, exception);
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
        if (receipt.isExpired(Instant.now(clock))
            || receipt.validationStatus() != PosSettlementReceipt.ValidationStatus.VALIDATED) {
            throw new PosSettlementReceiptExceptions.NotFound(posSettlementId);
        }
        if (!ALLOWED_CONTENT_TYPES.contains(receipt.contentType())
            || !hasExpectedExtension(receipt.fileName(), receipt.contentType())) {
            throw new PosSettlementReceiptExceptions.NotFound(posSettlementId);
        }
        try {
            byte[] bytes = storage.get(receipt.objectKey());
            validateStoredReceipt(receipt, bytes);
            return new ReceiptDownload(receipt, bytes);
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

    private ValidatedUpload validate(String fileName, String contentType, byte[] bytes) {
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
        String normalizedContentType = contentType == null
            ? null
            : contentType.toLowerCase(java.util.Locale.ROOT);
        if (normalizedContentType == null || !ALLOWED_CONTENT_TYPES.contains(normalizedContentType)) {
            throw new PosSettlementReceiptExceptions.Validation(
                PosSettlementReceiptExceptions.Validation.Reason.UNSUPPORTED_MEDIA_TYPE,
                "Receipt file must be JPG or PNG"
            );
        }
        String detected = detectContentType(bytes);
        if (!normalizedContentType.equals(detected)) {
            throw new PosSettlementReceiptExceptions.Validation(
                PosSettlementReceiptExceptions.Validation.Reason.CONTENT_SIGNATURE_MISMATCH,
                "Receipt file content does not match its media type"
            );
        }
        if (!hasExactImageEnding(bytes, normalizedContentType)) {
            throw invalidImage();
        }
        if (fileName == null || fileName.isBlank()) {
            throw new PosSettlementReceiptExceptions.Validation(
                PosSettlementReceiptExceptions.Validation.Reason.EMPTY_FILE,
                "Receipt file name must be supplied"
            );
        }
        String storedFileName = safeFileName(fileName);
        if (!hasExpectedExtension(storedFileName, normalizedContentType)) {
            throw new PosSettlementReceiptExceptions.Validation(
                PosSettlementReceiptExceptions.Validation.Reason.FILE_NAME_MISMATCH,
                "Receipt file extension does not match its media type"
            );
        }
        validateDecodableImage(bytes, normalizedContentType);
        return new ValidatedUpload(storedFileName, normalizedContentType, Arrays.copyOf(bytes, bytes.length));
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
        return "application/octet-stream";
    }

    private void validateDecodableImage(byte[] bytes, String contentType) {
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalidImage();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String expectedFormat = contentType.equals("image/png") ? "png" : "jpeg";
                if (!reader.getFormatName().equalsIgnoreCase(expectedFormat)) {
                    throw invalidImage();
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS) {
                    throw new PosSettlementReceiptExceptions.Validation(
                        PosSettlementReceiptExceptions.Validation.Reason.IMAGE_DIMENSIONS_TOO_LARGE,
                        "Receipt image dimensions are too large"
                    );
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw invalidImage();
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw invalidImage();
        }
    }

    private void validateStoredReceipt(PosSettlementReceipt receipt, byte[] bytes) {
        try {
            if (bytes == null || bytes.length != receipt.sizeBytes()
                || !receipt.contentType().equals(detectContentType(bytes))
                || !hasExactImageEnding(bytes, receipt.contentType())) {
                throw invalidImage();
            }
            validateDecodableImage(bytes, receipt.contentType());
        } catch (PosSettlementReceiptExceptions.Validation exception) {
            throw new IllegalStateException("Stored receipt failed integrity validation", exception);
        }
    }

    private PosSettlementReceiptExceptions.Validation invalidImage() {
        return new PosSettlementReceiptExceptions.Validation(
            PosSettlementReceiptExceptions.Validation.Reason.INVALID_IMAGE,
            "Receipt file is not a valid decodable image"
        );
    }

    private boolean hasExpectedExtension(String fileName, String contentType) {
        String normalized = fileName.toLowerCase(java.util.Locale.ROOT);
        return contentType.equals("image/png")
            ? normalized.endsWith(".png")
            : normalized.endsWith(".jpg") || normalized.endsWith(".jpeg");
    }

    private boolean hasExactImageEnding(byte[] bytes, String contentType) {
        if (contentType.equals("image/jpeg")) {
            return hasExactJpegEnding(bytes);
        }
        return hasExactPngEnding(bytes);
    }

    private boolean hasExactPngEnding(byte[] bytes) {
        if (bytes.length < 8 || detectContentType(bytes).equals("application/octet-stream")) {
            return false;
        }
        int offset = 8;
        while (offset <= bytes.length - 12) {
            long chunkLength = ((long) (bytes[offset] & 0xff) << 24)
                | ((long) (bytes[offset + 1] & 0xff) << 16)
                | ((long) (bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xffL);
            long chunkEnd = offset + 12L + chunkLength;
            if (chunkEnd > bytes.length) {
                return false;
            }
            boolean iend = bytes[offset + 4] == 'I' && bytes[offset + 5] == 'E'
                && bytes[offset + 6] == 'N' && bytes[offset + 7] == 'D';
            if (iend) {
                return chunkLength == 0 && chunkEnd == bytes.length;
            }
            offset = (int) chunkEnd;
        }
        return false;
    }

    private boolean hasExactJpegEnding(byte[] bytes) {
        if (bytes.length < 4 || (bytes[0] & 0xff) != 0xff || (bytes[1] & 0xff) != 0xd8) {
            return false;
        }
        int offset = 2;
        boolean inScan = false;
        while (offset < bytes.length) {
            if (inScan) {
                while (offset < bytes.length && (bytes[offset] & 0xff) != 0xff) {
                    offset++;
                }
                if (offset >= bytes.length) {
                    return false;
                }
            }
            if ((bytes[offset++] & 0xff) != 0xff) {
                return false;
            }
            while (offset < bytes.length && (bytes[offset] & 0xff) == 0xff) {
                offset++;
            }
            if (offset >= bytes.length) {
                return false;
            }
            int marker = bytes[offset++] & 0xff;
            if (inScan && marker == 0x00) {
                continue;
            }
            if (inScan && marker >= 0xd0 && marker <= 0xd7) {
                continue;
            }
            inScan = false;
            if (marker == 0xd9) {
                return offset == bytes.length;
            }
            if (marker == 0x01) {
                continue;
            }
            if (marker == 0xd8 || marker >= 0xd0 && marker <= 0xd7 || offset + 2 > bytes.length) {
                return false;
            }
            int segmentLength = ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
            if (segmentLength < 2 || offset + segmentLength > bytes.length) {
                return false;
            }
            offset += segmentLength;
            if (marker == 0xda) {
                inScan = true;
            }
        }
        return false;
    }

    private String safeFileName(String fileName) {
        String leaf = fileName.replace('\\', '/');
        leaf = leaf.substring(leaf.lastIndexOf('/') + 1).trim();
        leaf = leaf.replaceAll("[\\p{Cntrl}]", "");
        if (leaf.isEmpty() || leaf.equals(".") || leaf.equals("..")) {
            return "receipt";
        }
        return leaf.length() > 180 ? leaf.substring(leaf.length() - 180) : leaf;
    }

    private record ValidatedUpload(String fileName, String contentType, byte[] bytes) {
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

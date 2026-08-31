package com.tieat.settlement.receipt.adapter.out.storage;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobContainerProperties;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.DeleteSnapshotsOptionType;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.tieat.settlement.receipt.domain.ReceiptStorage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Production private Azure Blob adapter. Defender for Storage tags gate every download. */
@Component
@ConditionalOnProperty(name = "tieat.receipts.provider", havingValue = "azure")
public final class AzureBlobPrivateReceiptStorageAdapter implements ReceiptStorage {

    private static final String SCAN_RESULT_TAG = "Malware scanning scan result";
    private static final String CLEAN_RESULT = "No threats found";

    private final BlobContainerClient container;
    private final Duration scanTimeout;
    private final Duration scanPollInterval;

    @Autowired
    public AzureBlobPrivateReceiptStorageAdapter(
        @Value("${tieat.receipts.azure.endpoint:}") String endpoint,
        @Value("${tieat.receipts.azure.managed-identity-client-id:}") String managedIdentityClientId,
        @Value("${tieat.receipts.azure.container:tieat-receipts-private}") String containerName,
        @Value("${tieat.receipts.azure.scan-timeout:PT30S}") Duration scanTimeout,
        @Value("${tieat.receipts.azure.scan-poll-interval:PT1S}") Duration scanPollInterval
    ) {
        this(
            privateContainer(endpoint, managedIdentityClientId, containerName),
            scanTimeout,
            scanPollInterval
        );
    }

    public AzureBlobPrivateReceiptStorageAdapter(
        BlobContainerClient container,
        Duration scanTimeout,
        Duration scanPollInterval
    ) {
        this.container = Objects.requireNonNull(container);
        verifyPrivateContainer(this.container);
        this.scanTimeout = Objects.requireNonNull(scanTimeout);
        this.scanPollInterval = Objects.requireNonNull(scanPollInterval);
        if (scanTimeout.isNegative() || scanTimeout.isZero() || scanPollInterval.isNegative() || scanPollInterval.isZero()) {
            throw new IllegalArgumentException("Azure scan durations must be positive");
        }
    }

    @Override
    public void put(String objectKey, byte[] bytes, String contentType) {
        validatePayload(objectKey, bytes, contentType);
        BlobClient blob = container.getBlobClient(objectKey);
        blob.upload(new ByteArrayInputStream(bytes), bytes.length, false);
        blob.setHttpHeaders(new BlobHttpHeaders().setContentType(contentType));
        try {
            awaitCleanScan(blob);
        } catch (RuntimeException exception) {
            deleteAllVersions(objectKey);
            throw exception;
        }
    }

    @Override
    public byte[] get(String objectKey) {
        validateKey(objectKey);
        BlobClient blob = container.getBlobClient(objectKey);
        Map<String, String> tags = blob.getTags();
        if (!CLEAN_RESULT.equals(tags.get(SCAN_RESULT_TAG))) {
            throw new IllegalStateException("Receipt has not passed Azure Defender malware scan");
        }
        try (var input = blob.openInputStream()) {
            return input.readAllBytes();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Azure receipt read failed", exception);
        }
    }

    @Override
    public void deleteAllVersions(String objectKey) {
        validateKey(objectKey);
        BlobClient blob = container.getBlobClient(objectKey);
        blob.deleteIfExistsWithResponse(DeleteSnapshotsOptionType.INCLUDE, null, null, null);
    }

    @Override
    public void reconcileOrphans(Set<String> knownObjectKeys) {
        Objects.requireNonNull(knownObjectKeys, "Known receipt object keys must be supplied");
        for (BlobItem item : container.listBlobs()) {
            if (!knownObjectKeys.contains(item.getName())) {
                deleteAllVersions(item.getName());
            }
        }
    }

    static void verifyPrivateContainer(BlobContainerClient container) {
        BlobContainerProperties properties = container.getProperties();
        if (properties == null || properties.getBlobPublicAccess() != null) {
            throw new IllegalStateException("Receipt Azure Blob container must have public access disabled");
        }
    }

    static DefaultAzureCredential managedIdentityCredential(String managedIdentityClientId) {
        DefaultAzureCredentialBuilder builder = new DefaultAzureCredentialBuilder();
        if (managedIdentityClientId != null && !managedIdentityClientId.isBlank()) {
            builder.managedIdentityClientId(managedIdentityClientId.trim());
        }
        return builder.build();
    }

    private static BlobContainerClient privateContainer(
        String endpoint,
        String managedIdentityClientId,
        String containerName
    ) {
        validateEndpoint(endpoint);
        BlobContainerClient container = new BlobContainerClientBuilder()
            .endpoint(endpoint)
            .credential(managedIdentityCredential(managedIdentityClientId))
            .containerName(Objects.requireNonNull(containerName))
            .buildClient();
        verifyPrivateContainer(container);
        return container;
    }

    private static void validateEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException("Azure receipt endpoint must be configured when the provider is azure");
        }
        URI uri;
        try {
            uri = URI.create(endpoint.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Azure receipt endpoint must be a valid HTTPS URI", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
            || uri.getHost() == null
            || uri.getUserInfo() != null
            || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
            || uri.getQuery() != null
            || uri.getFragment() != null) {
            throw new IllegalStateException("Azure receipt endpoint must be a credential-free HTTPS URI");
        }
    }

    private void awaitCleanScan(BlobClient blob) {
        long deadline = System.nanoTime() + scanTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, String> tags = blob.getTags();
            String result = tags.get(SCAN_RESULT_TAG);
            if (CLEAN_RESULT.equals(result)) {
                return;
            }
            if ("Malicious".equals(result) || "Error".equals(result) || "Not scanned".equals(result)) {
                throw new ReceiptStorage.UnsafeReceiptException("Azure Defender rejected receipt scan result: " + result);
            }
            try {
                Thread.sleep(scanPollInterval.toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Azure Defender scan was interrupted", exception);
            }
        }
        throw new ReceiptStorage.UnsafeReceiptException("Azure Defender scan did not complete before timeout");
    }
}

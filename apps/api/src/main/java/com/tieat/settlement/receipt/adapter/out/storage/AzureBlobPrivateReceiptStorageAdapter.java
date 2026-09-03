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
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Production private Azure Blob adapter. Validation happens in the application before storage. */
@Component
@ConditionalOnProperty(name = "tieat.receipts.provider", havingValue = "azure")
public final class AzureBlobPrivateReceiptStorageAdapter implements ReceiptStorage {

    private static final int MAX_DOWNLOAD_SIZE_BYTES = 10 * 1024 * 1024;

    private final BlobContainerClient container;

    @Autowired
    public AzureBlobPrivateReceiptStorageAdapter(
        @Value("${tieat.receipts.azure.endpoint:}") String endpoint,
        @Value("${tieat.receipts.azure.managed-identity-client-id:}") String managedIdentityClientId,
        @Value("${tieat.receipts.azure.container:tieat-receipts-private}") String containerName
    ) {
        this(privateContainer(endpoint, managedIdentityClientId, containerName));
    }

    public AzureBlobPrivateReceiptStorageAdapter(BlobContainerClient container) {
        this.container = Objects.requireNonNull(container);
        verifyPrivateContainer(this.container);
    }

    @Override
    public void put(String objectKey, byte[] bytes, String contentType) {
        validatePayload(objectKey, bytes, contentType);
        BlobClient blob = container.getBlobClient(objectKey);
        blob.upload(new ByteArrayInputStream(bytes), bytes.length, false);
        blob.setHttpHeaders(new BlobHttpHeaders().setContentType(contentType));
    }

    @Override
    public byte[] get(String objectKey) {
        validateKey(objectKey);
        BlobClient blob = container.getBlobClient(objectKey);
        long contentLength = blob.getProperties().getBlobSize();
        if (contentLength < 1 || contentLength > MAX_DOWNLOAD_SIZE_BYTES) {
            throw new IllegalStateException("Azure receipt size is outside the allowed range");
        }
        try (var input = blob.openInputStream()) {
            byte[] bytes = input.readNBytes(MAX_DOWNLOAD_SIZE_BYTES + 1);
            if (bytes.length != contentLength) {
                throw new IllegalStateException("Azure receipt size changed while reading");
            }
            return bytes;
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

}

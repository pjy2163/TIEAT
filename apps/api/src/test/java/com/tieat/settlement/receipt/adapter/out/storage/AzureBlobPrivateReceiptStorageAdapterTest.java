package com.tieat.settlement.receipt.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobContainerProperties;
import com.azure.storage.blob.models.PublicAccessType;
import com.azure.identity.DefaultAzureCredential;
import org.junit.jupiter.api.Test;

class AzureBlobPrivateReceiptStorageAdapterTest {

    @Test
    void buildsAzureCredentialWithoutConnectionString() {
        DefaultAzureCredential credential = AzureBlobPrivateReceiptStorageAdapter.managedIdentityCredential("client-id");

        assertThat(credential).isInstanceOf(DefaultAzureCredential.class);
    }

    @Test
    void failsClosedWhenContainerPublicAccessIsEnabled() {
        BlobContainerClient container = mock(BlobContainerClient.class);
        BlobContainerProperties properties = mock(BlobContainerProperties.class);
        when(container.getProperties()).thenReturn(properties);
        when(properties.getBlobPublicAccess()).thenReturn(PublicAccessType.CONTAINER);

        assertThatThrownBy(() -> new AzureBlobPrivateReceiptStorageAdapter(container))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("public access disabled");
    }

    @Test
    void acceptsOnlyAContainerWithNoPublicAccess() {
        BlobContainerClient container = mock(BlobContainerClient.class);
        BlobContainerProperties properties = mock(BlobContainerProperties.class);
        when(container.getProperties()).thenReturn(properties);
        when(properties.getBlobPublicAccess()).thenReturn(null);

        assertThatCode(() -> new AzureBlobPrivateReceiptStorageAdapter(container)).doesNotThrowAnyException();
    }

    @Test
    void uploadsWithoutDependingOnBlobScanTags() {
        BlobContainerClient container = mock(BlobContainerClient.class);
        BlobContainerProperties properties = mock(BlobContainerProperties.class);
        BlobClient blob = mock(BlobClient.class);
        when(container.getProperties()).thenReturn(properties);
        when(properties.getBlobPublicAccess()).thenReturn(null);
        when(container.getBlobClient("opaque-receipt-object")).thenReturn(blob);

        AzureBlobPrivateReceiptStorageAdapter storage = new AzureBlobPrivateReceiptStorageAdapter(container);
        storage.put("opaque-receipt-object", new byte[] {1}, "image/png");

        verify(blob).upload(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(false));
        verify(blob, never()).getTags();
    }
}

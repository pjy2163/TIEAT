package com.tieat.settlement.receipt.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobContainerProperties;
import com.azure.storage.blob.models.PublicAccessType;
import com.azure.identity.DefaultAzureCredential;
import java.time.Duration;
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

        assertThatThrownBy(() -> new AzureBlobPrivateReceiptStorageAdapter(
            container,
            Duration.ofSeconds(1),
            Duration.ofMillis(1)
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("public access disabled");
    }

    @Test
    void acceptsOnlyAContainerWithNoPublicAccess() {
        BlobContainerClient container = mock(BlobContainerClient.class);
        BlobContainerProperties properties = mock(BlobContainerProperties.class);
        when(container.getProperties()).thenReturn(properties);
        when(properties.getBlobPublicAccess()).thenReturn(null);

        assertThatCode(() -> new AzureBlobPrivateReceiptStorageAdapter(
            container,
            Duration.ofSeconds(1),
            Duration.ofMillis(1)
        )).doesNotThrowAnyException();
    }
}

package com.tieat.settlement.receipt.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LocalPrivateReceiptStorageAdapterTest {

    @Test
    void keepsObjectsOutsideWebResourcesAndDeletesRecoveryCopiesTogether() throws Exception {
        Path root = Files.createTempDirectory("tieat-private-receipts");
        LocalPrivateReceiptStorageAdapter storage = new LocalPrivateReceiptStorageAdapter(root);
        byte[] bytes = "%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        storage.put("opaque-receipt-object", bytes, "application/pdf");
        assertThat(storage.get("opaque-receipt-object")).containsExactly(bytes);
        Path object = root.resolve("opaque-receipt-object");
        Files.write(object.resolveSibling("opaque-receipt-object.backup"), bytes);
        Files.write(object.resolveSibling("opaque-receipt-object.version"), bytes);

        storage.deleteAllVersions("opaque-receipt-object");

        assertThat(Files.exists(object)).isFalse();
        assertThat(Files.exists(object.resolveSibling("opaque-receipt-object.backup"))).isFalse();
        assertThat(Files.exists(object.resolveSibling("opaque-receipt-object.version"))).isFalse();
    }

    @Test
    void reconciliationKeepsRecoveryCopiesForKnownObjectsAndDeletesUnknownObjects() throws Exception {
        Path root = Files.createTempDirectory("tieat-private-receipts-reconcile");
        LocalPrivateReceiptStorageAdapter storage = new LocalPrivateReceiptStorageAdapter(root);
        byte[] bytes = "%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        storage.put("known-object", bytes, "application/pdf");
        Files.write(root.resolve("known-object.backup"), bytes);
        Files.write(root.resolve("known-object.version"), bytes);
        storage.put("orphan-object", bytes, "application/pdf");

        storage.reconcileOrphans(java.util.Set.of("known-object"));

        assertThat(Files.exists(root.resolve("known-object"))).isTrue();
        assertThat(Files.exists(root.resolve("known-object.backup"))).isTrue();
        assertThat(Files.exists(root.resolve("known-object.version"))).isTrue();
        assertThat(Files.exists(root.resolve("orphan-object"))).isFalse();
    }
}

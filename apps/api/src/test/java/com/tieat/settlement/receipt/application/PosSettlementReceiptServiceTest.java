package com.tieat.settlement.receipt.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tieat.settlement.receipt.adapter.out.persistence.PosSettlementReceiptPersistenceAdapter;
import com.tieat.settlement.receipt.domain.PosSettlementReceipt;
import com.tieat.settlement.receipt.domain.ReceiptStorage;
import com.tieat.store.domain.StoreId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class PosSettlementReceiptServiceTest {

    private static final StoreId STORE_ID = new StoreId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final UUID SETTLEMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant UPLOADED_AT = Instant.parse("2026-08-14T00:00:00Z");

    @Mock
    private PosSettlementReceiptPersistenceAdapter repository;
    @Mock
    private ReceiptStorage storage;

    private PosSettlementReceiptService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new PosSettlementReceiptService(
            repository,
            storage,
            Clock.fixed(UPLOADED_AT, ZoneOffset.UTC)
        );
        when(repository.lockSettlement(SETTLEMENT_ID, STORE_ID)).thenReturn(true);
        when(repository.findBySettlementIdAndStoreId(SETTLEMENT_ID, STORE_ID)).thenReturn(Optional.empty());
    }

    @Test
    void storesOneCleanReceiptWithServerExpiryWithoutTouchingSettlementAmounts() {
        byte[] png = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00
        };

        PosSettlementReceipt receipt = service.upload(
            STORE_ID,
            SETTLEMENT_ID,
            "../../pos.png",
            "image/png",
            png
        );

        assertThat(receipt.posSettlementId()).isEqualTo(SETTLEMENT_ID);
        assertThat(receipt.fileName()).isEqualTo("pos.png");
        assertThat(receipt.uploadedAt()).isEqualTo(UPLOADED_AT);
        assertThat(receipt.expiresAt()).isEqualTo(Instant.parse("2027-08-14T00:00:00Z"));
        assertThat(receipt.scanStatus()).isEqualTo(PosSettlementReceipt.ScanStatus.CLEAN);
        assertThat(UUID.fromString(receipt.objectKey()).toString()).isEqualTo(receipt.objectKey());
        verify(storage).put(anyString(), any(byte[].class), org.mockito.ArgumentMatchers.eq("image/png"));
        verify(repository).insert(receipt);
    }

    @Test
    void rejectsMimeSignatureMismatchBeforeAnyStorageWrite() {
        byte[] pdf = "%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> service.upload(STORE_ID, SETTLEMENT_ID, "receipt.jpg", "image/jpeg", pdf))
            .isInstanceOf(PosSettlementReceiptExceptions.Validation.class)
            .extracting(exception -> ((PosSettlementReceiptExceptions.Validation) exception).reason())
            .isEqualTo(PosSettlementReceiptExceptions.Validation.Reason.CONTENT_SIGNATURE_MISMATCH);

        verify(storage, never()).put(anyString(), any(byte[].class), anyString());
    }

    @Test
    void rejectsDuplicateAttachmentWithoutReplacingTheExistingObject() {
        PosSettlementReceipt existing = new PosSettlementReceipt(
            UUID.randomUUID(),
            SETTLEMENT_ID,
            STORE_ID,
            "opaque-receipt-object",
            "receipt.png",
            "image/png",
            9,
            UPLOADED_AT,
            UPLOADED_AT.plusSeconds(365L * 24 * 60 * 60),
            PosSettlementReceipt.ScanStatus.CLEAN,
            null
        );
        when(repository.findBySettlementIdAndStoreId(SETTLEMENT_ID, STORE_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.upload(
            STORE_ID,
            SETTLEMENT_ID,
            "receipt.png",
            "image/png",
            new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}
        )).isInstanceOf(PosSettlementReceiptExceptions.AlreadyAttached.class);

        verify(storage, never()).put(anyString(), any(byte[].class), anyString());
        verify(repository, never()).insert(any());
    }

    @Test
    void doesNotDownloadExpiredReceipt() {
        PosSettlementReceipt expired = new PosSettlementReceipt(
            UUID.randomUUID(),
            SETTLEMENT_ID,
            STORE_ID,
            "opaque-receipt-object",
            "receipt.png",
            "image/png",
            9,
            UPLOADED_AT,
            UPLOADED_AT.plusSeconds(365L * 24 * 60 * 60),
            PosSettlementReceipt.ScanStatus.CLEAN,
            null
        );
        when(repository.findBySettlementIdAndStoreId(SETTLEMENT_ID, STORE_ID)).thenReturn(Optional.of(expired));
        service = new PosSettlementReceiptService(repository, storage, Clock.fixed(expired.expiresAt(), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.download(STORE_ID, SETTLEMENT_ID))
            .isInstanceOf(PosSettlementReceiptExceptions.NotFound.class);
        verify(storage, never()).get(anyString());
    }
}

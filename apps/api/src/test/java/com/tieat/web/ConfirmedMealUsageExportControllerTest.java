package com.tieat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tieat.identity.adapter.in.security.StoreAccountPrincipal;
import com.tieat.identity.domain.StoreAccount;
import com.tieat.ledger.application.ConfirmedMealUsageExportService;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.store.domain.StoreId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class ConfirmedMealUsageExportControllerTest {

    private static final StoreId STORE_ID = new StoreId(UUID.fromString("9d5e37dd-dbe2-40dc-97fb-8e77c89aa4cb"));
    private static final MealContractId CONTRACT_ID = new MealContractId(
        UUID.fromString("8cb73a47-d5c5-4f7a-8db0-b61e171c4f0a")
    );

    @Test
    void returnsNoStoreXlsxWithTheFixedSafeFilenameAndForwardsAuthenticatedScope() {
        ConfirmedMealUsageExportService exportService = mock(ConfirmedMealUsageExportService.class);
        byte[] bytes = new byte[] { 80, 75, 3, 4 };
        when(exportService.export(STORE_ID, "2026-08-01", "2026-08-31", CONTRACT_ID)).thenReturn(bytes);
        ConfirmedMealUsageExportController controller = new ConfirmedMealUsageExportController(exportService);
        StoreAccountPrincipal principal = new StoreAccountPrincipal(
            new StoreAccount("store-hk", "password-hash", STORE_ID, true)
        );

        ResponseEntity<ByteArrayResource> response = controller.download(
            "2026-08-01", "2026-08-31", CONTRACT_ID.value(), principal
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getContentType()).isEqualTo(
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        );
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .isEqualTo("attachment; filename=\"confirmed-meal-usages.xlsx\"");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getByteArray()).isEqualTo(bytes);
        verify(exportService).export(STORE_ID, "2026-08-01", "2026-08-31", CONTRACT_ID);
    }
}

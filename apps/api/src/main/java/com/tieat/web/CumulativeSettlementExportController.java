package com.tieat.web;

import com.tieat.identity.adapter.in.security.StoreAccountPrincipal;
import com.tieat.partnership.domain.MealContractId;
import com.tieat.settlement.application.CumulativeSettlementExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pos-settlements")
class CumulativeSettlementExportController {

    private static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    private final CumulativeSettlementExportService exportService;

    CumulativeSettlementExportController(CumulativeSettlementExportService exportService) {
        this.exportService = exportService;
    }

    @Operation(summary = "Download cumulative settlement workbook for one meal contract")
    @SecurityRequirement(name = "sessionCookie")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Cumulative settlement XLSX bytes"),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Access denied", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Meal contract was not found in the authenticated store", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "500", description = "Internal error", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @GetMapping("/exports/{mealContractId}")
    ResponseEntity<ByteArrayResource> download(
        @PathVariable UUID mealContractId,
        @AuthenticationPrincipal StoreAccountPrincipal principal
    ) {
        byte[] bytes = exportService.export(
            principal.storeId(),
            new MealContractId(mealContractId)
        );
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename("cumulative-settlement.xlsx", StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(XLSX_MEDIA_TYPE)
            .contentLength(bytes.length)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .body(new ByteArrayResource(bytes));
    }
}

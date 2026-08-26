package com.tieat.web;

import com.tieat.identity.adapter.in.security.StoreAccountPrincipal;
import com.tieat.ledger.application.ConfirmedMealUsageExportService;
import com.tieat.partnership.domain.MealContractId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meal-usages/confirmed")
class ConfirmedMealUsageExportController {

    private static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );
    private static final String FILE_NAME = "confirmed-meal-usages.xlsx";

    private final ConfirmedMealUsageExportService exportService;

    ConfirmedMealUsageExportController(ConfirmedMealUsageExportService exportService) {
        this.exportService = exportService;
    }

    @Operation(summary = "Download confirmed meal usage ledger for an authenticated store date range")
    @SecurityRequirement(name = "sessionCookie")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Confirmed meal usage XLSX bytes"),
        @ApiResponse(responseCode = "400", description = "Invalid date range", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Access denied", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Meal contract was not found in the authenticated store", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "413", description = "Export contains more than 10000 rows", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "500", description = "Internal error", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @GetMapping("/export")
    ResponseEntity<ByteArrayResource> download(
        @Parameter(required = true, schema = @Schema(pattern = "^\\d{4}-(0[1-9]|1[0-2])-([0-2]\\d|3[01])$"))
        @RequestParam String fromDate,
        @Parameter(required = true, schema = @Schema(pattern = "^\\d{4}-(0[1-9]|1[0-2])-([0-2]\\d|3[01])$"))
        @RequestParam String toDate,
        @Parameter(required = false, description = "Optional authenticated-store partner contract filter")
        @RequestParam(required = false) UUID mealContractId,
        @AuthenticationPrincipal StoreAccountPrincipal principal
    ) {
        byte[] bytes = exportService.export(
            principal.storeId(),
            fromDate,
            toDate,
            mealContractId == null ? null : new MealContractId(mealContractId)
        );
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(FILE_NAME)
            .build();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(XLSX_MEDIA_TYPE)
            .contentLength(bytes.length)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .body(new ByteArrayResource(bytes));
    }
}

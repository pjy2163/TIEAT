package com.tieat.web;

import com.tieat.identity.adapter.in.security.StoreAccountPrincipal;
import com.tieat.settlement.receipt.application.PosSettlementReceiptService;
import com.tieat.settlement.receipt.domain.PosSettlementReceipt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/pos-settlements")
class PosSettlementReceiptController {

    private final PosSettlementReceiptService receiptService;

    PosSettlementReceiptController(PosSettlementReceiptService receiptService) {
        this.receiptService = receiptService;
    }

    @Operation(summary = "Attach one private receipt to an existing POS settlement")
    @SecurityRequirement(name = "sessionCookie")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Private receipt metadata", content = @Content(schema = @Schema(implementation = PosSettlementReceiptResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid receipt file", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Access denied or invalid CSRF token", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Settlement was not found in the authenticated store", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "409", description = "Settlement already has a receipt", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "422", description = "Receipt did not pass malware scanning", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @PostMapping(value = "/{posSettlementId}/receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<PosSettlementReceiptResponse> upload(
        @PathVariable UUID posSettlementId,
        @RequestPart("file") MultipartFile file,
        @AuthenticationPrincipal StoreAccountPrincipal principal
    ) throws IOException {
        PosSettlementReceipt receipt = receiptService.upload(
            principal.storeId(),
            posSettlementId,
            file.getOriginalFilename(),
            file.getContentType(),
            file.getBytes()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(PosSettlementReceiptResponse.from(receipt));
    }

    @Operation(summary = "Download a clean private POS settlement receipt")
    @SecurityRequirement(name = "sessionCookie")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Private receipt bytes"),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "403", description = "Access denied", content = @Content(schema = @Schema(implementation = ProblemResponse.class))),
        @ApiResponse(responseCode = "404", description = "Receipt was not found, expired, or not clean", content = @Content(schema = @Schema(implementation = ProblemResponse.class)))
    })
    @GetMapping("/{posSettlementId}/receipt")
    ResponseEntity<ByteArrayResource> download(
        @PathVariable UUID posSettlementId,
        @AuthenticationPrincipal StoreAccountPrincipal principal
    ) {
        PosSettlementReceiptService.ReceiptDownload download = receiptService.download(principal.storeId(), posSettlementId);
        PosSettlementReceipt receipt = download.receipt();
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(receipt.fileName(), StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.parseMediaType(receipt.contentType()))
            .contentLength(download.bytes().length)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .body(new ByteArrayResource(download.bytes()));
    }

    record PosSettlementReceiptResponse(
        UUID posSettlementId,
        String fileName,
        String contentType,
        long sizeBytes,
        Instant uploadedAt,
        Instant expiresAt
    ) {

        static PosSettlementReceiptResponse from(PosSettlementReceipt receipt) {
            return new PosSettlementReceiptResponse(
                receipt.posSettlementId(),
                receipt.fileName(),
                receipt.contentType(),
                receipt.sizeBytes(),
                receipt.uploadedAt(),
                receipt.expiresAt()
            );
        }
    }
}

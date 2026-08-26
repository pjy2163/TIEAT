package com.tieat.ledger.adapter.out.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.tieat.ledger.application.ConfirmedMealUsageExportSnapshot;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ApachePoiConfirmedMealUsageWorkbookGeneratorTest {

    private static final Instant USED_AT = Instant.parse("2026-08-05T01:00:00Z");
    private static final Instant CONFIRMED_AT = Instant.parse("2026-08-05T02:00:00Z");

    @Test
    void writesSummaryAndAllowlistedDetailColumnsWithoutInternalOrPersonalIdentifiers() throws Exception {
        ConfirmedMealUsageExportSnapshot snapshot = new ConfirmedMealUsageExportSnapshot(
            "2026-08-01",
            "2026-08-31",
            "선택한 협력사·계약",
            Instant.parse("2026-08-26T04:00:00Z"),
            List.of(new ConfirmedMealUsageExportSnapshot.Row("협력사 A", USED_AT, CONFIRMED_AT, 12_000, "결제 전")),
            12_000
        );

        byte[] bytes = new ApachePoiConfirmedMealUsageWorkbookGenerator().generate(snapshot);

        assertThat(bytes).startsWith((byte) 'P', (byte) 'K');
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            assertThat(workbook.getSheetName(0)).isEqualTo("전체 장부");
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("조회 기간");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("2026-08-01~2026-08-31");
            assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("선택한 협력사·계약");
            assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).isEqualTo("총금액(원)");
            assertThat(sheet.getRow(6).getCell(1).getNumericCellValue()).isEqualTo(12_000D);
            assertThat(sheet.getRow(9).getPhysicalNumberOfCells()).isEqualTo(5);
            assertThat(sheet.getRow(9).getCell(0).getStringCellValue()).isEqualTo("협력사명");
            assertThat(sheet.getRow(9).getCell(1).getStringCellValue()).isEqualTo("사용 시각");
            assertThat(sheet.getRow(9).getCell(2).getStringCellValue()).isEqualTo("확인 시각");
            assertThat(sheet.getRow(9).getCell(3).getStringCellValue()).isEqualTo("금액(원)");
            assertThat(sheet.getRow(9).getCell(4).getStringCellValue()).isEqualTo("결제 상태");
            assertThat(sheet.getRow(10).getCell(0).getStringCellValue()).isEqualTo("협력사 A");
            assertThat(sheet.getRow(10).getCell(1).getStringCellValue()).isEqualTo(USED_AT.toString());
            assertThat(sheet.getRow(10).getCell(2).getStringCellValue()).isEqualTo(CONFIRMED_AT.toString());
            assertThat(sheet.getRow(10).getCell(3).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(10).getCell(3).getNumericCellValue()).isEqualTo(12_000D);
            assertThat(sheet.getRow(10).getCell(4).getStringCellValue()).isEqualTo("결제 전");
            assertThat(allCellText(sheet)).doesNotContain(
                "customer@example.com",
                "storeId",
                "mealContractId",
                "usageId",
                "receiptId",
                "objectKey",
                "8cb73a47-d5c5-4f7a-8db0-b61e171c4f0a"
            );
        }
    }

    private String allCellText(org.apache.poi.ss.usermodel.Sheet sheet) {
        StringBuilder text = new StringBuilder();
        for (Row row : sheet) {
            for (var cell : row) {
                if (cell.getCellType() == CellType.STRING) {
                    text.append(cell.getStringCellValue()).append('\n');
                }
            }
        }
        return text.toString();
    }
}

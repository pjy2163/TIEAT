package com.tieat.settlement.adapter.out.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.settlement.domain.CumulativeSettlementSnapshot;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ApachePoiCumulativeSettlementWorkbookGeneratorTest {

    private static final MealContractId CONTRACT_ID = new MealContractId(
        UUID.fromString("f49a63ea-e09e-4ce6-8e36-e531521cdbcf")
    );

    @Test
    void writesAReadableMoneyOnlyWorkbookWithoutPartnerIdentifiers() throws Exception {
        CumulativeSettlementSnapshot snapshot = new CumulativeSettlementSnapshot(
            CONTRACT_ID,
            java.time.Instant.parse("2026-08-25T04:00:00Z"),
            15_000,
            4_000,
            2_000,
            11_000,
            7_000,
            7_000
        );

        byte[] bytes = new ApachePoiCumulativeSettlementWorkbookGenerator().generate(snapshot);

        assertThat(bytes).startsWith((byte) 'P', (byte) 'K');
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            assertThat(workbook.getSheetName(0)).isEqualTo("누적 정산");
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("누적 정산");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("요청 시점까지 누적");
            assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("2026-08-25T04:00:00Z");
            assertThat(sheet.getRow(5).getCell(1).getStringCellValue()).isEqualTo("포함하지 않음");
            assertAmount(sheet.getRow(8), "결제 전 총금액", 15_000);
            assertAmount(sheet.getRow(9), "선불 적용액", 4_000);
            assertAmount(sheet.getRow(10), "현재 선불 잔액", 2_000);
            assertAmount(sheet.getRow(11), "미수금 발생액", 11_000);
            assertAmount(sheet.getRow(12), "기록된 POS 결제액", 7_000);
            assertAmount(sheet.getRow(13), "정산 배분액", 7_000);
            assertAmount(sheet.getRow(14), "정산 후 미수 잔액", 4_000);
            assertThat(allCellText(sheet)).doesNotContain("협력사 대표", CONTRACT_ID.value().toString());
        }
    }

    private void assertAmount(Row row, String label, long expected) {
        assertThat(row.getCell(0).getStringCellValue()).isEqualTo(label);
        assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(row.getCell(1).getNumericCellValue()).isEqualTo((double) expected);
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

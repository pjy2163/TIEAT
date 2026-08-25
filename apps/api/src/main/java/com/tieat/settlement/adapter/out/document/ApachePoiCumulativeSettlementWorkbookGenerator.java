package com.tieat.settlement.adapter.out.document;

import com.tieat.settlement.application.CumulativeSettlementWorkbookGenerationException;
import com.tieat.settlement.application.CumulativeSettlementWorkbookGenerator;
import com.tieat.settlement.domain.CumulativeSettlementSnapshot;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public final class ApachePoiCumulativeSettlementWorkbookGenerator implements CumulativeSettlementWorkbookGenerator {

    private static final String SHEET_NAME = "누적 정산";
    private static final String AMOUNT_FORMAT = "#,##0";

    @Override
    public byte[] generate(CumulativeSettlementSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "Settlement snapshot must be supplied");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(SHEET_NAME);
            CellStyle titleStyle = titleStyle(workbook);
            CellStyle metadataLabelStyle = metadataLabelStyle(workbook);
            CellStyle metadataValueStyle = metadataValueStyle(workbook);
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle amountLabelStyle = amountLabelStyle(workbook);
            CellStyle amountStyle = amountStyle(workbook);

            Row title = sheet.createRow(0);
            title.setHeightInPoints(24);
            title.createCell(0).setCellValue("누적 정산");
            title.getCell(0).setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));

            metadataRow(sheet, 2, "데이터 범위", "요청 시점까지 누적", metadataLabelStyle, metadataValueStyle);
            metadataRow(sheet, 3, "생성 시각", snapshot.generatedAt().toString(), metadataLabelStyle, metadataValueStyle);
            metadataRow(sheet, 4, "통화", "KRW", metadataLabelStyle, metadataValueStyle);
            metadataRow(sheet, 5, "개인 식별 정보", "포함하지 않음", metadataLabelStyle, metadataValueStyle);

            Row header = sheet.createRow(7);
            header.createCell(0).setCellValue("금액 항목");
            header.createCell(1).setCellValue("금액(원)");
            header.getCell(0).setCellStyle(headerStyle);
            header.getCell(1).setCellStyle(headerStyle);

            amountRow(sheet, 8, "결제 전 총금액", snapshot.confirmedUsageTotalMinor(), amountLabelStyle, amountStyle);
            amountRow(sheet, 9, "선불 적용액", snapshot.prepaidAppliedTotalMinor(), amountLabelStyle, amountStyle);
            amountRow(sheet, 10, "현재 선불 잔액", snapshot.prepaidBalanceMinor(), amountLabelStyle, amountStyle);
            amountRow(sheet, 11, "미수금 발생액", snapshot.receivableCreatedTotalMinor(), amountLabelStyle, amountStyle);
            amountRow(sheet, 12, "기록된 POS 결제액", snapshot.recordedPosPaymentTotalMinor(), amountLabelStyle, amountStyle);
            amountRow(sheet, 13, "정산 배분액", snapshot.allocatedReceivableTotalMinor(), amountLabelStyle, amountStyle);
            amountRow(sheet, 14, "정산 후 미수 잔액", snapshot.outstandingReceivableTotalMinor(), amountLabelStyle, amountStyle);

            sheet.setColumnWidth(0, 28 * 256);
            sheet.setColumnWidth(1, 18 * 256);
            sheet.createFreezePane(0, 8);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new CumulativeSettlementWorkbookGenerationException(exception);
        }
    }

    private void metadataRow(
        Sheet sheet,
        int rowIndex,
        String label,
        String value,
        CellStyle labelStyle,
        CellStyle valueStyle
    ) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(label);
        row.createCell(1).setCellValue(value);
        row.getCell(0).setCellStyle(labelStyle);
        row.getCell(1).setCellStyle(valueStyle);
    }

    private void amountRow(
        Sheet sheet,
        int rowIndex,
        String label,
        long amountMinor,
        CellStyle labelStyle,
        CellStyle amountStyle
    ) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(label);
        row.createCell(1).setCellValue(amountMinor);
        row.getCell(0).setCellStyle(labelStyle);
        row.getCell(1).setCellStyle(amountStyle);
    }

    private CellStyle titleStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 16);
        style.setFont(font);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private CellStyle metadataLabelStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setFont(boldFont(workbook));
        style.setAlignment(HorizontalAlignment.LEFT);
        return style;
    }

    private CellStyle metadataValueStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setAlignment(HorizontalAlignment.LEFT);
        return style;
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = boldFont(workbook);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private CellStyle amountLabelStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private CellStyle amountStyle(XSSFWorkbook workbook) {
        CellStyle style = amountLabelStyle(workbook);
        style.setAlignment(HorizontalAlignment.RIGHT);
        style.setDataFormat(workbook.createDataFormat().getFormat(AMOUNT_FORMAT));
        return style;
    }

    private Font boldFont(XSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        return font;
    }
}

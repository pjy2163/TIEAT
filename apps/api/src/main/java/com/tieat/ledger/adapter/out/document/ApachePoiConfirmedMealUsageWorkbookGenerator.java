package com.tieat.ledger.adapter.out.document;

import com.tieat.ledger.application.ConfirmedMealUsageExportSnapshot;
import com.tieat.ledger.application.ConfirmedMealUsageWorkbookGenerationException;
import com.tieat.ledger.application.ConfirmedMealUsageWorkbookGenerator;
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
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public final class ApachePoiConfirmedMealUsageWorkbookGenerator implements ConfirmedMealUsageWorkbookGenerator {

    private static final String SHEET_NAME = "전체 장부";
    private static final String AMOUNT_FORMAT = "#,##0";

    @Override
    public byte[] generate(ConfirmedMealUsageExportSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "Confirmed meal usage snapshot must be supplied");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(SHEET_NAME);
            CellStyle titleStyle = titleStyle(workbook);
            CellStyle metadataLabelStyle = metadataLabelStyle(workbook);
            CellStyle metadataValueStyle = metadataValueStyle(workbook);
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle amountStyle = amountStyle(workbook);

            Row title = sheet.createRow(0);
            title.setHeightInPoints(24);
            title.createCell(0).setCellValue("전체 장부");
            title.getCell(0).setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));

            metadataRow(sheet, 2, "조회 기간", snapshot.fromDate() + "~" + snapshot.toDate(), metadataLabelStyle, metadataValueStyle);
            metadataRow(sheet, 3, "조회 범위", snapshot.scopeLabel(), metadataLabelStyle, metadataValueStyle);
            metadataRow(sheet, 4, "생성 시각", snapshot.generatedAt().toString(), metadataLabelStyle, metadataValueStyle);
            metadataNumberRow(sheet, 5, "건수", snapshot.rows().size(), metadataLabelStyle, metadataValueStyle);
            metadataNumberRow(sheet, 6, "총금액(원)", snapshot.totalAmountMinor(), metadataLabelStyle, amountStyle);

            Row detailLabel = sheet.createRow(8);
            detailLabel.createCell(0).setCellValue("상세 내역");
            detailLabel.getCell(0).setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(8, 8, 0, 4));

            Row header = sheet.createRow(9);
            String[] headers = {"협력사명", "사용 시각", "확인 시각", "금액(원)", "결제 상태"};
            for (int column = 0; column < headers.length; column++) {
                header.createCell(column).setCellValue(headers[column]);
                header.getCell(column).setCellStyle(headerStyle);
            }

            for (int index = 0; index < snapshot.rows().size(); index++) {
                ConfirmedMealUsageExportSnapshot.Row source = snapshot.rows().get(index);
                Row row = sheet.createRow(10 + index);
                row.createCell(0).setCellValue(source.partnerDisplayName() == null ? "협력사 정보 없음" : source.partnerDisplayName());
                row.createCell(1).setCellValue(source.usedAt().toString());
                row.createCell(2).setCellValue(source.confirmedAt().toString());
                row.createCell(3).setCellValue(source.amountMinor());
                row.createCell(4).setCellValue(source.paymentStatus() == null ? "" : source.paymentStatus());
                row.getCell(3).setCellStyle(amountStyle);
            }

            sheet.setColumnWidth(0, 24 * 256);
            sheet.setColumnWidth(1, 24 * 256);
            sheet.setColumnWidth(2, 24 * 256);
            sheet.setColumnWidth(3, 16 * 256);
            sheet.setColumnWidth(4, 20 * 256);
            sheet.createFreezePane(0, 10);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new ConfirmedMealUsageWorkbookGenerationException(exception);
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

    private void metadataNumberRow(
        Sheet sheet,
        int rowIndex,
        String label,
        long value,
        CellStyle labelStyle,
        CellStyle valueStyle
    ) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(label);
        row.createCell(1).setCellValue(value);
        row.getCell(0).setCellStyle(labelStyle);
        row.getCell(1).setCellStyle(valueStyle);
    }

    private CellStyle titleStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 16);
        style.setFont(font);
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

    private CellStyle amountStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
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

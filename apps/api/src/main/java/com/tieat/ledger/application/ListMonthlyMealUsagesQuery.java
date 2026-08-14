package com.tieat.ledger.application;

import com.tieat.store.domain.StoreId;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

public record ListMonthlyMealUsagesQuery(StoreId storeId, String fromMonth, String toMonth, int page, int size) {

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter
        .ofPattern("uuuu-MM", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT);

    public ListMonthlyMealUsagesQuery(StoreId storeId, String month, int page, int size) {
        this(storeId, month, month, page, size);
    }

    public ListMonthlyMealUsagesQuery {
        if (toMonth == null) {
            toMonth = fromMonth;
        }
        if (storeId == null || !isYearMonth(fromMonth) || !isYearMonth(toMonth) || page < 0 || size < 1 || size > 100) {
            throw new InvalidMonthlyMealUsageQueryException();
        }
        YearMonth from = parse(fromMonth);
        YearMonth to = parse(toMonth);
        long monthCount = ChronoUnit.MONTHS.between(from, to) + 1;
        if (to.isBefore(from) || monthCount > 12) {
            throw new InvalidMonthlyMealUsageQueryException();
        }
    }

    public String month() {
        return fromMonth;
    }

    public YearMonth fromYearMonth() {
        return parse(fromMonth);
    }

    public YearMonth yearMonth() {
        return fromYearMonth();
    }

    public YearMonth toYearMonth() {
        return parse(toMonth);
    }

    private static YearMonth parse(String value) {
        try {
            return YearMonth.parse(value, MONTH_FORMATTER);
        } catch (DateTimeParseException exception) {
            throw new InvalidMonthlyMealUsageQueryException();
        }
    }

    private static boolean isYearMonth(String value) {
        return value != null && value.matches("\\d{4}-(0[1-9]|1[0-2])");
    }
}

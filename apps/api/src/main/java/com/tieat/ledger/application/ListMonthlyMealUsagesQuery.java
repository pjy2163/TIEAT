package com.tieat.ledger.application;

import com.tieat.store.domain.StoreId;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;

public record ListMonthlyMealUsagesQuery(StoreId storeId, String month, int page, int size) {

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter
        .ofPattern("uuuu-MM", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT);

    public ListMonthlyMealUsagesQuery {
        if (storeId == null || !isYearMonth(month) || page < 0 || size < 1 || size > 100) {
            throw new InvalidMonthlyMealUsageQueryException();
        }
    }

    public YearMonth yearMonth() {
        try {
            return YearMonth.parse(month, MONTH_FORMATTER);
        } catch (DateTimeParseException exception) {
            throw new InvalidMonthlyMealUsageQueryException();
        }
    }

    private static boolean isYearMonth(String value) {
        return value != null && value.matches("\\d{4}-(0[1-9]|1[0-2])");
    }
}

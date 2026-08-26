package com.tieat.ledger.application;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.store.domain.StoreId;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

public record ListConfirmedMealUsagesQuery(
    StoreId storeId,
    String fromDate,
    String toDate,
    int page,
    int size,
    MealContractId mealContractId
) {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter
        .ofPattern("uuuu-MM-dd", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT);
    private static final long MAX_RANGE_DAYS = 366;

    public ListConfirmedMealUsagesQuery {
        if (storeId == null
            || !isDate(fromDate)
            || !isDate(toDate)
            || page < 0
            || size < 1
            || size > 100) {
            throw new InvalidConfirmedMealUsageQueryException();
        }
        LocalDate from = parse(fromDate);
        LocalDate to = parse(toDate);
        long rangeDays = ChronoUnit.DAYS.between(from, to) + 1;
        if (to.isBefore(from) || rangeDays > MAX_RANGE_DAYS) {
            throw new InvalidConfirmedMealUsageQueryException();
        }
    }

    public LocalDate fromLocalDate() {
        return parse(fromDate);
    }

    public LocalDate toLocalDate() {
        return parse(toDate);
    }

    private static LocalDate parse(String value) {
        try {
            return LocalDate.parse(value, DATE_FORMATTER);
        } catch (DateTimeParseException exception) {
            throw new InvalidConfirmedMealUsageQueryException();
        }
    }

    private static boolean isDate(String value) {
        return value != null && value.matches("\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])");
    }
}

package com.tieat.qr.adapter.in.cli;

import com.tieat.partnership.domain.MealContractId;
import com.tieat.qr.application.ManageMealUsageQrOperationsUseCase;
import com.tieat.qr.application.QrOperationException;
import com.tieat.qr.domain.MealUsageQrContext;
import com.tieat.qr.domain.MealUsageQrOperationsRepository.QrPartnerSelection;
import com.tieat.store.domain.StoreId;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnNotWebApplication
@ConditionalOnProperty(prefix = "tieat.qr-operations", name = "command")
public class QrOperationsCommandRunner implements ApplicationRunner {

    private static final String PREFIX = "tieat.qr-operations.";
    private static final String COMMAND = PREFIX + "command";
    private static final String STORE_ID = PREFIX + "store-id";
    private static final String STORE_DISPLAY_NAME = PREFIX + "store-display-name";
    private static final String OPERATOR = PREFIX + "operator";
    private static final String WEB_ORIGIN = PREFIX + "web-origin";
    private static final String MEAL_CONTRACT_ID = PREFIX + "meal-contract-id";
    private static final String QR_SELECTABLE = PREFIX + "qr-selectable";

    private final ManageMealUsageQrOperationsUseCase operations;

    public QrOperationsCommandRunner(ManageMealUsageQrOperationsUseCase operations) {
        this.operations = Objects.requireNonNull(operations);
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!arguments.getNonOptionArgs().isEmpty()) {
            throw new QrOperationException("QR operations do not accept positional arguments");
        }
        String command = required(arguments, COMMAND);
        switch (command) {
            case "issue" -> issue(arguments);
            case "reissue" -> reissue(arguments);
            case "revoke" -> revoke(arguments);
            case "status" -> status(arguments);
            case "list-partners" -> listPartners(arguments);
            case "set-partner-active" -> setPartnerActive(arguments);
            default -> throw new QrOperationException("Unsupported QR operations command: " + command);
        }
    }

    private void issue(ApplicationArguments arguments) {
        rejectUnexpected(arguments, Set.of(COMMAND, STORE_ID, STORE_DISPLAY_NAME, OPERATOR, WEB_ORIGIN));
        String webOrigin = webOrigin(arguments);
        var issued = operations.issue(new ManageMealUsageQrOperationsUseCase.IssueCommand(
            storeId(arguments),
            required(arguments, STORE_DISPLAY_NAME),
            required(arguments, OPERATOR)
        ));
        printIssued(issued, webOrigin);
    }

    private void reissue(ApplicationArguments arguments) {
        rejectUnexpected(arguments, Set.of(COMMAND, STORE_ID, OPERATOR, WEB_ORIGIN));
        String webOrigin = webOrigin(arguments);
        var issued = operations.reissue(new ManageMealUsageQrOperationsUseCase.ReissueCommand(
            storeId(arguments),
            required(arguments, OPERATOR)
        ));
        printIssued(issued, webOrigin);
    }

    private void revoke(ApplicationArguments arguments) {
        rejectUnexpected(arguments, Set.of(COMMAND, STORE_ID, OPERATOR));
        StoreId storeId = storeId(arguments);
        operations.revoke(new ManageMealUsageQrOperationsUseCase.RevokeCommand(storeId, required(arguments, OPERATOR)));
        System.out.println("QR_REVOKED_STORE_ID=" + storeId.value());
    }

    private void status(ApplicationArguments arguments) {
        rejectUnexpected(arguments, Set.of(COMMAND, STORE_ID));
        StoreId storeId = storeId(arguments);
        operations.currentQrContext(storeId).ifPresentOrElse(
            this::printCurrentContext,
            () -> System.out.println("CURRENT_QR=NONE")
        );
    }

    private void listPartners(ApplicationArguments arguments) {
        rejectUnexpected(arguments, Set.of(COMMAND, STORE_ID));
        List<QrPartnerSelection> selections = operations.partnerSelections(storeId(arguments));
        for (QrPartnerSelection selection : selections) {
            System.out.println(
                "PARTNER=" + selection.partnerDisplayName()
                    + "\tMEAL_CONTRACT_ID=" + selection.mealContractId().value()
                    + "\tQR_SELECTABLE=" + selection.qrSelectable()
            );
        }
    }

    private void setPartnerActive(ApplicationArguments arguments) {
        rejectUnexpected(arguments, Set.of(COMMAND, STORE_ID, MEAL_CONTRACT_ID, QR_SELECTABLE, OPERATOR));
        StoreId storeId = storeId(arguments);
        MealContractId mealContractId = mealContractId(arguments);
        var result = operations.changePartnerSelection(new ManageMealUsageQrOperationsUseCase.ChangePartnerSelectionCommand(
            storeId,
            mealContractId,
            requiredBoolean(arguments, QR_SELECTABLE),
            required(arguments, OPERATOR)
        ));
        System.out.println(
            "PARTNER_SELECTION_CHANGED=" + result.changed()
                + "\tMEAL_CONTRACT_ID=" + result.selection().mealContractId().value()
                + "\tQR_SELECTABLE=" + result.selection().qrSelectable()
        );
    }

    private StoreId storeId(ApplicationArguments arguments) {
        return new StoreId(parseUuid(required(arguments, STORE_ID), "store id"));
    }

    private MealContractId mealContractId(ApplicationArguments arguments) {
        return new MealContractId(parseUuid(required(arguments, MEAL_CONTRACT_ID), "meal contract id"));
    }

    private String webOrigin(ApplicationArguments arguments) {
        String value = required(arguments, WEB_ORIGIN);
        try {
            URI uri = new URI(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
                throw new QrOperationException("QR web origin must be an HTTPS origin without a path, query, or fragment");
            }
            return uri.toString().endsWith("/") ? uri.toString().substring(0, uri.toString().length() - 1) : uri.toString();
        } catch (URISyntaxException exception) {
            throw new QrOperationException("QR web origin is invalid");
        }
    }

    private void printIssued(ManageMealUsageQrOperationsUseCase.IssuedQr issued, String webOrigin) {
        System.out.println("QR_CONTEXT_ID=" + issued.context().id().value());
        System.out.println("QR_URL=" + webOrigin + "/qr/" + issued.rawToken());
    }

    private void printCurrentContext(MealUsageQrContext context) {
        System.out.println("CURRENT_QR_CONTEXT_ID=" + context.id().value());
        System.out.println("STORE_DISPLAY_NAME=" + context.storeDisplayName());
        System.out.println("QR_EXPIRES_AT=" + context.expiresAt());
    }

    private String required(ApplicationArguments arguments, String option) {
        List<String> values = arguments.getOptionValues(option);
        if (values == null || values.size() != 1 || values.getFirst() == null || values.getFirst().isBlank()) {
            throw new QrOperationException("QR operations requires one nonblank --" + option + " value");
        }
        return values.getFirst().trim();
    }

    private boolean requiredBoolean(ApplicationArguments arguments, String option) {
        String value = required(arguments, option);
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new QrOperationException("QR operations option --" + option + " must be true or false");
    }

    private UUID parseUuid(String value, String label) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new QrOperationException("QR operations " + label + " must be a UUID");
        }
    }

    private void rejectUnexpected(ApplicationArguments arguments, Set<String> allowed) {
        arguments.getOptionNames().stream()
            .filter(option -> option.startsWith(PREFIX))
            .filter(option -> !allowed.contains(option))
            .findFirst()
            .ifPresent(option -> {
                throw new QrOperationException("Unsupported QR operations option: --" + option);
            });
    }
}

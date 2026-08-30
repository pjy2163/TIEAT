package com.tieat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class TieatApiApplication {

    public static void main(String[] args) {
        boolean nonWebCommandInvocation = isNonWebCommandInvocation(args);
        SpringApplication application = new SpringApplication(TieatApiApplication.class);
        if (nonWebCommandInvocation) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        ConfigurableApplicationContext context = application.run(args);
        if (nonWebCommandInvocation) {
            SpringApplication.exit(context);
        }
    }

    static boolean isNonWebCommandInvocation(String[] args) {
        boolean qrCommandFound = isQrOperationsInvocation(args);
        boolean customerNameAnonymizationOptionFound = false;
        boolean customerNameAnonymizationCommandFound = false;
        for (String arg : args) {
            if (arg.startsWith("--tieat.customer-name-anonymization.")) {
                customerNameAnonymizationOptionFound = true;
                customerNameAnonymizationCommandFound |=
                    arg.startsWith("--tieat.customer-name-anonymization.command=");
            }
        }
        if (customerNameAnonymizationOptionFound && !customerNameAnonymizationCommandFound) {
            throw new IllegalArgumentException(
                "Customer name anonymization requires --tieat.customer-name-anonymization.command=<command>"
            );
        }
        if (qrCommandFound && customerNameAnonymizationCommandFound) {
            throw new IllegalArgumentException(
                "QR operations and customer name anonymization commands cannot be combined"
            );
        }
        return qrCommandFound || customerNameAnonymizationCommandFound;
    }

    static boolean isQrOperationsInvocation(String[] args) {
        boolean qrOperationsOptionFound = false;
        boolean commandFound = false;
        for (String arg : args) {
            if (!arg.startsWith("--tieat.qr-operations.")) {
                continue;
            }
            qrOperationsOptionFound = true;
            commandFound |= arg.startsWith("--tieat.qr-operations.command=");
        }
        if (qrOperationsOptionFound && !commandFound) {
            throw new IllegalArgumentException("QR operations require --tieat.qr-operations.command=<command>");
        }
        return commandFound;
    }
}

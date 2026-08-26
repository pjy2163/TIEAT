package com.tieat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class TieatApiApplication {

    public static void main(String[] args) {
        boolean qrOperationsInvocation = isQrOperationsInvocation(args);
        SpringApplication application = new SpringApplication(TieatApiApplication.class);
        if (qrOperationsInvocation) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        ConfigurableApplicationContext context = application.run(args);
        if (qrOperationsInvocation) {
            SpringApplication.exit(context);
        }
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

package com.tieat.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "tieat.security.public-qr-create")
public record PublicQrCreateProtectionProperties(
    boolean enabled,
    @Min(1) int requestsPerMinute,
    @NotNull List<String> trustedProxyCidrs
) {

    public PublicQrCreateProtectionProperties {
        trustedProxyCidrs = trustedProxyCidrs == null ? List.of() : List.copyOf(trustedProxyCidrs);
    }
}

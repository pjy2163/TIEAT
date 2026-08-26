package com.tieat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "tieat.session.cookie")
public record SessionCookieProperties(Boolean secure) {
}

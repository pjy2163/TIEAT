package com.tieat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SessionCookieProperties.class)
public class SessionCookieConfiguration {

    @Bean
    CookieSerializer cookieSerializer(SessionCookieProperties properties) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("TIEAT_SESSION");
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        if (properties.secure() != null) {
            serializer.setUseSecureCookie(properties.secure());
        }
        return new RememberedSessionCookieSerializer(serializer);
    }
}

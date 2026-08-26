package com.tieat.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.session.web.http.CookieSerializer;

final class RememberedSessionCookieSerializer implements CookieSerializer {

    private final CookieSerializer delegate;

    RememberedSessionCookieSerializer(CookieSerializer delegate) {
        this.delegate = delegate;
    }

    @Override
    public void writeCookieValue(CookieValue cookieValue) {
        if (cookieValue.getCookieMaxAge() != 0) {
            Object requestedMaxAge = cookieValue.getRequest().getAttribute(RememberedSessionPolicy.COOKIE_MAX_AGE_ATTRIBUTE);
            if (requestedMaxAge instanceof Integer maxAge && maxAge > 0) {
                cookieValue.setCookieMaxAge(maxAge);
            }
        }
        delegate.writeCookieValue(cookieValue);
    }

    @Override
    public List<String> readCookieValues(HttpServletRequest request) {
        return delegate.readCookieValues(request);
    }
}

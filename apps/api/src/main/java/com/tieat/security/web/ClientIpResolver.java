package com.tieat.security.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

public final class ClientIpResolver {

    private final List<IpAddressMatcher> trustedProxies;

    public ClientIpResolver(List<String> trustedProxyCidrs) {
        Objects.requireNonNull(trustedProxyCidrs, "Trusted proxy CIDRs must be supplied");
        this.trustedProxies = trustedProxyCidrs.stream()
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(IpAddressMatcher::new)
            .toList();
    }

    public String resolve(HttpServletRequest request) {
        Objects.requireNonNull(request, "Request must be supplied");
        String remoteAddress = canonicalAddress(request.getRemoteAddr());
        if (remoteAddress == null) {
            return "<unknown>";
        }
        if (!isTrustedProxy(remoteAddress)) {
            return remoteAddress;
        }

        List<String> forwardedAddresses = forwardedAddresses(request);
        if (forwardedAddresses.isEmpty()) {
            return remoteAddress;
        }
        String leftmostAddress = null;
        for (int index = forwardedAddresses.size() - 1; index >= 0; index--) {
            String candidate = canonicalAddress(forwardedAddresses.get(index));
            if (candidate == null) {
                return remoteAddress;
            }
            leftmostAddress = candidate;
            if (!isTrustedProxy(candidate)) {
                return candidate;
            }
        }
        return leftmostAddress == null ? remoteAddress : leftmostAddress;
    }

    private List<String> forwardedAddresses(HttpServletRequest request) {
        List<String> addresses = new ArrayList<>();
        for (String value : Collections.list(request.getHeaders("X-Forwarded-For"))) {
            for (String address : value.split(",", -1)) {
                String trimmed = address.trim();
                if (trimmed.isEmpty()) {
                    return List.of();
                }
                addresses.add(trimmed);
            }
        }
        return addresses;
    }

    private boolean isTrustedProxy(String address) {
        return trustedProxies.stream().anyMatch(matcher -> matcher.matches(address));
    }

    private String canonicalAddress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (candidate.indexOf(':') < 0) {
            return canonicalIpv4(candidate);
        }
        if (!candidate.matches("[0-9a-fA-F:]+")) {
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(candidate);
            return address instanceof Inet6Address ? address.getHostAddress() : null;
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    private String canonicalIpv4(String candidate) {
        String[] parts = candidate.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] octets = new int[4];
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (!part.matches("0|[1-9][0-9]{0,2}")) {
                return null;
            }
            int octet = Integer.parseInt(part);
            if (octet > 255) {
                return null;
            }
            octets[index] = octet;
        }
        return octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
    }
}

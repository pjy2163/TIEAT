package com.tieat.onboarding.adapter.out.naver;

import com.tieat.onboarding.application.OnboardingException;
import com.tieat.onboarding.application.StorePlaceSearchGateway;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.HtmlUtils;

@Component
public class NaverLocalPlaceSearchAdapter implements StorePlaceSearchGateway {

    private static final String NAVER_API_HUB_BASE_URL = "https://naverapihub.apigw.ntruss.com";
    private static final String LOCAL_SEARCH_PATH = "/search/v1/local";
    private static final int RESULT_LIMIT = 5;

    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;

    public NaverLocalPlaceSearchAdapter(
        @Qualifier("naverApiHubRestClientBuilder") RestClient.Builder restClientBuilder,
        @Value("${NAVER_API_HUB_CLIENT_ID:}") String clientId,
        @Value("${NAVER_API_HUB_CLIENT_SECRET:}") String clientSecret
    ) {
        this.restClient = Objects.requireNonNull(restClientBuilder)
            .baseUrl(NAVER_API_HUB_BASE_URL)
            .build();
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
    }

    @Override
    public List<PlaceSearchResult> search(String query) {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw OnboardingException.placeSearchUnavailable();
        }
        try {
            NaverLocalSearchResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path(LOCAL_SEARCH_PATH)
                    .queryParam("query", query)
                    .queryParam("display", RESULT_LIMIT)
                    .queryParam("start", 1)
                    .queryParam("sort", "random")
                    .queryParam("format", "json")
                    .build())
                .header("X-NCP-APIGW-API-KEY-ID", clientId)
                .header("X-NCP-APIGW-API-KEY", clientSecret)
                .retrieve()
                .body(NaverLocalSearchResponse.class);
            if (response == null || response.items() == null) {
                throw OnboardingException.placeSearchUnavailable();
            }
            return response.items().stream()
                .limit(RESULT_LIMIT)
                .map(this::toPlaceSearchResult)
                .toList();
        } catch (RestClientException | IllegalArgumentException exception) {
            throw OnboardingException.placeSearchUnavailable();
        }
    }

    private PlaceSearchResult toPlaceSearchResult(NaverPlaceDocument document) {
        if (document == null) {
            throw OnboardingException.placeSearchUnavailable();
        }
        String storeDisplayName = displayText(document.title());
        if (storeDisplayName == null) {
            throw OnboardingException.placeSearchUnavailable();
        }
        String address = firstNonBlank(document.roadAddress(), document.address());
        String category = displayText(document.category());
        return new PlaceSearchResult(
            syntheticPlaceId(document, storeDisplayName, address, category),
            storeDisplayName,
            address,
            category
        );
    }

    private String syntheticPlaceId(
        NaverPlaceDocument document,
        String storeDisplayName,
        String address,
        String category
    ) {
        String identity = String.join(
            "|",
            "naver",
            displayText(document.link()) == null ? "" : displayText(document.link()),
            storeDisplayName,
            address == null ? "" : address,
            category == null ? "" : category
        );
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String firstNonBlank(String preferred, String fallback) {
        String normalizedPreferred = displayText(preferred);
        return normalizedPreferred == null ? displayText(fallback) : normalizedPreferred;
    }

    private String displayText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = HtmlUtils.htmlUnescape(value)
            .replaceAll("<[^>]*>", "")
            .trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private record NaverLocalSearchResponse(
        String lastBuildDate,
        Integer total,
        Integer start,
        Integer display,
        List<NaverPlaceDocument> items
    ) {
    }

    private record NaverPlaceDocument(
        String title,
        String link,
        String category,
        String description,
        String telephone,
        String address,
        String roadAddress,
        String mapx,
        String mapy
    ) {
    }
}

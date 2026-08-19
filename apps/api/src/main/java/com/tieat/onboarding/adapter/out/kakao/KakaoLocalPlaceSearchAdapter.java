package com.tieat.onboarding.adapter.out.kakao;

import com.tieat.onboarding.application.OnboardingException;
import com.tieat.onboarding.application.StorePlaceSearchGateway;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Component
public class KakaoLocalPlaceSearchAdapter implements StorePlaceSearchGateway {

    private static final String KAKAO_LOCAL_BASE_URL = "https://dapi.kakao.com";
    private static final int RESULT_LIMIT = 10;

    private final RestClient restClient;
    private final String restApiKey;

    public KakaoLocalPlaceSearchAdapter(
        @Qualifier("kakaoLocalRestClientBuilder") RestClient.Builder restClientBuilder,
        @Value("${KAKAO_LOCAL_REST_API_KEY:}") String restApiKey
    ) {
        this.restClient = Objects.requireNonNull(restClientBuilder)
            .baseUrl(KAKAO_LOCAL_BASE_URL)
            .build();
        this.restApiKey = restApiKey == null ? "" : restApiKey.trim();
    }

    @Override
    public List<PlaceSearchResult> search(String query) {
        if (restApiKey.isBlank()) {
            throw OnboardingException.placeSearchUnavailable();
        }
        try {
            KakaoKeywordSearchResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/v2/local/search/keyword.json")
                    .queryParam("query", query)
                    .queryParam("page", 1)
                    .queryParam("size", RESULT_LIMIT)
                    .queryParam("sort", "accuracy")
                    .build())
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey)
                .retrieve()
                .body(KakaoKeywordSearchResponse.class);
            if (response == null || response.documents() == null) {
                throw OnboardingException.placeSearchUnavailable();
            }
            return response.documents().stream()
                .limit(RESULT_LIMIT)
                .map(this::toPlaceSearchResult)
                .toList();
        } catch (RestClientException | IllegalArgumentException exception) {
            throw OnboardingException.placeSearchUnavailable();
        }
    }

    private PlaceSearchResult toPlaceSearchResult(KakaoPlaceDocument document) {
        if (document == null || isBlank(document.id()) || isBlank(document.placeName())) {
            throw OnboardingException.placeSearchUnavailable();
        }
        String address = firstNonBlank(document.roadAddressName(), document.addressName());
        return new PlaceSearchResult(
            document.id(),
            document.placeName(),
            address,
            blankToNull(document.categoryName())
        );
    }

    private String firstNonBlank(String preferred, String fallback) {
        String normalizedPreferred = blankToNull(preferred);
        return normalizedPreferred == null ? blankToNull(fallback) : normalizedPreferred;
    }

    private boolean isBlank(String value) {
        return blankToNull(value) == null;
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private record KakaoKeywordSearchResponse(List<KakaoPlaceDocument> documents) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record KakaoPlaceDocument(
        String id,
        String placeName,
        String categoryName,
        String addressName,
        String roadAddressName
    ) {
    }
}

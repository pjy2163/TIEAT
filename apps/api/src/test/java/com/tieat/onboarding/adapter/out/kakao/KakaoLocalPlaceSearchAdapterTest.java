package com.tieat.onboarding.adapter.out.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.tieat.onboarding.application.OnboardingException;
import com.tieat.onboarding.application.StorePlaceSearchGateway.PlaceSearchResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KakaoLocalPlaceSearchAdapterTest {

    @Test
    void sendsTheFixedKakaoKeywordRequestAndProjectsOnlyPlaceDisplayFields() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KakaoLocalPlaceSearchAdapter adapter = new KakaoLocalPlaceSearchAdapter(builder, "test-rest-key");
        server.expect(request -> {
                assertThat(request.getURI().getScheme()).isEqualTo("https");
                assertThat(request.getURI().getHost()).isEqualTo("dapi.kakao.com");
                assertThat(request.getURI().getPath()).isEqualTo("/v2/local/search/keyword.json");
                assertThat(request.getURI().getQuery()).contains("query=TIEAT 강남점", "page=1", "size=10", "sort=accuracy");
            })
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "KakaoAK test-rest-key"))
            .andRespond(withSuccess("""
                {"documents":[
                  {"id":"26338954","place_name":"TIEAT 강남점","category_name":"음식점 > 한식", "address_name":"서울 강남구 지번 1", "road_address_name":"서울 강남구 테헤란로 123"},
                  {"id":"26338955","place_name":"TIEAT 역삼점","category_name":"음식점 > 분식", "address_name":"서울 강남구 역삼동 2", "road_address_name":""}
                ]}
                """, MediaType.APPLICATION_JSON));

        List<PlaceSearchResult> results = adapter.search("TIEAT 강남점");

        assertThat(results).containsExactly(
            new PlaceSearchResult("26338954", "TIEAT 강남점", "서울 강남구 테헤란로 123", "음식점 > 한식"),
            new PlaceSearchResult("26338955", "TIEAT 역삼점", "서울 강남구 역삼동 2", "음식점 > 분식")
        );
        server.verify();
    }

    @Test
    void failsClosedWithoutAKeyBeforeMakingARequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KakaoLocalPlaceSearchAdapter adapter = new KakaoLocalPlaceSearchAdapter(builder, " ");

        assertThatThrownBy(() -> adapter.search("TIEAT"))
            .isInstanceOf(OnboardingException.class)
            .extracting(exception -> ((OnboardingException) exception).reason())
            .isEqualTo(OnboardingException.Reason.PLACE_SEARCH_UNAVAILABLE);
        server.verify();
    }

    @Test
    void hidesUpstreamFailuresBehindTheUnavailableContract() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KakaoLocalPlaceSearchAdapter adapter = new KakaoLocalPlaceSearchAdapter(builder, "test-rest-key");
        server.expect(method(HttpMethod.GET)).andRespond(withServerError());

        assertThatThrownBy(() -> adapter.search("TIEAT"))
            .isInstanceOf(OnboardingException.class)
            .extracting(exception -> ((OnboardingException) exception).reason())
            .isEqualTo(OnboardingException.Reason.PLACE_SEARCH_UNAVAILABLE);
        server.verify();
    }
}

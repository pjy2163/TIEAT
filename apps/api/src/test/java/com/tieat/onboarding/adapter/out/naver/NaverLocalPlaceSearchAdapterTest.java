package com.tieat.onboarding.adapter.out.naver;

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

class NaverLocalPlaceSearchAdapterTest {

    @Test
    void sendsTheFixedNaverLocalRequestAndProjectsOnlyPlaceDisplayFields() {
        RestClient.Builder builder = new NaverApiHubRestClientConfiguration().naverApiHubRestClientBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NaverLocalPlaceSearchAdapter adapter = new NaverLocalPlaceSearchAdapter(builder, "test-client-id", "test-client-secret");
        server.expect(request -> {
                assertThat(request.getURI().getScheme()).isEqualTo("https");
                assertThat(request.getURI().getHost()).isEqualTo("naverapihub.apigw.ntruss.com");
                assertThat(request.getURI().getPath()).isEqualTo("/search/v1/local");
                assertThat(request.getURI().getQuery()).contains(
                    "query=TIEAT 강남점", "display=5", "start=1", "sort=random", "format=json"
                );
            })
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("X-NCP-APIGW-API-KEY-ID", "test-client-id"))
            .andExpect(header("X-NCP-APIGW-API-KEY", "test-client-secret"))
            .andRespond(withSuccess("""
                {"lastBuildDate":"Thu, 11 Jun 2026 19:41:08 +0900","total":2,"start":1,"display":2,"items":[
                  {"title":"<b>TIEAT 강남점</b>","link":"https://m.place.naver.com/place/1","category":"음식점 &gt; 한식", "description":"", "telephone":"", "address":"서울 강남구 지번 1", "roadAddress":"서울 강남구 테헤란로 123", "mapx":"127", "mapy":"37"},
                  {"title":"TIEAT 역삼점","link":"https://m.place.naver.com/place/2","category":"음식점 > 분식", "description":"", "telephone":"", "address":"서울 강남구 역삼동 2", "roadAddress":"" , "mapx":"128", "mapy":"38"}
                ]}
                """, MediaType.TEXT_PLAIN));

        List<PlaceSearchResult> results = adapter.search("TIEAT 강남점");

        assertThat(results).hasSize(2);
        assertThat(results.get(0).placeId()).isNotBlank().doesNotContain("place.naver.com");
        assertThat(results.get(0).storeDisplayName()).isEqualTo("TIEAT 강남점");
        assertThat(results.get(0).address()).isEqualTo("서울 강남구 테헤란로 123");
        assertThat(results.get(0).category()).isEqualTo("음식점 > 한식");
        assertThat(results.get(1).placeId()).isNotEqualTo(results.get(0).placeId());
        assertThat(results.get(1).storeDisplayName()).isEqualTo("TIEAT 역삼점");
        assertThat(results.get(1).address()).isEqualTo("서울 강남구 역삼동 2");
        assertThat(results.get(1).category()).isEqualTo("음식점 > 분식");
        server.verify();
    }

    @Test
    void capsTheProjectionAtTheNaverLocalSearchMaximum() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NaverLocalPlaceSearchAdapter adapter = new NaverLocalPlaceSearchAdapter(builder, "test-client-id", "test-client-secret");
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess("""
            {"items":[
              {"title":"TIEAT 1"}, {"title":"TIEAT 2"}, {"title":"TIEAT 3"},
              {"title":"TIEAT 4"}, {"title":"TIEAT 5"}, {"title":"TIEAT 6"}
            ]}
            """, MediaType.APPLICATION_JSON));

        assertThat(adapter.search("TIEAT")).hasSize(5);
        server.verify();
    }

    @Test
    void failsClosedWithoutCredentialsBeforeMakingARequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NaverLocalPlaceSearchAdapter adapter = new NaverLocalPlaceSearchAdapter(builder, "test-client-id", " ");

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
        NaverLocalPlaceSearchAdapter adapter = new NaverLocalPlaceSearchAdapter(builder, "test-client-id", "test-client-secret");
        server.expect(method(HttpMethod.GET)).andRespond(withServerError());

        assertThatThrownBy(() -> adapter.search("TIEAT"))
            .isInstanceOf(OnboardingException.class)
            .extracting(exception -> ((OnboardingException) exception).reason())
            .isEqualTo(OnboardingException.Reason.PLACE_SEARCH_UNAVAILABLE);
        server.verify();
    }
}

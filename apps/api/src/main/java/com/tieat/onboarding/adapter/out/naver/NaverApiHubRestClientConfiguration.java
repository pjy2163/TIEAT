package com.tieat.onboarding.adapter.out.naver;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class NaverApiHubRestClientConfiguration {

    @Bean
    @Qualifier("naverApiHubRestClientBuilder")
    RestClient.Builder naverApiHubRestClientBuilder() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        JacksonJsonHttpMessageConverter jsonConverter = new JacksonJsonHttpMessageConverter();
        jsonConverter.setSupportedMediaTypes(List.of(
            MediaType.APPLICATION_JSON,
            new MediaType("application", "*+json"),
            MediaType.TEXT_PLAIN
        ));
        return RestClient.builder()
            .requestFactory(requestFactory)
            .configureMessageConverters(converters -> converters.withJsonConverter(jsonConverter));
    }
}

package com.tieat.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class StoreOnboardingHttpIntegrationSupport {

    static final String INVITE_CODE = "integration-pilot-code";

    private StoreOnboardingHttpIntegrationSupport() {
    }

    static MockHttpSession csrfSession(MockMvc mockMvc) throws Exception {
        return (MockHttpSession) mockMvc.perform(get("/api/v1/csrf"))
            .andExpect(status().isOk())
            .andReturn()
            .getRequest()
            .getSession(false);
    }

    static String csrfToken(MockMvc mockMvc, ObjectMapper objectMapper, MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/csrf").session(session))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return response.get("token").asText();
    }

    static MockHttpSession signUpManualStore(
        MockMvc mockMvc,
        ObjectMapper objectMapper,
        String loginId,
        String password,
        String storeName
    ) throws Exception {
        MockHttpSession session = csrfSession(mockMvc);
        MvcResult result = mockMvc.perform(signupRequest(
                session,
                csrfToken(mockMvc, objectMapper, session),
                "{\"inviteCode\":\"" + INVITE_CODE + "\",\"loginId\":\"" + loginId
                    + "\",\"password\":\"" + password + "\",\"manualStoreName\":\"" + storeName + "\"}"
            ))
            .andExpect(status().isCreated())
            .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    static MockHttpSession authenticatedSession(
        MockMvc mockMvc,
        ObjectMapper objectMapper,
        String loginId,
        String password
    ) throws Exception {
        MockHttpSession session = csrfSession(mockMvc);
        MvcResult result = mockMvc.perform(post("/api/v1/sessions")
                .session(session)
                .header("X-CSRF-TOKEN", csrfToken(mockMvc, objectMapper, session))
                .param("loginId", loginId)
                .param("password", password))
            .andExpect(status().isNoContent())
            .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    static MockHttpServletRequestBuilder signupRequest(MockHttpSession session, String csrfToken, String body) {
        return post("/api/v1/store-signups")
            .session(session)
            .header("X-CSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    }

    static MockHttpServletRequestBuilder partnerRequest(MockHttpSession session, String csrfToken, String body) {
        return post("/api/v1/store-onboarding/partners")
            .session(session)
            .header("X-CSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    }

    static ResultMatcher problem(int expectedStatus, String errorCode) {
        return result -> {
            status().is(expectedStatus).match(result);
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
            jsonPath("$.status").value(expectedStatus).match(result);
            jsonPath("$.errorCode").value(errorCode).match(result);
        };
    }

    static UUID uuidColumn(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate, String query, Object... arguments) {
        return jdbcTemplate.queryForObject(query, UUID.class, arguments);
    }

    static void assertProblem(HttpStatus status, String errorCode, MockMvc mockMvc, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request).andExpect(problem(status.value(), errorCode));
    }
}

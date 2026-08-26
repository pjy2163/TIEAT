package com.tieat.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class StoreOnboardingHttpIntegrationSupport {

    static final String INVITE_CODE = "integration-pilot-code";
    private static final String SESSION_COOKIE = "TIEAT_SESSION";

    private StoreOnboardingHttpIntegrationSupport() {
    }

    static SessionHandle csrfSession(MockMvc mockMvc, ObjectMapper objectMapper) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/csrf"))
            .andExpect(status().isOk())
            .andReturn();
        Cookie cookie = responseCookie(result);
        return new SessionHandle(cookie, decodeSessionId(cookie.getValue()), csrfToken(objectMapper, result));
    }

    static String csrfToken(MockMvc mockMvc, ObjectMapper objectMapper, SessionHandle session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/csrf").cookie(session.cookie()))
            .andExpect(status().isOk())
            .andReturn();
        return csrfToken(objectMapper, result);
    }

    static SessionHandle signUpManualStore(
        MockMvc mockMvc,
        ObjectMapper objectMapper,
        String loginId,
        String password,
        String storeName
    ) throws Exception {
        SessionHandle session = csrfSession(mockMvc, objectMapper);
        MvcResult result = mockMvc.perform(signupRequest(
                session,
                csrfToken(mockMvc, objectMapper, session),
                "{\"inviteCode\":\"" + INVITE_CODE + "\",\"loginId\":\"" + loginId
                    + "\",\"password\":\"" + password + "\",\"manualStoreName\":\"" + storeName + "\"}"
            ))
            .andExpect(status().isCreated())
            .andReturn();
        return authenticatedSession(mockMvc, objectMapper, result);
    }

    static SessionHandle authenticatedSession(
        MockMvc mockMvc,
        ObjectMapper objectMapper,
        String loginId,
        String password
    ) throws Exception {
        SessionHandle session = csrfSession(mockMvc, objectMapper);
        MvcResult result = mockMvc.perform(post("/api/v1/sessions")
                .cookie(session.cookie())
                .header("X-CSRF-TOKEN", csrfToken(mockMvc, objectMapper, session))
                .param("loginId", loginId)
                .param("password", password))
            .andExpect(status().isNoContent())
            .andReturn();
        return authenticatedSession(mockMvc, objectMapper, result);
    }

    static MockHttpServletRequestBuilder signupRequest(SessionHandle session, String csrfToken, String body) {
        return post("/api/v1/store-signups")
            .cookie(session.cookie())
            .header("X-CSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    }

    static MockHttpServletRequestBuilder partnerRequest(SessionHandle session, String csrfToken, String body) {
        return post("/api/v1/store-onboarding/partners")
            .cookie(session.cookie())
            .header("X-CSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    }

    static SessionHandle authenticatedSession(MockMvc mockMvc, ObjectMapper objectMapper, MvcResult result)
        throws Exception {
        Cookie cookie = responseCookie(result);
        return new SessionHandle(cookie, decodeSessionId(cookie.getValue()), csrfToken(mockMvc, objectMapper, cookie));
    }

    private static String csrfToken(MockMvc mockMvc, ObjectMapper objectMapper, Cookie cookie) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/csrf").cookie(cookie))
            .andExpect(status().isOk())
            .andReturn();
        return csrfToken(objectMapper, result);
    }

    private static String csrfToken(ObjectMapper objectMapper, MvcResult result) throws Exception {
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return response.get("token").asText();
    }

    private static Cookie responseCookie(MvcResult result) {
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        if (setCookie == null || !setCookie.startsWith(SESSION_COOKIE + "=")) {
            throw new AssertionError("Missing " + SESSION_COOKIE + " response cookie");
        }
        String value = setCookie.substring(SESSION_COOKIE.length() + 1, setCookie.indexOf(';'));
        return new Cookie(SESSION_COOKIE, value);
    }

    private static String decodeSessionId(String cookieValue) {
        return new String(Base64.getDecoder().decode(cookieValue), StandardCharsets.UTF_8);
    }

    record SessionHandle(Cookie cookie, String sessionId, String csrfToken) {
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

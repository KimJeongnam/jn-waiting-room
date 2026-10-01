/**
 * @author jeongnam
 * @since 2026-09-22
 * @file WaitingRoomControllerTest.java
 * @description 실제 Redis를 사용해 대기 신청과 상태조회 HTTP 계약을 검증합니다.
 */
package org.jn.waitingroom.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.jn.waitingroom.config.WaitingRoomProperties;
import org.jn.waitingroom.redis.RedisWaitingRoomStore;
import org.jn.waitingroom.redis.RedisWaitingRoomStore.WriteResult;
import org.jn.waitingroom.service.WaitingClientAuthorization;
import org.jn.waitingroom.service.WaitingRoomService;
import org.jn.waitingroom.service.WaitingRoomRecoveryCoordinator;
import org.jn.waitingroom.service.RedisOperationalSafety;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@DisplayName("대기열 HTTP API 계약 테스트")
class WaitingRoomControllerTest {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8-alpine")
    ).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RedisWaitingRoomStore store;
    private static MockMvc mockMvc;
    private static MockMvc securedMockMvc;
    private static WaitingClientAuthorization securedAuthorization;
    private JwtAuthenticationToken authentication;
    private final Map<String, String> cookies = new java.util.HashMap<>();

    @BeforeAll
    static void setUpController() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();

        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();

        store = new RedisWaitingRoomStore(redis, new ObjectMapper());
        var serviceProperties = new WaitingRoomProperties.ServiceProperties(
                1,
                Duration.ofMinutes(20),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                Duration.ofMinutes(5),
                10,
                Duration.ofMinutes(1),
                0.0,
                Duration.ofDays(1),
                Duration.ofHours(23),
                Map.of(
                        "service-entry", "https://service.example/entry",
                        "other-entry", "https://service.example/other"
                )
        );
        var properties = new WaitingRoomProperties(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofSeconds(5),
                100,
                100,
                100,
                300,
                Map.of("reservation-service", serviceProperties)
        );
var coordinator = new WaitingRoomRecoveryCoordinator(store, properties, new org.springframework.beans.factory.support.StaticListableBeanFactory()
.getBeanProvider(org.springframework.data.redis.listener.RedisMessageListenerContainer.class), mock(RedisOperationalSafety.class));
        coordinator.recoverIfNeeded();
        var service = new WaitingRoomService(store, properties, coordinator, mock(RedisOperationalSafety.class));
        mockMvc = MockMvcBuilders.standaloneSetup(
                new WaitingRoomController(service, new WaitingClientAuthorization(properties), coordinator)).build();
        var securedProperties = new WaitingRoomProperties(
                Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofHours(1), Duration.ofSeconds(5), 100, 100, 100, 300,
                Map.of("reservation-service", serviceProperties, "other-service", serviceProperties),
                new WaitingRoomProperties.SecurityProperties(
                        new WaitingRoomProperties.OAuth2Properties(true),
                        Map.of(
                                "ClientA", new WaitingRoomProperties.ClientProperties(java.util.List.of("reservation-service")),
                                "ClientB", new WaitingRoomProperties.ClientProperties(java.util.List.of("other-service"))
                        )
                )
        );
        securedAuthorization = new WaitingClientAuthorization(securedProperties);
        var securedCoordinator = new WaitingRoomRecoveryCoordinator(store, securedProperties, new org.springframework.beans.factory.support.StaticListableBeanFactory()
.getBeanProvider(org.springframework.data.redis.listener.RedisMessageListenerContainer.class), mock(RedisOperationalSafety.class));
        securedCoordinator.recoverIfNeeded();
        securedMockMvc = MockMvcBuilders.standaloneSetup(
                new WaitingRoomController(new WaitingRoomService(store, securedProperties, securedCoordinator,
                        mock(RedisOperationalSafety.class)),
                        securedAuthorization, securedCoordinator)).build();
    }

    @AfterAll
    static void closeConnectionFactory() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void clearRedis() {
        authentication = null;
        cookies.clear();
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    /** 검증 목적: 상태 조회 로그에 브라우저 세션 비밀값이 노출되지 않는다. */
    @DisplayName("상태 조회 로그에 브라우저 세션 비밀값이 노출되지 않는다")
    @Test
    void pollingTraceLogsDoNotExposeTheSessionSecret() throws Exception {
        create("trace-request");
        String cookie = cookies.get("trace-request");
        String secret = cookie.substring(cookie.indexOf('.') + 1);
        Logger logger = (Logger) LoggerFactory.getLogger("org.springframework.web");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
        logger.setLevel(Level.TRACE);
        try {
            mockMvc.perform(pollRequest("trace-request")).andExpect(status().isOk());
            assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains("Arguments:")),
                    "Spring MVC handler argument TRACE 로그가 실제 수집되어야 합니다.");
            assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains(secret)),
                    "Spring MVC TRACE 로그에 세션 비밀값이 노출되어서는 안 됩니다.");
        } finally {
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** 검증 목적: 인증 정보 응답 객체의 진단 문자열에 비밀값이 노출되지 않는다. */
    @DisplayName("인증 정보 응답 객체의 진단 문자열에 비밀값이 노출되지 않는다")
    @Test
    void credentialDtosDoNotExposeSecretsInDiagnosticText() {
        String secret = "unique-secret-value";
        var response = new org.jn.waitingroom.vo.CreateWaitingResponse("request",
                org.jn.waitingroom.domain.WaitingRequestStatus.WAITING, "/waiting#token=" + secret);
        var registration = new WaitingRoomService.Registration(RedisWaitingRoomStore.ResultType.CREATED,
                org.jn.waitingroom.domain.WaitingRequestStatus.WAITING, "/waiting#token=" + secret);
        assertTrue(!response.toString().contains(secret));
        assertTrue(!registration.toString().contains(secret));
        assertTrue(!new WaitingRoomController.WaitingSessionRequest("service", secret).toString().contains(secret));
    }

    /** 검증 목적: 등록은 토큰 해시만 저장하고 재요청 시 해시를 바꾸지 않는다. */
    @DisplayName("등록은 토큰 해시만 저장하고 재요청 시 해시를 바꾸지 않는다")
    @Test
    void registrationStoresOnlyTokenHashAndReplayDoesNotRotateIt() throws Exception {
        String url = registeredUrl("secure-request");
        assertTrue(url.startsWith("/waiting?serviceId=reservation-service#token="));
        String token = url.substring(url.indexOf("#token=") + 7);
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        String state = redis.opsForValue().get("waiting-request:{reservation-service}:secure-request");
        assertTrue(!state.contains(token));
        String tokenHash = new ObjectMapper().readTree(state).get("currentWaitingTokenHash").asText();
        assertNotNull(redis.opsForValue().get("waiting-access:{reservation-service}:" + tokenHash));
        mockMvc.perform(post("/api/v1/waiting-requests")
                        .header("Idempotency-Key", "secure-request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceId\":\"reservation-service\",\"redirectTargetId\":\"service-entry\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.waitingUrl").doesNotExist());
        assertEquals(state, redis.opsForValue().get("waiting-request:{reservation-service}:secure-request"));
    }

    /** 검증 목적: 상태 조회에는 쿠키가 필요하고 잘못된 요청은 하트비트를 갱신하지 않는다. */
    @DisplayName("상태 조회에는 쿠키가 필요하고 잘못된 요청은 하트비트를 갱신하지 않는다")
    @Test
    void cookieIsMandatoryAndInvalidPollingDoesNotRefreshHeartbeat() throws Exception {
        create("secure-request");
        Double heartbeat = redis.opsForZSet().score("waiting-heartbeat:{reservation-service}", "secure-request");
        mockMvc.perform(get("/api/v1/waiting-session")
                        .queryParam("serviceId", "reservation-service")
                        .queryParam("reservationRequestId", "secure-request")
                        .header("X-Waiting-Request", "1").header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WAITING_SESSION_INVALID"))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
        assertEquals(heartbeat, redis.opsForZSet().score("waiting-heartbeat:{reservation-service}", "secure-request"));
    }

    /** 검증 목적: 대기 토큰은 한 번만 안전한 쿠키로 교환할 수 있다. */
    @DisplayName("대기 토큰은 한 번만 안전한 쿠키로 교환할 수 있다")
    @Test
    void tokenCanBeExchangedOnlyOnceForStrictSecureCookie() throws Exception {
        String url = registeredUrl("secure-request");
        String token = url.substring(url.indexOf("#token=") + 7);
        var response = mockMvc.perform(post("/api/v1/waiting-session")
                        .header("X-Waiting-Request", "1").header("Sec-Fetch-Site", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceId\":\"reservation-service\",\"token\":\"" + token + "\"}"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("__Host-waiting_session="),
                        org.hamcrest.Matchers.containsString("Path=/"),
                        org.hamcrest.Matchers.containsString("Secure"),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Strict"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Domain=")))))
                .andReturn().getResponse();
        String cookie = response.getHeader("Set-Cookie").split(";", 2)[0].split("=", 2)[1];
        mockMvc.perform(get("/api/v1/waiting-session")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-waiting_session", cookie))
                        .header("X-Waiting-Request", "1").header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reservationRequestId").value("secure-request"));
        mockMvc.perform(post("/api/v1/waiting-session")
                        .header("X-Waiting-Request", "1").header("Sec-Fetch-Site", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceId\":\"reservation-service\",\"token\":\"" + token + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    private String registeredUrl(String requestId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/waiting-requests")
                        .header("Idempotency-Key", requestId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceId\":\"reservation-service\",\"redirectTargetId\":\"service-entry\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("waitingUrl").asText();
    }

    /** 검증 목적: 토큰 재발급은 이전 토큰과 현재 세션을 즉시 무효화한다. */
    @DisplayName("토큰 재발급은 이전 토큰과 현재 세션을 즉시 무효화한다")
    @Test
    void reissueInvalidatesOutstandingTokensAndTheCurrentSessionImmediately() throws Exception {
        String firstToken = tokenFrom(registeredUrl("rotate-request"));
        String nextToken = reissueToken("rotate-request");
        exchange(firstToken).andExpect(status().isUnauthorized());
        String cookie = sessionCookie(nextToken);
        Double heartbeat = redis.opsForZSet().score("waiting-heartbeat:{reservation-service}", "rotate-request");
        String lastToken = reissueToken("rotate-request");
        pollCookie(cookie).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WAITING_SESSION_INVALID"));
        assertEquals(heartbeat, redis.opsForZSet().score("waiting-heartbeat:{reservation-service}", "rotate-request"));
        exchange(nextToken).andExpect(status().isUnauthorized());
        pollCookie(sessionCookie(lastToken)).andExpect(status().isOk());
        assertEquals(3, store.find("reservation-service", "rotate-request").waitingSessionVersion());
    }

    /** 검증 목적: 잘못된 쿠키 버전과 다른 출처의 요청은 하트비트를 갱신하지 않는다. */
    @DisplayName("잘못된 쿠키 버전과 다른 출처의 요청은 하트비트를 갱신하지 않는다")
    @Test
    void invalidCookieVersionAndCrossOriginRequestsCannotRefreshHeartbeat() throws Exception {
        String cookie = sessionCookie(tokenFrom(registeredUrl("origin-request")));
        Double heartbeat = redis.opsForZSet().score("waiting-heartbeat:{reservation-service}", "origin-request");
        for (String origin : java.util.List.of("https://evil.example", "http://localhost:9999", "null", "http://localhost/path")) {
            mockMvc.perform(get("/api/v1/waiting-session")
                            .cookie(new jakarta.servlet.http.Cookie("__Host-waiting_session", cookie))
                            .header("X-Waiting-Request", "1").header("Sec-Fetch-Site", "same-origin")
                            .header("Origin", origin))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get("/api/v1/waiting-session")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-waiting_session", cookie)))
                .andExpect(status().isUnauthorized());
        pollCookie(cookie + "x").andExpect(status().isUnauthorized());
        String key = redis.keys("waiting-session:{reservation-service}:*").iterator().next();
        String saved = redis.opsForValue().get(key);
        redis.opsForValue().set(key, saved.replace("\"version\":1", "\"version\":0"));
        pollCookie(cookie).andExpect(status().isUnauthorized());
        assertEquals(heartbeat, redis.opsForZSet().score("waiting-heartbeat:{reservation-service}", "origin-request"));
    }

    /** 검증 목적: 만료되거나 존재하지 않는 토큰으로 세션을 만들 수 없다. */
    @DisplayName("만료되거나 존재하지 않는 토큰으로 세션을 만들 수 없다")
    @Test
    void expiredAndUnknownTokensCannotCreateASession() throws Exception {
        String token = tokenFrom(registeredUrl("expired-token"));
        redis.delete(redis.keys("waiting-access:{reservation-service}:*"));
        exchange(token).andExpect(status().isUnauthorized());
        exchange("A".repeat(43)).andExpect(status().isUnauthorized());
        assertTrue(redis.keys("waiting-session:{reservation-service}:*").isEmpty());
    }

    private String reissueToken(String requestId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/waiting-requests/{id}/access-tokens", requestId)
                        .queryParam("serviceId", "reservation-service"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return tokenFrom(new ObjectMapper().readTree(body).get("waitingUrl").asText());
    }

    private static String tokenFrom(String url) {
        return url.substring(url.indexOf("#token=") + 7);
    }

    private org.springframework.test.web.servlet.ResultActions exchange(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/waiting-session")
                .header("X-Waiting-Request", "1").header("Sec-Fetch-Site", "same-origin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceId\":\"reservation-service\",\"token\":\"" + token + "\"}"));
    }

    private String sessionCookie(String token) throws Exception {
        return exchange(token).andExpect(status().isNoContent()).andReturn().getResponse()
                .getHeader("Set-Cookie").split(";", 2)[0].split("=", 2)[1];
    }

    private org.springframework.test.web.servlet.ResultActions pollCookie(String cookie) throws Exception {
        return mockMvc.perform(get("/api/v1/waiting-session")
                .cookie(new jakarta.servlet.http.Cookie("__Host-waiting_session", cookie))
                .header("X-Waiting-Request", "1").header("Sec-Fetch-Site", "same-origin"));
    }

    /** 검증 목적: 명시적으로 전달한 인증 주체는 보안 컨텍스트 없이도 권한을 부여한다. */
    @DisplayName("명시적으로 전달한 인증 주체는 보안 컨텍스트 없이도 권한을 부여한다")
    @Test
    void explicitRequestPrincipalAuthorizesWithoutAmbientSecurityContext() throws Exception {
        securedMockMvc.perform(post("/api/v1/waiting-requests")
                        .principal(jwtAuthentication(Map.of("client_id", "ClientA")))
                        .header("Idempotency-Key", "principal-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceId":"reservation-service","redirectTargetId":"service-entry"}
                                """))
                .andExpect(status().isCreated());
    }

    /** 검증 목적: 다른 클라이언트의 등록과 상태 변경은 같은 404 계약을 반환한다. */
    @DisplayName("다른 클라이언트의 등록과 상태 변경은 같은 404 계약을 반환한다")
    @Test
    void foreignClientGetsTheSameNotFoundContractForRegistrationAndTransitions() throws Exception {
        create("existing-request");
        authenticate(Map.of("client_id", "ClientB"));

        securedMockMvc.perform(post("/api/v1/waiting-requests")
                        .principal(authentication)
                        .header("Idempotency-Key", "foreign-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceId":"reservation-service","redirectTargetId":"service-entry"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("대기 신청을 찾을 수 없습니다."));

        for (String action : java.util.List.of("enter", "complete", "cancel", "access-tokens")) {
            securedMockMvc.perform(post("/api/v1/waiting-requests/existing-request/" + action)
                            .principal(authentication)
                            .queryParam("serviceId", "reservation-service"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("대기 신청을 찾을 수 없습니다."));
        }

        authenticate(Map.of("client_id", "ClientA"));
        securedMockMvc.perform(post("/api/v1/waiting-requests/missing/enter")
                        .principal(authentication)
                        .queryParam("serviceId", "reservation-service"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("대기 신청을 찾을 수 없습니다."));
    }

    /** 검증 목적: 소유 클라이언트는 신청을 등록하고 상태를 변경할 수 있다. */
    @DisplayName("소유 클라이언트는 신청을 등록하고 상태를 변경할 수 있다")
    @Test
    void ownedClientCanRegisterAndChangeRequestState() throws Exception {
        authenticate(Map.of("client_id", "ClientA"));
        securedMockMvc.perform(post("/api/v1/waiting-requests")
                        .principal(authentication)
                        .header("Idempotency-Key", "owned-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceId":"reservation-service","redirectTargetId":"service-entry"}
                                """))
                .andExpect(status().isCreated());
        admitNext();

        securedMockMvc.perform(post("/api/v1/waiting-requests/owned-request/enter")
                        .principal(authentication)
                        .queryParam("serviceId", "reservation-service"))
                .andExpect(status().isOk());
        securedMockMvc.perform(post("/api/v1/waiting-requests/owned-request/complete")
                        .principal(authentication)
                        .queryParam("serviceId", "reservation-service"))
                .andExpect(status().isOk());

        securedMockMvc.perform(post("/api/v1/waiting-requests")
                        .principal(authentication)
                        .header("Idempotency-Key", "owned-cancel-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceId":"reservation-service","redirectTargetId":"service-entry"}
                                """))
                .andExpect(status().isCreated());
        securedMockMvc.perform(post("/api/v1/waiting-requests/owned-cancel-request/cancel")
                        .principal(authentication)
                        .queryParam("serviceId", "reservation-service"))
                .andExpect(status().isOk());
    }

    /** 검증 목적: 클라이언트 ID는 대소문자를 구분하며 sub와 azp로 대체할 수 없다. */
    @DisplayName("클라이언트 ID는 대소문자를 구분하며 sub와 azp로 대체할 수 없다")
    @Test
    void clientIdIsCaseSensitiveAndSubOrAzpCannotReplaceIt() throws Exception {
        authenticate(Map.of("client_id", "clienta", "sub", "ClientA", "azp", "ClientA"));
        securedMockMvc.perform(post("/api/v1/waiting-requests")
                        .principal(authentication)
                        .header("Idempotency-Key", "wrong-case")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceId":"reservation-service","redirectTargetId":"service-entry"}
                                """))
                .andExpect(status().isNotFound());

        authenticate(Map.of("sub", "ClientA", "azp", "ClientA"));
        assertThrows(AuthenticationException.class,
                () -> securedAuthorization.requireServiceOwnership(authentication, "reservation-service"));
        authenticate(Map.of("client_id", 42));
        assertThrows(AuthenticationException.class,
                () -> securedAuthorization.requireServiceOwnership(authentication, "reservation-service"));
    }

    private void authenticate(Map<String, Object> claims) {
        authentication = jwtAuthentication(claims);
    }

    private JwtAuthenticationToken jwtAuthentication(Map<String, Object> claims) {
        var jwt = new Jwt("test-token", Instant.now().minusSeconds(10), Instant.now().plusSeconds(120),
                Map.of("alg", "RS256"), claims);
        return new JwtAuthenticationToken(jwt, java.util.List.of());
    }

    /** 검증 목적: 같은 멱등성 키의 최초 요청과 재요청 및 충돌은 각각 정해진 결과를 반환한다. */
    @DisplayName("같은 멱등성 키의 최초 요청과 재요청 및 충돌은 각각 정해진 결과를 반환한다")
    @Test
    void createReturnsCreatedReplayAndConflictForTheSameIdempotencyKey() throws Exception {
        String firstPayload = """
                {"serviceId":"reservation-service","redirectTargetId":"service-entry"}
                """;
        String otherPayload = """
                {"serviceId":"reservation-service","redirectTargetId":"other-entry"}
                """;

        mockMvc.perform(post("/api/v1/waiting-requests")
                        .header("Idempotency-Key", "reservation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationRequestId").value("reservation-1"))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.waitingUrl").value(org.hamcrest.Matchers.startsWith(
                        "/waiting?serviceId=reservation-service#token=")));

        mockMvc.perform(post("/api/v1/waiting-requests")
                        .header("Idempotency-Key", "reservation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waitingUrl").doesNotExist());

        mockMvc.perform(post("/api/v1/waiting-requests")
                        .header("Idempotency-Key", "reservation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(otherPayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYLOAD_MISMATCH"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    /** 검증 목적: 멱등성 키가 없으면 공통 오류 형식을 반환한다. */
    @DisplayName("멱등성 키가 없으면 공통 오류 형식을 반환한다")
    @Test
    void createReturnsTheCommonErrorContractWhenTheIdempotencyKeyIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/waiting-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceId":"reservation-service","redirectTargetId":"service-entry"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    /** 검증 목적: 요청 본문이 잘못되면 공통 오류 형식을 반환한다. */
    @DisplayName("요청 본문이 잘못되면 공통 오류 형식을 반환한다")
    @Test
    void createReturnsTheCommonErrorContractWhenTheRequestBodyIsInvalid() throws Exception {
        mockMvc.perform(post("/api/v1/waiting-requests")
                        .header("Idempotency-Key", "reservation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceId":"","redirectTargetId":"service-entry"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    /** 검증 목적: 브라우저 인증 정보가 없으면 상태 조회는 세션 오류 형식을 반환한다. */
    @DisplayName("브라우저 인증 정보가 없으면 상태 조회는 세션 오류 형식을 반환한다")
    @Test
    void statusReturnsTheSessionErrorContractWithoutBrowserCredentials() throws Exception {
        mockMvc.perform(get("/api/v1/waiting-session")
                        .queryParam("serviceId", "reservation-service"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WAITING_SESSION_INVALID"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    /** 검증 목적: 서비스 완료로 반환된 슬롯은 다음 대기 신청에 배정된다. */
    @DisplayName("서비스 완료로 반환된 슬롯은 다음 대기 신청에 배정된다")
    @Test
    void fullFlowReturnsTheReleasedSlotToTheNextWaitingRequest() throws Exception {
        create("reservation-1");
        create("reservation-2");
        assertEquals(RedisWaitingRoomStore.ResultType.APPLIED, admitNext().type());

        mockMvc.perform(pollRequest("reservation-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.redirectUrl").value(
                        "https://service.example/entry?reservationRequestId=reservation-1"
                ));
        assertEquals(RedisWaitingRoomStore.ResultType.FULL, admitNext().type());
        enter("reservation-1");
        mockMvc.perform(post("/api/v1/waiting-requests/reservation-1/complete")
                        .queryParam("serviceId", "reservation-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENTERED"));

        assertEquals(RedisWaitingRoomStore.ResultType.APPLIED, admitNext().type());

        mockMvc.perform(pollRequest("reservation-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADMITTED"));
        assertEquals(1, redis.opsForZSet().size("active-slots:{reservation-service}"));
    }

    /** 검증 목적: 가용 슬롯에 들어갈 신청은 1초 조회 주기를 사용한다. */
    @DisplayName("가용 슬롯에 들어갈 신청은 1초 조회 주기를 사용한다")
    @Test
    void statusUsesOneSecondPollingWhenTheRequestFitsInAvailableSlots() throws Exception {
        create("reservation-1");
        Double heartbeatBefore = redis.opsForZSet().score(
                "waiting-heartbeat:{reservation-service}",
                "reservation-1"
        );
        Thread.sleep(5);

        mockMvc.perform(pollRequest("reservation-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationRequestId").value("reservation-1"))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.waitingCount").value(1))
                .andExpect(jsonPath("$.estimatedWaitSeconds").value(1))
                .andExpect(jsonPath("$.nextPollAfterMs").value(allOf(
                        greaterThanOrEqualTo(900),
                        lessThanOrEqualTo(1_100)
                )));

        Double heartbeatAfter = redis.opsForZSet().score(
                "waiting-heartbeat:{reservation-service}",
                "reservation-1"
        );
        assertNotNull(heartbeatBefore);
        assertNotNull(heartbeatAfter);
        assertTrue(heartbeatAfter > heartbeatBefore);
    }

    /** 검증 목적: 가용 슬롯이 없으면 긴 조회 주기를 유지한다. */
    @DisplayName("가용 슬롯이 없으면 긴 조회 주기를 유지한다")
    @Test
    void statusKeepsLongPollingWhenNoActiveSlotIsAvailable() throws Exception {
        create("active-request");
        admitNext();
        create("waiting-request");

        mockMvc.perform(pollRequest("waiting-request"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.nextPollAfterMs").value(allOf(
                        greaterThanOrEqualTo(13_500),
                        lessThanOrEqualTo(16_500)
                )));
    }

    /** 검증 목적: 가용 슬롯 범위 밖의 신청은 긴 조회 주기를 유지한다. */
    @DisplayName("가용 슬롯 범위 밖의 신청은 긴 조회 주기를 유지한다")
    @Test
    void statusKeepsLongPollingWhenTheRequestIsOutsideAvailableSlots() throws Exception {
        create("first-request");
        create("second-request");

        mockMvc.perform(pollRequest("second-request"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(2))
                .andExpect(jsonPath("$.nextPollAfterMs").value(allOf(
                        greaterThanOrEqualTo(13_500),
                        lessThanOrEqualTo(16_500)
                )));
    }

    /** 검증 목적: 서버가 허용한 시각 이전의 조회는 거절되고 하트비트가 갱신되지 않는다. */
    @DisplayName("서버가 허용한 시각 이전의 조회는 거절되고 하트비트가 갱신되지 않는다")
    @Test
    void statusRejectsPollingBeforeTheServerAllowedTimeWithoutRefreshingHeartbeat() throws Exception {
        create("reservation-1");
        mockMvc.perform(pollRequest("reservation-1"))
                .andExpect(status().isOk());
        Double heartbeatAfterAllowedPoll = redis.opsForZSet().score(
                "waiting-heartbeat:{reservation-service}",
                "reservation-1"
        );

        mockMvc.perform(pollRequest("reservation-1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.retryable").value(true));

        assertEquals(
                heartbeatAfterAllowedPoll,
                redis.opsForZSet().score("waiting-heartbeat:{reservation-service}", "reservation-1")
        );
    }

    /** 검증 목적: 입장 허용 후 상태 조회는 등록된 이동 주소를 반환한다. */
    @DisplayName("입장 허용 후 상태 조회는 등록된 이동 주소를 반환한다")
    @Test
    void statusReturnsRegisteredRedirectAfterAdmission() throws Exception {
        create("reservation-1");
        admitNext();

        mockMvc.perform(pollRequest("reservation-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.redirectUrl").value(
                        "https://service.example/entry?reservationRequestId=reservation-1"
                ));
    }

    /** 검증 목적: 입장 요청은 세션 만료 시각을 반환하며 재요청해도 결과가 같다. */
    @DisplayName("입장 요청은 세션 만료 시각을 반환하며 재요청해도 결과가 같다")
    @Test
    void enterReturnsTheSessionExpiryAndIsIdempotent() throws Exception {
        create("reservation-1");
        admitNext();

        mockMvc.perform(post("/api/v1/waiting-requests/reservation-1/enter")
                        .queryParam("serviceId", "reservation-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationRequestId").value("reservation-1"))
                .andExpect(jsonPath("$.status").value("ENTERED"))
                .andExpect(jsonPath("$.sessionExpiresAt").isNotEmpty());

        mockMvc.perform(post("/api/v1/waiting-requests/reservation-1/enter")
                        .queryParam("serviceId", "reservation-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENTERED"))
                .andExpect(jsonPath("$.sessionExpiresAt").isNotEmpty());
    }

    /** 이미 입장한 세션도 새로고침 시 등록된 목적지로 돌아갈 수 있습니다. */
    @DisplayName("입장 완료 후에도 등록된 이동 주소를 유지한다")
    @Test
    void enteredSessionKeepsTheRegisteredRedirect() throws Exception {
        create("entered-redirect");
        admitNext();
        enter("entered-redirect");

        mockMvc.perform(pollRequest("entered-redirect"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENTERED"))
                .andExpect(jsonPath("$.redirectUrl").value(
                        "https://service.example/entry?reservationRequestId=entered-redirect"));
    }

    /** 검증 목적: 이용 완료는 활성 슬롯을 반환하며 재요청해도 결과가 같다. */
    @DisplayName("이용 완료는 활성 슬롯을 반환하며 재요청해도 결과가 같다")
    @Test
    void completeReturnsTheActiveSlotAndIsIdempotent() throws Exception {
        create("reservation-1");
        admitNext();
        enter("reservation-1");

        mockMvc.perform(post("/api/v1/waiting-requests/reservation-1/complete")
                        .queryParam("serviceId", "reservation-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationRequestId").value("reservation-1"))
                .andExpect(jsonPath("$.status").value("ENTERED"));

        mockMvc.perform(post("/api/v1/waiting-requests/reservation-1/complete")
                        .queryParam("serviceId", "reservation-service"))
                .andExpect(status().isOk());

        assertEquals(0, redis.opsForZSet().size("active-slots:{reservation-service}"));
    }

    /** 검증 목적: 취소는 대기 중 신청만 허용하고 입장 완료 신청은 거절한다. */
    @DisplayName("취소는 대기 중 신청만 허용하고 입장 완료 신청은 거절한다")
    @Test
    void cancelAcceptsWaitingButRejectsEnteredRequests() throws Exception {
        create("waiting-request");

        mockMvc.perform(post("/api/v1/waiting-requests/waiting-request/cancel")
                        .queryParam("serviceId", "reservation-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        create("entered-request");
        admitNext();
        enter("entered-request");

        mockMvc.perform(post("/api/v1/waiting-requests/entered-request/cancel")
                        .queryParam("serviceId", "reservation-service"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    /** 검증 목적: 존재하지 않는 신청의 상태 변경은 404를 반환한다. */
    @DisplayName("존재하지 않는 신청의 상태 변경은 404를 반환한다")
    @Test
    void transitionReturnsNotFoundForAnUnknownRequest() throws Exception {
        mockMvc.perform(post("/api/v1/waiting-requests/missing/enter")
                        .queryParam("serviceId", "reservation-service"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("대기 신청을 찾을 수 없습니다."));
    }

    /** 검증 목적: 입장 슬롯이 만료되면 입장 요청은 만료 오류를 반환한다. */
    @DisplayName("입장 슬롯이 만료되면 입장 요청은 만료 오류를 반환한다")
    @Test
    void enterReturnsAdmissionExpiredWhenTheSlotAlreadyPassed() throws Exception {
        create("reservation-1");
        store.admitNext(
                "reservation-service",
                100,
                Duration.ofMillis(10),
                Duration.ofMinutes(20),
                Duration.ofHours(23),
                Duration.ofHours(1)
        );
        Thread.sleep(30);

        mockMvc.perform(post("/api/v1/waiting-requests/reservation-1/enter")
                        .queryParam("serviceId", "reservation-service"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMISSION_EXPIRED"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    /** 검증 목적: Redis 상태 오류는 재시도 가능한 서비스 장애 응답을 반환한다. */
    @DisplayName("Redis 상태 오류는 재시도 가능한 서비스 장애 응답을 반환한다")
    @Test
    void redisStateFailureReturnsRetryableServiceUnavailable() throws Exception {
        create("reservation-1");
        redis.opsForValue().set(
                "waiting-request:{reservation-service}:reservation-1",
                "invalid-json"
        );

        mockMvc.perform(pollRequest("reservation-1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    /** Redis 연결/명령 장애는 내부 주소를 숨기고 쓰기 재시도 없이 동일한 503 계약으로 변환합니다. */
    @DisplayName("Redis 접근 오류는 내부 정보 없이 제한된 재시도 응답을 반환한다")
    @Test
    void redisAccessFailuresReturnBoundedRetryWithoutExposingInternalDetails() throws Exception {
        for (DataAccessException failure : java.util.List.of(
                new RedisConnectionFailureException("redis://private-host:6379 connection refused"),
                new QueryTimeoutException("private-key command timed out"))) {
            var service = mock(WaitingRoomService.class);
            var coordinator = mock(WaitingRoomRecoveryCoordinator.class);
            var mvc = MockMvcBuilders.standaloneSetup(
                    new WaitingRoomController(service, mock(WaitingClientAuthorization.class), coordinator)).build();
            doThrow(failure).when(service).register("reservation-service", "redis-unavailable", "service-entry");
            doThrow(failure).when(service).poll("unavailable-session");

            var requests = java.util.List.of(
                    post("/api/v1/waiting-requests").header("Idempotency-Key", "redis-unavailable")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"serviceId\":\"reservation-service\",\"redirectTargetId\":\"service-entry\"}"),
                    get("/api/v1/waiting-session")
                            .cookie(new jakarta.servlet.http.Cookie("__Host-waiting_session", "unavailable-session"))
                            .header("X-Waiting-Request", "1").header("Sec-Fetch-Site", "same-origin"));
            for (var request : requests) {
                mvc.perform(request).andExpect(status().isServiceUnavailable())
                        .andExpect(header().string("Retry-After", "1"))
                        .andExpect(header().doesNotExist("WWW-Authenticate"))
                        .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                        .andExpect(jsonPath("$.message").value("서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."))
                        .andExpect(jsonPath("$.retryable").value(true))
                        .andExpect(jsonPath("$.traceId").isNotEmpty());
            }
            verify(service).register("reservation-service", "redis-unavailable", "service-entry");
            verify(service).poll("unavailable-session");
            verify(coordinator, org.mockito.Mockito.times(2)).markRedisUnavailable();
        }
    }

    /** 복구 전 요청은 Redis 호출 없이 동일한 고정 503 계약을 반환합니다. */
    @DisplayName("복구 조정기가 중단되면 Redis 호출 없이 제한된 503을 반환한다")
    @Test
    void unavailableCoordinatorReturnsBounded503WithoutCallingRedis() throws Exception {
        var unavailableStore = mock(RedisWaitingRoomStore.class);
        var serviceProperties = new WaitingRoomProperties.ServiceProperties(1, Duration.ofMinutes(20),
                Duration.ofMinutes(2), Duration.ofMinutes(30), Duration.ofMinutes(5), 10,
                Duration.ofMinutes(1), 0, Duration.ofDays(1), Duration.ofHours(23),
                Map.of("service-entry", "https://service.example/entry"));
        var properties = new WaitingRoomProperties(Duration.ofSeconds(1), Duration.ofSeconds(30),
                Duration.ofSeconds(1), Duration.ofHours(1), Duration.ofSeconds(5), 100, 100, 100, 300,
                Map.of("reservation-service", serviceProperties));
        var coordinator = new WaitingRoomRecoveryCoordinator(unavailableStore, properties, new org.springframework.beans.factory.support.StaticListableBeanFactory()
.getBeanProvider(org.springframework.data.redis.listener.RedisMessageListenerContainer.class), mock(RedisOperationalSafety.class));
        var service = new WaitingRoomService(unavailableStore, properties, coordinator, mock(RedisOperationalSafety.class));
        var mvc = MockMvcBuilders.standaloneSetup(new WaitingRoomController(
                service, new WaitingClientAuthorization(properties), coordinator)).build();

        mvc.perform(post("/api/v1/waiting-requests").header("Idempotency-Key", "unavailable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceId\":\"reservation-service\",\"redirectTargetId\":\"service-entry\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."))
                .andExpect(jsonPath("$.retryable").value(true));
        org.mockito.Mockito.verifyNoInteractions(unavailableStore);

        // 복구 중에 들어온 차단 요청은 새 Redis 장애를 관측한 것이 아닙니다.
        org.mockito.Mockito.when(unavailableStore.bootstrapRecovery(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    mvc.perform(get("/api/v1/waiting-session").header("X-Waiting-Request", "1")
                                    .header("Sec-Fetch-Site", "same-origin"))
                            .andExpect(status().isServiceUnavailable())
                            .andExpect(header().string("Retry-After", "1"));
                    return new RedisWaitingRoomStore.RecoveryBootstrap(false, 0);
                });
        coordinator.recoverIfNeeded();
        assertTrue(coordinator.isAvailable());
    }

    private void create(String reservationRequestId) throws Exception {
        cookies.put(reservationRequestId, sessionCookie(tokenFrom(registeredUrl(reservationRequestId))));
    }

    /** 검증 목적: 수용 인원 처리 오류는 복구 차단 없이 고정된 503을 반환한다. */
    @DisplayName("수용 인원 처리 오류는 복구 차단 없이 고정된 503을 반환한다")
    @Test
    void capacityFailureReturnsFixed503WithoutMarkingRecoveryUnavailable() throws Exception {
        var service = mock(WaitingRoomService.class);
        var coordinator = mock(WaitingRoomRecoveryCoordinator.class);
        doThrow(new RedisOperationalSafety.CapacityUnavailableException()).when(service)
                .register("reservation-service", "capacity-unavailable", "service-entry");
        var mvc = MockMvcBuilders.standaloneSetup(new WaitingRoomController(
                service, mock(WaitingClientAuthorization.class), coordinator)).build();
        mvc.perform(post("/api/v1/waiting-requests").header("Idempotency-Key", "capacity-unavailable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceId\":\"reservation-service\",\"redirectTargetId\":\"service-entry\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."))
                .andExpect(jsonPath("$.retryable").value(true));
        org.mockito.Mockito.verifyNoInteractions(coordinator);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder pollRequest(String requestId) {
        return get("/api/v1/waiting-session")
                .cookie(new jakarta.servlet.http.Cookie("__Host-waiting_session", cookies.get(requestId)))
                .header("X-Waiting-Request", "1").header("Sec-Fetch-Site", "same-origin");
    }

    private WriteResult admitNext() {
        return store.admitNext(
                "reservation-service",
                1,
                Duration.ofMinutes(2),
                Duration.ofMinutes(20),
                Duration.ofHours(23),
                Duration.ofHours(1)
        );
    }

    private void enter(String reservationRequestId) throws Exception {
        mockMvc.perform(post("/api/v1/waiting-requests/{reservationRequestId}/enter", reservationRequestId)
                        .queryParam("serviceId", "reservation-service"))
                .andExpect(status().isOk());
    }
}

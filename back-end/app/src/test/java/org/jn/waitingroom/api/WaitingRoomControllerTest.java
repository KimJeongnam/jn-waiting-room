/**
 * @author jeongnam
 * @since 2026-09-22
 * @file WaitingRoomControllerTest.java
 * @description 실제 Redis를 사용해 대기 신청과 상태조회 HTTP 계약을 검증합니다.
 */
package org.jn.waitingroom.api;

import org.jn.waitingroom.config.WaitingRoomProperties;
import org.jn.waitingroom.redis.RedisWaitingRoomStore;
import org.jn.waitingroom.redis.RedisWaitingRoomStore.WriteResult;
import org.jn.waitingroom.service.WaitingRoomService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
class WaitingRoomControllerTest {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8-alpine")
    ).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RedisWaitingRoomStore store;
    private static MockMvc mockMvc;

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
        var service = new WaitingRoomService(store, properties);
        mockMvc = MockMvcBuilders.standaloneSetup(new WaitingRoomController(service)).build();
    }

    @AfterAll
    static void closeConnectionFactory() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void clearRedis() {
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

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
                .andExpect(jsonPath("$.waitingUrl").value(
                        "/waiting?serviceId=reservation-service&reservationRequestId=reservation-1"
                ));

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

    @Test
    void statusReturnsTheCommonErrorContractWhenARequiredParameterIsMissing() throws Exception {
        mockMvc.perform(get("/api/v1/waiting-session")
                        .queryParam("serviceId", "reservation-service"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void fullFlowReturnsTheReleasedSlotToTheNextWaitingRequest() throws Exception {
        create("reservation-1");
        create("reservation-2");
        assertEquals(RedisWaitingRoomStore.ResultType.APPLIED, admitNext().type());

        mockMvc.perform(get("/api/v1/waiting-session")
                        .queryParam("serviceId", "reservation-service")
                        .queryParam("reservationRequestId", "reservation-1"))
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

        mockMvc.perform(get("/api/v1/waiting-session")
                        .queryParam("serviceId", "reservation-service")
                        .queryParam("reservationRequestId", "reservation-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADMITTED"));
        assertEquals(1, redis.opsForZSet().size("active-slots:{reservation-service}"));
    }

    @Test
    void statusUsesOneSecondPollingWhenTheRequestFitsInAvailableSlots() throws Exception {
        create("reservation-1");
        Double heartbeatBefore = redis.opsForZSet().score(
                "waiting-heartbeat:{reservation-service}",
                "reservation-1"
        );
        Thread.sleep(5);

        mockMvc.perform(get("/api/v1/waiting-session")
                        .queryParam("serviceId", "reservation-service")
                        .queryParam("reservationRequestId", "reservation-1"))
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

    @Test
    void statusKeepsLongPollingWhenNoActiveSlotIsAvailable() throws Exception {
        create("active-request");
        admitNext();
        create("waiting-request");

        mockMvc.perform(get("/api/v1/waiting-session")
                        .queryParam("serviceId", "reservation-service")
                        .queryParam("reservationRequestId", "waiting-request"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.nextPollAfterMs").value(allOf(
                        greaterThanOrEqualTo(13_500),
                        lessThanOrEqualTo(16_500)
                )));
    }

    @Test
    void statusKeepsLongPollingWhenTheRequestIsOutsideAvailableSlots() throws Exception {
        create("first-request");
        create("second-request");

        mockMvc.perform(get("/api/v1/waiting-session")
                        .queryParam("serviceId", "reservation-service")
                        .queryParam("reservationRequestId", "second-request"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(2))
                .andExpect(jsonPath("$.nextPollAfterMs").value(allOf(
                        greaterThanOrEqualTo(13_500),
                        lessThanOrEqualTo(16_500)
                )));
    }

    @Test
    void statusRejectsPollingBeforeTheServerAllowedTimeWithoutRefreshingHeartbeat() throws Exception {
        create("reservation-1");
        mockMvc.perform(get("/api/v1/waiting-session")
                        .queryParam("serviceId", "reservation-service")
                        .queryParam("reservationRequestId", "reservation-1"))
                .andExpect(status().isOk());
        Double heartbeatAfterAllowedPoll = redis.opsForZSet().score(
                "waiting-heartbeat:{reservation-service}",
                "reservation-1"
        );

        mockMvc.perform(get("/api/v1/waiting-session")
                        .queryParam("serviceId", "reservation-service")
                        .queryParam("reservationRequestId", "reservation-1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.retryable").value(true));

        assertEquals(
                heartbeatAfterAllowedPoll,
                redis.opsForZSet().score("waiting-heartbeat:{reservation-service}", "reservation-1")
        );
    }

    @Test
    void statusReturnsRegisteredRedirectAfterAdmission() throws Exception {
        create("reservation-1");
        admitNext();

        mockMvc.perform(get("/api/v1/waiting-session")
                        .queryParam("serviceId", "reservation-service")
                        .queryParam("reservationRequestId", "reservation-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.redirectUrl").value(
                        "https://service.example/entry?reservationRequestId=reservation-1"
                ));
    }

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

    @Test
    void transitionReturnsNotFoundForAnUnknownRequest() throws Exception {
        mockMvc.perform(post("/api/v1/waiting-requests/missing/enter")
                        .queryParam("serviceId", "reservation-service"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("대기 신청을 찾을 수 없습니다."));
    }

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

    @Test
    void redisStateFailureReturnsRetryableServiceUnavailable() throws Exception {
        create("reservation-1");
        redis.opsForValue().set(
                "waiting-request:{reservation-service}:reservation-1",
                "invalid-json"
        );

        mockMvc.perform(get("/api/v1/waiting-session")
                        .queryParam("serviceId", "reservation-service")
                        .queryParam("reservationRequestId", "reservation-1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    private void create(String reservationRequestId) throws Exception {
        mockMvc.perform(post("/api/v1/waiting-requests")
                        .header("Idempotency-Key", reservationRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceId":"reservation-service","redirectTargetId":"service-entry"}
                                """))
                .andExpect(status().isCreated());
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

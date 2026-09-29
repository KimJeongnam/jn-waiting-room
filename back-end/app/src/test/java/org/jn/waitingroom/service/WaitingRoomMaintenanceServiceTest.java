/**
 * @author jeongnam
 * @since 2026-09-22
 * @file WaitingRoomMaintenanceServiceTest.java
 * @description 실제 Redis에서 슬롯 보충과 대기·활성 만료 처리를 검증합니다.
 */
package org.jn.waitingroom.service;

import org.jn.waitingroom.config.WaitingRoomProperties;
import org.jn.waitingroom.domain.WaitingRequestStatus;
import org.jn.waitingroom.redis.RedisWaitingRoomStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@Testcontainers
class WaitingRoomMaintenanceServiceTest {
    private static final String SERVICE_ID = "reservation-service";

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8-alpine")
    ).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RedisWaitingRoomStore store;

    @BeforeAll
    static void setUpStore() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        store = new RedisWaitingRoomStore(redis, new ObjectMapper());
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
    void fillsOnlyTheConfiguredNumberOfActiveSlots() {
        register("reservation-1");
        register("reservation-2");
        register("reservation-3");

        maintenance(properties(2, Duration.ofMinutes(20), Duration.ofMinutes(2))).maintain(SERVICE_ID);

        assertEquals(WaitingRequestStatus.ADMITTED, store.find(SERVICE_ID, "reservation-1").status());
        assertEquals(WaitingRequestStatus.ADMITTED, store.find(SERVICE_ID, "reservation-2").status());
        assertEquals(WaitingRequestStatus.WAITING, store.find(SERVICE_ID, "reservation-3").status());
        assertEquals(2, redis.opsForZSet().size("active-slots:{reservation-service}"));
    }

    @Test
    void automaticMaintenanceWaitsUntilApplicationStartupIsComplete() {
        register("reservation-1");
        var maintenance = maintenance(properties(1, Duration.ofMinutes(20), Duration.ofMinutes(2)));

        maintenance.maintainAll();

        assertEquals(WaitingRequestStatus.WAITING, store.find(SERVICE_ID, "reservation-1").status());

        maintenance.onApplicationReady();
        maintenance.maintainAll();

        assertEquals(WaitingRequestStatus.ADMITTED, store.find(SERVICE_ID, "reservation-1").status());
    }

    @Test
    void expiresAnAdmissionSlotAndAdmitsTheNextRequest() throws InterruptedException {
        register("reservation-1");
        register("reservation-2");
        var maintenance = maintenance(properties(1, Duration.ofMinutes(20), Duration.ofMillis(10)));
        maintenance.maintain(SERVICE_ID);
        Thread.sleep(30);

        maintenance.maintain(SERVICE_ID);

        var expired = store.find(SERVICE_ID, "reservation-1");
        assertEquals(WaitingRequestStatus.EXPIRED, expired.status());
        assertEquals("ADMISSION_TIMEOUT", expired.expirationReason());
        assertEquals(WaitingRequestStatus.ADMITTED, store.find(SERVICE_ID, "reservation-2").status());
        assertEquals(1, redis.opsForZSet().size("slot-release-events:{reservation-service}"));
    }

    @Test
    void expiresAWaitingRequestWhenItsHeartbeatStops() throws InterruptedException {
        register("reservation-1");
        var maintenance = maintenance(properties(1, Duration.ofMillis(10), Duration.ofMinutes(2)));
        Thread.sleep(30);

        maintenance.maintain(SERVICE_ID);

        var expired = store.find(SERVICE_ID, "reservation-1");
        assertEquals(WaitingRequestStatus.EXPIRED, expired.status());
        assertEquals("HEARTBEAT_TIMEOUT", expired.expirationReason());
        assertEquals(0, redis.opsForZSet().size("waiting:{reservation-service}"));
    }

    @Test
    void expiresAnEnteredRequestWhenItsSessionLimitPasses() throws InterruptedException {
        register("reservation-1");
        var maintenance = maintenance(properties(1, Duration.ofMinutes(20), Duration.ofMinutes(2)));
        maintenance.maintain(SERVICE_ID);
        store.enter(SERVICE_ID, "reservation-1", Duration.ofMillis(10), Duration.ofMinutes(5));
        Thread.sleep(30);

        maintenance.maintain(SERVICE_ID);

        var expired = store.find(SERVICE_ID, "reservation-1");
        assertEquals(WaitingRequestStatus.EXPIRED, expired.status());
        assertEquals("SESSION_TIMEOUT", expired.expirationReason());
        assertEquals(0, redis.opsForZSet().size("active-slots:{reservation-service}"));
    }

    @Test
    void expiresAWaitingRequestWhenItsMaximumWaitPasses() throws InterruptedException {
        register("reservation-1");
        var maintenance = maintenance(properties(
                1,
                Duration.ofMinutes(20),
                Duration.ofMinutes(2),
                Duration.ofMillis(10)
        ));
        Thread.sleep(30);

        maintenance.maintain(SERVICE_ID);

        var expired = store.find(SERVICE_ID, "reservation-1");
        assertEquals(WaitingRequestStatus.EXPIRED, expired.status());
        assertEquals("MAX_WAIT_DURATION", expired.expirationReason());
        assertEquals(0, redis.opsForZSet().size("waiting:{reservation-service}"));
    }

    @Test
    void removesAnOrphanedQueueHeadAndAdmitsTheNextWaitingRequest() {
        register("reservation-1");
        register("reservation-2");
        redis.delete("waiting-request:{reservation-service}:reservation-1");

        maintenance(properties(1, Duration.ofMinutes(20), Duration.ofMinutes(2))).maintain(SERVICE_ID);

        assertEquals(WaitingRequestStatus.ADMITTED, store.find(SERVICE_ID, "reservation-2").status());
        assertNull(redis.opsForZSet().rank("waiting:{reservation-service}", "reservation-1"));
        assertNull(redis.opsForZSet().rank("waiting-heartbeat:{reservation-service}", "reservation-1"));
    }

    @Test
    void quarantinesACorruptedExpiredQueueHeadAndAdmitsTheNextWaitingRequest() {
        register("reservation-1");
        register("reservation-2");
        redis.opsForValue().set(
                "waiting-request:{reservation-service}:reservation-1",
                "invalid-json",
                Duration.ofDays(1)
        );
        long now = System.currentTimeMillis();
        redis.opsForZSet().add(
                "waiting-heartbeat:{reservation-service}",
                "reservation-1",
                now - Duration.ofSeconds(10).toMillis()
        );
        redis.opsForZSet().add(
                "waiting-heartbeat:{reservation-service}",
                "reservation-2",
                now
        );

        maintenance(properties(1, Duration.ofSeconds(1), Duration.ofMinutes(2))).maintain(SERVICE_ID);

        var corrupted = store.find(SERVICE_ID, "reservation-1");
        assertEquals(WaitingRequestStatus.EXPIRED, corrupted.status());
        assertEquals("STATE_CORRUPTED", corrupted.expirationReason());
        assertEquals(WaitingRequestStatus.ADMITTED, store.find(SERVICE_ID, "reservation-2").status());
        assertNull(redis.opsForZSet().rank("waiting:{reservation-service}", "reservation-1"));
        String quarantineKey = redis.keys("waiting-quarantine:{reservation-service}:reservation-1:*").stream()
                .findFirst()
                .orElse(null);
        assertNotNull(quarantineKey);
        assertEquals("invalid-json", redis.opsForValue().get(quarantineKey));
    }

    @Test
    void quarantinesACorruptedExpiredActiveSlotAndAdmitsTheNextWaitingRequest() throws InterruptedException {
        register("reservation-1");
        register("reservation-2");
        var maintenance = maintenance(properties(1, Duration.ofMinutes(20), Duration.ofMillis(10)));
        maintenance.maintain(SERVICE_ID);
        redis.opsForValue().set(
                "waiting-request:{reservation-service}:reservation-1",
                "invalid-json",
                Duration.ofDays(1)
        );
        Thread.sleep(30);

        maintenance.maintain(SERVICE_ID);

        var corrupted = store.find(SERVICE_ID, "reservation-1");
        assertEquals(WaitingRequestStatus.EXPIRED, corrupted.status());
        assertEquals("STATE_CORRUPTED", corrupted.expirationReason());
        assertEquals(WaitingRequestStatus.ADMITTED, store.find(SERVICE_ID, "reservation-2").status());
        assertNull(redis.opsForZSet().rank("active-slots:{reservation-service}", "reservation-1"));
    }

    @Test
    void quarantinesStructurallyInvalidJsonAndAdmitsTheNextWaitingRequest() {
        register("reservation-1");
        register("reservation-2");
        redis.opsForValue().set(
                "waiting-request:{reservation-service}:reservation-1",
                "{}",
                Duration.ofDays(1)
        );

        maintenance(properties(1, Duration.ofMinutes(20), Duration.ofMinutes(2))).maintain(SERVICE_ID);

        var corrupted = store.find(SERVICE_ID, "reservation-1");
        assertEquals(WaitingRequestStatus.EXPIRED, corrupted.status());
        assertEquals("STATE_CORRUPTED", corrupted.expirationReason());
        assertEquals("reservation-1", corrupted.reservationRequestId());
        assertEquals(SERVICE_ID, corrupted.serviceId());
        assertEquals(WaitingRequestStatus.ADMITTED, store.find(SERVICE_ID, "reservation-2").status());
        String quarantineKey = redis.keys("waiting-quarantine:{reservation-service}:reservation-1:*").stream()
                .findFirst()
                .orElse(null);
        assertNotNull(quarantineKey);
        assertEquals("{}", redis.opsForValue().get(quarantineKey));
    }

    @Test
    void quarantinesWaitingStateWithoutRedirectTargetAndAdmitsTheNextWaitingRequest() {
        register("reservation-1");
        register("reservation-2");
        redis.opsForValue().set(
                "waiting-request:{reservation-service}:reservation-1",
                """
                        {"reservationRequestId":"reservation-1","serviceId":"reservation-service",\
                        "status":"WAITING","payloadFingerprint":"sha256:reservation-1","createdAt":1}
                        """,
                Duration.ofDays(1)
        );

        maintenance(properties(1, Duration.ofMinutes(20), Duration.ofMinutes(2))).maintain(SERVICE_ID);

        assertEquals("STATE_CORRUPTED", store.find(SERVICE_ID, "reservation-1").expirationReason());
        assertEquals(WaitingRequestStatus.ADMITTED, store.find(SERVICE_ID, "reservation-2").status());
    }

    @Test
    void quarantinesUnknownStatusAndAdmitsTheNextWaitingRequest() {
        register("reservation-1");
        register("reservation-2");
        redis.opsForValue().set(
                "waiting-request:{reservation-service}:reservation-1",
                """
                        {"reservationRequestId":"reservation-1","serviceId":"reservation-service",\
                        "status":"BROKEN","redirectTargetId":"service-entry",\
                        "payloadFingerprint":"sha256:reservation-1","createdAt":1}
                        """,
                Duration.ofDays(1)
        );

        maintenance(properties(1, Duration.ofMinutes(20), Duration.ofMinutes(2))).maintain(SERVICE_ID);

        assertEquals("STATE_CORRUPTED", store.find(SERVICE_ID, "reservation-1").expirationReason());
        assertEquals(WaitingRequestStatus.ADMITTED, store.find(SERVICE_ID, "reservation-2").status());
    }

    @Test
    void quarantinesInvalidOptionalFieldTypeAndAdmitsTheNextWaitingRequest() {
        register("reservation-1");
        register("reservation-2");
        redis.opsForValue().set(
                "waiting-request:{reservation-service}:reservation-1",
                """
                        {"reservationRequestId":"reservation-1","serviceId":"reservation-service",\
                        "status":"WAITING","redirectTargetId":"service-entry",\
                        "payloadFingerprint":"sha256:reservation-1","createdAt":1,\
                        "nextPollAllowedAt":"bad"}
                        """,
                Duration.ofDays(1)
        );

        maintenance(properties(1, Duration.ofMinutes(20), Duration.ofMinutes(2))).maintain(SERVICE_ID);

        assertEquals("STATE_CORRUPTED", store.find(SERVICE_ID, "reservation-1").expirationReason());
        assertEquals(WaitingRequestStatus.ADMITTED, store.find(SERVICE_ID, "reservation-2").status());
    }

    @Test
    void removesMismatchedQueueMembershipAndAdmitsTheNextWaitingRequest() {
        register("reservation-1");
        register("reservation-2");
        redis.opsForValue().set(
                "waiting-request:{reservation-service}:reservation-1",
                """
                        {"reservationRequestId":"reservation-1","serviceId":"reservation-service",\
                        "status":"CANCELLED","redirectTargetId":"service-entry",\
                        "payloadFingerprint":"sha256:reservation-1","createdAt":1}
                        """,
                Duration.ofDays(1)
        );

        maintenance(properties(1, Duration.ofMinutes(20), Duration.ofMinutes(2))).maintain(SERVICE_ID);

        assertEquals(WaitingRequestStatus.CANCELLED, store.find(SERVICE_ID, "reservation-1").status());
        assertEquals(WaitingRequestStatus.ADMITTED, store.find(SERVICE_ID, "reservation-2").status());
        assertNull(redis.opsForZSet().rank("waiting:{reservation-service}", "reservation-1"));
        assertNull(redis.opsForZSet().rank("waiting-heartbeat:{reservation-service}", "reservation-1"));
    }

    @Test
    void expiresAWaitingRequestWithoutHeartbeatAndAdmitsTheNextWaitingRequest() {
        register("reservation-1");
        register("reservation-2");
        redis.opsForZSet().remove("waiting-heartbeat:{reservation-service}", "reservation-1");

        maintenance(properties(1, Duration.ofMinutes(20), Duration.ofMinutes(2))).maintain(SERVICE_ID);

        var inconsistent = store.find(SERVICE_ID, "reservation-1");
        assertEquals(WaitingRequestStatus.EXPIRED, inconsistent.status());
        assertEquals("INTERNAL_INCONSISTENCY", inconsistent.expirationReason());
        assertEquals(WaitingRequestStatus.ADMITTED, store.find(SERVICE_ID, "reservation-2").status());
        assertNull(redis.opsForZSet().rank("waiting:{reservation-service}", "reservation-1"));
    }

    private WaitingRoomMaintenanceService maintenance(WaitingRoomProperties properties) {
        return new WaitingRoomMaintenanceService(store, properties, "test-instance");
    }

    private WaitingRoomProperties properties(
            int maxConcurrentUsers,
            Duration heartbeatTimeout,
            Duration admissionTimeout
    ) {
        return properties(
                maxConcurrentUsers,
                heartbeatTimeout,
                admissionTimeout,
                Duration.ofHours(23)
        );
    }

    private WaitingRoomProperties properties(
            int maxConcurrentUsers,
            Duration heartbeatTimeout,
            Duration admissionTimeout,
            Duration maxWaitDuration
    ) {
        var service = new WaitingRoomProperties.ServiceProperties(
                maxConcurrentUsers,
                heartbeatTimeout,
                admissionTimeout,
                Duration.ofMinutes(30),
                Duration.ofMinutes(5),
                10,
                Duration.ofMinutes(1),
                0.0,
                Duration.ofDays(1),
                maxWaitDuration,
                Map.of("service-entry", "https://service.example/entry")
        );
        Duration schedulerInterval = admissionTimeout.compareTo(Duration.ofSeconds(2)) < 0
                ? admissionTimeout.dividedBy(2)
                : Duration.ofSeconds(1);
        Duration maxRetryAfter = heartbeatTimeout.compareTo(Duration.ofSeconds(30)) > 0
                ? Duration.ofSeconds(30)
                : heartbeatTimeout.dividedBy(2);
        Duration retryAfterSafetyMargin = heartbeatTimeout.compareTo(Duration.ofSeconds(1)) > 0
                ? Duration.ofSeconds(1)
                : heartbeatTimeout.dividedBy(4);
        return new WaitingRoomProperties(
                schedulerInterval,
                maxRetryAfter,
                retryAfterSafetyMargin,
                Duration.ofHours(1),
                Duration.ofSeconds(5),
                100,
                100,
                100,
                300,
                Map.of(SERVICE_ID, service)
        );
    }

    private void register(String reservationRequestId) {
        store.register(
                SERVICE_ID,
                reservationRequestId,
                "sha256:" + reservationRequestId,
                "service-entry",
                Duration.ofDays(1)
        );
    }
}

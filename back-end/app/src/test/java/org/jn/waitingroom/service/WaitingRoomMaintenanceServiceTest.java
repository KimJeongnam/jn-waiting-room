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
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("대기열 유지보수와 만료 처리 테스트")
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

    /** 검증 목적: 설정된 활성 슬롯 수까지만 대기 신청을 입장시킨다. */
    @DisplayName("설정된 활성 슬롯 수까지만 대기 신청을 입장시킨다")
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

    /** 정상 lease 작업이 끝나야 다른 인스턴스가 참고할 유지보수 heartbeat를 남깁니다. */
    @DisplayName("유지보수 성공 시 만료 시간이 있는 공유 하트비트를 기록한다")
    @Test
    void successfulMaintenanceRecordsASharedHeartbeatWithExpiration() {
        maintenance(properties(1, Duration.ofMinutes(20), Duration.ofMinutes(2))).maintain(SERVICE_ID);

        assertNotNull(redis.opsForValue().get("maintenance-heartbeat:{reservation-service}"));
        org.junit.jupiter.api.Assertions.assertTrue(
                redis.getExpire("maintenance-heartbeat:{reservation-service}") > 0);
    }

    /** 동일 프로세스의 두 pass도 lease 세대가 달라야 이전 pass가 새 lease를 건드리지 않습니다. */
    @DisplayName("만료된 작업은 다음 작업의 임대를 해제하거나 하트비트를 쓸 수 없다")
    @Test
    void expiredPassOfTheSameInstanceCannotWriteHeartbeatOrReleaseTheNextPassLease() throws Exception {
        String leaseKey = "maintenance-lease:{reservation-service}";
        var firstEntered = new java.util.concurrent.CountDownLatch(1);
        var secondEntered = new java.util.concurrent.CountDownLatch(1);
        var releaseFirst = new java.util.concurrent.CountDownLatch(1);
        var releaseSecond = new java.util.concurrent.CountDownLatch(1);
        var passes = new java.util.concurrent.atomic.AtomicInteger();
        var controlledStore = org.mockito.Mockito.spy(store);
        org.mockito.Mockito.doAnswer(call -> {
            int pass = passes.incrementAndGet();
            (pass == 1 ? firstEntered : secondEntered).countDown();
            org.junit.jupiter.api.Assertions.assertTrue((pass == 1 ? releaseFirst : releaseSecond)
                    .await(5, java.util.concurrent.TimeUnit.SECONDS));
            return call.callRealMethod();
        }).when(controlledStore).activeExpirationCandidates(org.mockito.ArgumentMatchers.eq(SERVICE_ID),
                org.mockito.ArgumentMatchers.anyInt());
        var coordinator = org.mockito.Mockito.mock(WaitingRoomRecoveryCoordinator.class);
        org.mockito.Mockito.when(coordinator.isAvailable()).thenReturn(true);
        var maintenance = new WaitingRoomMaintenanceService(controlledStore,
                properties(1, Duration.ofMinutes(20), Duration.ofMinutes(2)), coordinator, "same-instance");

        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> maintenance.maintain(SERVICE_ID));
            try {
                org.junit.jupiter.api.Assertions.assertTrue(firstEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                String firstToken = redis.opsForValue().get(leaseKey);
                redis.expire(leaseKey, Duration.ZERO);
                var second = executor.submit(() -> maintenance.maintain(SERVICE_ID));
                org.junit.jupiter.api.Assertions.assertTrue(secondEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                String secondToken = redis.opsForValue().get(leaseKey);
                releaseFirst.countDown();
                first.get(5, java.util.concurrent.TimeUnit.SECONDS);

                org.junit.jupiter.api.Assertions.assertAll(
                        () -> org.junit.jupiter.api.Assertions.assertNotEquals(firstToken, secondToken),
                        () -> assertNull(redis.opsForValue().get("maintenance-heartbeat:{reservation-service}")),
                        () -> assertEquals(secondToken, redis.opsForValue().get(leaseKey)));
                releaseSecond.countDown();
                second.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertNotNull(redis.opsForValue().get("maintenance-heartbeat:{reservation-service}"));
                assertNull(redis.opsForValue().get(leaseKey));
            } finally {
                releaseFirst.countDown();
                releaseSecond.countDown();
            }
        }
    }

    /** 검증 목적: 자동 유지보수는 애플리케이션 기동 완료까지 기다린다. */
    @DisplayName("자동 유지보수는 애플리케이션 기동 완료까지 기다린다")
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

    /** 검증 목적: 입장 허용 슬롯이 만료되면 다음 신청을 입장시킨다. */
    @DisplayName("입장 허용 슬롯이 만료되면 다음 신청을 입장시킨다")
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

    /** 검증 목적: 대기 하트비트가 중단되면 신청을 만료한다. */
    @DisplayName("대기 하트비트가 중단되면 신청을 만료한다")
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

    /** 검증 목적: 입장 완료 세션 제한 시간이 지나면 신청을 만료한다. */
    @DisplayName("입장 완료 세션 제한 시간이 지나면 신청을 만료한다")
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

    /** 검증 목적: 최대 대기시간이 지나면 신청을 만료한다. */
    @DisplayName("최대 대기시간이 지나면 신청을 만료한다")
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

    /** 검증 목적: 정보가 없는 대기열 선두를 제거하고 다음 신청을 입장시킨다. */
    @DisplayName("정보가 없는 대기열 선두를 제거하고 다음 신청을 입장시킨다")
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

    /** 검증 목적: 손상된 만료 대기열 선두를 격리하고 다음 신청을 입장시킨다. */
    @DisplayName("손상된 만료 대기열 선두를 격리하고 다음 신청을 입장시킨다")
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

    /** 검증 목적: 손상된 만료 활성 슬롯을 격리하고 다음 신청을 입장시킨다. */
    @DisplayName("손상된 만료 활성 슬롯을 격리하고 다음 신청을 입장시킨다")
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

    /** 검증 목적: 구조가 잘못된 JSON을 격리하고 다음 신청을 입장시킨다. */
    @DisplayName("구조가 잘못된 JSON을 격리하고 다음 신청을 입장시킨다")
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

    /** 검증 목적: 이동 주소가 없는 대기 상태를 격리하고 다음 신청을 입장시킨다. */
    @DisplayName("이동 주소가 없는 대기 상태를 격리하고 다음 신청을 입장시킨다")
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

    /** 검증 목적: 알 수 없는 상태를 격리하고 다음 신청을 입장시킨다. */
    @DisplayName("알 수 없는 상태를 격리하고 다음 신청을 입장시킨다")
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

    /** 검증 목적: 선택 필드의 자료형이 잘못되면 격리하고 다음 신청을 입장시킨다. */
    @DisplayName("선택 필드의 자료형이 잘못되면 격리하고 다음 신청을 입장시킨다")
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

    /** 검증 목적: 일치하지 않는 대기열 멤버십을 제거하고 다음 신청을 입장시킨다. */
    @DisplayName("일치하지 않는 대기열 멤버십을 제거하고 다음 신청을 입장시킨다")
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

    /** 검증 목적: 하트비트가 없는 대기 신청을 만료하고 다음 신청을 입장시킨다. */
    @DisplayName("하트비트가 없는 대기 신청을 만료하고 다음 신청을 입장시킨다")
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
        // 기존 만료 테스트는 recovery barrier와 분리해 lease 안의 유지보수 업무를 검증합니다.
        var coordinator = org.mockito.Mockito.mock(WaitingRoomRecoveryCoordinator.class);
        org.mockito.Mockito.when(coordinator.isAvailable()).thenReturn(true);
        return new WaitingRoomMaintenanceService(store, properties, coordinator, "test-instance");
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

/**
 * @author jeongnam
 * @since 2026-09-21
 * @file WaitingRoomPropertiesTest.java
 * @description Waiting Room 설정의 기본값과 환경변수 바인딩을 검증합니다.
 */
package org.jn.waitingroom.config;

import org.jn.waitingroom.domain.WaitingRequestStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("대기열 설정값 검증 테스트")
class WaitingRoomPropertiesTest {
    /** 검증 목적: Redis 안전 설정의 기본값과 환경 변수 재정의를 바인딩한다. */
    @DisplayName("Redis 안전 설정의 기본값과 환경 변수 재정의를 바인딩한다")
    @Test
    void bindsRedisSafetyDefaultsAndEnvironmentOverrides() throws IOException {
        var defaults = bind(recoveryEnvironment("unused", "unused"));
        assertEquals(Duration.ofSeconds(5), defaults.redisSafetyCheckInterval());
        assertEquals(0.80, defaults.redisMemoryWarningRatio());
        assertEquals(0.90, defaults.redisMemoryBlockRatio());
        assertEquals(false, defaults.redisMaxmemoryPolicyAttested());
        assertEquals(0, defaults.redisMaxmemoryBytes());
        var variables = new HashMap<>(recoveryEnvironment("WAITING_ROOM_REDIS_SAFETY_CHECK_INTERVAL", "PT2S"));
        variables.put("WAITING_ROOM_REDIS_MEMORY_WARNING_RATIO", "0.60");
        variables.put("WAITING_ROOM_REDIS_MEMORY_BLOCK_RATIO", "0.75");
        variables.put("WAITING_ROOM_REDIS_MAXMEMORY_POLICY_ATTESTED", "true");
        variables.put("WAITING_ROOM_REDIS_MAXMEMORY_BYTES", "67108864");
        var properties = bind(variables);
        assertEquals(Duration.ofSeconds(2), properties.redisSafetyCheckInterval());
        assertEquals(0.60, properties.redisMemoryWarningRatio());
        assertEquals(0.75, properties.redisMemoryBlockRatio());
        assertEquals(true, properties.redisMaxmemoryPolicyAttested());
        assertEquals(67108864, properties.redisMaxmemoryBytes());
    }

    /** 검증 목적: 잘못된 Redis 점검 주기 비율과 대체 한도는 거절한다. */
    @DisplayName("잘못된 Redis 점검 주기 비율과 대체 한도는 거절한다")
    @Test
    void rejectsInvalidRedisSafetyIntervalsRatiosAndFallbackLimits() {
        for (var setting : Map.ofEntries(
                Map.entry("WAITING_ROOM_REDIS_SAFETY_CHECK_INTERVAL", "PT0S"),
                Map.entry("WAITING_ROOM_REDIS_MEMORY_WARNING_RATIO", "0"),
                Map.entry("WAITING_ROOM_REDIS_MEMORY_BLOCK_RATIO", "1"),
                Map.entry("WAITING_ROOM_REDIS_MAXMEMORY_BYTES", "-1")).entrySet()) {
            assertThrows(BindException.class, () -> bind(recoveryEnvironment(setting.getKey(), setting.getValue())),
                    setting.getKey());
        }
        var environment = new HashMap<>(recoveryEnvironment("WAITING_ROOM_REDIS_MEMORY_WARNING_RATIO", "0.90"));
        environment.put("WAITING_ROOM_REDIS_MEMORY_BLOCK_RATIO", "0.90");
        assertThrows(BindException.class, () -> bind(environment));
        environment.put("WAITING_ROOM_REDIS_MEMORY_WARNING_RATIO", "0.91");
        assertThrows(BindException.class, () -> bind(environment));
        for (String name : java.util.List.of("WAITING_ROOM_REDIS_MEMORY_WARNING_RATIO", "WAITING_ROOM_REDIS_MEMORY_BLOCK_RATIO")) {
            for (String invalid : java.util.List.of("-0.1", "NaN", "Infinity")) {
                assertThrows(BindException.class, () -> bind(recoveryEnvironment(name, invalid)));
            }
        }
        assertThrows(BindException.class, () -> bind(recoveryEnvironment("WAITING_ROOM_REDIS_SAFETY_CHECK_INTERVAL", "-PT1S")));
    }

    /** 검증 목적: 모든 편의 생성자는 Redis 안전 설정 기본값을 유지한다. */
    @DisplayName("모든 편의 생성자는 Redis 안전 설정 기본값을 유지한다")
    @Test
    void allConvenienceConstructorsPreserveRedisSafetyDefaults() throws IOException {
        var p = bind(recoveryEnvironment("unused", "unused"));
        var callers = java.util.List.of(
                new WaitingRoomProperties(p.schedulerInterval(), p.maxRetryAfter(), p.retryAfterSafetyMargin(),
                        p.quarantineTtl(), p.maintenanceLeaseTimeout(), p.maxAdmissionsPerRun(),
                        p.maxActiveExpirationsPerRun(), p.maxWaitingExpirationsPerRun(), p.maxCandidatesScannedPerRun(), p.services()),
                new WaitingRoomProperties(p.schedulerInterval(), p.maxRetryAfter(), p.retryAfterSafetyMargin(),
                        p.quarantineTtl(), p.maintenanceLeaseTimeout(), p.maxAdmissionsPerRun(),
                        p.maxActiveExpirationsPerRun(), p.maxWaitingExpirationsPerRun(), p.maxCandidatesScannedPerRun(),
                        p.services(), p.security()),
                new WaitingRoomProperties(p.schedulerInterval(), p.maxRetryAfter(), p.retryAfterSafetyMargin(),
                        p.quarantineTtl(), p.maintenanceLeaseTimeout(), p.maxAdmissionsPerRun(),
                        p.maxActiveExpirationsPerRun(), p.maxWaitingExpirationsPerRun(), p.maxCandidatesScannedPerRun(),
                        p.services(), p.security(), p.heartbeatExpirationRecoveryGrace(), p.maintenanceHealthTimeout()));
        for (var properties : callers) {
            assertEquals(Duration.ofSeconds(5), properties.redisSafetyCheckInterval());
            assertEquals(0.80, properties.redisMemoryWarningRatio());
            assertEquals(0.90, properties.redisMemoryBlockRatio());
            assertEquals(false, properties.redisMaxmemoryPolicyAttested());
            assertEquals(0, properties.redisMaxmemoryBytes());
        }
    }

    /** 검증 목적: 복구 유예 시간이 서비스 하트비트보다 짧으면 거절한다. */
    @DisplayName("복구 유예 시간이 서비스 하트비트보다 짧으면 거절한다")
    @Test
    void rejectsRecoveryGraceShorterThanServiceHeartbeat() {
        assertThrows(BindException.class, () -> bind(recoveryEnvironment(
                "WAITING_ROOM_HEARTBEAT_EXPIRATION_RECOVERY_GRACE", "PT19M")));
    }

    /** 검증 목적: 복구 유예 시간과 서비스 하트비트가 같으면 허용한다. */
    @DisplayName("복구 유예 시간과 서비스 하트비트가 같으면 허용한다")
    @Test
    void acceptsRecoveryGraceEqualToServiceHeartbeat() throws IOException {
        var properties = bind(recoveryEnvironment("WAITING_ROOM_HEARTBEAT_EXPIRATION_RECOVERY_GRACE", "PT20M"));
        assertEquals(Duration.ofMinutes(20), properties.heartbeatExpirationRecoveryGrace());
    }

    /** 검증 목적: 유지보수 상태 제한 시간이 임대 안전 경계보다 짧으면 거절한다. */
    @DisplayName("유지보수 상태 제한 시간이 임대 안전 경계보다 짧으면 거절한다")
    @Test
    void rejectsMaintenanceHealthTimeoutBelowLeaseSafetyBoundary() {
        assertThrows(BindException.class, () -> bind(recoveryEnvironment(
                "WAITING_ROOM_MAINTENANCE_HEALTH_TIMEOUT", "PT9.999S")));
    }

    /** 검증 목적: 유지보수 상태 제한 시간이 임대 안전 경계와 같으면 허용한다. */
    @DisplayName("유지보수 상태 제한 시간이 임대 안전 경계와 같으면 허용한다")
    @Test
    void acceptsMaintenanceHealthTimeoutAtLeaseSafetyBoundary() throws IOException {
        var properties = bind(recoveryEnvironment("WAITING_ROOM_MAINTENANCE_HEALTH_TIMEOUT", "PT10S"));
        assertEquals(Duration.ofSeconds(10), properties.maintenanceHealthTimeout());
    }

    /** 검증 목적: 스케줄러 안전 경계가 임대 시간 두 배를 넘을 때 유효성을 검사한다. */
    @DisplayName("스케줄러 안전 경계가 임대 시간 두 배를 넘을 때 유효성을 검사한다")
    @Test
    void validatesSchedulerSafetyBoundaryWhenItExceedsTwiceTheLease() throws IOException {
        var environment = new HashMap<>(recoveryEnvironment("WAITING_ROOM_SCHEDULER_INTERVAL", "PT4S"));
        environment.put("WAITING_ROOM_MAINTENANCE_HEALTH_TIMEOUT", "PT11.999S");
        assertThrows(BindException.class, () -> bind(environment));
        environment.put("WAITING_ROOM_MAINTENANCE_HEALTH_TIMEOUT", "PT12S");
        assertEquals(Duration.ofSeconds(12), bind(environment).maintenanceHealthTimeout());
    }

    /** 검증 목적: 복구와 상태 제한 시간이 양수가 아니면 거절한다. */
    @DisplayName("복구와 상태 제한 시간이 양수가 아니면 거절한다")
    @Test
    void rejectsNonPositiveRecoveryAndHealthTimeouts() {
        for (String property : java.util.List.of("WAITING_ROOM_HEARTBEAT_EXPIRATION_RECOVERY_GRACE",
                "WAITING_ROOM_MAINTENANCE_HEALTH_TIMEOUT")) {
            for (String value : java.util.List.of("PT0S", "-PT1S")) {
                assertThrows(BindException.class, () -> bind(recoveryEnvironment(property, value)));
            }
        }
    }

    /** 검증 목적: 유지보수 임대 시간을 밀리초로 변환할 때 오버플로를 거절한다. */
    @DisplayName("유지보수 임대 시간을 밀리초로 변환할 때 오버플로를 거절한다")
    @Test
    void rejectsMaintenanceLeaseMillisOverflowAsConfigurationError() {
        var environment = recoveryEnvironment("WAITING_ROOM_MAINTENANCE_LEASE_TIMEOUT", "PT9223372036854775807S");
        var exception = assertThrows(BindException.class, () -> bind(environment));
        assertThat(exception).hasRootCauseInstanceOf(ArithmeticException.class);
        assertThat(exception).hasStackTraceContaining("IllegalArgumentException: maintenanceLeaseTimeout의 밀리초 값");
    }

    /** 거대한 Duration은 런타임 Redis 호출 전에 설정 오류로 거부합니다. */
    @DisplayName("전역 시간 설정의 밀리초 오버플로를 거절한다")
    @ParameterizedTest(name = "전역 시간 설정 {0}의 밀리초 오버플로를 거절한다")
    @CsvSource({
            "scheduler-interval, schedulerInterval",
            "max-retry-after, maxRetryAfter",
            "retry-after-safety-margin, retryAfterSafetyMargin",
            "quarantine-ttl, quarantineTtl",
            "maintenance-lease-timeout, maintenanceLeaseTimeout",
            "heartbeat-expiration-recovery-grace, heartbeatExpirationRecoveryGrace",
            "maintenance-health-timeout, maintenanceHealthTimeout",
            "redis-safety-check-interval, redisSafetyCheckInterval"
    })
    void rejectsGlobalDurationMillisOverflow(String property, String field) {
        var exception = assertThrows(BindException.class, () -> bind(recoveryEnvironment(
                "waiting-room." + property, Duration.ofSeconds(Long.MAX_VALUE).toString())));
        assertThat(exception).hasRootCauseInstanceOf(ArithmeticException.class)
                .hasStackTraceContaining("IllegalArgumentException: " + field + "의 밀리초 값");
    }

    /** 검증 목적: 서비스별 시간 설정의 밀리초 오버플로를 거절한다. */
    @DisplayName("서비스별 시간 설정의 밀리초 오버플로를 거절한다")
    @ParameterizedTest(name = "서비스별 시간 설정 {0}의 밀리초 오버플로를 거절한다")
    @CsvSource({
            "heartbeat-timeout, heartbeatTimeout",
            "admission-timeout, admissionTimeout",
            "max-session-duration, maxSessionDuration",
            "eta-window, etaWindow",
            "eta-min-observation, etaMinObservation",
            "request-ttl, requestTtl",
            "max-wait-duration, maxWaitDuration",
            "waiting-token-ttl, waitingTokenTtl",
            "cleanup-margin, cleanupMargin"
    })
    void rejectsServiceDurationMillisOverflow(String property, String field) {
        var exception = assertThrows(BindException.class, () -> bind(recoveryEnvironment(
                "waiting-room.services.reservation-service." + property,
                Duration.ofSeconds(Long.MAX_VALUE).toString())));
        assertThat(exception).hasRootCauseInstanceOf(ArithmeticException.class)
                .hasStackTraceContaining("IllegalArgumentException: " + field + "의 밀리초 값");
    }

    /** 검증 목적: 복구 유예 시간과 정리 여유 시간의 합계 오버플로를 거절한다. */
    @DisplayName("복구 유예 시간과 정리 여유 시간의 합계 오버플로를 거절한다")
    @Test
    void rejectsRecoveryGraceAndCleanupMarginMillisSumOverflow() {
        // 각 값은 long 밀리초 범위 안이지만 합계는 1ms 초과합니다.
        var grace = Duration.ofMillis(Long.MAX_VALUE - 599_999);
        var exception = assertThrows(BindException.class, () -> bind(recoveryEnvironment(
                "WAITING_ROOM_HEARTBEAT_EXPIRATION_RECOVERY_GRACE", grace.toString())));
        assertThat(exception).hasRootCauseInstanceOf(ArithmeticException.class)
                .hasStackTraceContaining("IllegalArgumentException: heartbeatExpirationRecoveryGrace + cleanupMargin");
    }

    /** 검증 목적: 복구 유예 시간과 정리 여유 시간이 밀리초 경계에 있으면 허용한다. */
    @DisplayName("복구 유예 시간과 정리 여유 시간이 밀리초 경계에 있으면 허용한다")
    @Test
    void acceptsRecoveryGraceAndCleanupMarginAtMillisBoundary() throws IOException {
        var grace = Duration.ofMillis(Long.MAX_VALUE - 600_000);
        var properties = bind(recoveryEnvironment(
                "WAITING_ROOM_HEARTBEAT_EXPIRATION_RECOVERY_GRACE", grace.toString()));
        assertEquals(Long.MAX_VALUE, properties.heartbeatExpirationRecoveryGrace()
                .plus(properties.services().get("reservation-service").cleanupMargin()).toMillis());
    }

    /** 검증 목적: 전역 시간 설정이 밀리초 경계에 있으면 허용한다. */
    @DisplayName("전역 시간 설정이 밀리초 경계에 있으면 허용한다")
    @Test
    void acceptsGlobalDurationAtMillisBoundary() throws IOException {
        var properties = bind(recoveryEnvironment("WAITING_ROOM_QUARANTINE_TTL",
                Duration.ofMillis(Long.MAX_VALUE).toString()));
        assertEquals(Long.MAX_VALUE, properties.quarantineTtl().toMillis());
    }

    /** 검증 목적: 서비스별 시간 설정이 밀리초 경계에 있으면 허용한다. */
    @DisplayName("서비스별 시간 설정이 밀리초 경계에 있으면 허용한다")
    @Test
    void acceptsServiceDurationAtMillisBoundary() throws IOException {
        var properties = bind(recoveryEnvironment("waiting-room.services.reservation-service.request-ttl",
                Duration.ofMillis(Long.MAX_VALUE).toString()));
        assertEquals(Long.MAX_VALUE, properties.services().get("reservation-service").requestTtl().toMillis());
    }

    private Map<String, Object> recoveryEnvironment(String name, String value) {
        return Map.of("WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS", 100,
                "WAITING_ROOM_RESERVATION_ENTRY_URL", "https://service.example/entry", name, value);
    }

    /** 검증 목적: 신청 TTL이 전체 생명주기와 같으면 거절한다. */
    @DisplayName("신청 TTL이 전체 생명주기와 같으면 거절한다")
    @Test
    void rejectsRequestTtlEqualToFullLifecycle() {
        assertThrows(IllegalArgumentException.class, () -> service(
                100, Duration.ofMinutes(20), Duration.ofMinutes(2), Duration.ofMinutes(30),
                Duration.ofMinutes(1422), Duration.ofHours(23)));
    }

    /** 검증 목적: 신청 TTL이 전체 생명주기보다 짧으면 거절한다. */
    @DisplayName("신청 TTL이 전체 생명주기보다 짧으면 거절한다")
    @Test
    void rejectsRequestTtlShorterThanFullLifecycle() {
        assertThrows(IllegalArgumentException.class, () -> service(
                100, Duration.ofMinutes(20), Duration.ofMinutes(2), Duration.ofMinutes(30),
                Duration.ofMinutes(1422).minusMillis(1), Duration.ofHours(23)));
    }

    /** 검증 목적: 신청 TTL이 전체 생명주기보다 1밀리초 길면 허용한다. */
    @DisplayName("신청 TTL이 전체 생명주기보다 1밀리초 길면 허용한다")
    @Test
    void acceptsRequestTtlOneMillisecondLongerThanFullLifecycle() {
        var service = service(
                100, Duration.ofMinutes(20), Duration.ofMinutes(2), Duration.ofMinutes(30),
                Duration.ofMinutes(1422).plusMillis(1), Duration.ofHours(23));

        assertEquals(Duration.ofMinutes(1422).plusMillis(1), service.requestTtl());
    }

    /** 검증 목적: 신청 TTL의 밀리초 오버플로를 설정 오류로 거절한다. */
    @DisplayName("신청 TTL의 밀리초 오버플로를 설정 오류로 거절한다")
    @Test
    void rejectsRequestTtlMillisOverflowAsConfigurationError() {
        var exception = assertThrows(IllegalArgumentException.class, () -> service(
                100, Duration.ofMinutes(20), Duration.ofMinutes(2), Duration.ofMinutes(30),
                Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(Long.MAX_VALUE - 1)));

        assertThat(exception).hasMessageContaining("requestTtl의 밀리초 값").hasCauseInstanceOf(ArithmeticException.class);
    }

    /** 검증 목적: application.yml의 기본 설정값을 바인딩한다. */
    @DisplayName("application.yml의 기본 설정값을 바인딩한다")
    @Test
    void bindsDefaultValuesFromApplicationYaml() throws IOException {
        var properties = bind(Map.of(
                "WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS", 100,
                "WAITING_ROOM_RESERVATION_ENTRY_URL", "https://service.example/entry"
        ));
        var service = properties.services().get("reservation-service");

        assertEquals(Duration.ofSeconds(1), properties.schedulerInterval());
        assertEquals(Duration.ofSeconds(30), properties.maxRetryAfter());
        assertEquals(Duration.ofSeconds(1), properties.retryAfterSafetyMargin());
        assertEquals(Duration.ofHours(1), properties.quarantineTtl());
        assertEquals(Duration.ofSeconds(5), properties.maintenanceLeaseTimeout());
        assertEquals(Duration.ofMinutes(20), properties.heartbeatExpirationRecoveryGrace());
        assertEquals(Duration.ofSeconds(30), properties.maintenanceHealthTimeout());
        assertEquals(100, properties.maxAdmissionsPerRun());
        assertEquals(100, properties.maxActiveExpirationsPerRun());
        assertEquals(100, properties.maxWaitingExpirationsPerRun());
        assertEquals(300, properties.maxCandidatesScannedPerRun());
        assertEquals(Duration.ofMinutes(20), service.heartbeatTimeout());
        assertEquals(Duration.ofMinutes(2), service.admissionTimeout());
        assertEquals(Duration.ofMinutes(30), service.maxSessionDuration());
        assertEquals(Duration.ofMinutes(5), service.etaWindow());
        assertEquals(10, service.etaMinSamples());
        assertEquals(Duration.ofMinutes(1), service.etaMinObservation());
        assertEquals(0.0, service.etaInitialReleaseRatePerSecond());
        assertEquals(Duration.ofHours(24), service.requestTtl());
        assertEquals(Duration.ofMinutes(5), service.waitingTokenTtl());
        assertEquals(Duration.ofHours(23), service.maxWaitDuration());
        assertEquals(Duration.ofMinutes(10), service.cleanupMargin());
        assertEquals(100, service.maxConcurrentUsers());
        assertEquals("https://service.example/entry", service.redirects().get("service-entry"));
        assertEquals(false, properties.security().oauth2().enabled());
    }

    /** 검증 목적: 환경 변수가 기본 설정값을 재정의한다. */
    @DisplayName("환경 변수가 기본 설정값을 재정의한다")
    @Test
    void environmentVariablesOverrideDefaultValues() throws IOException {
        var properties = bind(Map.ofEntries(
                Map.entry("WAITING_ROOM_MAX_RETRY_AFTER", "PT10S"),
                Map.entry("WAITING_ROOM_RETRY_AFTER_SAFETY_MARGIN", "PT2S"),
                Map.entry("WAITING_ROOM_QUARANTINE_TTL", "PT3H"),
                Map.entry("WAITING_ROOM_HEARTBEAT_EXPIRATION_RECOVERY_GRACE", "PT25M"),
                Map.entry("WAITING_ROOM_MAINTENANCE_HEALTH_TIMEOUT", "PT45S"),
                Map.entry("WAITING_ROOM_RESERVATION_HEARTBEAT_TIMEOUT", "PT45S"),
                Map.entry("WAITING_ROOM_RESERVATION_ETA_WINDOW", "PT10M"),
                Map.entry("WAITING_ROOM_RESERVATION_ETA_MIN_SAMPLES", 20),
                Map.entry("WAITING_ROOM_RESERVATION_ETA_MIN_OBSERVATION", "PT2M"),
                Map.entry("WAITING_ROOM_RESERVATION_ETA_INITIAL_RELEASE_RATE_PER_SECOND", 55.6),
                Map.entry("WAITING_ROOM_RESERVATION_REQUEST_TTL", "PT48H"),
                Map.entry("WAITING_ROOM_RESERVATION_TOKEN_TTL", "PT2M"),
                Map.entry("WAITING_ROOM_RESERVATION_MAX_WAIT_DURATION", "PT12H"),
                Map.entry("WAITING_ROOM_RESERVATION_CLEANUP_MARGIN", "PT20M"),
                Map.entry("WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS", 250),
                Map.entry("WAITING_ROOM_RESERVATION_ENTRY_URL", "https://other.example/entry")
        ));
        var service = properties.services().get("reservation-service");

        assertEquals(Duration.ofSeconds(10), properties.maxRetryAfter());
        assertEquals(Duration.ofSeconds(2), properties.retryAfterSafetyMargin());
        assertEquals(Duration.ofHours(3), properties.quarantineTtl());
        assertEquals(Duration.ofMinutes(25), properties.heartbeatExpirationRecoveryGrace());
        assertEquals(Duration.ofSeconds(45), properties.maintenanceHealthTimeout());
        assertEquals(Duration.ofSeconds(45), service.heartbeatTimeout());
        assertEquals(Duration.ofMinutes(10), service.etaWindow());
        assertEquals(20, service.etaMinSamples());
        assertEquals(Duration.ofMinutes(2), service.etaMinObservation());
        assertEquals(55.6, service.etaInitialReleaseRatePerSecond());
        assertEquals(Duration.ofHours(48), service.requestTtl());
        assertEquals(Duration.ofMinutes(2), service.waitingTokenTtl());
        assertEquals(Duration.ofHours(12), service.maxWaitDuration());
        assertEquals(Duration.ofMinutes(20), service.cleanupMargin());
        assertEquals(250, service.maxConcurrentUsers());
        assertEquals("https://other.example/entry", service.redirects().get("service-entry"));
    }

    /** 검증 목적: 대기 토큰 TTL이 양수가 아니면 거절한다. */
    @DisplayName("대기 토큰 TTL이 양수가 아니면 거절한다")
    @Test
    void rejectsNonPositiveWaitingTokenTtl() {
        for (String ttl : java.util.List.of("PT0S", "-PT1S", "PT0.000000001S")) {
            assertThrows(BindException.class, () -> bind(Map.of(
                    "WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS", 100,
                    "WAITING_ROOM_RESERVATION_ENTRY_URL", "https://service.example/entry",
                    "WAITING_ROOM_RESERVATION_TOKEN_TTL", ttl)));
        }
    }

    /** 검증 목적: 승인된 신청 상태만 외부에 노출한다. */
    @DisplayName("승인된 신청 상태만 외부에 노출한다")
    @Test
    void exposesOnlyTheApprovedRequestStatuses() {
        var names = Stream.of(WaitingRequestStatus.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertEquals(Set.of("WAITING", "ADMITTED", "ENTERED", "EXPIRED", "CANCELLED"), names);
    }

    /** 검증 목적: 안전하지 않은 스케줄러 및 배치 설정을 거절한다. */
    @DisplayName("안전하지 않은 스케줄러 및 배치 설정을 거절한다")
    @Test
    void rejectsUnsafeSchedulerAndBatchSettings() {
        var service = validService();

        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofSeconds(1),
                100,
                100,
                100,
                300,
                Map.of("reservation-service", service)
        ));
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ZERO,
                Duration.ofSeconds(5),
                100,
                100,
                100,
                300,
                Map.of("reservation-service", service)
        ));
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties(
                Duration.ofSeconds(1),
                Duration.ofMinutes(20),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofSeconds(5),
                100,
                100,
                100,
                300,
                Map.of("reservation-service", service)
        ));
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofMinutes(20),
                Duration.ofHours(1),
                Duration.ofSeconds(5),
                100,
                100,
                100,
                300,
                Map.of("reservation-service", service)
        ));
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofSeconds(5),
                0,
                100,
                100,
                300,
                Map.of("reservation-service", service)
        ));
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofSeconds(5),
                100,
                100,
                100,
                99,
                Map.of("reservation-service", service)
        ));
    }

    /** 검증 목적: 안전하지 않은 서비스 제한 시간과 수용 인원을 거절한다. */
    @DisplayName("안전하지 않은 서비스 제한 시간과 수용 인원을 거절한다")
    @Test
    void rejectsUnsafeServiceTimeoutsAndCapacity() {
        assertThrows(IllegalArgumentException.class, () -> service(
                0,
                Duration.ofMinutes(20),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                Duration.ofDays(1),
                Duration.ofHours(23)
        ));
        assertThrows(IllegalArgumentException.class, () -> service(
                100,
                Duration.ofMinutes(20),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                Duration.ofHours(23),
                Duration.ofHours(23)
        ));
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties(
                Duration.ofMinutes(2),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofMinutes(5),
                100,
                100,
                100,
                300,
                Map.of("reservation-service", validService())
        ));
    }

    /** 검증 목적: 잘못된 서비스 ID와 이동 주소를 거절한다. */
    @DisplayName("잘못된 서비스 ID와 이동 주소를 거절한다")
    @Test
    void rejectsInvalidServiceIdsAndRedirectTargets() {
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofSeconds(5),
                100,
                100,
                100,
                300,
                Map.of("Reservation_Service", validService())
        ));

        var redirects = new HashMap<String, String>();
        redirects.put("service-entry", "/relative-entry");
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties.ServiceProperties(
                100,
                Duration.ofMinutes(20),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                Duration.ofMinutes(5),
                10,
                Duration.ofMinutes(1),
                0.0,
                Duration.ofDays(1),
                Duration.ofHours(23),
                redirects
        ));
    }

    /** 검증 목적: 예상 시간 관측 길이가 집계 창보다 길면 거절한다. */
    @DisplayName("예상 시간 관측 길이가 집계 창보다 길면 거절한다")
    @Test
    void rejectsAnEtaObservationLongerThanItsWindow() {
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties.ServiceProperties(
                100,
                Duration.ofMinutes(20),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                Duration.ofSeconds(30),
                10,
                Duration.ofMinutes(1),
                0.0,
                Duration.ofDays(1),
                Duration.ofHours(23),
                Map.of("service-entry", "https://service.example/entry")
        ));
    }

    /** 검증 목적: 잘못된 초기 예상 해제 속도를 거절한다. */
    @DisplayName("잘못된 초기 예상 해제 속도를 거절한다")
    @Test
    void rejectsInvalidInitialEtaReleaseRates() {
        assertThrows(IllegalArgumentException.class, () -> serviceWithInitialReleaseRate(-0.1));
        assertThrows(IllegalArgumentException.class, () -> serviceWithInitialReleaseRate(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> serviceWithInitialReleaseRate(Double.POSITIVE_INFINITY));
    }

    /** 검증 목적: 등록되지 않은 서비스에 대한 클라이언트 매핑을 거절한다. */
    @DisplayName("등록되지 않은 서비스에 대한 클라이언트 매핑을 거절한다")
    @Test
    void rejectsClientMappingToUnregisteredService() {
        assertThrows(BindException.class, () -> bind(Map.ofEntries(
                Map.entry("WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS", 100),
                Map.entry("WAITING_ROOM_RESERVATION_ENTRY_URL", "https://service.example/entry"),
                Map.entry("waiting-room.security.clients.reservation-service-client.services[0]", "unknown-service")
        )));
    }

    /** 검증 목적: OAuth 활성화 시 issuer와 audience가 모두 필요하다. */
    @DisplayName("OAuth 활성화 시 issuer와 audience가 모두 필요하다")
    @Test
    void oauth2RequiresIssuerAndAudienceAtStartup() throws IOException {
        contextRunner().withPropertyValues("WAITING_ROOM_OAUTH2_ENABLED=true")
                .run(context -> assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .hasRootCauseMessage("OAuth2 활성화 시 issuer-uri와 audiences가 필수입니다."));
    }

    /** 검증 목적: issuer만 설정해도 audience가 없으면 거절한다. */
    @DisplayName("issuer만 설정해도 audience가 없으면 거절한다")
    @Test
    void oauth2RequiresAudienceEvenWithIssuer() throws IOException {
        contextRunner().withPropertyValues(
                "WAITING_ROOM_OAUTH2_ENABLED=true",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://auth.example.com"
        ).run(context -> assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("OAuth2 활성화 시 issuer-uri와 audiences가 필수입니다."));
    }

    /** 검증 목적: issuer와 audience가 있으면 OAuth 설정으로 시작한다. */
    @DisplayName("issuer와 audience가 있으면 OAuth 설정으로 시작한다")
    @Test
    void oauth2StartsWithIssuerAndAudience() throws IOException {
        contextRunner().withPropertyValues(
                "WAITING_ROOM_OAUTH2_ENABLED=true",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://auth.example.com",
                "spring.security.oauth2.resourceserver.jwt.audiences[0]=waiting-room-api"
        ).run(context -> assertNull(context.getStartupFailure()));
    }

    /** 검증 목적: 운영과 데모 프로필은 동시에 시작할 수 없다. */
    @DisplayName("운영과 데모 프로필은 동시에 시작할 수 없다")
    @Test
    void prodAndDemoProfilesCannotStartTogether() throws IOException {
        contextRunner().withPropertyValues("spring.profiles.active=prod,demo")
                .run(context -> assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .hasRootCauseMessage("prod와 demo 프로필을 동시에 활성화할 수 없습니다."));
    }

    /** 검증 목적: OAuth를 꺼도 운영 프로필은 시작할 수 있다. */
    @DisplayName("OAuth를 꺼도 운영 프로필은 시작할 수 있다")
    @Test
    void productionStartsWithOauth2Disabled() throws IOException {
        contextRunner().withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertNull(context.getStartupFailure()));
    }

    /** 검증 목적: 운영 프로필 없이 데모 프로필은 시작할 수 있다. */
    @DisplayName("운영 프로필 없이 데모 프로필은 시작할 수 있다")
    @Test
    void demoStartsWithoutProdProfile() throws IOException {
        contextRunner().withPropertyValues("spring.profiles.active=demo")
                .run(context -> assertNull(context.getStartupFailure()));
    }

    /** 운영 환경은 모든 redirect를 HTTPS로 제한합니다. */
    @DisplayName("운영 프로필은 HTTP 이동 주소를 거절한다")
    @Test
    void productionRejectsHttpRedirect() throws IOException {
        contextRunner().withPropertyValues("spring.profiles.active=prod",
                        "WAITING_ROOM_RESERVATION_ENTRY_URL=http://localhost:5173/demo/admitted")
                .run(context -> assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .hasRootCauseMessage("prod 프로필의 redirect URL은 HTTPS여야 합니다."));
    }

    /** 개발 프로필은 localhost HTTP 데모를 계속 지원합니다. */
    @DisplayName("운영 외 프로필은 HTTP 이동 주소를 허용한다")
    @Test
    void nonProductionAllowsHttpRedirect() throws IOException {
        for (String profile : java.util.List.of("demo", "default")) {
            contextRunner().withPropertyValues("spring.profiles.active=" + profile,
                            "WAITING_ROOM_RESERVATION_ENTRY_URL=http://localhost:5173/demo/admitted")
                    .run(context -> assertNull(context.getStartupFailure()));
        }
    }

    /** 검증 목적: 데모 클라이언트 매핑은 데모 프로필에서만 적용된다. */
    @DisplayName("데모 클라이언트 매핑은 데모 프로필에서만 적용된다")
    @Test
    void demoClientMappingIsLimitedToDemoProfile() {
        var runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS=100",
                        "WAITING_ROOM_RESERVATION_ENTRY_URL=https://service.example/entry"
                )
                .withUserConfiguration(ValidationTestConfiguration.class);

        runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(false, context.getBean(WaitingRoomProperties.class)
                    .security().clients().containsKey("demo-reservation-client"));
        });
        runner.withPropertyValues("spring.profiles.active=demo").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(java.util.List.of("reservation-service"), context.getBean(WaitingRoomProperties.class)
                    .security().clients().get("demo-reservation-client").services());
        });
    }

    /** 검증 목적: 데모 JWT는 서비스 소유권이 없는 클라이언트를 거절한다. */
    @DisplayName("데모 JWT는 서비스 소유권이 없는 클라이언트를 거절한다")
    @Test
    void demoJwtRejectsClientWithoutServiceOwnership() {
        var runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS=100",
                        "WAITING_ROOM_RESERVATION_ENTRY_URL=https://service.example/entry",
                        "spring.profiles.active=demo",
                        "WAITING_ROOM_DEMO_JWT_ENABLED=true",
                        "WAITING_ROOM_DEMO_JWT_CLIENT_ID=custom-demo-client"
                )
                .withUserConfiguration(ValidationTestConfiguration.class);

        runner.run(context -> assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("데모 JWT clientId는 데모 serviceId에 접근할 수 있어야 합니다."));
    }

    /** 검증 목적: 데모 JWT는 매핑된 사용자 지정 클라이언트를 허용한다. */
    @DisplayName("데모 JWT는 매핑된 사용자 지정 클라이언트를 허용한다")
    @Test
    void demoJwtAcceptsMappedCustomClient() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS=100",
                        "WAITING_ROOM_RESERVATION_ENTRY_URL=https://service.example/entry",
                        "spring.profiles.active=demo",
                        "WAITING_ROOM_DEMO_JWT_ENABLED=true",
                        "WAITING_ROOM_DEMO_JWT_CLIENT_ID=custom-demo-client",
                        "waiting-room.security.clients.custom-demo-client.services[0]=reservation-service"
                )
                .withUserConfiguration(ValidationTestConfiguration.class)
                .run(context -> assertNull(context.getStartupFailure()));
    }

    /** 검증 목적: OAuth 스위치와 클라이언트 서비스 매핑을 바인딩한다. */
    @DisplayName("OAuth 스위치와 클라이언트 서비스 매핑을 바인딩한다")
    @Test
    void bindsOauth2SwitchAndClientServices() throws IOException {
        var properties = bind(Map.of(
                "WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS", 100,
                "WAITING_ROOM_RESERVATION_ENTRY_URL", "https://service.example/entry",
                "WAITING_ROOM_OAUTH2_ENABLED", true
        ));

        assertEquals(true, properties.security().oauth2().enabled());
        assertEquals(java.util.List.of("reservation-service"),
                properties.security().clients().get("reservation-service-client").services());
    }

    /** 검증 목적: 비어 있는 클라이언트 ID와 서비스 ID를 거절한다. */
    @DisplayName("비어 있는 클라이언트 ID와 서비스 ID를 거절한다")
    @Test
    void rejectsBlankClientAndServiceIds() {
        var client = new WaitingRoomProperties.ClientProperties(java.util.List.of("reservation-service"));
        var security = new WaitingRoomProperties.SecurityProperties(
                new WaitingRoomProperties.OAuth2Properties(false), Map.of(" ", client)
        );
        assertThrows(IllegalArgumentException.class, () -> new WaitingRoomProperties(
                Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofHours(1), Duration.ofSeconds(5), 100, 100, 100, 300,
                Map.of("reservation-service", validService()), security
        ));
        assertThrows(IllegalArgumentException.class,
                () -> new WaitingRoomProperties.ClientProperties(java.util.List.of(" ")));
    }

    /** 검증 목적: 데모 JWT가 활성일 때 TTL이 양수가 아니면 거절한다. */
    @DisplayName("데모 JWT가 활성일 때 TTL이 양수가 아니면 거절한다")
    @Test
    void rejectsNonPositiveDemoJwtTtlWhenEnabled() throws IOException {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("testEnvironment", Map.of(
                "WAITING_ROOM_DEMO_JWT_ENABLED", true,
                "WAITING_ROOM_DEMO_JWT_TTL", "PT0S"
        )));
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);

        assertThrows(BindException.class, () -> Binder.get(environment)
                .bind("waiting-room.demo", WaitingRoomDemoProperties.class)
                .orElseThrow(() -> new IllegalStateException("demo 설정을 바인딩할 수 없습니다.")));
    }

    /** 검증 목적: 환경 변수에서 데모 JWT 설정을 바인딩한다. */
    @DisplayName("환경 변수에서 데모 JWT 설정을 바인딩한다")
    @Test
    void bindsDemoJwtSettingsFromEnvironment() throws IOException {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("testEnvironment", Map.of(
                "WAITING_ROOM_DEMO_JWT_ENABLED", true,
                "WAITING_ROOM_DEMO_JWT_TTL", "PT30M",
                "WAITING_ROOM_DEMO_JWT_ISSUER", "demo-issuer",
                "WAITING_ROOM_DEMO_JWT_AUDIENCE", "demo-audience",
                "WAITING_ROOM_DEMO_JWT_CLIENT_ID", "demo-client"
        )));
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);

        var demo = Binder.get(environment).bind("waiting-room.demo", WaitingRoomDemoProperties.class)
                .orElseThrow(() -> new IllegalStateException("demo 설정을 바인딩할 수 없습니다."));
        assertEquals(true, demo.jwtEnabled());
        assertEquals(Duration.ofMinutes(30), demo.jwtTtl());
        assertEquals("demo-issuer", demo.jwtIssuer());
        assertEquals("demo-audience", demo.jwtAudience());
        assertEquals("demo-client", demo.jwtClientId());
    }

    private ApplicationContextRunner contextRunner() throws IOException {
        var yamlSources = new YamlPropertySourceLoader().load(
                "application.yml", new ClassPathResource("application.yml")
        );
        return new ApplicationContextRunner()
                .withInitializer(context -> yamlSources.forEach(context.getEnvironment().getPropertySources()::addLast))
                .withPropertyValues(
                        "WAITING_ROOM_RESERVATION_MAX_CONCURRENT_USERS=100",
                        "WAITING_ROOM_RESERVATION_ENTRY_URL=https://service.example/entry"
                )
                .withUserConfiguration(ValidationTestConfiguration.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({WaitingRoomProperties.class, WaitingRoomDemoProperties.class})
    @ComponentScan(basePackageClasses = WaitingRoomProperties.class,
            excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = WaitingRoomMaintenanceConfiguration.class))
    static class ValidationTestConfiguration {
    }

    private WaitingRoomProperties bind(Map<String, Object> environmentVariables) throws IOException {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("testEnvironment", environmentVariables)
        );

        var yamlSources = new YamlPropertySourceLoader().load(
                "application.yml",
                new ClassPathResource("application.yml")
        );
        yamlSources.forEach(environment.getPropertySources()::addLast);

        return Binder.get(environment)
                .bind("waiting-room", WaitingRoomProperties.class)
                .orElseThrow(() -> new IllegalStateException("waiting-room 설정을 바인딩할 수 없습니다."));
    }

    private WaitingRoomProperties.ServiceProperties validService() {
        return service(
                100,
                Duration.ofMinutes(20),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                Duration.ofDays(1),
                Duration.ofHours(23)
        );
    }

    private WaitingRoomProperties.ServiceProperties service(
            int maxConcurrentUsers,
            Duration heartbeatTimeout,
            Duration admissionTimeout,
            Duration maxSessionDuration,
            Duration requestTtl,
            Duration maxWaitDuration
    ) {
        return new WaitingRoomProperties.ServiceProperties(
                maxConcurrentUsers,
                heartbeatTimeout,
                admissionTimeout,
                maxSessionDuration,
                Duration.ofMinutes(5),
                10,
                Duration.ofMinutes(1),
                0.0,
                requestTtl,
                maxWaitDuration,
                Map.of("service-entry", "https://service.example/entry")
        );
    }

    private WaitingRoomProperties.ServiceProperties serviceWithInitialReleaseRate(double initialReleaseRate) {
        return new WaitingRoomProperties.ServiceProperties(
                100,
                Duration.ofMinutes(20),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                Duration.ofMinutes(5),
                10,
                Duration.ofMinutes(1),
                initialReleaseRate,
                Duration.ofDays(1),
                Duration.ofHours(23),
                Map.of("service-entry", "https://service.example/entry")
        );
    }
}

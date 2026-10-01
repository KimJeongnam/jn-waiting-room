/**
 * @author jeongnam
 * @since 2026-09-21
 * @file WaitingRoomProperties.java
 * @description Waiting Room의 전역 설정과 대상 서비스별 정책을 정의합니다.
 */
package org.jn.waitingroom.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 대기열의 시간 정책과 서비스별 동시 수용량을 외부 설정에서 읽습니다.
 *
 * @param schedulerInterval 모든 서비스의 입장·만료 처리를 확인하는 스케줄러 실행 주기
 * @param maxRetryAfter 재시도 응답에서 안내할 수 있는 최대 대기시간
 * @param retryAfterSafetyMargin heartbeat 만료 전에 확보할 재시도 안전 여유시간
 * @param quarantineTtl 손상된 요청 원문을 운영 확인용으로 보관하는 시간
 * @param maintenanceLeaseTimeout 서비스별 유지보수 실행 lease 유지시간
 * @param maxAdmissionsPerRun 한 번의 유지보수 실행에서 허용할 최대 입장 수
 * @param maxActiveExpirationsPerRun 한 번에 정리할 최대 활성 슬롯 수
 * @param maxWaitingExpirationsPerRun 한 번에 정리할 최대 대기 요청 수
 * @param maxCandidatesScannedPerRun 한 번에 검사할 최대 요청 후보 수
 * @param services 서비스 ID를 키로 사용하는 대상 서비스별 대기 정책
 * @param security Backend API 인증 모드와 client별 접근 가능한 서비스
 * @param heartbeatExpirationRecoveryGrace 복구 후 기존 대기 heartbeat 만료를 유예하는 시간
 * @param maintenanceHealthTimeout 마지막 정상 유지보수 기록을 유지하는 시간
 * @param redisSafetyCheckInterval 운영 Redis 정책과 메모리 상태를 확인하는 주기
 * @param redisMemoryWarningRatio 메모리 사용률 경고 경계
 * @param redisMemoryBlockRatio 새 등록을 차단하는 메모리 사용률 경계
 * @param redisMaxmemoryPolicyAttested CONFIG 접근 거부 시 noeviction 정책에 대한 운영자 확인
 * @param redisMaxmemoryBytes INFO에 제한값이 없는 환경의 명시적 메모리 한도
 */
@ConfigurationProperties("waiting-room")
public record WaitingRoomProperties(
        Duration schedulerInterval,
        Duration maxRetryAfter,
        Duration retryAfterSafetyMargin,
        Duration quarantineTtl,
        Duration maintenanceLeaseTimeout,
        int maxAdmissionsPerRun,
        int maxActiveExpirationsPerRun,
        int maxWaitingExpirationsPerRun,
        int maxCandidatesScannedPerRun,
        Map<String, ServiceProperties> services,
        SecurityProperties security,
        @DefaultValue("PT20M") Duration heartbeatExpirationRecoveryGrace,
        @DefaultValue("PT30S") Duration maintenanceHealthTimeout,
        @DefaultValue("PT5S") Duration redisSafetyCheckInterval,
        @DefaultValue("0.80") double redisMemoryWarningRatio,
        @DefaultValue("0.90") double redisMemoryBlockRatio,
        @DefaultValue("false") boolean redisMaxmemoryPolicyAttested,
        @DefaultValue("0") long redisMaxmemoryBytes
) {
    private static final String ID_PATTERN = "[a-z0-9-]+";

    /** 기존 복구 설정 호출자는 기본 Redis 안전 정책을 사용합니다. */
    public WaitingRoomProperties(
            Duration schedulerInterval, Duration maxRetryAfter, Duration retryAfterSafetyMargin,
            Duration quarantineTtl, Duration maintenanceLeaseTimeout, int maxAdmissionsPerRun,
            int maxActiveExpirationsPerRun, int maxWaitingExpirationsPerRun, int maxCandidatesScannedPerRun,
            Map<String, ServiceProperties> services, SecurityProperties security,
            Duration heartbeatExpirationRecoveryGrace, Duration maintenanceHealthTimeout
    ) {
        this(schedulerInterval, maxRetryAfter, retryAfterSafetyMargin, quarantineTtl,
                maintenanceLeaseTimeout, maxAdmissionsPerRun, maxActiveExpirationsPerRun,
                maxWaitingExpirationsPerRun, maxCandidatesScannedPerRun, services, security,
                heartbeatExpirationRecoveryGrace, maintenanceHealthTimeout,
                Duration.ofSeconds(5), 0.80, 0.90, false, 0);
    }

    /** 기존 인증 설정 호출자는 복구 유예 20분과 유지보수 상태 30초를 사용합니다. */
    public WaitingRoomProperties(
            Duration schedulerInterval, Duration maxRetryAfter, Duration retryAfterSafetyMargin,
            Duration quarantineTtl, Duration maintenanceLeaseTimeout, int maxAdmissionsPerRun,
            int maxActiveExpirationsPerRun, int maxWaitingExpirationsPerRun, int maxCandidatesScannedPerRun,
            Map<String, ServiceProperties> services, SecurityProperties security
    ) {
        this(schedulerInterval, maxRetryAfter, retryAfterSafetyMargin, quarantineTtl,
                maintenanceLeaseTimeout, maxAdmissionsPerRun, maxActiveExpirationsPerRun,
                maxWaitingExpirationsPerRun, maxCandidatesScannedPerRun, services, security,
                Duration.ofMinutes(20), Duration.ofSeconds(30));
    }

    /**
     * 기존 내부 호출자가 인증 설정을 전달하지 않으면 OAuth2를 비활성화합니다.
     */
    public WaitingRoomProperties(
            Duration schedulerInterval,
            Duration maxRetryAfter,
            Duration retryAfterSafetyMargin,
            Duration quarantineTtl,
            Duration maintenanceLeaseTimeout,
            int maxAdmissionsPerRun,
            int maxActiveExpirationsPerRun,
            int maxWaitingExpirationsPerRun,
            int maxCandidatesScannedPerRun,
            Map<String, ServiceProperties> services
    ) {
        this(schedulerInterval, maxRetryAfter, retryAfterSafetyMargin, quarantineTtl,
                maintenanceLeaseTimeout, maxAdmissionsPerRun, maxActiveExpirationsPerRun,
                maxWaitingExpirationsPerRun, maxCandidatesScannedPerRun, services,
                new SecurityProperties(new OAuth2Properties(false), Map.of()));
    }

    /**
     * Waiting Room이 안전하게 실행될 수 있는 전역 설정 관계를 검증합니다.
     */
    @ConstructorBinding
    public WaitingRoomProperties {
        requirePositive(schedulerInterval, "schedulerInterval");
        requirePositive(maxRetryAfter, "maxRetryAfter");
        requirePositive(retryAfterSafetyMargin, "retryAfterSafetyMargin");
        requirePositive(quarantineTtl, "quarantineTtl");
        requirePositive(maintenanceLeaseTimeout, "maintenanceLeaseTimeout");
        requirePositive(heartbeatExpirationRecoveryGrace, "heartbeatExpirationRecoveryGrace");
        requirePositive(maintenanceHealthTimeout, "maintenanceHealthTimeout");
        requirePositive(redisSafetyCheckInterval, "redisSafetyCheckInterval");
        if (!(0 < redisMemoryWarningRatio && redisMemoryWarningRatio < redisMemoryBlockRatio
                && redisMemoryBlockRatio < 1)) {
            throw new IllegalArgumentException("Redis 메모리 비율은 0 < warningRatio < blockRatio < 1이어야 합니다.");
        }
        if (redisMaxmemoryBytes < 0) {
            throw new IllegalArgumentException("redisMaxmemoryBytes는 0 이상이어야 합니다.");
        }
        Duration minimumMaintenanceHealthTimeout;
        try {
            Duration schedulerSafety = schedulerInterval.multipliedBy(3);
            Duration leaseSafety = maintenanceLeaseTimeout.multipliedBy(2);
            minimumMaintenanceHealthTimeout = schedulerSafety.compareTo(leaseSafety) >= 0
                    ? schedulerSafety : leaseSafety;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("maintenanceHealthTimeout 안전시간 계산이 Duration 범위를 초과합니다.", exception);
        }
        if (maintenanceHealthTimeout.compareTo(minimumMaintenanceHealthTimeout) < 0) {
            throw new IllegalArgumentException(
                    "maintenanceHealthTimeout은 max(schedulerInterval * 3, maintenanceLeaseTimeout * 2) 이상이어야 합니다.");
        }
        if (maintenanceLeaseTimeout.compareTo(schedulerInterval) <= 0) {
            throw new IllegalArgumentException("maintenanceLeaseTimeout은 schedulerInterval보다 길어야 합니다.");
        }
        requirePositive(maxAdmissionsPerRun, "maxAdmissionsPerRun");
        requirePositive(maxActiveExpirationsPerRun, "maxActiveExpirationsPerRun");
        requirePositive(maxWaitingExpirationsPerRun, "maxWaitingExpirationsPerRun");
        requirePositive(maxCandidatesScannedPerRun, "maxCandidatesScannedPerRun");
        if (maxCandidatesScannedPerRun < maxAdmissionsPerRun) {
            throw new IllegalArgumentException(
                    "maxCandidatesScannedPerRun은 maxAdmissionsPerRun 이상이어야 합니다."
            );
        }
        if (services == null || services.isEmpty()) {
            throw new IllegalArgumentException("services는 하나 이상이어야 합니다.");
        }
        services.forEach((serviceId, service) -> {
            requireId(serviceId, "serviceId");
            if (service == null) {
                throw new IllegalArgumentException("서비스 설정은 비어 있을 수 없습니다.");
            }
            // 복구 보호 key의 TTL도 Redis가 받는 long 밀리초 범위 안이어야 합니다.
            try {
                heartbeatExpirationRecoveryGrace.plus(service.cleanupMargin()).toMillis();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(
                        "heartbeatExpirationRecoveryGrace + cleanupMargin의 밀리초 합계가 long 범위를 초과합니다.", exception);
            }
            if (heartbeatExpirationRecoveryGrace.compareTo(service.heartbeatTimeout()) < 0) {
                throw new IllegalArgumentException("heartbeatExpirationRecoveryGrace는 모든 서비스의 heartbeatTimeout 이상이어야 합니다.");
            }
            if (schedulerInterval.compareTo(service.admissionTimeout()) >= 0) {
                throw new IllegalArgumentException("schedulerInterval은 admissionTimeout보다 짧아야 합니다.");
            }
            if (maxRetryAfter.compareTo(service.heartbeatTimeout()) >= 0) {
                throw new IllegalArgumentException("maxRetryAfter는 heartbeatTimeout보다 짧아야 합니다.");
            }
            if (retryAfterSafetyMargin.compareTo(service.heartbeatTimeout()) >= 0) {
                throw new IllegalArgumentException("retryAfterSafetyMargin은 heartbeatTimeout보다 짧아야 합니다.");
            }
        });
        if (security == null) {
            throw new IllegalArgumentException("security 설정은 필수입니다.");
        }
        security.clients().forEach((clientId, client) -> {
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalArgumentException("clientId는 비어 있을 수 없습니다.");
            }
            for (String serviceId : client.services()) {
                requireId(serviceId, "serviceId");
                if (!services.containsKey(serviceId)) {
                    throw new IllegalArgumentException("client에 연결된 serviceId는 등록된 서비스여야 합니다: " + serviceId);
                }
            }
        });
    }

    /**
     * Backend API 인증 모드와 client별 서비스 접근 범위입니다.
     *
     * @param oauth2 OAuth2 Resource Server 활성화 여부
     * @param clients JWT client_id별 접근 가능한 서비스 목록
     */
    public record SecurityProperties(OAuth2Properties oauth2, Map<String, ClientProperties> clients) {
        /** 인증 모드와 client 매핑의 기본 구조를 검증합니다. */
        public SecurityProperties {
            if (oauth2 == null) {
                throw new IllegalArgumentException("oauth2 설정은 필수입니다.");
            }
            clients = clients == null ? Map.of() : clients;
            if (clients.values().stream().anyMatch(client -> client == null)) {
                throw new IllegalArgumentException("client 설정은 비어 있을 수 없습니다.");
            }
        }
    }

    /**
     * OAuth2 Resource Server 사용 여부입니다.
     *
     * @param enabled true이면 Backend API에서 JWT를 검증
     */
    public record OAuth2Properties(boolean enabled) {
    }

    /**
     * 한 client가 호출할 수 있는 대상 서비스입니다.
     *
     * @param services 접근 가능한 서비스 ID 목록
     */
    public record ClientProperties(List<String> services) {
        /** 비어 있거나 공백인 서비스 ID를 거부합니다. */
        public ClientProperties {
            if (services == null || services.isEmpty() || services.stream().anyMatch(id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException("client의 services는 비어 있을 수 없습니다.");
            }
        }
    }

    /**
     * 대상 서비스마다 독립적으로 적용되는 대기 및 이용 정책입니다.
     *
     * @param maxConcurrentUsers 서비스가 동시에 수용할 수 있는 최대 활성 사용자 수
     * @param heartbeatTimeout 마지막 상태 조회 후 대기 요청을 유지하는 최대 시간
     * @param admissionTimeout 입장 허용 후 실제 입장 확인까지 기다리는 최대 시간
     * @param maxSessionDuration 입장 확인 후 활성 슬롯을 점유할 수 있는 최대 이용시간
     * @param etaWindow 예상 대기시간 계산에 사용할 최근 슬롯 반환 표본 구간
     * @param etaMinSamples 슬롯 반환률 계산에 필요한 최소 표본 수
     * @param etaMinObservation 짧은 관측 구간의 반환률 과대평가를 막는 최소 관측시간
     * @param etaInitialReleaseRatePerSecond 실측 표본 부족 시 사용할 초당 초기 슬롯 반환률이며 0이면 비활성화
     * @param requestTtl 대기 신청 상태와 응답 정보를 Redis에 보관하는 전체 시간
     * @param maxWaitDuration heartbeat가 유지되더라도 대기할 수 있는 최대 시간
     * @param redirects redirect target ID를 실제 대상 서비스 URL로 변환하는 목록
     * @param waitingTokenTtl 일회용 Browser 접근 토큰의 유효시간
     * @param cleanupMargin 전체 대기·입장·이용 생명주기 종료 후 요청 정리를 위한 여유시간
     */
    public record ServiceProperties(
            int maxConcurrentUsers,
            Duration heartbeatTimeout,
            Duration admissionTimeout,
            Duration maxSessionDuration,
            Duration etaWindow,
            int etaMinSamples,
            Duration etaMinObservation,
            double etaInitialReleaseRatePerSecond,
            Duration requestTtl,
            Duration maxWaitDuration,
            Map<String, String> redirects,
            @DefaultValue("5m") Duration waitingTokenTtl,
            @DefaultValue("PT10M") Duration cleanupMargin
    ) {
        /** 기존 내부 호출은 기본 5분 토큰 정책을 사용합니다. */
        public ServiceProperties(
                int maxConcurrentUsers, Duration heartbeatTimeout, Duration admissionTimeout,
                Duration maxSessionDuration, Duration etaWindow, int etaMinSamples, Duration etaMinObservation,
                double etaInitialReleaseRatePerSecond, Duration requestTtl, Duration maxWaitDuration,
                Map<String, String> redirects
        ) {
            this(maxConcurrentUsers, heartbeatTimeout, admissionTimeout, maxSessionDuration, etaWindow,
                    etaMinSamples, etaMinObservation, etaInitialReleaseRatePerSecond, requestTtl, maxWaitDuration,
                    redirects, Duration.ofMinutes(5), Duration.ofMinutes(10));
        }

        /**
         * 대상 서비스의 수용량, 시간 정책과 redirect target을 검증합니다.
         */
        @ConstructorBinding
        public ServiceProperties {
            requirePositive(waitingTokenTtl, "waitingTokenTtl");
            if (waitingTokenTtl.toMillis() == 0) {
                throw new IllegalArgumentException("waitingTokenTtl은 Redis 만료 단위인 1ms 이상이어야 합니다.");
            }
            requirePositive(maxConcurrentUsers, "maxConcurrentUsers");
            requirePositive(heartbeatTimeout, "heartbeatTimeout");
            requirePositive(admissionTimeout, "admissionTimeout");
            requirePositive(maxSessionDuration, "maxSessionDuration");
            requirePositive(etaWindow, "etaWindow");
            requirePositive(etaMinSamples, "etaMinSamples");
            requirePositive(etaMinObservation, "etaMinObservation");
            if (etaMinObservation.compareTo(etaWindow) > 0) {
                throw new IllegalArgumentException("etaMinObservation은 etaWindow 이하여야 합니다.");
            }
            if (!Double.isFinite(etaInitialReleaseRatePerSecond) || etaInitialReleaseRatePerSecond < 0) {
                throw new IllegalArgumentException("etaInitialReleaseRatePerSecond는 0 이상의 유한한 값이어야 합니다.");
            }
            requirePositive(requestTtl, "requestTtl");
            requirePositive(maxWaitDuration, "maxWaitDuration");
            requirePositive(cleanupMargin, "cleanupMargin");
            Duration fullLifecycleDuration;
            try {
                fullLifecycleDuration = maxWaitDuration.plus(admissionTimeout)
                        .plus(maxSessionDuration).plus(cleanupMargin);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("전체 생명주기 시간 합계가 Duration 범위를 초과합니다.", exception);
            }
            if (requestTtl.compareTo(fullLifecycleDuration) <= 0) {
                throw new IllegalArgumentException(
                        "requestTtl은 maxWaitDuration + admissionTimeout + maxSessionDuration + cleanupMargin보다 길어야 합니다."
                );
            }
            if (redirects == null || redirects.isEmpty()) {
                throw new IllegalArgumentException("redirects는 하나 이상이어야 합니다.");
            }
            redirects.forEach((targetId, targetUrl) -> {
                requireId(targetId, "redirectTargetId");
                requireRedirectUrl(targetUrl);
            });
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + "은 0보다 커야 합니다.");
        }
        // 모든 시간 정책은 실행 중 밀리초로 변환되므로 시작 시 범위를 검증합니다.
        try {
            value.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + "의 밀리초 값이 long 범위를 초과합니다.", exception);
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + "은 0보다 커야 합니다.");
        }
    }

    private static void requireId(String value, String name) {
        if (value == null || !value.matches(ID_PATTERN)) {
            throw new IllegalArgumentException(name + "는 영문 소문자, 숫자와 '-'만 사용할 수 있습니다.");
        }
    }

    private static void requireRedirectUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (!uri.isAbsolute()
                    || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("redirect URL은 안전한 HTTP absolute URI여야 합니다.");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("redirect URL은 안전한 HTTP absolute URI여야 합니다.", exception);
        }
    }
}

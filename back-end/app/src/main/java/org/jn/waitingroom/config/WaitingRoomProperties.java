/**
 * @author jeongnam
 * @since 2026-09-21
 * @file WaitingRoomProperties.java
 * @description Waiting Room의 전역 설정과 대상 서비스별 정책을 정의합니다.
 */
package org.jn.waitingroom.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
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
        Map<String, ServiceProperties> services
) {
    private static final String ID_PATTERN = "[a-z0-9-]+";

    /**
     * Waiting Room이 안전하게 실행될 수 있는 전역 설정 관계를 검증합니다.
     */
    public WaitingRoomProperties {
        requirePositive(schedulerInterval, "schedulerInterval");
        requirePositive(maxRetryAfter, "maxRetryAfter");
        requirePositive(retryAfterSafetyMargin, "retryAfterSafetyMargin");
        requirePositive(quarantineTtl, "quarantineTtl");
        requirePositive(maintenanceLeaseTimeout, "maintenanceLeaseTimeout");
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
            Map<String, String> redirects
    ) {
        /**
         * 대상 서비스의 수용량, 시간 정책과 redirect target을 검증합니다.
         */
        public ServiceProperties {
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
            if (requestTtl.compareTo(maxWaitDuration) <= 0) {
                throw new IllegalArgumentException("requestTtl은 maxWaitDuration보다 길어야 합니다.");
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

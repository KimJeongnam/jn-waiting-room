/**
 * @author jeongnam
 * @since 2026-09-22
 * @file WaitingRoomService.java
 * @description 대기 신청과 상태조회에 필요한 업무 규칙을 처리합니다.
 */
package org.jn.waitingroom.service;

import org.jn.waitingroom.config.WaitingRoomProperties;
import org.jn.waitingroom.domain.WaitingRequestStatus;
import org.jn.waitingroom.domain.WaitingSecret;
import org.jn.waitingroom.redis.RedisWaitingRoomStore;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * HTTP 계층과 Redis 저장소 사이에서 설정과 대기 신청 규칙을 적용합니다.
 */
@Service
public class WaitingRoomService {
    private final RedisWaitingRoomStore store;
    private final WaitingRoomProperties properties;
    private final WaitingRoomRecoveryCoordinator coordinator;
    private final RedisOperationalSafety redisSafety;

    /**
     * 필요한 저장소와 서비스별 설정을 주입받습니다.
     *
     * @param store Redis 대기 상태 저장소
     * @param properties Waiting Room 설정
     * @param coordinator Redis 복구 완료 gate
     * @param redisSafety 운영 Redis 정책과 새 등록 용량 gate
     */
    public WaitingRoomService(RedisWaitingRoomStore store, WaitingRoomProperties properties,
                              WaitingRoomRecoveryCoordinator coordinator, RedisOperationalSafety redisSafety) {
        this.store = Objects.requireNonNull(store);
        this.properties = Objects.requireNonNull(properties);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.redisSafety = Objects.requireNonNull(redisSafety);
    }

    /**
     * 대기 신청을 등록하거나 동일 요청의 현재 결과를 반환합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param reservationRequestId Idempotency-Key로 전달된 신청 식별자
     * @param redirectTargetId 등록된 redirect 대상 식별자
     * @return 등록 결과와 Browser 대기 URL
     */
    public Registration register(String serviceId, String reservationRequestId, String redirectTargetId) {
        coordinator.requireAvailable();
        redisSafety.requireRegistrationCapacity();
        requireText(reservationRequestId, "Idempotency-Key");
        requireText(redirectTargetId, "redirectTargetId");
        var service = service(serviceId);
        if (!service.redirects().containsKey(redirectTargetId)) {
            throw new IllegalArgumentException("등록되지 않은 redirectTargetId입니다.");
        }

        String fingerprint = fingerprint(serviceId, redirectTargetId);
        String token = WaitingSecret.generate();
        var result = store.register(
                serviceId,
                reservationRequestId,
                fingerprint,
                redirectTargetId,
                service.requestTtl(),
                WaitingSecret.hash(token),
                service.waitingTokenTtl()
        );
        if (result.type() == RedisWaitingRoomStore.ResultType.STORE_ERROR) {
            throw new IllegalStateException("Redis 대기 요청 상태가 올바르지 않습니다.");
        }

        String waitingUrl = result.type() == RedisWaitingRoomStore.ResultType.CREATED
                ? UriComponentsBuilder.fromPath("/waiting")
                        .queryParam("serviceId", serviceId)
                        .fragment("token=" + token)
                        .build()
                        .encode()
                        .toUriString()
                : null;
        return new Registration(result.type(), result.state().status(), waitingUrl);
    }

    /**
     * 현재 요청 상태와 대기 순번을 조회합니다.
     *
     * @param cookie 서비스 식별자와 난수 세션 비밀값을 포함하는 cookie
     * @return Front Polling 응답에 필요한 현재 상태
     */
    public WaitingStatus poll(String cookie) {
        coordinator.requireAvailable();
        if (cookie == null) throw new WaitingSessionInvalidException();
        String[] parts = cookie.split("\\.", -1);
        if (parts.length != 2 || !properties.services().containsKey(parts[0])
                || !parts[1].matches("[A-Za-z0-9_-]{43}")) {
            throw new WaitingSessionInvalidException();
        }
        String serviceId = parts[0];
        String sessionHash = WaitingSecret.hash(parts[1]);
        var session = store.findSession(serviceId, sessionHash);
        if (session == null || !serviceId.equals(session.serviceId())
                || session.reservationRequestId() == null || session.reservationRequestId().isBlank()) {
            throw new WaitingSessionInvalidException();
        }
        String reservationRequestId = session.reservationRequestId();
        var service = service(serviceId);
        var snapshot = store.poll(
                serviceId,
                reservationRequestId,
                java.time.Duration.ofMillis(PollingPolicy.nextPollAfterMs(0L)),
                java.time.Duration.ofMillis(PollingPolicy.nextPollAfterMs(60L)),
                java.time.Duration.ofMillis(PollingPolicy.nextPollAfterMs(null)),
                service.maxConcurrentUsers(),
                service.heartbeatTimeout(),
                properties.maxRetryAfter(),
                properties.retryAfterSafetyMargin(),
                service.etaWindow(),
                service.etaMinSamples(),
                service.etaMinObservation(),
                service.etaInitialReleaseRatePerSecond(),
                properties.maxAdmissionsPerRun(),
                properties.schedulerInterval(),
                sessionHash
        );
        if (snapshot.type() == RedisWaitingRoomStore.ResultType.NOT_FOUND
                || snapshot.type() == RedisWaitingRoomStore.ResultType.INVALID_SESSION) {
            throw new WaitingSessionInvalidException();
        }
        if (snapshot.type() == RedisWaitingRoomStore.ResultType.STORE_ERROR) {
            throw new IllegalStateException("Redis 대기 요청 상태가 올바르지 않습니다.");
        }
        if (snapshot.type() == RedisWaitingRoomStore.ResultType.RATE_LIMITED) {
            throw new PollingRateLimitException(snapshot.delayMs());
        }

        var state = snapshot.state();
        // 입장 완료 뒤 새로고침한 세션도 저장된 목적지로 복귀할 수 있습니다.
        String redirectUrl = state.status() == WaitingRequestStatus.ADMITTED
                || state.status() == WaitingRequestStatus.ENTERED
                ? redirectUrl(service, state.redirectTargetId(), state.reservationRequestId())
                : null;
        Long nextPollAfterMs = state.status() == WaitingRequestStatus.WAITING
                ? snapshot.delayMs()
                : null;
        return new WaitingStatus(
                state.reservationRequestId(),
                state.status(),
                snapshot.position(),
                snapshot.waitingCount(),
                snapshot.estimatedWaitSeconds(),
                nextPollAfterMs,
                redirectUrl,
                state.expirationReason()
        );
    }

    /** 일회용 토큰을 소비하고 Browser에 한 번 전달할 세션 cookie 값을 생성합니다. */
    public String exchangeToken(String serviceId, String token) {
        coordinator.requireAvailable();
        if (serviceId == null || !properties.services().containsKey(serviceId) || token == null
                || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new WaitingSessionInvalidException();
        }
        String sessionSecret = WaitingSecret.generate();
        var settings = service(serviceId);
        var result = store.consumeToken(serviceId, WaitingSecret.hash(token), WaitingSecret.hash(sessionSecret),
                settings.heartbeatTimeout(), settings.maxWaitDuration(), settings.etaWindow());
        if (result.type() != RedisWaitingRoomStore.ResultType.APPLIED) {
            throw new WaitingSessionInvalidException();
        }
        // 공개 serviceId로 Redis hash slot을 찾으며 비밀값과의 연결은 Lua가 검증합니다.
        return serviceId + "." + sessionSecret;
    }

    /** Backend이 소유권 검증 후 요청한 새 일회용 접근 URL을 반환합니다. */
    public Registration issueToken(String serviceId, String reservationRequestId) {
        coordinator.requireAvailable();
        var settings = service(serviceId);
        String token = WaitingSecret.generate();
        var result = requireTransitionResult(store.issueToken(serviceId, reservationRequestId,
                WaitingSecret.hash(token), settings.waitingTokenTtl(), settings.heartbeatTimeout(),
                settings.maxWaitDuration(), settings.etaWindow()));
        String url = result.type() == RedisWaitingRoomStore.ResultType.APPLIED
                ? UriComponentsBuilder.fromPath("/waiting").queryParam("serviceId", serviceId)
                        .fragment("token=" + token).build().encode().toUriString() : null;
        return new Registration(result.type(), result.state().status(), url);
    }

    /**
     * 입장 허용 요청을 실제 서비스 입장 상태로 전환합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param reservationRequestId 대기 신청 식별자
     * @return 상태 전이 결과와 서비스 이용 만료시각
     */
    public Transition enter(String serviceId, String reservationRequestId) {
        coordinator.requireAvailable();
        var service = service(serviceId);
        var result = requireTransitionResult(store.enter(
                serviceId,
                reservationRequestId,
                service.maxSessionDuration(),
                service.etaWindow()
        ));
        Instant sessionExpiresAt = result.state() == null || result.state().enteredAt() == null
                ? null
                : Instant.ofEpochMilli(result.state().enteredAt() + service.maxSessionDuration().toMillis());
        return new Transition(result.type(), result.state(), sessionExpiresAt);
    }

    /**
     * 실제 입장한 요청의 완료시각을 기록하고 활성 슬롯을 반환합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param reservationRequestId 대기 신청 식별자
     * @return 완료 처리 또는 멱등 재호출 결과
     */
    public Transition complete(String serviceId, String reservationRequestId) {
        coordinator.requireAvailable();
        var service = service(serviceId);
        var result = requireTransitionResult(store.complete(serviceId, reservationRequestId, service.etaWindow()));
        return new Transition(result.type(), result.state(), null);
    }

    /**
     * 대기 중이거나 입장이 허용된 요청을 취소합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param reservationRequestId 대기 신청 식별자
     * @return 취소 처리 또는 멱등 재호출 결과
     */
    public Transition cancel(String serviceId, String reservationRequestId) {
        coordinator.requireAvailable();
        var service = service(serviceId);
        var result = requireTransitionResult(store.cancel(serviceId, reservationRequestId, service.etaWindow()));
        return new Transition(result.type(), result.state(), null);
    }

    private WaitingRoomProperties.ServiceProperties service(String serviceId) {
        requireText(serviceId, "serviceId");
        var service = properties.services().get(serviceId);
        if (service == null) {
            throw new IllegalArgumentException("등록되지 않은 serviceId입니다.");
        }
        return service;
    }

    private String redirectUrl(
            WaitingRoomProperties.ServiceProperties service,
            String redirectTargetId,
            String reservationRequestId
    ) {
        String target = service.redirects().get(redirectTargetId);
        if (target == null) {
            throw new IllegalStateException("요청에 저장된 redirect target 설정을 찾을 수 없습니다.");
        }
        return UriComponentsBuilder.fromUriString(target)
                .queryParam("reservationRequestId", reservationRequestId)
                .build()
                .encode()
                .toUriString();
    }

    private RedisWaitingRoomStore.WriteResult requireTransitionResult(
            RedisWaitingRoomStore.WriteResult result
    ) {
        if (result.type() == RedisWaitingRoomStore.ResultType.NOT_FOUND) {
            throw new NoSuchElementException("대기 신청을 찾을 수 없습니다.");
        }
        if (result.type() == RedisWaitingRoomStore.ResultType.STORE_ERROR) {
            throw new IllegalStateException("Redis 대기 요청 상태가 올바르지 않습니다.");
        }
        return result;
    }

    private static String fingerprint(String serviceId, String redirectTargetId) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] value = (serviceId + "\n" + redirectTargetId).getBytes(StandardCharsets.UTF_8);
            return "sha256:" + HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "는 비어 있을 수 없습니다.");
        }
    }

    /**
     * 대기 등록 업무 결과입니다.
     *
     * @param resultType 신규 등록, 재요청 또는 충돌 결과
     * @param status 현재 요청 상태
     * @param waitingUrl 신규 등록에만 반환되는 Browser 대기 URL
     */
    public record Registration(
            RedisWaitingRoomStore.ResultType resultType,
            WaitingRequestStatus status,
            String waitingUrl
    ) {
        /** 업무 결과를 진단할 때도 접근 URL의 토큰 원문은 노출하지 않습니다. */
        @Override
        public String toString() {
            return "Registration[resultType=" + resultType + ", status=" + status + ", waitingUrl=redacted]";
        }
    }

    /**
     * 상태조회 업무 결과입니다.
     *
     * @param reservationRequestId 대기 신청 식별자
     * @param status 현재 요청 상태
     * @param position 현재 대기 순번
     * @param waitingCount 전체 대기 인원
     * @param estimatedWaitSeconds 예상 대기시간이며 계산 근거가 없으면 {@code null}
     * @param nextPollAfterMs 다음 상태조회까지 기다릴 milliseconds
     * @param redirectUrl 입장 허용 또는 입장 완료 시 이동할 대상 URL
     * @param expirationReason 만료 상태의 원인
     */
    public record WaitingStatus(
            String reservationRequestId,
            WaitingRequestStatus status,
            Long position,
            long waitingCount,
            Long estimatedWaitSeconds,
            Long nextPollAfterMs,
            String redirectUrl,
            String expirationReason
    ) {
    }

    /**
     * 외부 서비스가 요청 상태를 변경한 결과입니다.
     *
     * @param resultType 적용, 멱등 재호출 또는 상태 충돌 결과
     * @param state 변경 후 요청 상태
     * @param sessionExpiresAt 실제 입장 후 서비스 이용 만료시각
     */
    public record Transition(
            RedisWaitingRoomStore.ResultType resultType,
            org.jn.waitingroom.domain.WaitingRequestState state,
            Instant sessionExpiresAt
    ) {
    }
}

/**
 * @author jeongnam
 * @since 2026-09-22
 * @file WaitingRoomService.java
 * @description 대기 신청과 상태조회에 필요한 업무 규칙을 처리합니다.
 */
package org.jn.waitingroom.service;

import org.jn.waitingroom.config.WaitingRoomProperties;
import org.jn.waitingroom.domain.WaitingRequestStatus;
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

    /**
     * 필요한 저장소와 서비스별 설정을 주입받습니다.
     *
     * @param store Redis 대기 상태 저장소
     * @param properties Waiting Room 설정
     */
    public WaitingRoomService(RedisWaitingRoomStore store, WaitingRoomProperties properties) {
        this.store = Objects.requireNonNull(store);
        this.properties = Objects.requireNonNull(properties);
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
        requireText(reservationRequestId, "Idempotency-Key");
        requireText(redirectTargetId, "redirectTargetId");
        var service = service(serviceId);
        if (!service.redirects().containsKey(redirectTargetId)) {
            throw new IllegalArgumentException("등록되지 않은 redirectTargetId입니다.");
        }

        String fingerprint = fingerprint(serviceId, redirectTargetId);
        var result = store.register(
                serviceId,
                reservationRequestId,
                fingerprint,
                redirectTargetId,
                service.requestTtl()
        );
        if (result.type() == RedisWaitingRoomStore.ResultType.STORE_ERROR) {
            throw new IllegalStateException("Redis 대기 요청 상태가 올바르지 않습니다.");
        }

        String waitingUrl = result.type() == RedisWaitingRoomStore.ResultType.CREATED
                ? UriComponentsBuilder.fromPath("/waiting")
                        .queryParam("serviceId", serviceId)
                        .queryParam("reservationRequestId", reservationRequestId)
                        .build()
                        .encode()
                        .toUriString()
                : null;
        return new Registration(result.type(), result.state().status(), waitingUrl);
    }

    /**
     * 현재 요청 상태와 대기 순번을 조회합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param reservationRequestId 대기 신청 식별자
     * @return Front Polling 응답에 필요한 현재 상태
     */
    public WaitingStatus poll(String serviceId, String reservationRequestId) {
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
                properties.schedulerInterval()
        );
        if (snapshot.type() == RedisWaitingRoomStore.ResultType.NOT_FOUND) {
            throw new NoSuchElementException("대기 신청을 찾을 수 없습니다.");
        }
        if (snapshot.type() == RedisWaitingRoomStore.ResultType.STORE_ERROR) {
            throw new IllegalStateException("Redis 대기 요청 상태가 올바르지 않습니다.");
        }
        if (snapshot.type() == RedisWaitingRoomStore.ResultType.RATE_LIMITED) {
            throw new PollingRateLimitException(snapshot.delayMs());
        }

        var state = snapshot.state();
        String redirectUrl = state.status() == WaitingRequestStatus.ADMITTED
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

    /**
     * 입장 허용 요청을 실제 서비스 입장 상태로 전환합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param reservationRequestId 대기 신청 식별자
     * @return 상태 전이 결과와 서비스 이용 만료시각
     */
    public Transition enter(String serviceId, String reservationRequestId) {
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
     * @param redirectUrl 입장 허용 시 이동할 대상 URL
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

/**
 * @author jeongnam
 * @since 2026-09-22
 * @file WaitingRoomController.java
 * @description 대기 신청과 상태조회 HTTP API를 제공합니다.
 */
package org.jn.waitingroom.api;

import jakarta.validation.Valid;
import org.jn.waitingroom.vo.*;
import org.jn.waitingroom.redis.RedisWaitingRoomStore;
import org.jn.waitingroom.service.PollingRateLimitException;
import org.jn.waitingroom.service.WaitingRoomService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * Waiting Room 외부 HTTP 계약을 Spring MVC endpoint로 노출합니다.
 */
@RestController
@RequestMapping("/api/v1")
public class WaitingRoomController {
    private final WaitingRoomService service;

    /**
     * 대기 신청 업무 서비스를 주입받습니다.
     *
     * @param service 대기 신청 업무 서비스
     */
    public WaitingRoomController(WaitingRoomService service) {
        this.service = Objects.requireNonNull(service);
    }

    /**
     * Idempotency-Key를 reservationRequestId로 사용해 대기 신청을 등록합니다.
     *
     * @param reservationRequestId 요청 식별자로 사용할 Idempotency-Key
     * @param request 대상 서비스와 redirect target
     * @return 신규 등록은 201, 동일 요청 재전송은 200, payload 충돌은 409
     */
    @PostMapping("/waiting-requests")
    public ResponseEntity<?> create(
            @RequestHeader("Idempotency-Key") String reservationRequestId,
            @Valid @RequestBody CreateWaitingRequest request
    ) {
        var registration = service.register(
                request.serviceId(),
                reservationRequestId,
                request.redirectTargetId()
        );
        if (registration.resultType() == RedisWaitingRoomStore.ResultType.CONFLICT) {
            return problem(
                    HttpStatus.CONFLICT,
                    "PAYLOAD_MISMATCH",
                    "같은 Idempotency-Key에 다른 요청 내용이 사용됐습니다.",
                    false
            );
        }

        var response = new CreateWaitingResponse(
                reservationRequestId,
                registration.status(),
                registration.waitingUrl()
        );
        HttpStatus status = registration.resultType() == RedisWaitingRoomStore.ResultType.CREATED
                ? HttpStatus.CREATED
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * 현재 상태와 대기 순번을 조회하고 WAITING 요청의 heartbeat를 갱신합니다.
     *
     * @param serviceId 대상 서비스 식별자
     * @param reservationRequestId 대기 신청 식별자
     * @return 현재 대기 상태와 다음 Polling 정보
     */
    @GetMapping("/waiting-session")
    public WaitingStatusResponse status(
            @RequestParam String serviceId,
            @RequestParam String reservationRequestId
    ) {
        // 현재 핵심 기능 단계에서는 query parameter로 식별하며 보안 단계에서 세션 쿠키로 교체합니다.
        var status = service.poll(serviceId, reservationRequestId);
        return new WaitingStatusResponse(
                status.reservationRequestId(),
                status.status(),
                status.position(),
                status.waitingCount(),
                status.estimatedWaitSeconds(),
                status.nextPollAfterMs(),
                status.redirectUrl(),
                status.expirationReason()
        );
    }

    /**
     * 서비스 Backend가 입장 허용 요청의 실제 진입을 확인합니다.
     *
     * @param reservationRequestId 대기 신청 식별자
     * @param serviceId 대상 서비스 식별자
     * @return 실제 입장 상태와 서비스 이용 만료시각
     */
    @PostMapping("/waiting-requests/{reservationRequestId}/enter")
    public ResponseEntity<?> enter(
            @PathVariable String reservationRequestId,
            @RequestParam String serviceId
    ) {
        return transitionResponse(service.enter(serviceId, reservationRequestId));
    }

    /**
     * 서비스 Backend가 이용 완료를 알리고 활성 슬롯을 반환합니다.
     *
     * @param reservationRequestId 대기 신청 식별자
     * @param serviceId 대상 서비스 식별자
     * @return 완료 처리 후 요청 상태
     */
    @PostMapping("/waiting-requests/{reservationRequestId}/complete")
    public ResponseEntity<?> complete(
            @PathVariable String reservationRequestId,
            @RequestParam String serviceId
    ) {
        return transitionResponse(service.complete(serviceId, reservationRequestId));
    }

    /**
     * 서비스 Backend가 대기 중이거나 입장 허용된 요청을 취소합니다.
     *
     * @param reservationRequestId 대기 신청 식별자
     * @param serviceId 대상 서비스 식별자
     * @return 취소 처리 후 요청 상태
     */
    @PostMapping("/waiting-requests/{reservationRequestId}/cancel")
    public ResponseEntity<?> cancel(
            @PathVariable String reservationRequestId,
            @RequestParam String serviceId
    ) {
        return transitionResponse(service.cancel(serviceId, reservationRequestId));
    }

    /**
     * 잘못된 서비스 또는 redirect 설정을 400 응답으로 변환합니다.
     *
     * @param exception 잘못된 요청 값 예외
     * @return 오류 설명을 포함한 400 응답
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> badRequest(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), false);
    }

    /**
     * Spring MVC가 Controller 호출 전에 거부한 요청도 공통 오류 계약으로 변환합니다.
     *
     * @param exception 필수 Header·Parameter, 요청 JSON 또는 Bean Validation 오류
     * @return 공통 오류 body를 포함한 400 응답
     */
    @ExceptionHandler({
            ServletRequestBindingException.class,
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiErrorResponse> invalidRequest(Exception exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "요청 형식이 올바르지 않습니다.",
                false
        );
    }

    /**
     * 존재하지 않는 대기 신청을 404 응답으로 변환합니다.
     *
     * @param exception 조회 대상 없음 예외
     * @return 오류 설명을 포함한 404 응답
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiErrorResponse> notFound(NoSuchElementException exception) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage(), false);
    }

    /**
     * Redis 정합성 오류를 503 응답으로 변환합니다.
     *
     * @param exception Redis 상태 처리 예외
     * @return 오류 설명을 포함한 503 응답
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> unavailable(IllegalStateException exception) {
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICE_UNAVAILABLE",
                exception.getMessage(),
                true
        );
    }

    /**
     * 서버가 안내한 허용 시각보다 이른 상태조회를 429 응답으로 변환합니다.
     *
     * @param exception 남은 조회 제한시간을 포함한 예외
     * @return Retry-After를 포함한 429 응답
     */
    @ExceptionHandler(PollingRateLimitException.class)
    public ResponseEntity<ApiErrorResponse> tooManyRequests(PollingRateLimitException exception) {
        return problem(
                HttpStatus.TOO_MANY_REQUESTS,
                "TOO_MANY_REQUESTS",
                exception.getMessage(),
                true,
                exception.retryAfterSeconds()
        );
    }

    private ResponseEntity<ApiErrorResponse> problem(
            HttpStatus status,
            String code,
            String message,
            boolean retryable
    ) {
        return problem(status, code, message, retryable, retryable ? 1L : null);
    }

    private ResponseEntity<ApiErrorResponse> problem(
            HttpStatus status,
            String code,
            String message,
            boolean retryable,
            Long retryAfterSeconds
    ) {
        var response = new ApiErrorResponse(code, message, UUID.randomUUID().toString(), retryable);
        var builder = ResponseEntity.status(status);
        if (retryAfterSeconds != null) {
            builder.header("Retry-After", Long.toString(retryAfterSeconds));
        }
        return builder.body(response);
    }

    private ResponseEntity<?> transitionResponse(WaitingRoomService.Transition transition) {
        if (transition.resultType() == RedisWaitingRoomStore.ResultType.ADMISSION_EXPIRED) {
            return problem(
                    HttpStatus.CONFLICT,
                    "ADMISSION_EXPIRED",
                    "입장 허용시간이 만료됐습니다.",
                    false
            );
        }
        if (transition.resultType() == RedisWaitingRoomStore.ResultType.CONFLICT) {
            return problem(
                    HttpStatus.CONFLICT,
                    "INVALID_STATE",
                    "현재 요청 상태에서는 처리할 수 없습니다.",
                    false
            );
        }
        var state = transition.state();
        return ResponseEntity.ok(new TransitionResponse(
                state.reservationRequestId(),
                state.status(),
                transition.sessionExpiresAt()
        ));
    }


}

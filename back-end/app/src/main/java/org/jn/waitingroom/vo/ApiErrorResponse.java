package org.jn.waitingroom.vo;

/**
 * Waiting Room API의 공통 오류 응답입니다.
 *
 * @param code 호출자가 분기 처리할 안정적인 오류 코드
 * @param message 사람이 확인할 수 있는 오류 설명
 * @param traceId 로그 추적에 사용할 오류 응답 식별자
 * @param retryable 같은 요청을 재시도할 수 있는지 여부
 */
public record ApiErrorResponse(
        String code,
        String message,
        String traceId,
        boolean retryable
) {
}

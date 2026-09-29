/**
 * @author jeongnam
 * @since 2026-09-28
 * @file PollingRateLimitException.java
 * @description 서버가 허용한 시각보다 이른 상태조회를 429 응답으로 전달합니다.
 */
package org.jn.waitingroom.service;

/** 너무 이른 상태조회에 적용할 HTTP Retry-After 값을 전달합니다. */
public final class PollingRateLimitException extends RuntimeException {
    /** Retry-After 헤더에 사용할 초 단위 대기시간입니다. */
    private final long retryAfterSeconds;

    /**
     * 남은 제한시간을 HTTP 초 단위로 올림해 보관합니다.
     *
     * @param retryAfterMs 다음 조회까지 남은 milliseconds
     */
    public PollingRateLimitException(long retryAfterMs) {
        super("서버가 안내한 다음 조회 시각 이후에 다시 요청해야 합니다.");
        retryAfterSeconds = Math.max(1, Math.ceilDiv(retryAfterMs, 1_000));
    }

    /**
     * HTTP 응답에 전달할 남은 제한시간을 반환합니다.
     *
     * @return Retry-After 헤더에 사용할 초 단위 대기시간
     */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

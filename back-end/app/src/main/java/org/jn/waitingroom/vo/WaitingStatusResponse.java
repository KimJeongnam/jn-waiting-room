package org.jn.waitingroom.vo;

import org.jn.waitingroom.domain.WaitingRequestStatus; /**
 * 상태조회 응답입니다.
 *
 * @param reservationRequestId 대기 신청 식별자
 * @param status 현재 상태
 * @param position 현재 대기 순번
 * @param waitingCount 전체 대기 인원
 * @param estimatedWaitSeconds 예상 대기시간
 * @param nextPollAfterMs 다음 상태조회까지 기다릴 milliseconds
 * @param redirectUrl 입장 허용 시 이동할 대상 URL
 * @param expirationReason 만료 상태의 원인
 */
public record WaitingStatusResponse(
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

package org.jn.waitingroom.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jn.waitingroom.domain.WaitingRequestStatus; /**
 * 대기 등록 응답입니다.
 *
 * @param reservationRequestId 대기 신청 식별자
 * @param status 현재 상태
 * @param waitingUrl 신규 등록 시 Browser가 이동할 Waiting Front URL
 */
public record CreateWaitingResponse(
        String reservationRequestId,
        WaitingRequestStatus status,
        @JsonInclude(JsonInclude.Include.NON_NULL) String waitingUrl
) {
    /** MVC 진단 로그에서도 fragment의 토큰 원문을 출력하지 않습니다. */
    @Override
    public String toString() {
        return "CreateWaitingResponse[reservationRequestId=" + reservationRequestId
                + ", status=" + status + ", waitingUrl=redacted]";
    }
}

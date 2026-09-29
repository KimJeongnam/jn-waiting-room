package org.jn.waitingroom.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jn.waitingroom.domain.WaitingRequestStatus;

import java.time.Instant; /**
 * 외부 서비스의 입장·완료·취소 처리 응답입니다.
 *
 * @param reservationRequestId 대기 신청 식별자
 * @param status 변경 후 요청 상태
 * @param sessionExpiresAt 실제 입장 후 서비스 이용 만료시각
 */
public record TransitionResponse(
        String reservationRequestId,
        WaitingRequestStatus status,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant sessionExpiresAt
) {
}

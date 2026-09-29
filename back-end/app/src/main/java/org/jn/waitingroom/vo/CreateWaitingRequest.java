package org.jn.waitingroom.vo;

import jakarta.validation.constraints.NotBlank; /**
 * 대기 등록 요청입니다.
 *
 * @param serviceId 대상 서비스 식별자
 * @param redirectTargetId 서버에 등록된 redirect 대상 식별자
 */
public record CreateWaitingRequest(
        @NotBlank String serviceId,
        @NotBlank String redirectTargetId
) {
}

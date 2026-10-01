package org.jn.waitingroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("애플리케이션 구성 테스트")
class WaitingRoomApplicationTest {
    /** 검증 목적: 대기열 애플리케이션에 스프링 부트 설정이 적용된다. */
    @DisplayName("대기열 애플리케이션에 스프링 부트 설정이 적용된다")
    @Test
    void applicationIsConfiguredAsSpringBootApplication() {
        assertTrue(WaitingRoomApplication.class
                .isAnnotationPresent(SpringBootApplication.class));
    }
}

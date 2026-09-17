package org.jn.waitingroom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 독립형 가상 대기실 백엔드의 시작 지점입니다.
 */
@SpringBootApplication
public class WaitingRoomApplication {
    public static void main(String[] args) {
        SpringApplication.run(WaitingRoomApplication.class, args);
    }
}

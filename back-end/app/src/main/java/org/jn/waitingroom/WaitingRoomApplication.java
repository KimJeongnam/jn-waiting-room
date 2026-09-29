/**
 * @author jeongnam
 * @since 2026-09-21
 * @file WaitingRoomApplication.java
 * @description Waiting Room 백엔드 애플리케이션을 시작합니다.
 */
package org.jn.waitingroom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 독립형 가상 대기실 백엔드의 시작 지점입니다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WaitingRoomApplication {
    /**
     * Spring Boot가 애플리케이션 설정 클래스를 생성할 때 사용합니다.
     */
    public WaitingRoomApplication() {
    }

    /**
     * Waiting Room 백엔드를 시작합니다.
     *
     * @param args 애플리케이션 시작 인자
     */
    public static void main(String[] args) {
        SpringApplication.run(WaitingRoomApplication.class, args);
    }
}

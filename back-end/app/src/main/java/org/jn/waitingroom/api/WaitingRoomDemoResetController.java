/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingRoomDemoResetController.java
 * @description 명시적으로 활성화한 demo 실행에서 초기 Redis 상태를 다시 생성합니다.
 */
package org.jn.waitingroom.api;

import org.jn.waitingroom.service.WaitingRoomDemoSeeder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** demo profile, seed와 OAuth2 활성화 조건이 모두 만족될 때만 reset 경로를 노출합니다. */
@RestController
@Profile("demo")
@ConditionalOnProperty(prefix = "waiting-room.demo", name = "seed-enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "waiting-room.security.oauth2", name = "enabled", havingValue = "true")
public class WaitingRoomDemoResetController {
    private final WaitingRoomDemoSeeder seeder;

    /** 기존 데모 상태 생성기를 주입받습니다. */
    public WaitingRoomDemoResetController(WaitingRoomDemoSeeder seeder) {
        this.seeder = seeder;
    }

    /** 초기 상태 생성이 끝난 후에만 성공 응답을 반환합니다. */
    @PostMapping("/api/v1/demo/reset")
    public ResponseEntity<Void> reset() {
        seeder.seed();
        return ResponseEntity.noContent().build();
    }
}

/**
 * @author jeongnam
 * @since 2026-09-22
 * @file WaitingRoomMaintenanceConfiguration.java
 * @description 유지보수 스케줄러와 활성 슬롯 반환 Redis Pub/Sub 구독을 구성합니다.
 */
package org.jn.waitingroom.config;

import org.jn.waitingroom.service.WaitingRoomMaintenanceService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.charset.StandardCharsets;

/**
 * 주기 보정과 슬롯 반환 즉시 처리를 위한 Spring 실행 요소를 등록합니다.
 */
@Configuration
@EnableScheduling
public class WaitingRoomMaintenanceConfiguration {
    /**
     * Spring이 유지보수 실행 요소를 구성할 때 사용합니다.
     */
    public WaitingRoomMaintenanceConfiguration() {
    }

    /**
     * 모든 서비스의 슬롯 반환 채널을 구독하고 공통 유지보수 서비스에 전달합니다.
     *
     * @param connectionFactory Spring Data Redis 연결 팩토리
     * @param maintenanceService 슬롯 보충과 만료 처리를 수행하는 서비스
     * @return Spring 생명주기에 따라 시작·종료되는 Redis 메시지 listener container
     */
    @Bean
    public RedisMessageListenerContainer waitingRoomRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            WaitingRoomMaintenanceService maintenanceService
    ) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> maintenanceService.onSlotReleased(
                        new String(message.getChannel(), StandardCharsets.UTF_8)
                ),
                new PatternTopic("waiting-room:slot-released:*")
        );
        return container;
    }
}

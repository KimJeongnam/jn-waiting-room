/**
 * @author jeongnam
 * @since 2026-09-30
 * @file WaitingRoomRecoveryCoordinator.java
 * @description 모든 서비스의 Redis 복구 bootstrap이 끝날 때까지 업무 접근을 차단합니다.
 */
package org.jn.waitingroom.service;

import org.jn.waitingroom.config.WaitingRoomProperties;
import org.jn.waitingroom.redis.RedisWaitingRoomStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/** startup runner와 기존 주기의 probe가 공유하는 로컬 복구 상태입니다. */
@Component
public class WaitingRoomRecoveryCoordinator implements ApplicationRunner, Ordered {
    private static final Logger LOGGER = LoggerFactory.getLogger(WaitingRoomRecoveryCoordinator.class);
    private final RedisWaitingRoomStore store;
    private final WaitingRoomProperties properties;
    private final ObjectProvider<RedisMessageListenerContainer> listenerContainer;
    private final RedisOperationalSafety redisSafety;
    private volatile boolean available;
    private volatile boolean startupRecoveryAttempted;
    private boolean recovering;
    private long failureGeneration;

    /** Redis bootstrap 저장소와 서비스별 복구 정책을 주입받습니다. */
    public WaitingRoomRecoveryCoordinator(RedisWaitingRoomStore store, WaitingRoomProperties properties,
            @Qualifier("waitingRoomRedisMessageListenerContainer")
            ObjectProvider<RedisMessageListenerContainer> listenerContainer, RedisOperationalSafety redisSafety) {
        this.store = Objects.requireNonNull(store);
        this.properties = Objects.requireNonNull(properties);
        // container → maintenance → coordinator 의존을 bootstrap 이후에 해석합니다.
        this.listenerContainer = Objects.requireNonNull(listenerContainer);
        this.redisSafety = Objects.requireNonNull(redisSafety);
    }

    /** 복구 전에는 Redis에 접근하지 않고 공통 503 처리 대상 예외를 반환합니다. */
    public void requireAvailable() {
        if (!available) {
            throw new UnavailableException();
        }
    }

    /** 이미 진행 중인 bootstrap이 이후 관측된 장애를 덮어쓰지 않게 세대를 증가시킵니다. */
    public void markRedisUnavailable() {
        synchronized (this) {
            available = false;
            failureGeneration++;
            if (recovering) {
                // 진행 중인 복구가 장애 세대를 확인한 뒤 구독을 해제합니다.
                return;
            }
            recovering = true;
        }
        try {
            stopListener();
        } finally {
            synchronized (this) {
                recovering = false;
            }
        }
    }

    /** 장애 중에만 전체 서비스 bootstrap을 재시도하며 동시에 실행되는 probe는 건너뜁니다. */
    public void recoverIfNeeded() {
        long generation;
        synchronized (this) {
            if (available || recovering) {
                return;
            }
            recovering = true;
            generation = failureGeneration;
        }
        boolean recovered = false;
        try {
            String recoveryId = UUID.randomUUID().toString();
            for (var entry : properties.services().entrySet()) {
                store.bootstrapRecovery(entry.getKey(), recoveryId,
                        properties.heartbeatExpirationRecoveryGrace(), entry.getValue().cleanupMargin(),
                        entry.getValue().heartbeatTimeout());
            }
            // 재연결 전의 NORMAL 상태로 등록을 재개하지 않도록 gate를 열기 전에 다시 확인합니다.
            redisSafety.refresh();
            var container = listenerContainer.getIfAvailable();
            if (container != null) {
                container.start();
            }
            recovered = true;
        } catch (RuntimeException exception) {
            // Redis 연결/스크립트 장애는 startup 또는 scheduler의 실행을 중단시키지 않습니다.
            LOGGER.warn("Waiting Room 복구 bootstrap에 실패했습니다.", exception);
        } finally {
            boolean stopRequired;
            synchronized (this) {
                available = recovered && generation == failureGeneration;
                stopRequired = !available;
                if (!stopRequired) {
                    recovering = false;
                }
            }
            if (stopRequired) {
                stopListener();
                synchronized (this) {
                    recovering = false;
                }
            }
        }
    }

    /** HTTP readiness와 업무 gate가 같은 상태를 읽습니다. */
    public boolean isAvailable() {
        return available;
    }

    /** startup 복구 시도가 끝나기 전에는 scheduler의 복구 실행을 건너뜁니다. */
    @Scheduled(fixedDelayString = "${waiting-room.scheduler-interval:PT1S}")
    public void probeRecovery() {
        if (startupRecoveryAttempted) {
            recoverIfNeeded();
        }
    }

    /** 일반 ApplicationRunner보다 먼저 한 번 복구를 시도합니다. */
    @Override
    public void run(ApplicationArguments args) {
        recoverIfNeeded();
        startupRecoveryAttempted = true;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private void stopListener() {
        try {
            var container = listenerContainer.getIfAvailable();
            if (container != null) {
                container.stop();
            }
        } catch (RuntimeException exception) {
            // 구독 해제 오류가 API의 고정 503 응답이나 recovery 재시도를 깨뜨리지 않습니다.
            LOGGER.warn("Waiting Room Pub/Sub 구독 해제에 실패했습니다.", exception);
        }
    }

    /** gate 차단은 새 Redis 장애가 아니므로 진행 중인 복구 세대를 바꾸지 않습니다. */
    public static final class UnavailableException extends RuntimeException {
        public UnavailableException() {
            super("Waiting Room 복구가 완료되지 않았습니다.");
        }
    }
}

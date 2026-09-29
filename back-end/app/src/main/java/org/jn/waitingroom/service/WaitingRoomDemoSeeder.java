/**
 * @author jeongnam
 * @since 2026-09-28
 * @file WaitingRoomDemoSeeder.java
 * @description 데모 프로필에서 활성 사용자와 대기 사용자의 초기 Redis 상태를 생성합니다.
 */
package org.jn.waitingroom.service;

import org.jn.waitingroom.config.WaitingRoomDemoProperties;
import org.jn.waitingroom.config.WaitingRoomProperties;
import org.jn.waitingroom.domain.WaitingRequestStatus;
import org.jn.waitingroom.redis.RedisWaitingRoomStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** 데모 전용 테스트 사용자를 기존 Waiting Room 상태 전이로 생성합니다. */
@Component
@Profile("demo")
@ConditionalOnProperty(prefix = "waiting-room.demo", name = "seed-enabled", havingValue = "true")
public class WaitingRoomDemoSeeder implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(WaitingRoomDemoSeeder.class);

    private final RedisWaitingRoomStore store;
    private final StringRedisTemplate redis;
    private final WaitingRoomProperties waitingRoomProperties;
    private final WaitingRoomDemoProperties demoProperties;

    /**
     * 데모 시드가 사용할 Redis 저장소와 실행 설정을 주입받습니다.
     *
     * @param store Waiting Room 상태 전이 저장소
     * @param redis 데모 시드 완료 표식을 저장할 Redis 접근 객체
     * @param waitingRoomProperties 대상 서비스의 운영 상태 전이 설정
     * @param demoProperties 데모 테스트 사용자와 만료시간 설정
     */
    public WaitingRoomDemoSeeder(
            RedisWaitingRoomStore store,
            StringRedisTemplate redis,
            WaitingRoomProperties waitingRoomProperties,
            WaitingRoomDemoProperties demoProperties
    ) {
        this.store = store;
        this.redis = redis;
        this.waitingRoomProperties = waitingRoomProperties;
        this.demoProperties = demoProperties;
    }

    /** Spring Boot 시작이 완료된 뒤 데모 테스트 상태를 한 번 구성합니다. */
    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /** 이전 데모 테스트 사용자만 제거한 뒤 동일한 초기 상태를 다시 생성합니다. */
    void seed() {
        String serviceId = demoProperties.serviceId();
        resetDemoState(serviceId);
        var service = serviceProperties(serviceId);
        String redirectTargetId = service.redirects().keySet().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("데모 대상 서비스의 redirect 설정이 없습니다."));
        seedActiveUsers(serviceId, redirectTargetId, service);
        seedWaitingUsers(serviceId, redirectTargetId, service);
        LOGGER.info(
                "Demo seed completed for service {}: active={}, waiting={}",
                serviceId,
                demoProperties.activeUsers(),
                demoProperties.waitingUsers()
        );
    }

    /** 대상 서비스의 런타임 큐를 초기화하고 고정된 데모 요청 상태를 제거합니다. */
    private void resetDemoState(String serviceId) {
        redis.delete(List.of(
                "waiting:{%s}".formatted(serviceId),
                "waiting-heartbeat:{%s}".formatted(serviceId),
                "active-slots:{%s}".formatted(serviceId),
                "slot-release-events:{%s}".formatted(serviceId),
                "maintenance-lease:{%s}".formatted(serviceId)
        ));

        List<String> requestIds = new ArrayList<>(demoProperties.activeUsers() + demoProperties.waitingUsers());
        for (int index = 1; index <= demoProperties.activeUsers(); index++) {
            requestIds.add("demo-active-%04d".formatted(index));
        }
        for (int index = 1; index <= demoProperties.waitingUsers(); index++) {
            requestIds.add("demo-waiting-%05d".formatted(index));
        }
        if (requestIds.isEmpty()) {
            return;
        }

        List<String> requestKeys = requestIds.stream()
                .map(requestId -> "waiting-request:{%s}:%s".formatted(serviceId, requestId))
                .toList();
        Object[] members = requestIds.toArray();
        redis.delete(requestKeys);
        redis.opsForZSet().remove("waiting:{%s}".formatted(serviceId), members);
        redis.opsForZSet().remove("waiting-heartbeat:{%s}".formatted(serviceId), members);
        redis.opsForZSet().remove("active-slots:{%s}".formatted(serviceId), members);
    }

    /** 최초 활성 테스트 사용자에게만 1~5분 범위의 개별 만료시간을 적용합니다. */
    private void seedActiveUsers(
            String serviceId,
            String redirectTargetId,
            WaitingRoomProperties.ServiceProperties service
    ) {
        if (demoProperties.activeUsers() > service.maxConcurrentUsers()) {
            throw new IllegalArgumentException("데모 활성 사용자 수는 서비스 최대 수용 인원을 초과할 수 없습니다.");
        }
        for (int index = 1; index <= demoProperties.activeUsers(); index++) {
            String requestId = "demo-active-%04d".formatted(index);
            register(serviceId, requestId, redirectTargetId, service.requestTtl());
            var admitted = store.admitNext(
                    serviceId,
                    service.maxConcurrentUsers(),
                    service.admissionTimeout(),
                    service.heartbeatTimeout(),
                    service.maxWaitDuration(),
                    waitingRoomProperties.quarantineTtl()
            );
            if (admitted.type() != RedisWaitingRoomStore.ResultType.APPLIED
                    || admitted.state() == null
                    || !requestId.equals(admitted.state().reservationRequestId())) {
                String admittedRequestId = admitted.state() == null
                        ? "없음"
                        : admitted.state().reservationRequestId();
                throw new IllegalStateException(
                        "데모 활성 테스트 사용자를 입장 허용할 수 없습니다: requestId=%s, result=%s, admittedRequestId=%s"
                                .formatted(requestId, admitted.type(), admittedRequestId)
                );
            }
            var entered = store.enter(serviceId, requestId, randomActiveDuration(), service.etaWindow());
            if (entered.state() == null || entered.state().status() != WaitingRequestStatus.ENTERED) {
                String status = entered.state() == null ? "없음" : entered.state().status().name();
                throw new IllegalStateException(
                        "데모 활성 테스트 사용자를 ENTERED 상태로 만들 수 없습니다: requestId=%s, result=%s, status=%s"
                                .formatted(requestId, entered.type(), status)
                );
            }
        }
    }

    /** 활성 슬롯을 점유하지 않는 WAITING 테스트 사용자를 등록합니다. */
    private void seedWaitingUsers(
            String serviceId,
            String redirectTargetId,
            WaitingRoomProperties.ServiceProperties service
    ) {
        for (int index = 1; index <= demoProperties.waitingUsers(); index++) {
            String requestId = "demo-waiting-%05d".formatted(index);
            register(serviceId, requestId, redirectTargetId, service.requestTtl());
        }
    }

    /** 멱등 등록에 사용할 고정 지문으로 테스트 요청을 생성합니다. */
    private void register(String serviceId, String requestId, String redirectTargetId, Duration requestTtl) {
        var result = store.register(
                serviceId,
                requestId,
                "sha256:" + requestId,
                redirectTargetId,
                requestTtl
        );
        if (result.state() == null) {
            throw new IllegalStateException("데모 테스트 사용자를 등록할 수 없습니다: " + requestId);
        }
    }

    /** 최초 활성 테스트 슬롯에 적용할 무작위 유지시간을 반환합니다. */
    private Duration randomActiveDuration() {
        long minimum = demoProperties.activeExpiryMin().toMillis();
        long maximum = demoProperties.activeExpiryMax().toMillis();
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(minimum, maximum + 1));
    }

    /** 데모 대상 서비스 설정을 조회합니다. */
    private WaitingRoomProperties.ServiceProperties serviceProperties(String serviceId) {
        var service = waitingRoomProperties.services().get(serviceId);
        if (service == null) {
            throw new IllegalArgumentException("등록되지 않은 데모 대상 서비스입니다: " + serviceId);
        }
        return service;
    }
}

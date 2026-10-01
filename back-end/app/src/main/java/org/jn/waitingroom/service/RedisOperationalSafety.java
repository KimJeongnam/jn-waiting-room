/**
 * @author jeongnam
 * @since 2026-09-30
 * @file RedisOperationalSafety.java
 * @description 운영 Redis eviction 정책과 메모리 한도를 확인하여 새 등록을 보호합니다.
 */
package org.jn.waitingroom.service;

import io.lettuce.core.RedisCommandExecutionException;
import org.jn.waitingroom.config.WaitingRoomProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** 정책 확인 실패와 메모리 등록 차단을 구분하며, 기존 상태 처리의 복구 gate는 변경하지 않습니다. */
@Component
public class RedisOperationalSafety implements ApplicationRunner, Ordered {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisOperationalSafety.class);
    private final boolean production;
    private final WaitingRoomProperties properties;
    private final Probe probe;
    private volatile State state;
    private volatile boolean startupAttempted;

    /** profile과 실제 Redis 조회를 주입받고 prod는 첫 확인 전까지 닫힌 상태로 시작합니다. */
    @Autowired
    public RedisOperationalSafety(StringRedisTemplate redis, WaitingRoomProperties properties, Environment environment) {
        this(environment.acceptsProfiles(Profiles.of("prod")), properties, new Probe() {
            @Override
            public Properties maxmemoryPolicies() {
                try {
                    return redis.execute((RedisCallback<Properties>) connection ->
                            connection.serverCommands().getConfig("maxmemory-policy"));
                } catch (RuntimeException exception) {
                    // 명령 권한 거부만 attestation 대상이며 연결·인증 장애는 그대로 실패 처리합니다.
                    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                        if (cause instanceof RedisCommandExecutionException
                                && cause.getMessage() != null && cause.getMessage().startsWith("NOPERM")) {
                            throw new PolicyAccessDeniedException();
                        }
                    }
                    throw exception;
                }
            }

            @Override
            public Properties memoryInfo() {
                return redis.execute((RedisCallback<Properties>) connection -> connection.serverCommands().info("memory"));
            }
        });
    }

    /** CONFIG/INFO 경계만 분리하여 정확한 메모리와 권한 거부를 검증할 수 있게 합니다. */
    RedisOperationalSafety(boolean production, WaitingRoomProperties properties, Probe probe) {
        this.production = production;
        this.properties = Objects.requireNonNull(properties);
        this.probe = Objects.requireNonNull(probe);
        this.state = production ? State.UNAVAILABLE : State.NORMAL;
    }

    /** 조회/파싱 실패를 로컬 상태에 반영하고 다음 실행의 재시도를 허용합니다. */
    public synchronized void refresh() {
        if (!production) return;
        try {
            Map<String, String> policies = null;
            try {
                policies = nodeValues(probe.maxmemoryPolicies(), "maxmemory-policy");
                if (policies.isEmpty() || policies.values().stream()
                        .anyMatch(policy -> !"noeviction".equalsIgnoreCase(policy))) {
                    state = State.UNAVAILABLE;
                    return;
                }
            } catch (PolicyAccessDeniedException exception) {
                if (!properties.redisMaxmemoryPolicyAttested()) {
                    state = State.UNAVAILABLE;
                    return;
                }
            }
            var memory = probe.memoryInfo();
            var usedByNode = nodeValues(memory, "used_memory");
            var limitsByNode = nodeValues(memory, "maxmemory");
            // CONFIG와 INFO의 노드가 다르거나 used_memory가 없는 노드는 안전 상태를 확인할 수 없습니다.
            if (usedByNode.isEmpty() || !usedByNode.keySet().containsAll(limitsByNode.keySet())
                    || policies != null && !policies.keySet().equals(usedByNode.keySet())) {
                throw new IllegalArgumentException("Incomplete Redis node memory data");
            }
            long highestUsed = 0;
            long highestLimit = 0;
            double usage = -1;
            for (var node : usedByNode.entrySet()) {
                long used = Long.parseLong(node.getValue());
                long limit = Long.parseLong(limitsByNode.getOrDefault(node.getKey(), "0"));
                if (used < 0 || limit < 0) throw new IllegalArgumentException("Invalid Redis memory value");
                // fallback은 클러스터 총합이 아닌 각 노드의 메모리 한도로 적용합니다.
                if (limit == 0) limit = properties.redisMaxmemoryBytes();
                if (limit <= 0) throw new IllegalArgumentException("Unknown Redis memory limit");
                // 나눗셈 전에 double로 변환하고 가장 높은 사용률로 상태를 결정합니다.
                double nodeUsage = (double) used / limit;
                if (nodeUsage > usage) {
                    usage = nodeUsage;
                    highestUsed = used;
                    highestLimit = limit;
                }
            }
            State next = usage >= properties.redisMemoryBlockRatio() ? State.BLOCKED
                    : usage >= properties.redisMemoryWarningRatio() ? State.WARNING : State.NORMAL;
            if (next != state && (next == State.WARNING || next == State.BLOCKED)) {
                LOGGER.warn("Waiting Room Redis memory state={} used={} limit={} usage={}", next, highestUsed, highestLimit, usage);
            }
            state = next;
        } catch (RuntimeException exception) {
            // 원문 예외에는 Redis 접속정보가 포함될 수 있어 로그와 health에 전달하지 않습니다.
            state = State.UNAVAILABLE;
        }
    }

    /** standalone의 직접 키와 Spring Redis Cluster의 {node}.{field} 키를 같은 노드 구조로 읽습니다. */
    private Map<String, String> nodeValues(Properties values, String field) {
        var nodes = new HashMap<String, String>();
        String suffix = "." + field;
        for (String key : values.stringPropertyNames()) {
            if (key.equals(field)) nodes.put("", values.getProperty(key));
            else if (key.endsWith(suffix)) {
                String node = key.substring(0, key.length() - suffix.length());
                if (node.isBlank()) throw new IllegalArgumentException("Invalid Redis node key");
                nodes.put(node, values.getProperty(key));
            }
        }
        return nodes;
    }

    /** 새 등록만 차단하고 기존 요청의 조회·상태 전이는 기존 recovery gate에 맡깁니다. */
    public void requireRegistrationCapacity() {
        State current = state;
        if (current == State.UNAVAILABLE || current == State.BLOCKED) throw new CapacityUnavailableException();
    }

    /** 높은 메모리 사용률에서도 기존 상태 처리를 계속할 수 있습니다. */
    public boolean isReady() {
        return state != State.UNAVAILABLE;
    }

    @Scheduled(fixedDelayString = "${waiting-room.redis-safety-check-interval:PT5S}")
    public void retrySafetyCheck() {
        if (startupAttempted) refresh();
    }

    /** recovery startup runner 이후 첫 정책 확인을 수행합니다. */
    @Override
    public void run(ApplicationArguments args) {
        refresh();
        startupAttempted = true;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    interface Probe {
        Properties maxmemoryPolicies();
        Properties memoryInfo();
    }

    private enum State { UNAVAILABLE, NORMAL, WARNING, BLOCKED }

    /** CONFIG 명령 권한 거부만 표현하며 Redis 연결 장애와 구별합니다. */
    static final class PolicyAccessDeniedException extends RuntimeException {
    }

    /** 등록 용량 부족은 새로운 recovery 장애로 처리하지 않습니다. */
    public static final class CapacityUnavailableException extends RuntimeException {
        public CapacityUnavailableException() {
            super("Waiting Room 등록 용량을 사용할 수 없습니다.");
        }
    }
}

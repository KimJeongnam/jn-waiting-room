/**
 * @author jeongnam
 * @since 2026-09-28
 * @file PollingPolicy.java
 * @description 예상 대기시간에 따른 상태조회 주기와 분산 jitter를 계산합니다.
 */
package org.jn.waitingroom.service;

import java.util.concurrent.ThreadLocalRandom;

/** Waiting Front가 그대로 사용할 서버 권위 Polling 주기를 계산합니다. */
final class PollingPolicy {
    private static final long ONE_MINUTE_SECONDS = 60;
    private static final long ONE_HOUR_SECONDS = 3_600;
    private static final long SHORT_POLL_DELAY_MS = 1_000;
    private static final long MEDIUM_POLL_DELAY_MS = 10_000;
    private static final long LONG_POLL_DELAY_MS = 15_000;
    private static final double MINIMUM_JITTER_FACTOR = 0.9;
    private static final double MAXIMUM_JITTER_FACTOR = 1.1;

    private PollingPolicy() {
    }

    /**
     * 예상 대기시간 구간의 기본 주기에 ±10% jitter를 적용합니다.
     *
     * @param estimatedWaitSeconds 예상 대기시간이며 계산할 수 없으면 {@code null}
     * @return 다음 상태조회까지 기다릴 milliseconds
     */
    static long nextPollAfterMs(Long estimatedWaitSeconds) {
        double factor = ThreadLocalRandom.current().nextDouble(
                MINIMUM_JITTER_FACTOR,
                MAXIMUM_JITTER_FACTOR
        );
        return Math.round(basePollDelayMs(estimatedWaitSeconds) * factor);
    }

    /**
     * 예상 대기시간에 해당하는 jitter 적용 전 기본 주기를 선택합니다.
     *
     * @param estimatedWaitSeconds 예상 대기시간이며 계산할 수 없으면 {@code null}
     * @return 구간별 기본 조회 주기 milliseconds
     */
    static long basePollDelayMs(Long estimatedWaitSeconds) {
        if (estimatedWaitSeconds == null || estimatedWaitSeconds >= ONE_HOUR_SECONDS) {
            return LONG_POLL_DELAY_MS;
        }
        if (estimatedWaitSeconds >= ONE_MINUTE_SECONDS) {
            return MEDIUM_POLL_DELAY_MS;
        }
        return SHORT_POLL_DELAY_MS;
    }
}

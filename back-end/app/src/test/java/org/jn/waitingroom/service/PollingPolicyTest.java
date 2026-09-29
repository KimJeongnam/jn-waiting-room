/**
 * @author jeongnam
 * @since 2026-09-28
 * @file PollingPolicyTest.java
 * @description 예상 대기시간 구간별 Polling 주기와 jitter 범위를 검증합니다.
 */
package org.jn.waitingroom.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollingPolicyTest {
    @Test
    void selectsTheDocumentedBaseDelayForEachEstimatedWaitRange() {
        assertEquals(1_000, PollingPolicy.basePollDelayMs(59L));
        assertEquals(10_000, PollingPolicy.basePollDelayMs(60L));
        assertEquals(10_000, PollingPolicy.basePollDelayMs(3_599L));
        assertEquals(15_000, PollingPolicy.basePollDelayMs(3_600L));
        assertEquals(15_000, PollingPolicy.basePollDelayMs(null));
    }

    @Test
    void appliesJitterWithinTenPercentOfTheSelectedDelay() {
        for (int index = 0; index < 100; index++) {
            long shortDelay = PollingPolicy.nextPollAfterMs(59L);
            long mediumDelay = PollingPolicy.nextPollAfterMs(60L);
            long longDelay = PollingPolicy.nextPollAfterMs(null);

            assertTrue(shortDelay >= 900 && shortDelay <= 1_100);
            assertTrue(mediumDelay >= 9_000 && mediumDelay <= 11_000);
            assertTrue(longDelay >= 13_500 && longDelay <= 16_500);
        }
    }
}

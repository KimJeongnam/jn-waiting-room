package org.jn.waitingroom;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WaitingRoomApplicationTest {
    @Test
    void applicationIsConfiguredAsSpringBootApplication() {
        assertTrue(WaitingRoomApplication.class
                .isAnnotationPresent(SpringBootApplication.class));
    }
}

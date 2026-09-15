package org.example;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkTicketReservationApplicationTest {
    @Test
    void applicationIsConfiguredAsSpringBootApplication() {
        assertTrue(BulkTicketReservationApplication.class
                .isAnnotationPresent(SpringBootApplication.class));
    }
}

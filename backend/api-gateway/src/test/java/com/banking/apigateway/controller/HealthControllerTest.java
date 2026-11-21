package com.banking.apigateway.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class HealthControllerTest {

    private HealthController healthController;

    @BeforeEach
    void setUp() {
        healthController = new HealthController();
        ReflectionTestUtils.setField(healthController, "applicationName", "test-api-gateway");
    }

    @Test
    void shouldReturnHealthStatus() {
        var response = healthController.health();
        
        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().getStatus());
        assertEquals("test-api-gateway", response.getBody().getService());
        assertEquals("1.0.0", response.getBody().getVersion());
        assertNotNull(response.getBody().getTimestamp());
    }

    @Test
    void shouldReturnReadinessStatus() {
        var response = healthController.readiness();
        
        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("READY", response.getBody().getStatus());
        assertEquals("test-api-gateway", response.getBody().getService());
        assertNotNull(response.getBody().getTimestamp());
    }

    @Test
    void shouldReturnLivenessStatus() {
        var response = healthController.liveness();
        
        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("ALIVE", response.getBody().getStatus());
        assertEquals("test-api-gateway", response.getBody().getService());
        assertNotNull(response.getBody().getTimestamp());
    }
}
package com.banking.apigateway.controller;

import com.banking.apigateway.contract.GatewayApi;
import com.banking.apigateway.contract.model.HealthReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
public class HealthController implements GatewayApi {

    @Value("${spring.application.name:api-gateway}")
    private String applicationName;

    @Override
    public ResponseEntity<HealthReport> health() {
        return ResponseEntity.ok(report("UP", "1.0.0"));
    }

    @Override
    public ResponseEntity<HealthReport> readiness() {
        return ResponseEntity.ok(report("READY", null));
    }

    @Override
    public ResponseEntity<HealthReport> liveness() {
        return ResponseEntity.ok(report("ALIVE", null));
    }

    @Override
    public ResponseEntity<HealthReport> healthz() {
        return health();
    }

    private HealthReport report(String status, String version) {
        HealthReport report = new HealthReport();
        report.setStatus(status);
        report.setService(applicationName);
        report.setTimestamp(LocalDateTime.now().toString());
        report.setVersion(version);
        return report;
    }
}

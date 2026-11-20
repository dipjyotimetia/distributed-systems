package com.banking.payments;

import com.banking.payments.settlement.ReconciliationJob;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PaymentsApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(PaymentsApplication.class, args);
        var job = context.getBeanProvider(ReconciliationJob.class).getIfAvailable();
        if (job != null) {
            try (context) {
                job.run();
            }
        }
    }
}

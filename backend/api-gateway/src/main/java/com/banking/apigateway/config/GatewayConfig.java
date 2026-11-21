package com.banking.apigateway.config;

import com.banking.apigateway.filter.AuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration
public class GatewayConfig {

    @Autowired
    AuthenticationFilter authFilter;

    // Rate limiting is backed by Redis. It can be disabled (e.g. in tests or
    // environments without Redis) so the gateway still routes without a Redis backend.
    @Value("${gateway.rate-limiter.enabled:true}")
    private boolean rateLimiterEnabled;

    @Value("${services.user-service.url}")
    private String userServiceUrl;

    @Value("${services.accounts-service.url}")
    private String accountsServiceUrl;

    @Value("${services.transactions-service.url}")
    private String transactionsServiceUrl;

    @Value("${services.disputes-service.url}")
    private String disputesServiceUrl;

    @Value("${services.payments-service.url:}")
    private String paymentsServiceUrl;

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "gateway.rate-limiter.enabled", havingValue = "true", matchIfMissing = true)
    public RedisRateLimiter redisRateLimiter() {
        return new RedisRateLimiter(10, 20, 1);
    }

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            var principal = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            return Mono.just(principal != null ? principal : "anonymous");
        };
    }

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder) {
        var routes = builder.routes()
                // API Gateway health check route
                .route("api-gateway-health", r -> r.path("/api/v1/healthz")
                        .filters(f -> f.rewritePath("/api/v1/healthz", "/health"))
                        .uri("http://localhost:8080"))
                // Health check routes (no authentication required)
                .route("user-service-health", r -> r.path("/api/v1/user-service/health")
                        .filters(f -> f.rewritePath("/api/v1/user-service/health", "/actuator/health"))
                        .uri(userServiceUrl))
                .route("accounts-service-health", r -> r.path("/api/v1/accounts-service/health")
                        .filters(f -> f.rewritePath("/api/v1/accounts-service/health", "/actuator/health"))
                        .uri(accountsServiceUrl))
                .route("transactions-service-health", r -> r.path("/api/v1/transactions-service/health")
                        .filters(f -> f.rewritePath("/api/v1/transactions-service/health", "/actuator/health"))
                        .uri(transactionsServiceUrl))
                // Main API routes with authentication and rate limiting
                .route("user-service", r -> r.path("/api/v1/users/**")
                        .filters(f -> f.filter(authFilter))
                        .uri(userServiceUrl))
                .route("accounts-service", r -> r.path("/api/v1/accounts/**")
                        .filters(f -> {
                            f.filter(authFilter);
                            if (rateLimiterEnabled) {
                                f.requestRateLimiter(c -> c.setRateLimiter(redisRateLimiter()).setKeyResolver(userKeyResolver()));
                            }
                            return f;
                        })
                        .uri(accountsServiceUrl))
                .route("transactions-service", r -> r.path("/api/v1/transactions/**")
                        .filters(f -> {
                            f.filter(authFilter);
                            if (rateLimiterEnabled) {
                                f.requestRateLimiter(c -> c.setRateLimiter(redisRateLimiter()).setKeyResolver(userKeyResolver()));
                            }
                            return f;
                        })
                        .uri(transactionsServiceUrl))
                .route("disputes-service", r -> r.path("/api/v1/disputes/**", "/api/v1/operations/disputes/**")
                        .filters(f -> {
                            f.filter(authFilter);
                            if (rateLimiterEnabled) {
                                f.requestRateLimiter(c -> c.setRateLimiter(redisRateLimiter()).setKeyResolver(userKeyResolver()));
                            }
                            return f;
                        })
                        .uri(disputesServiceUrl));
        if (paymentsServiceUrl != null && !paymentsServiceUrl.isBlank()) {
            routes.route("payments-service", r -> r.path("/api/v1/payments", "/api/v1/payments/**", "/api/v1/operations/payments/reconciliation")
                    .filters(f -> {
                        f.filter(authFilter);
                        if (rateLimiterEnabled) {
                            f.requestRateLimiter(c -> c.setRateLimiter(redisRateLimiter()).setKeyResolver(userKeyResolver()));
                        }
                        return f;
                    })
                    .uri(paymentsServiceUrl));
        }
        return routes.build();
    }
}

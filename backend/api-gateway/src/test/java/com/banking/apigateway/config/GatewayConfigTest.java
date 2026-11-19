package com.banking.apigateway.config;

import com.banking.apigateway.filter.AuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.factory.RewritePathGatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class GatewayConfigTest {
    @Test
    void routesBothDisputeApisToTheService() {
        GatewayConfig config = new GatewayConfig();
        config.authFilter = mock(AuthenticationFilter.class);
        ReflectionTestUtils.setField(config, "userServiceUrl", "http://users:8080");
        ReflectionTestUtils.setField(config, "accountsServiceUrl", "http://accounts:8080");
        ReflectionTestUtils.setField(config, "transactionsServiceUrl", "http://transactions:8080");
        ReflectionTestUtils.setField(config, "disputesServiceUrl", "http://disputes:8080");

        try (StaticApplicationContext context = new StaticApplicationContext()) {
            context.registerSingleton("pathPredicate", PathRoutePredicateFactory.class);
            context.registerSingleton("rewritePathFilter", RewritePathGatewayFilterFactory.class);
            Route route = config.routes(new RouteLocatorBuilder(context)).getRoutes()
                    .filter(candidate -> candidate.getId().equals("disputes-service"))
                    .blockFirst();
            assertNotNull(route);
            assertEquals("http://disputes:8080", route.getUri().toString());
            assertEquals(1, route.getFilters().size());
            for (String path : new String[]{"/api/disputes", "/api/disputes/1",
                    "/api/operations/disputes", "/api/operations/disputes/1/resolve"}) {
                assertTrue(matches(route, path));
            }
            assertFalse(matches(route, "/api/transactions/1"));
        }
    }

    private boolean matches(Route route, String path) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        return Boolean.TRUE.equals(Mono.from(route.getPredicate().apply(exchange)).block());
    }
}

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
import org.yaml.snakeyaml.Yaml;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
        ReflectionTestUtils.setField(config, "knowledgeServiceUrl", "http://knowledge:8080");
        ReflectionTestUtils.setField(config, "paymentsServiceUrl", "http://payments:8080");

        try (StaticApplicationContext context = new StaticApplicationContext()) {
            context.getBeanFactory().registerSingleton("pathPredicate",
                    new PathRoutePredicateFactory(new org.springframework.boot.webflux.autoconfigure.WebFluxProperties()));
            context.registerSingleton("rewritePathFilter", RewritePathGatewayFilterFactory.class);
            Route route = config.routes(new RouteLocatorBuilder(context)).getRoutes()
                    .filter(candidate -> candidate.getId().equals("disputes-service"))
                    .blockFirst();
            assertNotNull(route);
            assertEquals("http://disputes:8080", route.getUri().toString());
            assertEquals(1, route.getFilters().size());
            for (String path : new String[]{"/api/v1/disputes", "/api/v1/disputes/1",
                    "/api/v1/operations/disputes", "/api/v1/operations/disputes/1/resolve"}) {
                assertTrue(matches(route, path));
            }
            assertFalse(matches(route, "/api/v1/transactions/1"));
            Route knowledge = config.routes(new RouteLocatorBuilder(context)).getRoutes()
                    .filter(candidate -> candidate.getId().equals("knowledge-service"))
                    .blockFirst();
            assertNotNull(knowledge);
            assertEquals("http://knowledge:8080", knowledge.getUri().toString());
            assertTrue(matches(knowledge, "/api/v1/knowledge/documents"));
            assertTrue(matches(knowledge, "/api/v1/knowledge/search"));
        }
    }

    @Test
    void publicOperationsMatchRoutesAndStayOnTheUnauthenticatedList() throws Exception {
        GatewayConfig config = new GatewayConfig();
        config.authFilter = mock(AuthenticationFilter.class);
        ReflectionTestUtils.setField(config, "userServiceUrl", "http://users:8080");
        ReflectionTestUtils.setField(config, "accountsServiceUrl", "http://accounts:8080");
        ReflectionTestUtils.setField(config, "transactionsServiceUrl", "http://transactions:8080");
        ReflectionTestUtils.setField(config, "disputesServiceUrl", "http://disputes:8080");
        ReflectionTestUtils.setField(config, "knowledgeServiceUrl", "http://knowledge:8080");
        ReflectionTestUtils.setField(config, "paymentsServiceUrl", "http://payments:8080");
        List<Route> routes;
        try (StaticApplicationContext context = new StaticApplicationContext()) {
            context.getBeanFactory().registerSingleton("pathPredicate",
                    new PathRoutePredicateFactory(new org.springframework.boot.webflux.autoconfigure.WebFluxProperties()));
            context.registerSingleton("rewritePathFilter", RewritePathGatewayFilterFactory.class);
            routes = config.routes(new RouteLocatorBuilder(context)).getRoutes().collectList().block();
        }
        assertNotNull(routes);
        Path specs = Path.of("").toAbsolutePath();
        while (specs != null && !Files.exists(specs.resolve("contracts/openapi/users.yaml"))) {
            specs = specs.getParent();
        }
        assertNotNull(specs);
        Yaml yaml = new Yaml();
        for (String name : List.of("users.yaml", "accounts.yaml", "transactions.yaml", "disputes.yaml", "payments.yaml", "knowledge.yaml")) {
            Map<String, Object> document = yaml.load(Files.readString(specs.resolve("contracts/openapi").resolve(name)));
            @SuppressWarnings("unchecked")
            Map<String, Object> paths = (Map<String, Object>) document.get("paths");
            for (String path : paths.keySet()) {
                String sample = path.replaceAll("\\{[^}]+}", "1");
                assertTrue(routes.stream().anyMatch(route -> matches(route, sample)), name + " " + sample);
            }
        }
        assertEquals(List.of(
                "/api/v1/users/register",
                "/api/v1/users/login",
                "/api/v1/users/check-username",
                "/api/v1/users/check-email"), RouterValidator.publicEndpoints);
        RouterValidator validator = new RouterValidator();
        for (String path : RouterValidator.publicEndpoints) {
            assertFalse(validator.requiresAuthentication.test(
                    MockServerHttpRequest.get(path).build()));
        }
        assertTrue(validator.requiresAuthentication.test(MockServerHttpRequest.get("/api/v1/accounts").build()));
    }

    private boolean matches(Route route, String path) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        return Boolean.TRUE.equals(Mono.from(route.getPredicate().apply(exchange)).block());
    }
}

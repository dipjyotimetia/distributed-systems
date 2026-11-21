package com.banking.apigateway.config;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Predicate;

@Component
public class RouterValidator {

    public static final List<String> publicEndpoints = List.of(
            "/api/v1/users/register",
            "/api/v1/users/login",
            "/api/v1/users/check-username",
            "/api/v1/users/check-email"
    );

    public Predicate<ServerHttpRequest> requiresAuthentication =
            request -> publicEndpoints
                    .stream()
                    .noneMatch(uri -> request.getURI().getPath().contains(uri));
}

package com.banking.accountsservice.service;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinancialDataServiceTest {
    private static final String PUBLIC_TOKEN = "/sandbox/public_token/create";
    private static final String ACCESS_TOKEN = "/item/public_token/exchange";
    private static final String BALANCE = "/accounts/balance/get";

    @Test
    void fetchesAndReusesPlaidAccessTokenWhileEnrichingEachAccount() {
        List<String> calls = new ArrayList<>();
        var service = service(calls, Map.of(PUBLIC_TOKEN, "{\"public_token\":\"fixture-public\"}",
                ACCESS_TOKEN, "{\"access_token\":\"fixture-access\"}", BALANCE, "{}",
                "/rates", "{}", "/ratings", "{}"));
        service.enrichAsync("first-account");
        service.enrichAsync("second-account");
        assertEquals(List.of(PUBLIC_TOKEN, ACCESS_TOKEN, BALANCE, "/rates", "/ratings", BALANCE, "/rates", "/ratings"), calls);
    }

    @Test
    void missingTokensAndProviderFailuresRemainIsolatedFromAccountCreation() {
        List<String> calls = new ArrayList<>();
        service(calls, Map.of(PUBLIC_TOKEN, "{\"public_token\":\"fixture-public\"}", ACCESS_TOKEN, "{}"))
                .enrichAsync("first-account");
        assertEquals(List.of(PUBLIC_TOKEN, ACCESS_TOKEN, "/rates", "/ratings"), calls);

        calls.clear();
        service(calls, Map.of(PUBLIC_TOKEN, "{}", "/rates", "{}")).enrichAsync("second-account");
        assertEquals(List.of(PUBLIC_TOKEN, "/rates", "/ratings"), calls);

        calls.clear();
        service(calls, Map.of()).enrichAsync("third-account");
        assertEquals(List.of(PUBLIC_TOKEN, "/rates", "/ratings"), calls);

        calls.clear();
        service(calls, Map.of(PUBLIC_TOKEN, "{\"public_token\":\"fixture-public\"}",
                ACCESS_TOKEN, "{\"access_token\":\"fixture-access\"}")).enrichAsync("fourth-account");
        assertTrue(calls.contains(BALANCE));
    }

    private static FinancialDataService service(List<String> calls, Map<String, String> replies) {
        var service = new FinancialDataService();
        ReflectionTestUtils.setField(service, "plaidBaseUrl", "http://fixture.test");
        ReflectionTestUtils.setField(service, "plaidClientId", "fixture-client");
        ReflectionTestUtils.setField(service, "plaidSecret", "fixture-secret");
        ReflectionTestUtils.setField(service, "exchangeRatesUrl", "http://fixture.test/rates");
        ReflectionTestUtils.setField(service, "exchangeRatesAppId", "fixture-app");
        ReflectionTestUtils.setField(service, "moodysApiUrl", "http://fixture.test/ratings");
        ReflectionTestUtils.setField(service, "moodysApiKey", "fixture-key");
        var client = mock(HttpClient.class);
        when(client.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    String path = request.uri().getPath();
                    calls.add(path);
                    String body = replies.get(path);
                    if (body == null) {
                        return CompletableFuture.failedFuture(new IOException("Fixture provider unavailable"));
                    }
                    @SuppressWarnings("unchecked")
                    HttpResponse<String> response = mock(HttpResponse.class);
                    when(response.body()).thenReturn(body);
                    when(response.statusCode()).thenReturn(200);
                    return CompletableFuture.completedFuture(response);
                });
        ReflectionTestUtils.setField(service, "httpClient", client);
        return service;
    }
}

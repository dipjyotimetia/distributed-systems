package com.banking.transactionsservice.client;

import jakarta.servlet.http.HttpServletRequest;
import com.banking.transactionsservice.service.InsufficientFundsException;
import com.banking.transactionsservice.service.MovementCoordinator.Action;
import com.banking.transactionsservice.service.MovementCoordinator.Movement;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class AccountsServiceClient {
    
    private static final Logger logger = LoggerFactory.getLogger(AccountsServiceClient.class);
    
    @Value("${accounts.service.url:http://localhost:8082}")
    private String accountsServiceUrl;
    
    @Value("${movement.secret:${jwt.secret}}")
    private String movementSecret;

    private final RestTemplate restTemplate;
    
    public AccountsServiceClient(RestTemplateBuilder builder) {
        this.restTemplate = builder.connectTimeout(Duration.ofSeconds(3)).readTimeout(Duration.ofSeconds(8)).build();
    }

    public void applyMovement(UUID operation, Action action, Movement movement) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(movementSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String data = "account-movement:v1:" + operation + ":" + action + ":" + movement.userId()
                    + ":" + movement.fromAccountId() + ":" + movement.toAccountId() + ":" + movement.amount().toPlainString();
            headers.set("X-Movement-Signature", HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8))));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to sign movement request", e);
        }
        try {
            var response = restTemplate.postForEntity(accountsServiceUrl + "/internal/account-movements/" + operation + "/" + action,
                    new HttpEntity<>(movement, headers), Void.class);
            if (response.getStatusCode() != HttpStatus.NO_CONTENT) {
                throw new IllegalStateException("Unexpected movement response status");
            }
        } catch (HttpStatusCodeException e) {
            if (action == Action.RESERVE && e.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                throw new InsufficientFundsException("Insufficient balance for movement");
            }
            if (e.getStatusCode() == HttpStatus.NOT_FOUND || e.getStatusCode() == HttpStatus.CONFLICT) {
                throw new ResponseStatusException(e.getStatusCode(), "Account movement rejected");
            }
            throw e;
        }
    }
    
    public boolean validateAccountOwnership(Long accountId, HttpServletRequest request) {
        try {
            String url = accountsServiceUrl + "/api/accounts/" + accountId;
            
            HttpHeaders headers = new HttpHeaders();
            String authHeader = request.getHeader("Authorization");
            headers.set("Authorization", authHeader);
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, Map.class);
            
            return response.getStatusCode() == HttpStatus.OK;
        } catch (RestClientException e) {
            logger.error("Error validating account ownership for account {}: {}", 
                        accountId, e.getMessage());
            return false;
        }
    }
    
}

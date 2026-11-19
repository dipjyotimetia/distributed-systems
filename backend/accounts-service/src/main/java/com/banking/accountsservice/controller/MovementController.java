package com.banking.accountsservice.controller;

import com.banking.accountsservice.service.MovementReservations;
import com.banking.accountsservice.service.MovementReservations.Action;
import com.banking.accountsservice.service.MovementReservations.Movement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@RestController
@RequestMapping("/internal/account-movements")
public class MovementController {
    private final MovementReservations reservations;
    private final String secret;

    public MovementController(MovementReservations reservations,
                              @Value("${movement.secret:${jwt.secret}}") String secret) {
        this.reservations = reservations;
        this.secret = secret;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Void> movementRejected(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).build();
    }

    @PostMapping("/{operation}/{action}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void apply(@PathVariable UUID operation, @PathVariable Action action,
                      @RequestBody Movement movement, @RequestHeader("X-Movement-Signature") String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String data = "account-movement:v1:" + operation + ":" + action + ":" + movement.userId()
                    + ":" + movement.fromAccountId() + ":" + movement.toAccountId() + ":" + movement.amount().toPlainString();
            byte[] expected = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to verify movement signature", e);
        }
        reservations.apply(operation, action, movement);
    }
}

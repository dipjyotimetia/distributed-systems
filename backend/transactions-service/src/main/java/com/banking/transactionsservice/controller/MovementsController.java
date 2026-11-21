package com.banking.transactionsservice.controller;

import com.banking.transactionsservice.contract.MovementsApi;
import com.banking.transactionsservice.contract.model.MovementError;
import com.banking.transactionsservice.contract.model.MovementIntent;
import com.banking.transactionsservice.contract.model.MovementOutcome;
import com.banking.transactionsservice.security.UserAuthenticationDetails;
import com.banking.transactionsservice.service.CustomerMovements;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.UUID;

@RestController
public class MovementsController implements MovementsApi {
    private final CustomerMovements movements;

    public MovementsController(CustomerMovements movements) {
        this.movements = movements;
    }

    @Override
    public ResponseEntity<MovementOutcome> submitMovement(String idempotencyKey, MovementIntent body) {
        var outcome = movements.submit(userId(), idempotencyKey, body);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).location(URI.create("/api/v1/movements/" + outcome.getId())).body(outcome);
    }

    @Override
    public ResponseEntity<MovementOutcome> getMovement(UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(movements.get(userId(), id));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<MovementError> status(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).body(new MovementError(failure.getReason()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<MovementError> invalid() {
        return ResponseEntity.badRequest().body(new MovementError("INVALID_INPUT"));
    }

    private static long userId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getDetails() instanceof UserAuthenticationDetails user) {
            return user.getUserId();
        }
        throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }
}

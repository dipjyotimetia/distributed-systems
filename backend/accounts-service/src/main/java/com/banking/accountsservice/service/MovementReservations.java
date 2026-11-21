package com.banking.accountsservice.service;

import com.banking.accountsservice.entity.Account;
import com.banking.accountsservice.repository.AccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class MovementReservations {
    public record Movement(long userId, Long fromAccountId, Long toAccountId, BigDecimal amount) {
        public Movement {
            if (userId <= 0 || Objects.equals(fromAccountId, toAccountId) || amount == null) {
                throw new IllegalArgumentException("Invalid movement");
            }
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
            if (amount.signum() <= 0 || amount.precision() > 19) {
                throw new IllegalArgumentException("Invalid movement amount");
            }
        }
    }

    public enum Action { RESERVE, COMMIT, COMPENSATE }

    private record Reservation(Movement movement, String state, String currency, String scope) {}

    private final AccountRepository accounts;
    private final JdbcTemplate jdbc;

    public MovementReservations(AccountRepository accounts, JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    @Transactional
    public void apply(UUID operation, Action action, Movement movement) {
        apply(operation, action, movement, null, null, null);
    }

    @Transactional
    public void applyOutbound(UUID operation, Action action, Movement movement) {
        if (movement.fromAccountId() == null || movement.toAccountId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Outbound payments require one source account");
        }
        apply(operation, action, movement, "AUD", null, null);
    }

    @Transactional
    public void applyExact(UUID operation, Action action, Movement movement, String currency, boolean outbound) {
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Invalid currency");
        }
        int scale = java.util.Currency.getInstance(currency).getDefaultFractionDigits();
        if (scale < 0 || scale > 2) throw new IllegalArgumentException("Unsupported currency scale");
        movement.amount().setScale(scale, RoundingMode.UNNECESSARY);
        apply(operation, action, movement, currency, currency, outbound ? "OUTBOUND" : "INTERNAL");
    }

    private void apply(UUID operation, Action action, Movement movement, String currency, String binding, String scope) {
        var locked = Stream.of(movement.fromAccountId(), movement.toAccountId())
                .filter(Objects::nonNull).sorted()
                .map(id -> accounts.findOwnedForUpdate(id, movement.userId()))
                .flatMap(java.util.Optional::stream)
                .toList();
        int expected = (movement.fromAccountId() == null ? 0 : 1) + (movement.toAccountId() == null ? 0 : 1);
        if (action != Action.COMPENSATE && locked.size() != expected) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        }
        Account from = locked.stream().filter(account -> account.getId().equals(movement.fromAccountId())).findFirst().orElse(null);
        Account to = locked.stream().filter(account -> account.getId().equals(movement.toAccountId())).findFirst().orElse(null);
        if (action == Action.RESERVE && ((currency != null
                && locked.stream().anyMatch(account -> !currency.equals(account.getCurrency())))
                || (from != null && to != null && !from.getCurrency().equals(to.getCurrency())))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENCY_MISMATCH");
        }
        var rows = jdbc.query("""
                SELECT * FROM accounts_service.movement_reservations WHERE operation_id = ?
                """, (rs, row) -> new Reservation(new Movement(rs.getLong("user_id"),
                rs.getObject("from_account_id", Long.class), rs.getObject("to_account_id", Long.class),
                rs.getBigDecimal("amount")), rs.getString("state"), rs.getString("requested_currency"),
                rs.getString("requested_scope")), operation);
        if (!rows.isEmpty()) {
            var reservation = rows.get(0);
            if (!reservation.movement().equals(movement) || !Objects.equals(binding, reservation.currency())
                    || !Objects.equals(scope, reservation.scope())) {
                throw conflict("Movement does not match reservation");
            }
            if (action == Action.RESERVE && !"COMPENSATED".equals(reservation.state())) {
                return;
            }
            String outcome = action == Action.COMMIT ? "COMMITTED" : "COMPENSATED";
            if (action != Action.RESERVE && outcome.equals(reservation.state())) {
                return;
            }
            if (action == Action.RESERVE || !"RESERVED".equals(reservation.state())) {
                throw conflict("Movement already resolved");
            }
            if (action == Action.COMMIT) {
                boolean outbound = "OUTBOUND".equals(scope) || (currency != null && binding == null);
                String source = outbound ? "OUTBOUND_PAYMENT" : "MOVEMENT";
                if (from != null) applyPosting(from, operation, movement.amount().negate(), source);
                if (to != null) applyPosting(to, operation, movement.amount(), source);
                accounts.flush();
            }
            jdbc.update("UPDATE accounts_service.movement_reservations SET state = ? WHERE operation_id = ?", outcome, operation);
            return;
        }

        if (action == Action.COMMIT) {
            throw conflict("Movement reservation missing");
        }
        if (action == Action.RESERVE) {
            for (Account account : locked) {
                if (account.getStatus() != Account.Status.ACTIVE) {
                    throw conflict("Account is not active");
                }
            }
            if (from != null && from.getBalance().subtract(held(from.getId())).compareTo(movement.amount()) < 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS");
            }
            if (to != null && to.getBalance().add(pendingCredits(to.getId())).add(movement.amount()).precision() > 19) {
                throw conflict("Destination balance exceeds the supported range");
            }
        }
        // A compensation without a reservation prevents a delayed reserve request from acquiring a hold.
        jdbc.update("""
                INSERT INTO accounts_service.movement_reservations
                    (operation_id, user_id, from_account_id, to_account_id, amount, original_from, original_to, state, requested_currency, requested_scope)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, operation, movement.userId(), movement.fromAccountId(), movement.toAccountId(), movement.amount(),
                from == null ? null : from.getBalance(), to == null ? null : to.getBalance(),
                action == Action.RESERVE ? "RESERVED" : "COMPENSATED", binding, scope);
    }

    BigDecimal held(long accountId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM accounts_service.movement_reservations
                WHERE state = 'RESERVED' AND from_account_id = ?
                """, BigDecimal.class, accountId);
    }

    private BigDecimal pendingCredits(long accountId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM accounts_service.movement_reservations
                WHERE state = 'RESERVED' AND to_account_id = ?
                """, BigDecimal.class, accountId);
    }

    void applyPosting(Account account, UUID operation, BigDecimal delta, String source) {
        BigDecimal next = account.getBalance().add(delta).setScale(2, RoundingMode.UNNECESSARY);
        if (next.signum() < 0 || next.precision() > 19) {
            throw conflict("Posted balance is outside the supported range");
        }
        account.setBalance(next);
        recordPosting(account, operation, delta, source);
    }

    void recordPosting(Account account, UUID operation, BigDecimal delta, String source) {
        if (delta.signum() != 0) {
            jdbc.update("""
                    INSERT INTO accounts_service.account_postings
                        (account_id, operation_id, currency, amount, balance_after, source, user_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, account.getId(), operation, account.getCurrency(), delta, account.getBalance(),
                    source, account.getUserId());
        }
    }

    // Call while holding the account row lock.
    public void assertUnreserved(long accountId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM accounts_service.movement_reservations
                WHERE state = 'RESERVED' AND (from_account_id = ? OR to_account_id = ?)
                """, Integer.class, accountId, accountId);
        if (count != null && count > 0) {
            throw conflict("Account has a pending movement");
        }
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}

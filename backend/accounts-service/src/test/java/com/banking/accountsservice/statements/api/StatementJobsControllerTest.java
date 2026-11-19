package com.banking.accountsservice.statements.api;

import com.banking.accountsservice.entity.Account;
import com.banking.accountsservice.repository.AccountRepository;
import com.banking.accountsservice.security.UserAuthenticationDetails;
import com.banking.accountsservice.statements.application.StatementJobs;
import com.banking.accountsservice.statements.persistence.StatementJobStore;
import com.banking.accountsservice.statements.persistence.StatementJobStore.Job;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StatementJobsControllerTest {
    @Test
    void handlesCreationListingStatusAndDownloadThroughTheAuthenticatedApi() throws Exception {
        var accounts = mock(AccountRepository.class);
        var store = mock(StatementJobStore.class);
        when(accounts.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(new Account()));
        var id = UUID.randomUUID();
        var now = Instant.now();
        var job = new Job(id, "READY", now, now.plusSeconds(60));
        when(store.create(42, 7, "create-1")).thenReturn(job);
        when(store.list(42, 7, 0)).thenReturn(List.of(job));
        when(store.find(42, 7, id)).thenReturn(Optional.of(job));
        when(store.download(42, 7, id)).thenReturn(Optional.of(new byte[] {'{', '}'}));
        var api = MockMvcBuilders.standaloneSetup(new StatementJobsController(new StatementJobs(accounts, store))).build();
        var owner = identity(7);
        var stranger = identity(8);
        String base = "/api/accounts/42/statement-jobs";
        String resource = base + "/" + id;

        api.perform(post(base).principal(owner).header("Idempotency-Key", "create-1"))
                .andExpect(status().isAccepted()).andExpect(header().string("Location", resource));
        api.perform(post(base).principal(owner)).andExpect(status().isBadRequest());
        api.perform(post(base).principal(owner).header("Idempotency-Key", " ")).andExpect(status().isBadRequest());
        api.perform(get(base).principal(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("READY")).andExpect(jsonPath("$.hasMore").value(false));
        api.perform(get(base).principal(owner).param("page", "0")).andExpect(status().isBadRequest());
        api.perform(get(base).principal(stranger)).andExpect(status().isNotFound());
        api.perform(get(resource).principal(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
        api.perform(get(base + "/bad").principal(owner)).andExpect(status().isBadRequest());
        api.perform(get(base + "/" + UUID.randomUUID()).principal(owner)).andExpect(status().isNotFound());
        api.perform(get(resource + "/download").principal(stranger)).andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"));
        api.perform(get(resource + "/download").principal(owner)).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"statement-" + id + ".json\""))
                .andExpect(content().json("{}"));
        when(store.download(42, 7, id)).thenReturn(Optional.empty());
        api.perform(get(resource + "/download").principal(owner)).andExpect(status().isGone());
    }

    private static UsernamePasswordAuthenticationToken identity(long id) {
        var auth = UsernamePasswordAuthenticationToken.authenticated("customer", null, List.of());
        auth.setDetails(new UserAuthenticationDetails(new MockHttpServletRequest(), id));
        return auth;
    }
}

package com.banking.accountsservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class StatementAuthenticationTest {
    private static final String SIGNING_KEY = "synthetic-accounts-service-test-signing-key";
    private static final String DOWNLOAD = "/api/accounts/42/statement-jobs/item/download";
    private final JwtTokenUtil tokens = new JwtTokenUtil();
    private final JwtRequestFilter filter = new JwtRequestFilter();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(tokens, "jwtSecret", SIGNING_KEY);
        ReflectionTestUtils.setField(filter, "jwtTokenUtil", tokens);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void suppliesTheSignedCustomerIdentityForBothSupportedClaimTypes() throws Exception {
        for (Object customerId : new Object[] {7L, "7"}) {
            var request = new MockHttpServletRequest("GET", DOWNLOAD);
            request.addHeader("Authorization", "Bearer " + signed(customerId, Instant.now().plusSeconds(60), SIGNING_KEY));
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(auth);
            assertEquals("customer", auth.getName());
            assertEquals(7L, ((UserAuthenticationDetails) auth.getDetails()).getUserId());
            assertEquals("ROLE_USER", auth.getAuthorities().iterator().next().getAuthority());
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void expiredOrMissingCredentialsCannotEstablishOwnership() throws Exception {
        var expired = new MockHttpServletRequest("GET", DOWNLOAD);
        expired.addHeader("Authorization", "Bearer " + signed("7", Instant.now().minusSeconds(60), SIGNING_KEY));
        filter.doFilter(expired, new MockHttpServletResponse(), new MockFilterChain());
        assertNull(SecurityContextHolder.getContext().getAuthentication());

        var missing = new MockHttpServletRequest("GET", DOWNLOAD);
        filter.doFilter(missing, new MockHttpServletResponse(), new MockFilterChain());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        var response = new MockHttpServletResponse();
        new JwtAuthenticationEntryPoint().commence(missing, response, new BadCredentialsException("Authentication required"));
        assertEquals(401, response.getStatus());
        assertEquals(DOWNLOAD, new ObjectMapper().readTree(response.getContentAsString()).get("path").asText());

        assertFalse(tokens.validateToken("malformed"));
        assertFalse(tokens.validateToken(""));
        assertFalse(tokens.validateToken(signed(7, Instant.now().plusSeconds(60), SIGNING_KEY + "-other")));
    }

    private static String signed(Object customerId, Instant expiration, String key) {
        return Jwts.builder().setSubject("customer").claim("userId", customerId).claim("roles", "USER")
                .setExpiration(Date.from(expiration))
                .signWith(Keys.hmacShaKeyFor(key.getBytes(StandardCharsets.UTF_8))).compact();
    }
}

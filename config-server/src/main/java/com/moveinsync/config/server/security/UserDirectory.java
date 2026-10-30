package com.moveinsync.config.server.security;

import com.moveinsync.config.server.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Principals configured under {@code app.security.users}. A real deployment would delegate to the
 * company identity provider; the token format and authorization rules would stay the same.
 *
 * <p>No credential lives in the repository: each user's password is supplied at runtime (environment
 * variable or the git-ignored {@code .env}) and hashed here. A user without one is disabled.
 */
@Component
public class UserDirectory {

    private static final Logger log = LoggerFactory.getLogger(UserDirectory.class);
    static final int MIN_PASSWORD_LENGTH = 12;

    private record Account(AppProperties.User user, String hash) {
    }

    private final Map<String, Account> users = new HashMap<>();
    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    // Checked when the username is unknown so both paths cost one hash comparison.
    private final String dummyHash = encoder.encode("timing-equaliser");

    public UserDirectory(AppProperties props) {
        for (AppProperties.User u : props.security().users()) {
            String hash = hashFor(u);
            if (hash == null) {
                log.warn("user '{}' is disabled: no password configured (or shorter than {} characters)",
                        u.username(), MIN_PASSWORD_LENGTH);
                continue;
            }
            users.put(u.username(), new Account(u, hash));
        }
        if (users.isEmpty()) {
            log.warn("no users are enabled; run scripts/init-env.sh or set the DEMO_*_PASSWORD variables");
        }
    }

    private String hashFor(AppProperties.User u) {
        if (u.passwordHash() != null && !u.passwordHash().isBlank()) {
            return u.passwordHash();
        }
        if (u.password() != null && u.password().length() >= MIN_PASSWORD_LENGTH) {
            return encoder.encode(u.password());
        }
        return null;
    }

    public Optional<AuthUser> authenticate(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }
        Account a = users.get(username);
        boolean ok = encoder.matches(password, a == null ? dummyHash : a.hash());
        if (a == null || !ok) {
            return Optional.empty();
        }
        AppProperties.User u = a.user();
        return Optional.of(new AuthUser(u.username(), u.role(), u.tenant(), u.tenants()));
    }
}

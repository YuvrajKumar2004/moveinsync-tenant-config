package com.moveinsync.config.server.security;

import com.moveinsync.config.server.config.AppProperties;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Principals configured under {@code app.security.users}. A real deployment would delegate to the
 * company identity provider; the token format and authorization rules would stay the same.
 */
@Component
public class UserDirectory {

    private final Map<String, AppProperties.User> users;
    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    // Checked when the username is unknown so both paths cost one hash comparison.
    private final String dummyHash = encoder.encode("timing-equaliser");

    public UserDirectory(AppProperties props) {
        this.users = props.security().users().stream()
                .collect(Collectors.toUnmodifiableMap(AppProperties.User::username, Function.identity()));
    }

    public Optional<AuthUser> authenticate(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }
        AppProperties.User u = users.get(username);
        boolean ok = encoder.matches(password, u == null ? dummyHash : u.passwordHash());
        if (u == null || !ok) {
            return Optional.empty();
        }
        return Optional.of(new AuthUser(u.username(), u.role(), u.tenant(), u.tenants()));
    }
}

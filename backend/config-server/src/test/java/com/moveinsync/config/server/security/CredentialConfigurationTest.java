package com.moveinsync.config.server.security;

import com.moveinsync.config.server.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** No credential or signing key is shipped: users need a runtime password, the server needs a runtime key. */
class CredentialConfigurationTest {

    private static AppProperties props(String secret, List<AppProperties.User> users) {
        return new AppProperties(new AppProperties.Security(secret, Duration.ofHours(1), users), null, null, null);
    }

    private static AppProperties.User user(String name, String password) {
        return new AppProperties.User(name, password, null, Role.OPERATOR, null, Set.of());
    }

    @Test
    void usersWithoutARuntimePasswordAreDisabled() {
        String good = "pw-" + UUID.randomUUID();
        UserDirectory dir = new UserDirectory(props(null, List.of(
                user("enabled", good), user("missing", null), user("blank", ""), user("tooShort", "short-pw"))));
        assertThat(dir.authenticate("enabled", good)).isPresent();
        assertThat(dir.authenticate("enabled", "wrong-password-123")).isEmpty();
        assertThat(dir.authenticate("missing", "")).isEmpty();
        assertThat(dir.authenticate("blank", "")).isEmpty();
        assertThat(dir.authenticate("tooShort", "short-pw")).isEmpty();
    }

    @Test
    void serverRefusesToStartWithoutASigningKey() {
        assertThatThrownBy(() -> new TokenService(props(null, List.of()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TokenService(props("", List.of()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TokenService(props("too-short", List.of()))).isInstanceOf(IllegalStateException.class);
        new TokenService(props(UUID.randomUUID() + "-" + UUID.randomUUID(), List.of()));
    }

    @Test
    void shippedConfigurationContainsNoPasswordsHashesOrKeyDefaults() throws IOException {
        for (String file : List.of("/application.yml", "/application-local.yml", "/application-demo.yml")) {
            String yml;
            try (InputStream in = getClass().getResourceAsStream(file)) {
                assertThat(in).as(file).isNotNull();
                yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertThat(yml).as(file).doesNotContain("{bcrypt}").doesNotContain("demo-pass")
                    .doesNotContainPattern("jwt-secret: \\$\\{JWT_SECRET:[^}]");
            assertThat(yml).as(file).doesNotContainPattern("password: \\$\\{[A-Z_]+:[^}]"); // no default after the colon
        }
    }
}

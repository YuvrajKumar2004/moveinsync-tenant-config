package com.moveinsync.config.server.config;

import com.moveinsync.config.server.security.Role;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Set;

@ConfigurationProperties("app")
public record AppProperties(Security security, Sync sync, Bootstrap bootstrap, Faults faults) {

    /**
     * @param users demo/bootstrap principals; passwordHash uses Spring's {id}hash format, e.g. {bcrypt}...
     */
    public record Security(String jwtSecret, Duration tokenTtl, List<User> users) {
        public Security {
            users = users == null ? List.of() : users;
            tokenTtl = tokenTtl == null ? Duration.ofHours(1) : tokenTtl;
        }
    }

    /**
     * @param tenant  the client id for ADMIN/EMPLOYEE
     * @param tenants client ids a SERVICE may read ("*" = all)
     */
    public record User(String username, String passwordHash, Role role, String tenant, Set<String> tenants) {
        public User {
            tenants = tenants == null ? Set.of() : tenants;
        }
    }

    public record Sync(int retainedEvents, Duration heartbeatInterval, int maxSubscribers,
                       int maxConcurrentFullSnapshots, int snapshotCacheEntries) {
    }

    public record Bootstrap(boolean demoCatalog) {
    }

    public record Faults(boolean enabled) {
    }
}

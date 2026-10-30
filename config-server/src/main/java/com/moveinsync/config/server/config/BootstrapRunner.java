package com.moveinsync.config.server.config;

import com.moveinsync.config.core.DemoCatalog;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.server.security.AuthUser;
import com.moveinsync.config.server.security.Role;
import com.moveinsync.config.server.service.ChangeSetService;
import com.moveinsync.config.server.service.ConfigStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Set;

/** On an empty database, commits the case-study setting definitions as version 1. */
@Component
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final AppProperties props;
    private final ConfigStateService state;
    private final ChangeSetService changes;

    public BootstrapRunner(AppProperties props, ConfigStateService state, ChangeSetService changes) {
        this.props = props;
        this.state = state;
        this.changes = changes;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (props.bootstrap() != null && props.bootstrap().demoCatalog() && state.version() == 0) {
            AuthUser system = new AuthUser("system", Role.OPERATOR, null, Set.of());
            var r = changes.commit(new ChangeSetService.CommitRequest(ChangeKind.BOOTSTRAP, system,
                    "bootstrap case-study setting catalog", DemoCatalog.bootstrapOps(), "bootstrap", 0L));
            log.info("bootstrapped setting catalog at version {}", r.version());
        }
    }
}

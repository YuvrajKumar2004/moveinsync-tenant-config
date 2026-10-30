package com.moveinsync.config.server.support;

import com.moveinsync.config.server.ConfigServerApplication;
import com.moveinsync.config.server.service.FaultInjector;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts the real server (Tomcat + database) in-process on a fixed port so a test can kill it and start
 * it again on the same address with the same database, exactly like a crash and restart.
 */
public final class ServerHarness implements AutoCloseable {

    public static final String JWT_SECRET = "REDACTED";

    private final int port;
    private final TestDatabase.Db db;
    private final List<String> extraArgs;
    private ConfigurableApplicationContext context;

    public ServerHarness(String... extraArgs) {
        this.port = freePort();
        this.db = TestDatabase.fresh();
        this.extraArgs = List.of(extraArgs);
    }

    public ServerHarness start() {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=" + port,
                "--spring.profiles.active=demo",
                "--spring.datasource.url=" + db.url(),
                "--spring.datasource.username=" + db.user(),
                "--spring.datasource.password=" + db.password(),
                "--app.security.jwt-secret=" + JWT_SECRET,
                "--app.faults.enabled=true",
                "--app.sync.heartbeat-interval=PT2S",
                "--logging.structured.format.console=",
                "--logging.level.root=WARN",
                "--spring.main.banner-mode=off"));
        args.addAll(extraArgs);
        context = new SpringApplicationBuilder(ConfigServerApplication.class).run(args.toArray(String[]::new));
        return this;
    }

    /** Abrupt stop: closes Tomcat (dropping every open stream) and the application context. */
    public void kill() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    public ServerHarness restart() {
        kill();
        return start();
    }

    public boolean running() {
        return context != null;
    }

    public String baseUrl() {
        return "http://localhost:" + port;
    }

    public <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    public void faults(boolean drop, boolean duplicate, boolean outOfOrder) {
        bean(FaultInjector.class).set(new FaultInjector.Faults(drop, duplicate, outOfOrder));
    }

    @Override
    public void close() {
        kill();
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.moveinsync.config.sim;

import com.moveinsync.config.client.ConfigClient;

import java.time.Duration;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Command line:
 * <pre>
 *   java -jar load-simulator-all.jar propagation --url http://localhost:8080 --clients 200 --rounds 10
 *   java -jar load-simulator-all.jar load        --url http://localhost:8080 --threads 32 --seconds 20
 *   java -jar load-simulator-all.jar writes      --url http://localhost:8080 --threads 8  --seconds 10
 *   java -jar load-simulator-all.jar watch       --url http://localhost:8080 --clients 50
 * </pre>
 * Credentials: --service-password / --operator-password, or DEMO_SERVICE_PASSWORD / DEMO_OPERATOR_PASSWORD
 * from the environment (load .env first).
 * "watch" keeps a fleet connected and prints version, connected streams and the booking cut-off every
 * second: kill and restart the server while it runs to see reads continue and the fleet catch up.
 */
public final class SimulatorMain {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: (propagation|load|writes|watch) --url URL [--clients N] [--rounds N] [--threads N] [--seconds N]");
            return;
        }
        Map<String, String> opt = new HashMap<>();
        for (int i = 1; i + 1 < args.length; i += 2) {
            opt.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        String url = opt.getOrDefault("url", "http://localhost:8080");
        String user = opt.getOrDefault("service-user", "booking-service");
        String pass = secret(opt, "service-password", "DEMO_SERVICE_PASSWORD");
        String opUser = opt.getOrDefault("operator-user", "operator");
        String opPass = secret(opt, "operator-password", "DEMO_OPERATOR_PASSWORD");
        AdminApi service = new AdminApi(url, user, pass);

        switch (args[0]) {
            case "propagation" -> {
                int n = Integer.parseInt(opt.getOrDefault("clients", "200"));
                int rounds = Integer.parseInt(opt.getOrDefault("rounds", "10"));
                AdminApi admin = new AdminApi(url, opUser, opPass);
                try (Fleet fleet = new Fleet(url, service.token(), Fleet.Options.defaults(n)).start()) {
                    Thread.sleep(2000); // let every stream connect
                    System.out.println("connected streams: " + fleet.connectedStreams() + "/" + n);
                    System.out.println(Experiments.propagation(fleet, admin, rounds, Duration.ofSeconds(10)).describe());
                }
            }
            case "load" -> {
                int threads = Integer.parseInt(opt.getOrDefault("threads", "32"));
                int secs = Integer.parseInt(opt.getOrDefault("seconds", "20"));
                Experiments.httpLoad("warmup", threads, Duration.ofSeconds(3), Experiments.resolveRequests(url, service.token()));
                System.out.println(Experiments.httpLoad("GET /api/v1/resolve", threads, Duration.ofSeconds(secs),
                        Experiments.resolveRequests(url, service.token())).describe());
            }
            case "writes" -> {
                int threads = Integer.parseInt(opt.getOrDefault("threads", "8"));
                int secs = Integer.parseInt(opt.getOrDefault("seconds", "10"));
                AdminApi admin = new AdminApi(url, opUser, opPass);
                System.out.println(Experiments.httpLoad("POST /api/v1/change-sets", threads, Duration.ofSeconds(secs),
                        Experiments.writeRequests(url, admin.token())).describe());
            }
            case "watch" -> {
                int n = Integer.parseInt(opt.getOrDefault("clients", "50"));
                try (Fleet fleet = new Fleet(url, service.token(), Fleet.Options.defaults(n)).start()) {
                    ConfigClient probe = fleet.clients().get(0);
                    while (true) {
                        long min = fleet.clients().stream().mapToLong(ConfigClient::version).min().orElse(0);
                        System.out.printf("%s version(min/probe)=%d/%d streams=%d/%d blr-1 cutoff=%s staleness=%dms%n",
                                LocalTime.now().withNano(0), min, probe.version(), fleet.connectedStreams(), n,
                                probe.view("acme", "blr-1", null).raw("booking.cutoff"), probe.metrics().stalenessMillis());
                        Thread.sleep(1000);
                    }
                }
            }
            default -> System.out.println("unknown mode " + args[0]);
        }
    }

    /** Credentials come from a flag or the environment (.env), never from a built-in default. */
    private static String secret(Map<String, String> opt, String flag, String envVar) {
        String v = opt.getOrDefault(flag, System.getenv(envVar));
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("set --" + flag + " or the " + envVar + " environment variable "
                    + "(e.g. `set -a; . ./.env; set +a`)");
        }
        return v;
    }
}

package com.moveinsync.config.server.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Appends measured numbers to target/measurements.txt so the README can quote real runs. */
public final class Measurements {

    private Measurements() {
    }

    public static void record(String line) {
        String entry = Instant.now() + " " + line;
        System.out.println("[measurement] " + entry);
        try {
            Path f = Path.of("target", "measurements.txt");
            Files.createDirectories(f.getParent());
            Files.writeString(f, entry + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // measurement output is best effort
        }
    }
}

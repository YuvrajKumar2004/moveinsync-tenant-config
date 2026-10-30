package com.moveinsync.config.client;

import com.moveinsync.config.client.transport.HttpConfigTransport;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.core.wire.Wire;
import com.moveinsync.config.core.wire.WireCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Last good snapshot on local disk, so a service can start (and keep serving) while the server is
 * down. Written to a temp file and atomically renamed, with a SHA-256 over the history id and payload:
 * a torn, truncated or edited file is detected and ignored rather than loaded. The history id records
 * which server change log the snapshot belongs to.
 */
public final class SnapshotStore {

    static final int FORMAT = 2;
    private static final int LEGACY_FORMAT = 1; // no history id; still readable

    private final Path file;

    /** A snapshot read from disk and the server history it came from (null for legacy files). */
    public record Loaded(Snapshot snapshot, String historyId) {
    }

    public SnapshotStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public void save(Snapshot snapshot, String historyId) throws IOException {
        Wire.SnapshotData data = WireCodec.toWire(snapshot);
        byte[] payload = HttpConfigTransport.JSON.writeValueAsBytes(data);
        Wire.DiskFile disk = new Wire.DiskFile(FORMAT, sha256(historyId, payload), historyId, data);
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, HttpConfigTransport.JSON.writeValueAsBytes(disk));
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Empty when there is no file. Throws {@link CorruptSnapshotException} when the file fails verification. */
    public Optional<Loaded> load() throws IOException {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Wire.DiskFile disk;
        try {
            disk = HttpConfigTransport.JSON.readValue(file.toFile(), Wire.DiskFile.class);
        } catch (IOException e) {
            throw new CorruptSnapshotException("snapshot file is not readable JSON");
        }
        if (disk == null || (disk.format() != FORMAT && disk.format() != LEGACY_FORMAT)
                || disk.snapshot() == null || disk.sha256() == null) {
            throw new CorruptSnapshotException("snapshot file has an unknown format");
        }
        String historyId = disk.format() == LEGACY_FORMAT ? null : disk.historyId();
        byte[] payload = HttpConfigTransport.JSON.writeValueAsBytes(disk.snapshot());
        String expected = disk.format() == LEGACY_FORMAT ? sha256(payload) : sha256(historyId, payload);
        if (!expected.equals(disk.sha256())) {
            throw new CorruptSnapshotException("snapshot checksum mismatch");
        }
        try {
            return Optional.of(new Loaded(WireCodec.fromWire(disk.snapshot()), historyId));
        } catch (RuntimeException e) {
            throw new CorruptSnapshotException("snapshot content is invalid");
        }
    }

    static String sha256(String historyId, byte[] payload) {
        byte[] prefix = ((historyId == null ? "" : historyId) + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[prefix.length + payload.length];
        System.arraycopy(prefix, 0, all, 0, prefix.length);
        System.arraycopy(payload, 0, all, prefix.length, payload.length);
        return sha256(all);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class CorruptSnapshotException extends IOException {
        public CorruptSnapshotException(String message) {
            super(message);
        }
    }
}

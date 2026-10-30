package com.moveinsync.config.client;

import com.moveinsync.config.client.transport.HttpConfigTransport;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.core.wire.Wire;
import com.moveinsync.config.core.wire.WireCodec;

import java.io.IOException;
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
 * down. Written to a temp file and atomically renamed, with a SHA-256 over the payload: a torn,
 * truncated or edited file is detected and ignored rather than loaded.
 */
public final class SnapshotStore {

    static final int FORMAT = 1;

    private final Path file;

    public SnapshotStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public void save(Snapshot snapshot) throws IOException {
        Wire.SnapshotData data = WireCodec.toWire(snapshot);
        byte[] payload = HttpConfigTransport.JSON.writeValueAsBytes(data);
        Wire.DiskFile disk = new Wire.DiskFile(FORMAT, sha256(payload), data);
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
    public Optional<Snapshot> load() throws IOException {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Wire.DiskFile disk;
        try {
            disk = HttpConfigTransport.JSON.readValue(file.toFile(), Wire.DiskFile.class);
        } catch (IOException e) {
            throw new CorruptSnapshotException("snapshot file is not readable JSON");
        }
        if (disk == null || disk.format() != FORMAT || disk.snapshot() == null || disk.sha256() == null) {
            throw new CorruptSnapshotException("snapshot file has an unknown format");
        }
        byte[] payload = HttpConfigTransport.JSON.writeValueAsBytes(disk.snapshot());
        if (!sha256(payload).equals(disk.sha256())) {
            throw new CorruptSnapshotException("snapshot checksum mismatch");
        }
        try {
            return Optional.of(WireCodec.fromWire(disk.snapshot()));
        } catch (RuntimeException e) {
            throw new CorruptSnapshotException("snapshot content is invalid");
        }
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

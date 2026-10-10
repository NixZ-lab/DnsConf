package com.novibe.dns.next_dns.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

public final class SyncFiles {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public static <T> T read(Path file, Class<T> type) {
        try { return JSON.fromJson(Files.readString(file), type); }
        catch (IOException | RuntimeException e) { throw new IllegalStateException("Cannot read " + file, e); }
    }
    public static void write(Path file, Object value) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "sync-", ".tmp");
            try {
                Files.writeString(temp, JSON.toJson(value));
                try { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")); }
                catch (UnsupportedOperationException ignored) { /* Non-POSIX filesystem. */ }
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        } catch (IOException e) { throw new IllegalStateException("Cannot persist " + file, e); }
    }
}

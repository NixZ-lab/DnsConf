package com.novibe.dns.next_dns.sync;

import java.nio.channels.*;
import java.nio.file.*;
import java.io.IOException;

/** Complements Actions' repository-wide concurrency guard for local invocations. */
public final class ProfileLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private ProfileLock(FileChannel channel, FileLock lock) {this.channel=channel;this.lock=lock;}
    public static ProfileLock acquire(Path dir) {
        FileChannel channel=null;
        try {
            Files.createDirectories(dir);
            channel=FileChannel.open(dir.resolve(".sync.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock lock=channel.tryLock();
            if (lock==null) throw new IllegalStateException("Profile synchronization already running");
            return new ProfileLock(channel,lock);
        } catch (IOException | RuntimeException error) {
            if (channel!=null) try {channel.close();} catch(IOException ignored) {}
            throw new IllegalStateException("Cannot acquire profile synchronization lock",error);
        }
    }
    @Override public void close() {
        try {lock.release();channel.close();} catch(IOException e) {throw new IllegalStateException("Cannot release profile lock",e);}
    }
}

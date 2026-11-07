package local.hxvia.chunkbackup;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.StampedLock;

/** The stamp may cross threads: capture starts on the server thread and ends on the worker. */
public final class DiskGate {
    private static final StampedLock LOCK = new StampedLock();

    private DiskGate() {}

    public static long beginWrite() { return LOCK.readLock(); }
    public static void endWrite(long stamp) { LOCK.unlockRead(stamp); }

    public static long beginCapture() throws IOException {
        try {
            long stamp = LOCK.tryWriteLock(2, TimeUnit.SECONDS);
            if (stamp == 0) throw new IOException("区块写入尚未完成，本次备份未开始");
            return stamp;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("等待区块保存时中断", exception);
        }
    }

    public static void endCapture(long stamp) { LOCK.unlockWrite(stamp); }
}

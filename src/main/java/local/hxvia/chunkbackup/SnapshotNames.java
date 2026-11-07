package local.hxvia.chunkbackup;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.regex.Pattern;

/** New names are readable on Windows and Linux; existing 1.0 snapshots remain valid. */
public final class SnapshotNames {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss_SSSxx").withZone(ZONE);
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZONE);
    private static final Pattern VALID = Pattern.compile("(?:[0-9]{8}T[0-9]{9}Z|[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}_[0-9]{3}\\+0800)-[0-9a-f]{8}");
    private SnapshotNames() {}
    public static boolean valid(String id) { return id != null && VALID.matcher(id).matches(); }
    public static String create(long time) { return FILE.format(Instant.ofEpochMilli(time)) + "-" + UUID.randomUUID().toString().substring(0, 8); }
    public static String display(long time) { return DISPLAY.format(Instant.ofEpochMilli(time)); }
}

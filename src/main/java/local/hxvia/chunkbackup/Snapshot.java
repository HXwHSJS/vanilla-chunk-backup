package local.hxvia.chunkbackup;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Complete indices reference shared immutable objects; no snapshot depends on an older index. */
public final class Snapshot {
    public static final int SCHEMA = 1;
    public String id;
    public String worldId;
    public String worldDirectory;
    public String minecraft = "26.3";
    public String reason;
    public String label;
    public long createdAt;
    public long newObjects;
    public long newBytes;
    public long captureMillis;
    public final Map<String, String> dimensions = new TreeMap<>();
    public final Map<String, String> chunks = new TreeMap<>();
    public final Map<String, String> files = new TreeMap<>();
    public final Map<String, String> regionFingerprints = new TreeMap<>();

    public void write(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Io.temporary(target);
        try {
            try (var output = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary))))) {
                output.writeUTF("VANILLA_CHUNK_BACKUP"); output.writeInt(SCHEMA);
                output.writeUTF(id); output.writeUTF(worldId); output.writeUTF(worldDirectory); output.writeUTF(minecraft);
                output.writeUTF(reason); output.writeUTF(label); output.writeLong(createdAt);
                output.writeLong(newObjects); output.writeLong(newBytes); output.writeLong(captureMillis);
                writeMap(output, dimensions); writeMap(output, chunks); writeMap(output, files); writeMap(output, regionFingerprints);
            }
            Io.force(temporary); Io.atomicMove(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
    }

    public static Snapshot read(Path source) throws IOException {
        return read(source, false);
    }

    public static Snapshot header(Path source) throws IOException {
        return read(source, true);
    }

    private static Snapshot read(Path source, boolean headerOnly) throws IOException {
        try (var input = new DataInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(source))))) {
            if (!input.readUTF().equals("VANILLA_CHUNK_BACKUP") || input.readInt() != SCHEMA)
                throw new IOException("不支持的备份格式: " + source);
            Snapshot snapshot = new Snapshot();
            snapshot.id = input.readUTF(); snapshot.worldId = input.readUTF(); snapshot.worldDirectory = input.readUTF();
            snapshot.minecraft = input.readUTF(); snapshot.reason = input.readUTF(); snapshot.label = input.readUTF();
            snapshot.createdAt = input.readLong(); snapshot.newObjects = input.readLong();
            snapshot.newBytes = input.readLong(); snapshot.captureMillis = input.readLong();
            if (headerOnly) return snapshot;
            readMap(input, snapshot.dimensions); readMap(input, snapshot.chunks);
            readMap(input, snapshot.files); readMap(input, snapshot.regionFingerprints);
            if (input.read() != -1) throw new IOException("备份索引存在额外数据");
            return snapshot;
        }
    }

    private static void writeMap(DataOutputStream output, Map<String, String> map) throws IOException {
        output.writeInt(map.size());
        for (var entry : map.entrySet()) { output.writeUTF(entry.getKey()); output.writeUTF(entry.getValue()); }
    }

    private static void readMap(DataInputStream input, Map<String, String> map) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > 20_000_000) throw new IOException("备份索引数量异常");
        for (int i = 0; i < count; i++) {
            if (map.put(input.readUTF(), input.readUTF()) != null) throw new IOException("备份索引键重复");
        }
    }
}

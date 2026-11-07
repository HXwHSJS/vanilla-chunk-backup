package local.hxvia.chunkbackup;

import java.io.IOException;
import java.nio.file.Path;

public record ChunkKey(String storage, int x, int z) implements Comparable<ChunkKey> {
    public ChunkKey {
        if (storage.isBlank() || storage.contains("|") || Path.of(storage).isAbsolute())
            throw new IllegalArgumentException("Invalid chunk storage directory");
    }

    public String encoded() { return storage + "|" + x + "|" + z; }

    public static ChunkKey parse(String value) throws IOException {
        String[] parts = value.split("\\|", -1);
        if (parts.length != 3) throw new IOException("Invalid chunk key: " + value);
        try { return new ChunkKey(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2])); }
        catch (IllegalArgumentException exception) { throw new IOException("Invalid chunk key", exception); }
    }

    public String regionFile() {
        return storage + "/r." + Math.floorDiv(x, 32) + "." + Math.floorDiv(z, 32) + ".mca";
    }

    @Override public int compareTo(ChunkKey other) { return encoded().compareTo(other.encoded()); }
}

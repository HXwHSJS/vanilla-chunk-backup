package local.hxvia.chunkbackup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Reconstruct a full snapshot into a new directory, including its global/player files. */
public final class WorldExport {
    private WorldExport() {}

    public static void export(BackupStore store, Snapshot snapshot, Path destination) throws IOException {
        Path output = destination.toAbsolutePath().normalize(); Io.rejectLinks(output);
        if (Files.exists(output)) throw new IOException("导出目标必须是尚不存在的新目录");
        if (output.startsWith(store.root) || store.root.startsWith(output)) throw new IOException("导出目标不能与备份仓库重叠");
        Path stage = output.resolveSibling(".vcb-export-" + UUID.randomUUID());
        Io.directories(stage);
        try {
            for (var entry : snapshot.files.entrySet()) {
                store.verifyObject(entry.getValue());
                Io.copyDurable(store.objectPath(entry.getValue()), Io.within(stage, entry.getKey()));
            }
            Map<String, Map<Integer, String>> regions = new TreeMap<>();
            for (var entry : snapshot.chunks.entrySet()) {
                ChunkKey key = ChunkKey.parse(entry.getKey());
                regions.computeIfAbsent(key.regionFile(), ignored -> new TreeMap<>())
                        .put(Math.floorMod(key.x(), 32) + 32 * Math.floorMod(key.z(), 32), entry.getValue());
            }
            for (var region : regions.entrySet()) {
                Path target = Io.within(stage, region.getKey());
                if (Files.exists(target)) throw new IOException("快照文件与区块索引冲突: " + region.getKey());
                Map<Integer, byte[]> changes = new HashMap<>();
                for (var chunk : region.getValue().entrySet()) changes.put(chunk.getKey(), store.get(chunk.getValue()));
                // The input does not yet exist; rewrite creates a new native MCA plus any MCC files.
                RegionIo.rewrite(target, target, changes);
            }
            try (var paths = Files.walk(stage)) {
                for (Path directory : paths.filter(Files::isDirectory).sorted(java.util.Comparator.reverseOrder()).toList()) Io.forceDirectory(directory);
            }
            if (Files.exists(output)) throw new IOException("导出目标在处理期间已出现，拒绝覆盖");
            Files.move(stage, output, StandardCopyOption.ATOMIC_MOVE); Io.forceDirectory(output.getParent());
        } catch (IOException | RuntimeException exception) {
            throw new IOException("整档导出失败；活动世界未修改，暂存目录: " + stage, exception);
        }
    }
}

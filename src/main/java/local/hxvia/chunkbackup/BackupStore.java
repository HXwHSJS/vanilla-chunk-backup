package local.hxvia.chunkbackup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public final class BackupStore {
    public static final String WORLD_MARKER = ".vanilla-chunk-backup-id";
    public final Path world;
    public final Path root;
    public final String worldId;

    public BackupStore(Path world, Path root, String worldId) throws IOException {
        this.world = world.toAbsolutePath().normalize(); this.root = root.toAbsolutePath().normalize(); this.worldId = worldId;
        if (this.root.startsWith(this.world) || this.world.startsWith(this.root)) throw new IOException("备份目录不能与世界目录重叠");
        Io.rejectLinks(this.world); Io.rejectLinks(this.root);
        Io.directories(this.root.resolve("objects")); Io.directories(this.root.resolve("snapshots"));
    }

    public static String worldId(Path world) throws IOException {
        Path marker = world.resolve(WORLD_MARKER);
        if (!Files.exists(marker)) Io.atomicWrite(marker, (UUID.randomUUID() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String id = Files.readString(marker).trim();
        try { return UUID.fromString(id).toString(); }
        catch (IllegalArgumentException exception) { throw new IOException("世界备份身份标记无效", exception); }
    }

    public RepoLock lock() throws IOException { return new RepoLock(root.resolve("repository.lock")); }

    public static final class RepoLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        RepoLock(Path path) throws IOException {
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock obtained;
            try { obtained = channel.tryLock(); }
            catch (RuntimeException | IOException exception) { channel.close(); throw exception; }
            if (obtained == null) { channel.close(); throw new IOException("备份仓库正在使用中"); }
            lock = obtained;
        }
        @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
    }

    public Path objectPath(String hash) throws IOException {
        if (!hash.matches("[0-9a-f]{64}")) throw new IOException("备份对象哈希无效");
        return Io.within(root, "objects/" + hash.substring(0, 2) + "/" + hash);
    }

    public byte[] get(String hash) throws IOException {
        Path path = objectPath(hash);
        if (Files.size(path) > RegionIo.MAX_RECORD_BYTES) throw new IOException("区块对象过大");
        byte[] bytes = Files.readAllBytes(path);
        if (!Io.hash(bytes).equals(hash)) throw new IOException("备份对象完整性检查失败: " + hash);
        return bytes;
    }

    public void verifyObject(String hash) throws IOException {
        if (!Io.hash(objectPath(hash)).equals(hash)) throw new IOException("备份对象完整性检查失败: " + hash);
    }

    private String put(byte[] data, Capture capture) throws IOException {
        String hash = Io.hash(data); Path target = objectPath(hash);
        if (!Files.exists(target)) {
            capture.reserveSpace(data.length);
            Files.createDirectories(target.getParent()); Path temporary = Io.temporary(target);
            try {
                Files.write(temporary, data, StandardOpenOption.CREATE_NEW);
                // Objects are synchronized after releasing the world write gate, before publishing the index.
                Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                capture.snapshot.newObjects++; capture.snapshot.newBytes += data.length;
            } finally { Files.deleteIfExists(temporary); }
        }
        capture.touched.add(hash); return hash;
    }

    private String putFile(Path file, Capture capture) throws IOException {
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class);
        capture.reserveSpace(before.size());
        Path temporary = root.resolve("objects").resolve("." + UUID.randomUUID() + ".tmp");
        var digest = Io.digest();
        try {
            try (InputStream input = Files.newInputStream(file); var output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[131072];
                for (int length; (length = input.read(buffer)) != -1;) { digest.update(buffer, 0, length); output.write(buffer, 0, length); }
            }
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);
            if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()))
                throw new IOException("世界附属文件在捕获时改变: " + file.getFileName());
            String hash = HexFormat.of().formatHex(digest.digest()); Path target = objectPath(hash);
            if (!Files.exists(target)) {
                Files.createDirectories(target.getParent()); Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                capture.snapshot.newObjects++; capture.snapshot.newBytes += after.size();
            }
            capture.touched.add(hash); return hash;
        } finally { Files.deleteIfExists(temporary); }
    }

    public Path snapshotPath(String id) throws IOException {
        if (!SnapshotNames.valid(id)) throw new IOException("快照编号无效");
        return root.resolve("snapshots").resolve(id + ".vcb.gz");
    }

    public Snapshot load(String id) throws IOException {
        Snapshot snapshot = Snapshot.read(snapshotPath(id));
        if (!snapshot.id.equals(id) || !snapshot.worldId.equals(worldId)) throw new IOException("快照与本世界不匹配");
        return snapshot;
    }

    public List<String> ids() throws IOException {
        try (var files = Files.list(root.resolve("snapshots"))) {
            return files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".vcb.gz"))
                    .map(name -> name.substring(0, name.length() - 7)).peek(id -> {
                        if (!SnapshotNames.valid(id)) throw new IllegalStateException("快照文件名无效: " + id);
                    }).sorted().toList();
        }
    }

    /** Listing reads only headers, never 24 full chunk indices at once. */
    public List<Snapshot> list() throws IOException {
        List<Snapshot> result = new ArrayList<>();
            for (String id : ids()) {
                Snapshot snapshot = Snapshot.header(snapshotPath(id));
                if (!snapshot.worldId.equals(worldId)) throw new IOException("仓库中存在其他世界的索引");
                result.add(snapshot);
            }
        result.sort(Comparator.comparingLong(snapshot -> snapshot.createdAt)); return result;
    }

    /** Called on the server thread after a flush and acquisition of DiskGate's exclusive stamp. */
    public Capture beginCapture(String worldDirectory, Map<String, String> dimensions, String reason, String label,
                                long minimumFreeBytes, int captureTimeoutSeconds) throws IOException {
        if (Files.getFileStore(root).getUsableSpace() < minimumFreeBytes)
            throw new IOException("备份磁盘可用空间低于预留值");
        List<Snapshot> previous = list();
        Snapshot last = previous.isEmpty() ? null : load(previous.getLast().id);
        Snapshot snapshot = new Snapshot();
        snapshot.createdAt = System.currentTimeMillis();
        snapshot.id = SnapshotNames.create(snapshot.createdAt);
        snapshot.worldId = worldId; snapshot.worldDirectory = worldDirectory;
        snapshot.reason = reason; snapshot.label = label; snapshot.dimensions.putAll(dimensions);
        Capture capture = new Capture(snapshot, last, captureTimeoutSeconds, minimumFreeBytes);
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(world, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attributes) throws IOException {
                if (!folder.equals(world) && Set.of(".git", ".cache").contains(folder.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink()) throw new IOException("世界目录包含符号链接，未创建快照: " + file);
                String name = file.getFileName().toString();
                if (attributes.isRegularFile() && !name.endsWith(".lock") && !name.endsWith(".tmp") && !name.endsWith(".part")) files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(Path::toString));
        Map<String, StringBuilder> externalStamps = new HashMap<>();
        for (Path file : files) {
            capture.checkDeadline();
            if (RegionIo.isRegion(file)) capture.regions.add(file);
            else if (RegionIo.isExternal(file)) {
                String[] coords = file.getFileName().toString().split("\\.");
                String region = Io.relative(world, file.getParent()) + "/r." + Math.floorDiv(Integer.parseInt(coords[1]), 32)
                        + "." + Math.floorDiv(Integer.parseInt(coords[2]), 32) + ".mca";
                externalStamps.computeIfAbsent(region, ignored -> new StringBuilder()).append(file.getFileName()).append(':').append(stamp(file)).append(';');
            } else snapshot.files.put(Io.relative(world, file), putFile(file, capture));
        }
        for (Path region : capture.regions) {
            String relative = Io.relative(world, region);
            snapshot.regionFingerprints.put(relative, stamp(region) + ":" + externalStamps.getOrDefault(relative, new StringBuilder()));
        }
        return capture;
    }

    private static String stamp(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        return attributes.size() + ":" + attributes.lastModifiedTime().toInstant() + ":" + attributes.fileKey();
    }

    public final class Capture {
        public final Snapshot snapshot;
        private final Snapshot last;
        private final Map<String, Map<String, String>> previousByRegion = new HashMap<>();
        private final List<Path> regions = new ArrayList<>();
        private final Set<String> touched = new HashSet<>();
        private final long started = System.nanoTime();
        private final long deadline;
        private final long minimumFreeBytes;
        private long estimatedFree;
        private long refreshBudget;
        public boolean published;

        Capture(Snapshot snapshot, Snapshot last, int timeoutSeconds, long minimumFreeBytes) throws IOException {
            this.snapshot = snapshot; this.last = last; deadline = started + timeoutSeconds * 1_000_000_000L;
            this.minimumFreeBytes = minimumFreeBytes;
            if (last != null) for (var entry : last.chunks.entrySet()) {
                ChunkKey key = ChunkKey.parse(entry.getKey());
                previousByRegion.computeIfAbsent(key.regionFile(), ignored -> new TreeMap<>()).put(entry.getKey(), entry.getValue());
            }
        }

        private void checkDeadline() throws IOException {
            if (System.nanoTime() > deadline) throw new IOException("捕获超过时限，本次快照未发布");
        }

        private void reserveSpace(long bytes) throws IOException {
            long estimatedAllocation = ((bytes + 4095) / 4096) * 4096 + 4096;
            if (refreshBudget <= 0 || estimatedFree - estimatedAllocation < minimumFreeBytes) {
                estimatedFree = Files.getFileStore(root).getUsableSpace(); refreshBudget = 32L * 1024 * 1024;
            }
            if (estimatedFree - estimatedAllocation < minimumFreeBytes) throw new IOException("写入将低于磁盘预留空间，本次捕获已停止");
            estimatedFree -= estimatedAllocation; refreshBudget -= estimatedAllocation;
        }

        public void captureRegions() throws IOException {
            for (Path region : regions) {
                checkDeadline(); String relative = Io.relative(world, region);
                if (last != null && snapshot.regionFingerprints.get(relative).equals(last.regionFingerprints.get(relative))) {
                    snapshot.chunks.putAll(previousByRegion.getOrDefault(relative, Map.of())); continue;
                }
                String storage = Io.relative(world, region.getParent());
                RegionIo.visit(region, (x, z, bytes) -> {
                    checkDeadline(); snapshot.chunks.put(new ChunkKey(storage, x, z).encoded(), put(bytes, this));
                });
            }
            snapshot.captureMillis = (System.nanoTime() - started) / 1_000_000;
        }

        /** Call only after releasing DiskGate. The manifest is published after every touched object is durable. */
        public void publish(int keepSnapshots, long minimumFreeBytes) throws IOException {
            if (Files.getFileStore(root).getUsableSpace() < minimumFreeBytes) throw new IOException("剩余空间低于预留值，快照未发布");
            Set<Path> directories = new HashSet<>();
            for (String hash : touched) { Path path = objectPath(hash); Io.force(path); directories.add(path.getParent()); }
            for (Path directory : directories) Io.forceDirectory(directory);
            Io.forceDirectory(root.resolve("objects"));
            snapshot.write(snapshotPath(snapshot.id));
            published = true;
            prune(keepSnapshots);
        }
    }

    public void prune(int keepSnapshots) throws IOException {
        if (keepSnapshots < 1) throw new IOException("至少保留一个快照");
        List<String> snapshots = list().stream().map(snapshot -> snapshot.id).toList();
        int firstRetained = Math.max(0, snapshots.size() - keepSnapshots);
        Set<String> referenced = new HashSet<>();
        // Read and validate all retained indices before removing any recovery point.
        for (int index = firstRetained; index < snapshots.size(); index++) {
            Snapshot snapshot = load(snapshots.get(index)); referenced.addAll(snapshot.chunks.values()); referenced.addAll(snapshot.files.values());
        }
        for (int index = 0; index < firstRetained; index++) Files.delete(snapshotPath(snapshots.get(index)));
        Io.forceDirectory(root.resolve("snapshots"));
        try (var paths = Files.walk(root.resolve("objects"))) {
            for (Path object : paths.filter(Io::regular).toList()) {
                String name = object.getFileName().toString();
                if ((name.matches("[0-9a-f]{64}") && !referenced.contains(name))
                        || name.matches("\\.(?:[0-9a-f]{64}\\.)?[0-9a-f-]{36}\\.tmp")) Files.delete(object);
            }
        }
    }

    public void verify(Snapshot snapshot) throws IOException {
        Set<String> hashes = new HashSet<>(snapshot.chunks.values()); hashes.addAll(snapshot.files.values());
        for (String hash : hashes) verifyObject(hash);
    }
}

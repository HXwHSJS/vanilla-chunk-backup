package local.hxvia.chunkbackup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;

/** Offline only. No Minecraft, Fabric, Gson, or other non-JDK classes in this class. */
public final class ColdRestore {
    public static final String REGISTRY = "config/vanilla_chunk_backup.world.properties";
    public static final String PENDING = "config/vanilla_chunk_backup.pending.properties";
    private ColdRestore() {}

    public static Properties readProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(path)) { properties.load(input); }
        return properties;
    }

    public static void writeProperties(Path path, Properties properties) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); properties.store(output, "Vanilla Chunk Backup");
        Io.atomicWrite(path, output.toByteArray());
    }

    public static Properties context(BackupStore store) {
        Properties result = new Properties(); result.setProperty("schema", "1");
        result.setProperty("world", store.world.toString()); result.setProperty("repository", store.root.toString());
        result.setProperty("worldId", store.worldId); return result;
    }

    public static BackupStore open(Properties context) throws IOException {
        if (!"1".equals(context.getProperty("schema"))) throw new IOException("不支持的回退计划格式");
        Path world = Path.of(required(context, "world")).toAbsolutePath().normalize();
        if (!Files.isDirectory(world)) throw new IOException("世界目录不存在: " + world);
        Path marker = world.resolve(BackupStore.WORLD_MARKER);
        if (!Files.isRegularFile(marker)) throw new IOException("世界缺少备份身份标记，拒绝恢复");
        String worldId = Files.readString(marker).trim();
        if (!UUID.fromString(worldId).toString().equals(required(context, "worldId"))) throw new IOException("世界身份不匹配");
        return new BackupStore(world, Path.of(required(context, "repository")), worldId);
    }

    public static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("缺少字段: " + key);
        return value;
    }

    public static final class WorldLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        public WorldLock(Path world) throws IOException {
            Io.rejectLinks(world.resolve("session.lock"));
            channel = FileChannel.open(world.resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock obtained;
            try { obtained = channel.tryLock(); }
            catch (RuntimeException | IOException exception) { channel.close(); throw new IOException("世界正被服务器使用，请先停服", exception); }
            if (obtained == null) { channel.close(); throw new IOException("世界正被服务器使用，请先停服"); }
            lock = obtained;
        }
        @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
    }

    public static String transactionId() { return "restore-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8); }

    private static Path transaction(BackupStore store, String id) throws IOException {
        if (!id.matches("restore-[0-9]{13}-[0-9a-f]{8}")) throw new IOException("回退事务编号无效");
        return Io.within(store.root, "transactions/" + id);
    }

    /** Caller holds both the world session lock and repository lock. */
    public static String restore(BackupStore store, Snapshot snapshot, Selection selection, String id) throws IOException {
        if (!snapshot.worldId.equals(store.worldId)) throw new IOException("快照世界不匹配");
        selection.validate(snapshot, 65_536);
        Path tx = transaction(store, id);
        if (Files.exists(tx)) {
            Properties old = readProperties(tx.resolve("transaction.properties"));
            if ("COMMITTED".equals(old.getProperty("state")) && snapshot.id.equals(old.getProperty("snapshot"))) return id;
            throw new IOException("事务已存在且未完成提交，先检查恢复日志: " + id);
        }
        Io.directories(tx.resolve("after")); Io.directories(tx.resolve("before"));
        Properties journal = context(store); journal.setProperty("id", id); journal.setProperty("snapshot", snapshot.id);
        journal.setProperty("selection", selection.toString()); journal.setProperty("state", "PREPARING");
        writeProperties(tx.resolve("transaction.properties"), journal);
        String dimension = snapshot.dimensions.get(selection.dimension());
        // Tree order makes staging and commit deterministic. Null values mean delete.
        Map<String, Path> operations = new TreeMap<>();
        for (String type : List.of("region", "entities", "poi")) {
            String storage = dimension.isEmpty() ? type : dimension + "/" + type;
            for (int rz = Math.floorDiv(selection.z1(), 32); rz <= Math.floorDiv(selection.z2(), 32); rz++) {
                for (int rx = Math.floorDiv(selection.x1(), 32); rx <= Math.floorDiv(selection.x2(), 32); rx++) {
                    String relative = storage + "/r." + rx + "." + rz + ".mca";
                    Path source = Io.within(store.world, relative);
                    Map<Integer, byte[]> changes = new LinkedHashMap<>();
                    boolean hasSnapshotData = false;
                    int minZ = Math.max(selection.z1(), rz * 32), maxZ = Math.min(selection.z2(), rz * 32 + 31);
                    int minX = Math.max(selection.x1(), rx * 32), maxX = Math.min(selection.x2(), rx * 32 + 31);
                    for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
                        String hash = snapshot.chunks.get(new ChunkKey(storage, x, z).encoded());
                        byte[] data = hash == null ? null : store.get(hash);
                        if (data != null) hasSnapshotData = true;
                        changes.put(Math.floorMod(x, 32) + 32 * Math.floorMod(z, 32), data);
                    }
                    // A never-generated area need not create empty MCA files.
                    if (!Files.exists(source) && !hasSnapshotData) continue;
                    Path staged = Io.within(tx.resolve("after"), relative);
                    RegionIo.Image image = RegionIo.rewrite(source, staged, changes);
                    operations.put(relative, image.region());
                    for (var external : image.externalWrites().entrySet()) operations.put(storage + "/" + external.getKey(), external.getValue());
                    for (String external : image.externalDeletes()) operations.put(storage + "/" + external, null);
                }
            }
        }
        journal.setProperty("count", Integer.toString(operations.size()));
        long bytesNeeded = 64L * 1024 * 1024;
        for (String path : operations.keySet()) {
            Path live = Io.within(store.world, path);
            if (Files.exists(live)) bytesNeeded += Files.size(live);
        }
        if (Files.getFileStore(tx).getUsableSpace() < bytesNeeded) throw new IOException("空间不足以保存回退前原件");
        int index = 0;
        for (var operation : operations.entrySet()) {
            String prefix = "file." + index++ + ".";
            String path = operation.getKey(); Path live = Io.within(store.world, path);
            journal.setProperty(prefix + "path", path);
            journal.setProperty(prefix + "existed", Boolean.toString(Files.exists(live)));
            if (Files.exists(live)) {
                Path before = Io.within(tx.resolve("before"), path); Io.copyDurable(live, before);
                journal.setProperty(prefix + "before", Io.hash(before));
            }
            journal.setProperty(prefix + "after", operation.getValue() == null ? "ABSENT" : Io.hash(operation.getValue()));
        }
        state(tx, journal, "PREPARED");
        try {
            state(tx, journal, "APPLYING");
            for (var operation : operations.entrySet()) {
                Path live = Io.within(store.world, operation.getKey());
                if (operation.getValue() == null) remove(live);
                else Io.copyDurable(operation.getValue(), live);
            }
            state(tx, journal, "COMMITTED");
        } catch (IOException | RuntimeException exception) {
            try { rollback(store, tx, journal, "ROLLED_BACK"); }
            catch (Exception recovery) { exception.addSuppressed(recovery); }
            throw exception;
        }
        return id;
    }

    private static void state(Path tx, Properties journal, String state) throws IOException {
        journal.setProperty("state", state); writeProperties(tx.resolve("transaction.properties"), journal);
    }

    private static void remove(Path path) throws IOException {
        if (Files.deleteIfExists(path)) Io.forceDirectory(path.getParent());
    }

    private static int count(Properties journal) throws IOException {
        try {
            int value = Integer.parseInt(required(journal, "count"));
            if (value < 0 || value > 300_000) throw new IOException("事务文件数量无效");
            return value;
        } catch (NumberFormatException exception) { throw new IOException("事务文件数量无效", exception); }
    }

    private static void validateJournal(BackupStore store, Properties journal) throws IOException {
        if (!store.worldId.equals(required(journal, "worldId")) || !store.world.toString().equals(required(journal, "world"))
                || !store.root.toString().equals(required(journal, "repository"))) throw new IOException("事务不属于此世界/仓库");
    }

    private static void rollback(BackupStore store, Path tx, Properties journal, String finalState) throws IOException {
        validateJournal(store, journal);
        // Verify every original before touching any live file.
        for (int i = 0; i < count(journal); i++) {
            String prefix = "file." + i + "."; String path = required(journal, prefix + "path");
            if (Boolean.parseBoolean(required(journal, prefix + "existed"))) {
                if (!Io.hash(Io.within(tx.resolve("before"), path)).equals(required(journal, prefix + "before")))
                    throw new IOException("回退前原件校验失败: " + path);
            }
        }
        journal.setProperty("recoveryFinalState", finalState); state(tx, journal, "RECOVERING");
        for (int i = 0; i < count(journal); i++) {
            String prefix = "file." + i + "."; String path = required(journal, prefix + "path");
            Path live = Io.within(store.world, path);
            if (Boolean.parseBoolean(required(journal, prefix + "existed"))) Io.copyDurable(Io.within(tx.resolve("before"), path), live);
            else remove(live);
        }
        state(tx, journal, finalState);
    }

    public static void recover(BackupStore store) throws IOException {
        Path transactions = store.root.resolve("transactions");
        if (!Files.exists(transactions)) return;
        try (var paths = Files.list(transactions)) {
            for (Path tx : paths.sorted().toList()) {
                if (!Files.isDirectory(tx)) continue;
                Io.rejectLinks(tx);
                Path journalPath = tx.resolve("transaction.properties");
                if (!Files.exists(journalPath)) continue; // No journal means staging never reached any world write.
                Properties journal = readProperties(journalPath);
                String state = required(journal, "state");
                switch (state) {
                    case "APPLYING" -> rollback(store, tx, journal, "ROLLED_BACK");
                    case "UNDOING" -> rollback(store, tx, journal, "UNDONE");
                    case "RECOVERING" -> rollback(store, tx, journal, required(journal, "recoveryFinalState"));
                    case "PREPARING", "PREPARED" -> state(tx, journal, "ABORTED");
                    case "COMMITTED", "ROLLED_BACK", "UNDONE", "ABORTED" -> { }
                    default -> throw new IOException("未知事务状态，拒绝启动: " + state);
                }
            }
        }
    }

    /** Undo refuses if the server has since changed any affected file. */
    public static void undo(BackupStore store, String id) throws IOException {
        Path tx = transaction(store, id); Properties journal = readProperties(tx.resolve("transaction.properties"));
        validateJournal(store, journal);
        if (!"COMMITTED".equals(required(journal, "state"))) throw new IOException("只有 COMMITTED 事务可撤销");
        for (int i = 0; i < count(journal); i++) {
            String prefix = "file." + i + "."; Path live = Io.within(store.world, required(journal, prefix + "path"));
            String expected = required(journal, prefix + "after");
            if (expected.equals("ABSENT") ? Files.exists(live) : !Files.exists(live) || !Io.hash(live).equals(expected))
                throw new IOException("回退后目标文件已有新保存，不能整文件撤销；请改用新快照做局部回退");
        }
        state(tx, journal, "UNDOING"); rollback(store, tx, journal, "UNDONE");
    }

    public static void processStartup(Path serverDirectory) throws IOException {
        Path server = serverDirectory.toAbsolutePath().normalize();
        Path pending = server.resolve(PENDING), registry = server.resolve(REGISTRY);
        Properties context = Files.exists(pending) ? readProperties(pending) : Files.exists(registry) ? readProperties(registry) : null;
        if (context == null) return;
        BackupStore store = open(context);
        if (Files.exists(pending)) {
            Properties serverProperties = readProperties(server.resolve("server.properties"));
            Path configuredWorld = server.resolve(serverProperties.getProperty("level-name", "world")).toAbsolutePath().normalize();
            if (!configuredWorld.equals(store.world)) throw new IOException("回退计划与当前 level-name 不一致，拒绝执行");
        }
        try (var worldLock = new WorldLock(store.world); var repoLock = store.lock()) {
            recover(store);
            if (Files.exists(pending)) {
                if (!server.toString().equals(required(context, "server"))) throw new IOException("回退计划的服务器目录不匹配");
                Selection selection;
                try {
                    selection = new Selection(required(context, "dimension"), Integer.parseInt(required(context, "x1")),
                            Integer.parseInt(required(context, "z1")), Integer.parseInt(required(context, "x2")), Integer.parseInt(required(context, "z2")));
                } catch (IllegalArgumentException exception) { throw new IOException("回退计划坐标无效", exception); }
                String id = restore(store, store.load(required(context, "snapshot")), selection, required(context, "transaction"));
                Io.copyDurable(pending, transaction(store, id).resolve("request.properties"));
                remove(pending);
                System.out.println("[Vanilla Chunk Backup] 停服区块回退已提交: " + id);
            }
        }
    }

    public static void queue(Path server, BackupStore store, String snapshot, Selection selection) throws IOException {
        Path pending = server.toAbsolutePath().normalize().resolve(PENDING);
        if (Files.exists(pending)) throw new IOException("已有待执行的回退计划");
        Properties plan = context(store); plan.setProperty("server", server.toAbsolutePath().normalize().toString());
        plan.setProperty("transaction", transactionId()); plan.setProperty("snapshot", snapshot);
        plan.setProperty("dimension", selection.dimension()); plan.setProperty("x1", Integer.toString(selection.x1()));
        plan.setProperty("z1", Integer.toString(selection.z1())); plan.setProperty("x2", Integer.toString(selection.x2()));
        plan.setProperty("z2", Integer.toString(selection.z2())); writeProperties(pending, plan);
    }

    public static void cancel(Path server) throws IOException {
        Path pending = server.toAbsolutePath().normalize().resolve(PENDING);
        BackupStore store = open(readProperties(pending));
        try (var worldLock = new WorldLock(store.world); var repoLock = store.lock()) {
            recover(store); Io.copyDurable(pending, store.root.resolve("cancelled-" + System.currentTimeMillis() + ".properties"));
            remove(pending);
        }
    }
}

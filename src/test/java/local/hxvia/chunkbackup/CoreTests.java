package local.hxvia.chunkbackup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.TreeMap;
import java.util.zip.DeflaterOutputStream;

/** Isolated synthetic records only; these fixtures are never copied into a real game world. */
public final class CoreTests {
    private static int checks;
    private static final String DIM = "dimensions/minecraft/overworld";
    private static Path world;
    private static BackupStore store;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message); checks++;
    }
    private static byte[] blob(String text) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); output.write(2);
        try (var compressed = new DeflaterOutputStream(output)) { compressed.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        return output.toByteArray();
    }
    private static Path region(String type, int x, int z) {
        return world.resolve(DIM + "/" + type + "/r." + Math.floorDiv(x, 32) + "." + Math.floorDiv(z, 32) + ".mca");
    }
    private static void put(String type, int x, int z, byte[] data) throws IOException {
        Path target = region(type, x, z);
        Path stage = Files.createTempDirectory(store.root, "fixture-stage-").resolve(target.getFileName());
        Map<Integer, byte[]> changes = new HashMap<>(); changes.put(Math.floorMod(x, 32) + 32 * Math.floorMod(z, 32), data);
        RegionIo.Image image = RegionIo.rewrite(target, stage, changes);
        for (var external : image.externalWrites().entrySet()) Io.copyDurable(external.getValue(), target.getParent().resolve(external.getKey()));
        for (String external : image.externalDeletes()) Files.deleteIfExists(target.getParent().resolve(external));
        Io.copyDurable(image.region(), target);
    }
    private static byte[] get(String type, int x, int z) throws IOException {
        RegionIo.Record record = RegionIo.readChunk(region(type, x, z), x, z);
        return record == null ? null : record.data();
    }
    private static Snapshot capture(String label, int keep) throws IOException {
        try (var lock = store.lock()) {
            BackupStore.Capture capture = store.beginCapture("synthetic-world", Map.of("minecraft:overworld", DIM), "test", label, 0, 120);
            capture.captureRegions(); capture.publish(keep, 0); return capture.snapshot;
        }
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void rejects(Checked operation, String message) throws Exception {
        try { operation.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError(message);
    }

    private static void voxyCompatibility(Path root) throws Exception {
        Path local = root.resolve("voxy-world");
        Path cache = local.resolve("voxy/synthetic-render-cache/storage");
        Io.directories(cache); Io.directories(local.resolve("data/voxy"));
        Files.writeString(local.resolve("level.dat"), "synthetic world metadata");
        Files.writeString(local.resolve("data/voxy/required.dat"), "synthetic gameplay data");
        Files.writeString(cache.resolve("CURRENT"), "synthetic database state");
        Files.writeString(cache.resolve("000001.sst"), "synthetic render data");
        Files.writeString(cache.resolve("r.0.0.mca"), "not a Minecraft region");
        Path databaseLock = cache.resolve("LOCK");
        Files.writeString(databaseLock, "synthetic live database lock");
        BackupStore isolated = new BackupStore(local, root.resolve("voxy-repository"), BackupStore.worldId(local));
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        try (var channel = FileChannel.open(databaseLock, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var liveDatabase = channel.lock(); var lock = isolated.lock()) {
            if (windows) rejects(() -> Files.readAllBytes(databaseLock), "fixture holds an unreadable Windows database lock");
            var capture = isolated.beginCapture("voxy-world", Map.of("minecraft:overworld", ""), "test", "voxy-cache", 0, 120);
            capture.captureRegions(); capture.publish(24, 0);
            Snapshot snapshot = capture.snapshot;
            check(snapshot.files.keySet().stream().noneMatch(path -> path.startsWith("voxy/")), "exclude the entire root Voxy database, not just LOCK");
            check(snapshot.regionFingerprints.keySet().stream().noneMatch(path -> path.startsWith("voxy/")), "Voxy cache region-like files are not world chunks");
            check(snapshot.files.containsKey("level.dat"), "Voxy exclusion preserves world metadata");
            check(snapshot.files.containsKey("data/voxy/required.dat"), "Voxy exclusion is limited to the world root");
            isolated.verify(snapshot); checks++;
            Path exported = root.resolve("voxy-export");
            WorldExport.export(isolated, snapshot, exported);
            check(!Files.exists(exported.resolve("voxy")), "exports omit excluded render cache");
            check(Files.readString(exported.resolve("data/voxy/required.dat")).equals("synthetic gameplay data"), "exports retain normal world data");
        }
        if (windows) {
            Path required = local.resolve("data/voxy/required.dat");
            try (var channel = FileChannel.open(required, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 var liveData = channel.lock(); var lock = isolated.lock()) {
                rejects(() -> isolated.beginCapture("voxy-world", Map.of("minecraft:overworld", ""), "test", "locked-world-data", 0, 120),
                        "unreadable real world data must still fail capture");
                check(isolated.ids().size() == 1, "unreadable real data cannot publish an incomplete snapshot");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "synthetic-core-");
        voxyCompatibility(root);
        world = root.resolve("synthetic-world"); Io.directories(world);
        store = new BackupStore(world, root.resolve("repository"), BackupStore.worldId(world));
        Files.writeString(world.resolve("level.dat"), "isolated synthetic metadata A");
        byte[] terrainA = blob("terrain A"), terrainB = blob("terrain B"), neighborA = blob("neighbor A"), neighborB = blob("neighbor B");
        byte[] entitiesA = blob("entities A"), entitiesB = blob("entities B"), poiA = blob("POI A"), poiB = blob("POI B");
        put("region", -1, -1, terrainA); put("region", -2, -1, neighborA);
        put("entities", -1, -1, entitiesA); put("poi", -1, -1, poiA);
        byte[] external = new byte[1_100_001]; new Random(42).nextBytes(external); external[0] = 3;
        put("region", 0, 0, external);
        check(Files.exists(world.resolve(DIM + "/region/c.0.0.mcc")), "large record uses .mcc");
        Snapshot baseline = capture("baseline", 100);
        check(SnapshotNames.valid(baseline.id) && baseline.id.contains("+0800-"), "readable dated snapshot name includes Beijing offset");
        check(SnapshotNames.display(0).equals("01-01 08:00:00"), "display consistently uses Beijing time");
        check(SnapshotNames.valid("20261107T175936064Z-ba84565c"), "existing 1.0 snapshot names remain valid");
        check(!SnapshotNames.valid("../20261107T175936064Z-ba84565c") && !SnapshotNames.valid("2026-11-08_02:59:36.vcb"), "names exclude paths and Windows-invalid punctuation");
        Snapshot identical = capture("identical", 100);
        check(identical.newObjects == 0 && identical.newBytes == 0, "unchanged snapshot stores no new objects");
        check(identical.chunks.equals(baseline.chunks), "full independent chunk index");
        check(baseline.chunks.size() == 5, "terrain entities POI and external captured individually");
        store.verify(baseline); checks++;
        rejects(() -> store.beginCapture("synthetic-world", Map.of("minecraft:overworld", DIM), "test", "no-space", Long.MAX_VALUE, 60), "low free space rejects a snapshot before new data");
        put("region", -1, -1, terrainB); put("region", -2, -1, neighborB);
        put("entities", -1, -1, entitiesB); put("poi", -1, -1, poiB);
        put("region", -3, -1, blob("generated after baseline"));
        Files.writeString(world.resolve("level.dat"), "isolated synthetic metadata B");
        Snapshot changed = capture("changed", 100);
        check(changed.newObjects == 6 && changed.newBytes < 4096, "only five changed/new chunks and one metadata object stored");
        check(changed.chunks.get(new ChunkKey(DIM + "/region", 0, 0).encoded()).equals(baseline.chunks.get(new ChunkKey(DIM + "/region", 0, 0).encoded())), "external object shared");
        int neighborTimestamp = RegionIo.readChunk(region("region", -2, -1), -2, -1).timestamp();
        String transaction;
        try (var worldLock = new ColdRestore.WorldLock(world); var lock = store.lock()) {
            rejects(() -> { try (var denied = new ColdRestore.WorldLock(world)) { } }, "world lock must exclude concurrent restore");
            transaction = ColdRestore.restore(store, baseline, new Selection("minecraft:overworld", -1, -1, -1, -1), ColdRestore.transactionId());
        }
        check(Arrays.equals(get("region", -1, -1), terrainA), "selected negative terrain restored");
        check(Arrays.equals(get("entities", -1, -1), entitiesA), "selected entities restored");
        check(Arrays.equals(get("poi", -1, -1), poiA), "selected POI restored");
        check(Arrays.equals(get("region", -2, -1), neighborB), "neighbor chunk unchanged");
        check(RegionIo.readChunk(region("region", -2, -1), -2, -1).timestamp() == neighborTimestamp, "neighbor timestamp unchanged");
        check(Files.readString(world.resolve("level.dat")).endsWith(" B"), "local restore preserves global metadata");
        try (var worldLock = new ColdRestore.WorldLock(world); var lock = store.lock()) { ColdRestore.undo(store, transaction); }
        check(Arrays.equals(get("region", -1, -1), terrainB) && Arrays.equals(get("poi", -1, -1), poiB), "undo restores pre-rollback files");
        String interrupted;
        try (var worldLock = new ColdRestore.WorldLock(world); var lock = store.lock()) {
            interrupted = ColdRestore.restore(store, baseline, new Selection("minecraft:overworld", -1, -1, -1, -1), ColdRestore.transactionId());
            Path journalPath = store.root.resolve("transactions/" + interrupted + "/transaction.properties");
            Properties journal = ColdRestore.readProperties(journalPath); journal.setProperty("state", "APPLYING");
            ColdRestore.writeProperties(journalPath, journal);
            ColdRestore.recover(store);
            check("ROLLED_BACK".equals(ColdRestore.readProperties(journalPath).getProperty("state")), "interrupted commit recovered");
        }
        check(Arrays.equals(get("region", -1, -1), terrainB), "recovery returns original chunk");
        try (var worldLock = new ColdRestore.WorldLock(world); var lock = store.lock()) {
            String undoInterrupted = ColdRestore.restore(store, baseline, new Selection("minecraft:overworld", -1, -1, -1, -1), ColdRestore.transactionId());
            Path journalPath = store.root.resolve("transactions/" + undoInterrupted + "/transaction.properties");
            Properties journal = ColdRestore.readProperties(journalPath); journal.setProperty("state", "UNDOING");
            ColdRestore.writeProperties(journalPath, journal); ColdRestore.recover(store);
            check("UNDONE".equals(ColdRestore.readProperties(journalPath).getProperty("state")) && Arrays.equals(get("region", -1, -1), terrainB), "interrupted undo is completed before startup");
        }
        try (var worldLock = new ColdRestore.WorldLock(world); var lock = store.lock()) {
            ColdRestore.restore(store, baseline, new Selection("minecraft:overworld", -3, -1, -3, -1), ColdRestore.transactionId());
        }
        check(get("region", -3, -1) == null && Arrays.equals(get("region", -2, -1), neighborB), "newly generated selected chunk removed, neighbor kept");
        put("region", 0, 0, blob("external replaced by small record"));
        check(!Files.exists(world.resolve(DIM + "/region/c.0.0.mcc")), "external to internal removes stale mcc");
        try (var worldLock = new ColdRestore.WorldLock(world); var lock = store.lock()) {
            ColdRestore.restore(store, baseline, new Selection("minecraft:overworld", 0, 0, 0, 0), ColdRestore.transactionId());
        }
        check(Arrays.equals(get("region", 0, 0), external), "external mcc roundtrip restores exact bytes");
        String conflict;
        try (var worldLock = new ColdRestore.WorldLock(world); var lock = store.lock()) {
            conflict = ColdRestore.restore(store, baseline, new Selection("minecraft:overworld", -1, -1, -1, -1), ColdRestore.transactionId());
        }
        put("region", -2, -1, blob("legitimate later neighbor save"));
        try (var worldLock = new ColdRestore.WorldLock(world); var lock = store.lock()) {
            rejects(() -> ColdRestore.undo(store, conflict), "undo must not overwrite later world saves");
        }
        String hash = baseline.chunks.get(new ChunkKey(DIM + "/region", 0, 0).encoded());
        byte[] original = Files.readAllBytes(store.objectPath(hash)); byte[] damaged = original.clone(); damaged[100] ^= 1;
        Files.write(store.objectPath(hash), damaged);
        rejects(() -> store.verifyObject(hash), "integrity verification detects damaged object");
        Files.write(store.objectPath(hash), original); store.verify(baseline); checks++;
        for (int i = 0; i < 27; i++) { Files.writeString(world.resolve("rotation.dat"), "isolated synthetic rotation " + i); capture("rotation-" + i, 24); }
        check(store.ids().size() == 24, "retention keeps exactly 24 recoverable snapshots");
        check(!Files.exists(store.snapshotPath(baseline.id)), "old index pruned");
        Snapshot oldest = store.load(store.ids().getFirst()), newest = store.load(store.ids().getLast());
        store.verify(oldest); store.verify(newest); checks += 2;
        check(Arrays.equals(store.get(newest.chunks.get(new ChunkKey(DIM + "/region", 0, 0).encoded())), external), "shared object survives baseline pruning");
        Path exported = root.resolve("exported-synthetic-world");
        try (var lock = store.lock()) { WorldExport.export(store, newest, exported); }
        check(Io.hash(exported.resolve("level.dat")).equals(newest.files.get("level.dat")), "full export restores global metadata");
        check(Arrays.equals(RegionIo.readChunk(exported.resolve(DIM + "/region/r.0.0.mca"), 0, 0).data(), external), "full export restores external chunk");
        check(Io.hash(exported.resolve(BackupStore.WORLD_MARKER)).equals(newest.files.get(BackupStore.WORLD_MARKER)), "full export retains world identity");
        rejects(() -> WorldExport.export(store, newest, exported), "full export refuses to overwrite an existing directory");
        Path abandoned = store.root.resolve("objects/." + java.util.UUID.randomUUID() + ".tmp"); Files.writeString(abandoned, "isolated synthetic abandoned object");
        try (var lock = store.lock()) { store.prune(24); }
        check(!Files.exists(abandoned), "pruning removes interrupted object temporaries");
        byte[] intactIndex = Files.readAllBytes(store.snapshotPath(newest.id));
        Files.write(store.snapshotPath(newest.id), new byte[] {0, 1, 2});
        rejects(() -> store.prune(1), "a damaged retained index stops pruning");
        check(store.ids().size() == 24, "a damaged retained index cannot cause valid older snapshots to be deleted");
        Files.write(store.snapshotPath(newest.id), intactIndex);
        long gate = DiskGate.beginCapture();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var blockedWriter = new java.util.concurrent.FutureTask<>(() -> {
            entered.countDown(); long stamp = DiskGate.beginWrite(); DiskGate.endWrite(stamp); return true;
        });
        new Thread(blockedWriter, "synthetic-write-gate-test").start(); entered.await();
        check(!blockedWriter.isDone(), "region writes wait while a snapshot holds the gate");
        DiskGate.endCapture(gate);
        check(blockedWriter.get(5, java.util.concurrent.TimeUnit.SECONDS), "writer proceeds after cross-thread gate release");
        BackupStore mixed = new BackupStore(world, root.resolve("mixed-name-repository"), store.worldId);
        try (var lock = mixed.lock()) {
            var first = mixed.beginCapture("synthetic-world", Map.of("minecraft:overworld", DIM), "test", "legacy", 0, 120);
            first.captureRegions(); first.publish(24, 0);
            Path firstPath = mixed.snapshotPath(first.snapshot.id);
            first.snapshot.id = "20261107T175936064Z-ba84565c";
            first.snapshot.createdAt = 1;
            first.snapshot.write(mixed.snapshotPath(first.snapshot.id)); Files.delete(firstPath);
            var second = mixed.beginCapture("synthetic-world", Map.of("minecraft:overworld", DIM), "test", "readable", 0, 120);
            second.captureRegions(); second.publish(24, 0);
            check(mixed.list().getFirst().id.equals(first.snapshot.id), "mixed names list by stored creation time");
            check(second.snapshot.newObjects == 0, "incremental capture reuses the correct prior legacy snapshot");
            mixed.prune(1);
            check(mixed.list().size() == 1 && mixed.list().getFirst().id.equals(second.snapshot.id), "retention uses chronology across old and new filename formats");
            mixed.verify(second.snapshot); checks++;
        }
        System.out.println("CORE_TESTS_OK checks=" + checks + " fixtures=" + root);
        Files.writeString(Path.of(args[0]).resolve("core-test-result.json"), "{\"status\":\"passed\",\"checks\":" + checks + "}\n");
    }
}

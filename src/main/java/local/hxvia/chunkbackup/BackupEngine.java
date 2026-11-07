package local.hxvia.chunkbackup;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class BackupEngine implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("VanillaChunkBackup");
    private final MinecraftServer server;
    private final Path configPath;
    private final Path instanceDirectory;
    private final BackupStore store;
    private BackupConfig config;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> new Thread(task, "vanilla-chunk-backup"));
    private final Map<UUID, Long> manualRequests = new HashMap<>();
    private final Map<ServerLevel, Boolean> previousNoSave = new IdentityHashMap<>();
    private boolean savingSuspended;
    private volatile boolean busy;
    private volatile boolean stopping;
    private boolean restoreQueued;
    private volatile String status = "空闲";
    private long nextAutomaticAt;
    private long nextCheck;
    private RestorePreview preview;

    private record RestorePreview(String token, String owner, String snapshot, Selection selection, long expiresAt) {}

    public BackupEngine(MinecraftServer server) throws IOException {
        this.server = server;
        instanceDirectory = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        configPath = instanceDirectory.resolve("config/vanilla_chunk_backup.json");
        config = BackupConfig.load(configPath);
        Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String id = BackupStore.worldId(world);
        Path repository = instanceDirectory.resolve(config.backupDirectory).resolve(id);
        store = new BackupStore(world, repository, id);
        if (server.isDedicatedServer()) ColdRestore.writeProperties(instanceDirectory.resolve(ColdRestore.REGISTRY), ColdRestore.context(store));
        long latestAutomatic = 0;
        for (Snapshot snapshot : store.list()) if (snapshot.reason.equals("automatic")) latestAutomatic = Math.max(latestAutomatic, snapshot.createdAt);
        nextAutomaticAt = latestAutomatic == 0 ? System.currentTimeMillis() + 15_000 : latestAutomatic + intervalMillis();
        LOGGER.info("VCB_READY world={} intervalMinutes={} keepSnapshots={} manualCooldownSeconds={}",
                id, config.intervalMinutes, config.keepSnapshots, config.manualCooldownSeconds);
    }

    private long intervalMillis() { return config.intervalMinutes * 60_000L; }
    private long minimumFreeBytes() { return config.minimumFreeSpaceMiB * 1024L * 1024L; }

    static void reply(CommandSourceStack source, String text) { reply(source, Messages.text(text, ChatFormatting.AQUA)); }
    static void reply(CommandSourceStack source, Component text) { source.sendSuccess(() -> text, false); }
    static void fail(CommandSourceStack source, String text) { source.sendFailure(Messages.text(text, ChatFormatting.RED)); }
    private void later(CommandSourceStack source, String text, boolean failed) {
        if (!stopping) server.execute(() -> { if (failed) fail(source, text); else reply(source, Messages.text(text, ChatFormatting.GREEN)); });
    }

    public void tick() {
        long now = System.currentTimeMillis();
        if (now < nextCheck) return; nextCheck = now + 1000;
        if (!stopping && !restoreQueued && !busy && now >= nextAutomaticAt) {
            if (startBackup(null, "automatic", "hourly")) {
                do { nextAutomaticAt += intervalMillis(); } while (nextAutomaticAt <= now);
            } else nextAutomaticAt = now + 60_000;
        }
    }

    public int create(CommandSourceStack source, String label) {
        if (label.length() > 80 || label.chars().anyMatch(Character::isISOControl)) { fail(source, "标签最多 80 个字符，不能含控制字符"); return 0; }
        UUID player = source.getPlayer() == null ? null : source.getPlayer().getUUID();
        long now = System.currentTimeMillis();
        if (player != null && config.manualCooldownSeconds > 0) {
            long remaining = manualRequests.getOrDefault(player, 0L) + config.manualCooldownSeconds * 1000L - now;
            if (remaining > 0) { fail(source, "请等待 " + (remaining + 999) / 1000 + " 秒后再备份"); return 0; }
        }
        if (!startBackup(source, "manual", label)) return 0;
        if (player != null && config.manualCooldownSeconds > 0) manualRequests.put(player, now);
        return 1;
    }

    private boolean startBackup(CommandSourceStack source, String reason, String label) {
        if (busy || stopping || restoreQueued) { if (source != null) fail(source, "任务进行中，请稍后再试"); return false; }
        busy = true; status = "保存世界并捕获附属文件";
        BackupStore.RepoLock repositoryLock = null;
        long stamp = 0;
        try {
            repositoryLock = store.lock();
            // Flush is synchronous on the server thread; workers never wait on a server-thread callback.
            server.saveEverything(true, true, true);
            previousNoSave.clear();
            for (ServerLevel level : server.getAllLevels()) { previousNoSave.put(level, level.noSave); level.noSave = true; }
            savingSuspended = true;
            stamp = DiskGate.beginCapture();
            Map<String, String> dimensions = new java.util.TreeMap<>();
            for (ServerLevel level : server.getAllLevels()) {
                Path folder = DimensionType.getStorageFolder(level.dimension(), store.world);
                dimensions.put(level.dimension().identifier().toString(), Io.relative(store.world, folder));
            }
            BackupStore.Capture capture = store.beginCapture(Io.relative(instanceDirectory, store.world), dimensions,
                    reason, label, minimumFreeBytes(), config.captureTimeoutSeconds);
            final long gate = stamp; final BackupStore.RepoLock lock = repositoryLock;
            final int keep = config.keepSnapshots; final long free = minimumFreeBytes();
            worker.execute(() -> capture(capture, gate, lock, keep, free, source));
            if (source != null) reply(source, "正在备份…");
            return true;
        } catch (Exception exception) {
            if (stamp != 0) DiskGate.endCapture(stamp);
            restoreSaving();
            if (repositoryLock != null) try { repositoryLock.close(); } catch (IOException close) { exception.addSuppressed(close); }
            busy = false; status = "失败: " + exception.getMessage();
            LOGGER.error("VCB_BACKUP_FAILED before capture", exception);
            if (source != null) fail(source, "备份失败，请查看日志");
            return false;
        }
    }

    private void capture(BackupStore.Capture capture, long gate, BackupStore.RepoLock lock, int keep, long free, CommandSourceStack source) {
        Exception failure = null;
        try (lock) {
            status = "捕获区块增量";
            try { capture.captureRegions(); }
            finally { DiskGate.endCapture(gate); if (!stopping) server.execute(this::restoreSaving); }
            status = "同步快照并清理过期对象";
            capture.publish(keep, free);
            Snapshot snapshot = capture.snapshot;
            status = "最近成功 " + snapshot.id;
            LOGGER.info("VCB_BACKUP_OK id={} reason={} label={} newObjects={} newBytes={} captureMillis={} chunks={}",
                    snapshot.id, snapshot.reason, snapshot.label, snapshot.newObjects, snapshot.newBytes, snapshot.captureMillis, snapshot.chunks.size());
            if (source != null && !stopping) server.execute(() -> reply(source, Messages.completed(snapshot)));
        } catch (Exception exception) {
            if (capture.published) {
                status = "快照已发布 " + capture.snapshot.id + "；仓库清理失败: " + exception.getMessage();
                LOGGER.warn("VCB_BACKUP_PUBLISHED_WITH_WARNING id={}", capture.snapshot.id, exception);
                if (source != null) later(source, "备份已保存，清理失败，请查看日志", true);
            } else {
                failure = exception;
                try (var cleanup = store.lock()) { store.prune(keep); }
                catch (Exception cleanup) { exception.addSuppressed(cleanup); }
                status = "失败: " + exception.getMessage(); LOGGER.error("VCB_BACKUP_FAILED", exception);
                if (source != null) later(source, "备份失败，请查看日志", true);
            }
        } finally {
            boolean failed = failure != null;
            if (!stopping) server.execute(() -> { restoreSaving(); busy = false; if (failed && capture.snapshot.reason.equals("automatic")) nextAutomaticAt = System.currentTimeMillis() + 60_000; });
        }
    }

    private void restoreSaving() {
        if (!savingSuspended) return;
        for (var entry : previousNoSave.entrySet()) entry.getKey().noSave = entry.getValue();
        previousNoSave.clear(); savingSuspended = false;
    }

    public int status(CommandSourceStack source) {
        boolean failed = status.startsWith("失败") || status.contains("清理失败");
        String activity = failed ? "上次备份异常" : busy ? "任务进行中" : "已就绪";
        reply(source, Messages.text(activity, failed ? ChatFormatting.RED : busy ? ChatFormatting.YELLOW : ChatFormatting.GREEN)
                .append(Component.literal(" · 下次 " + SnapshotNames.display(nextAutomaticAt) + " · 保留 " + config.keepSnapshots + " 份")
                        .withStyle(ChatFormatting.GRAY))); return 1;
    }

    private boolean beginWork(CommandSourceStack source, String activity) {
        if (busy || stopping || restoreQueued) { fail(source, "当前有任务进行中，请稍后重试"); return false; }
        busy = true; status = activity; return true;
    }

    private void finishWork() { if (!stopping) server.execute(() -> { busy = false; status = "空闲"; }); }

    public int list(CommandSourceStack source) {
        if (!beginWork(source, "读取快照目录")) return 0;
        worker.execute(() -> {
            try (var lock = store.lock()) {
                List<Snapshot> snapshots = store.list();
                if (snapshots.isEmpty()) later(source, "还没有备份", false);
                else if (!stopping) server.execute(() -> {
                    for (Snapshot snapshot : snapshots.reversed()) reply(source, Messages.snapshot(snapshot));
                });
            } catch (Exception exception) { later(source, exception.getMessage(), true); }
            finally { finishWork(); }
        }); return 1;
    }

    public int verify(CommandSourceStack source, String id) {
        if (!beginWork(source, "校验快照")) return 0;
        worker.execute(() -> {
            try (var lock = store.lock()) { store.verify(store.load(id)); later(source, "备份校验通过", false); LOGGER.info("VCB_VERIFY_OK id={}", id); }
            catch (Exception exception) { later(source, "校验失败: " + exception.getMessage(), true); }
            finally { finishWork(); }
        }); return 1;
    }

    public int prune(CommandSourceStack source) {
        if (!beginWork(source, "清理过期快照和未引用对象")) return 0;
        int keep = config.keepSnapshots;
        worker.execute(() -> {
            try (var lock = store.lock()) { store.prune(keep); later(source, "清理完成 · 保留 " + keep + " 份", false); }
            catch (Exception exception) { later(source, "仓库清理失败: " + exception.getMessage(), true); }
            finally { finishWork(); }
        }); return 1;
    }

    public int configure(CommandSourceStack source, String name, int value) {
        try {
            BackupConfig changed = config.copy(); changed.set(name, value); changed.save(configPath);
            if (config.intervalMinutes != changed.intervalMinutes) nextAutomaticAt = System.currentTimeMillis() + changed.intervalMinutes * 60_000L;
            config = changed; if (config.manualCooldownSeconds == 0) manualRequests.clear();
            reply(source, name + " = " + value + "，已持久保存"); return 1;
        } catch (Exception exception) { fail(source, exception.getMessage()); return 0; }
    }

    private static String owner(CommandSourceStack source) { return source.getPlayer() == null ? "console" : source.getPlayer().getUUID().toString(); }

    public int preview(CommandSourceStack source, String id, Selection selection) {
        if (!server.isDedicatedServer()) { fail(source, "本地回退请先退出世界，再使用离线工具"); return 0; }
        if (!beginWork(source, "检查停服回退范围")) return 0;
        final int maximum = config.maxRestoreChunks;
        worker.execute(() -> {
            try (var lock = store.lock()) {
                Snapshot snapshot = store.load(id); selection.validate(snapshot, maximum);
                String folder = snapshot.dimensions.get(selection.dimension());
                for (String type : List.of("region", "entities", "poi")) {
                    String storage = folder.isEmpty() ? type : folder + "/" + type;
                    for (int z = selection.z1(); z <= selection.z2(); z++) for (int x = selection.x1(); x <= selection.x2(); x++) {
                        String hash = snapshot.chunks.get(new ChunkKey(storage, x, z).encoded()); if (hash != null) store.verifyObject(hash);
                    }
                }
                String token = UUID.randomUUID().toString().substring(0, 8);
                if (!stopping) server.execute(() -> {
                    preview = new RestorePreview(token, owner(source), id, selection, System.currentTimeMillis() + 300_000);
                    reply(source, "回退预览: " + id + "，" + selection.dimension() + "，区块 (" + selection.x1() + "," + selection.z1()
                            + ") 到 (" + selection.x2() + "," + selection.z2() + ")，共 " + selection.count() + " 个。包含地形、实体、POI；不回退玩家背包或全局数据。"
                            + "快照中不存在的所选区块会被移除。输入 /chunkbackup confirm " + token + " 将正常停服，下次启动时执行回退；确认 5 分钟内有效。");
                });
            } catch (Exception exception) { later(source, "预览失败: " + exception.getMessage(), true); }
            finally { finishWork(); }
        }); return 1;
    }

    public int confirm(CommandSourceStack source, String token) {
        if (busy || stopping || restoreQueued) { fail(source, "当前有任务进行中，请稍后重试"); return 0; }
        if (preview == null || !preview.token.equals(token) || !preview.owner.equals(owner(source)) || System.currentTimeMillis() > preview.expiresAt) {
            fail(source, "确认码无效/已过期，请重新预览回退范围"); return 0;
        }
        try (var lock = store.lock()) {
            Snapshot snapshot = store.load(preview.snapshot); preview.selection.validate(snapshot, config.maxRestoreChunks);
            ColdRestore.queue(instanceDirectory, store, snapshot.id, preview.selection);
            restoreQueued = true;
            reply(source, Messages.text("即将停服回退，请稍后由管理员启动服务器", ChatFormatting.YELLOW));
            LOGGER.warn("VCB_RESTORE_QUEUED snapshot={} selection={}", snapshot.id, preview.selection);
            server.halt(false); return 1;
        } catch (Exception exception) { fail(source, "未能安排停服回退: " + exception.getMessage()); return 0; }
    }

    @Override public void close() {
        stopping = true; worker.shutdown();
        boolean interrupted = false;
        try {
            while (true) {
                try { if (worker.awaitTermination(5, TimeUnit.SECONDS)) break; }
                catch (InterruptedException exception) { interrupted = true; }
                LOGGER.info("等待备份线程结束后保存并关闭世界");
            }
        } finally { restoreSaving(); busy = false; if (interrupted) Thread.currentThread().interrupt(); }
    }
}

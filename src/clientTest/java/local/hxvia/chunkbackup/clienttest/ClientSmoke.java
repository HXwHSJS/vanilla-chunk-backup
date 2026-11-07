package local.hxvia.chunkbackup.clienttest;

import com.google.gson.GsonBuilder;
import local.hxvia.chunkbackup.BackupStore;
import local.hxvia.chunkbackup.Snapshot;
import local.hxvia.chunkbackup.SnapshotNames;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Executes only in the marked synthetic client profile. No real account or user world is used. */
public final class ClientSmoke implements ClientModInitializer {
    private final Path root = FabricLoader.getInstance().getGameDir();
    private final boolean expectVoxy = Boolean.getBoolean("vcb.test.expectVoxy");
    private final boolean checkAutomatic = Boolean.getBoolean("vcb.test.checkAutomatic");
    private final List<Map<String, Object>> messages = new ArrayList<>();
    private final long deadline = System.currentTimeMillis() + 240_000;
    private long nextCommand;
    private boolean finished;
    private boolean hintColored;
    private boolean completionGreen;
    private boolean configured;
    private boolean configurationRequested;
    private boolean sawServer;
    private boolean voxyDatabaseObserved;
    private boolean voxyLockBlocked;
    private String backupFailure;

    @Override public void onInitializeClient() {
        if (!Files.exists(root.resolve("SYNTHETIC-CLIENT-TEST.txt"))) throw new IllegalStateException("Missing synthetic client marker");
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!message.getString().startsWith("[备份]")) return;
            if (message.getString().contains("备份失败") || message.getString().contains("清理失败")) backupFailure = message.getString();
            if (message.getString().contains("manualCooldownSeconds = 0")) configured = true;
            List<Map<String, String>> parts = new ArrayList<>();
            message.visit((style, text) -> {
                Map<String, String> part = new LinkedHashMap<>(); part.put("text", text);
                if (style.getColor() != null) part.put("color", style.getColor().toString());
                if (style.getClickEvent() != null) part.put("click", style.getClickEvent().action().toString());
                parts.add(part);
                if (text.equals("/chunkbackup create") && style.getColor() != null
                        && style.getColor().getValue() == 0x55FFFF
                        && style.getClickEvent() instanceof ClickEvent.SuggestCommand) hintColored = true;
                if (text.equals("备份完成") && style.getColor() != null
                        && style.getColor().getValue() == 0x55FF55) completionGreen = true;
                return Optional.empty();
            }, Style.EMPTY);
            messages.add(Map.of("text", message.getString(), "parts", parts));
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void finish(Minecraft client, Map<String, Object> result) {
        if (finished) return; finished = true;
        result.put("messages", messages);
        try { Files.writeString(root.resolve("client-smoke-result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result)); }
        catch (Exception failure) { failure.printStackTrace(); }
        System.out.println("VCB_CLIENT_SMOKE " + result.get("status"));
        client.stop();
    }

    private void tick(Minecraft client) {
        if (finished) return;
        if (backupFailure != null) {
            finish(client, new LinkedHashMap<>(Map.of("status", "failed", "reason", backupFailure))); return;
        }
        if (System.currentTimeMillis() > deadline) {
            finish(client, new LinkedHashMap<>(Map.of("status", "failed", "reason", "client smoke timeout"))); return;
        }
        var server = client.getSingleplayerServer();
        if (server != null) sawServer = true;
        else if (sawServer) { finish(client, new LinkedHashMap<>(Map.of("status", "failed", "reason", "integrated server stopped before checks finished"))); return; }
        if (server == null || client.player == null || client.level == null || System.currentTimeMillis() < nextCommand) return;
        nextCommand = System.currentTimeMillis() + 2000;
        try {
            Path world = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            if (expectVoxy && !voxyDatabaseObserved) {
                Path cache = world.resolve("voxy");
                if (!FabricLoader.getInstance().isModLoaded("voxy")) throw new IllegalStateException("Expected Voxy is not loaded");
                if (!Files.isDirectory(cache)) return;
                try (var paths = Files.walk(cache)) {
                    for (Path path : paths.filter(p -> p.getFileName().toString().equals("LOCK")).toList()) {
                        if (!Files.isRegularFile(path.getParent().resolve("CURRENT"))) continue;
                        try (var input = Files.newInputStream(path)) { input.read(); }
                        catch (IOException locked) { voxyLockBlocked = true; }
                        voxyDatabaseObserved = voxyLockBlocked || !System.getProperty("os.name").startsWith("Windows");
                    }
                }
                if (!voxyDatabaseObserved) return;
            }
            Path marker = world.resolve(BackupStore.WORLD_MARKER);
            if (!Files.exists(marker)) return;
            String id = Files.readString(marker).trim();
            BackupStore store = new BackupStore(world, root.resolve("backups/vanilla-chunk-backup").resolve(id), id);
            List<Snapshot> snapshots = store.list();
            Snapshot first = snapshots.stream().filter(s -> s.label.equals("client-first")).findFirst().orElse(null);
            Snapshot second = snapshots.stream().filter(s -> s.label.equals("client-second")).findFirst().orElse(null);
            Snapshot automatic = snapshots.stream().filter(s -> s.reason.equals("automatic")).findFirst().orElse(null);
            if (first == null) { client.player.connection.sendCommand("chunkbackup create client-first"); return; }
            if (second == null) { client.player.connection.sendCommand("chunkbackup create client-second"); return; }
            if (!completionGreen || !hintColored) return;
            if (!configurationRequested) { configurationRequested = true; client.player.connection.sendCommand("chunkbackup config manualCooldownSeconds 0"); return; }
            if (!configured) return;
            if (checkAutomatic && automatic == null) return;
            int sharedChunks;
            int referencedObjects;
            boolean cacheExcluded;
            try (var lock = store.lock()) {
                first = store.load(first.id); second = store.load(second.id);
                store.verify(first); store.verify(second);
                if (automatic != null) { automatic = store.load(automatic.id); store.verify(automatic); }
                cacheExcluded = cacheExcluded(first) && cacheExcluded(second) && (automatic == null || cacheExcluded(automatic));
                var shared = new HashSet<>(first.chunks.values()); shared.retainAll(second.chunks.values());
                sharedChunks = shared.size();
                var referenced = new HashSet<>(second.chunks.values()); referenced.addAll(second.files.values());
                referencedObjects = referenced.size();
            }
            var profile = server.getSingleplayerProfile();
            var player = server.getPlayerList().getPlayer(profile.id());
            boolean ownerAdmin = server.getCommands().getDispatcher().getRoot().getChild("chunkbackup").getChild("config").canUse(player.createCommandSourceStack());
            boolean valid = !server.isDedicatedServer() && ownerAdmin && SnapshotNames.valid(second.id)
                    && sharedChunks > 0 && second.newObjects < referencedObjects
                    && (!expectVoxy || (voxyDatabaseObserved && cacheExcluded && Files.isDirectory(world.resolve("voxy"))))
                    && (!checkAutomatic || automatic != null)
                    && !Files.exists(root.resolve("config/vanilla_chunk_backup.world.properties"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", valid ? "passed" : "failed"); result.put("integratedServer", !server.isDedicatedServer());
            result.put("ownerCanConfigure", ownerAdmin); result.put("hintColoredAndClickable", hintColored);
            result.put("ownerConfigurationExecuted", configured);
            result.put("successGreen", completionGreen); result.put("snapshotVerified", true);
            result.put("firstSnapshot", first.id); result.put("firstNewBytes", first.newBytes);
            result.put("secondSnapshot", second.id); result.put("secondNewBytes", second.newBytes);
            result.put("sharedChunkObjects", sharedChunks); result.put("secondReferencedObjects", referencedObjects);
            result.put("secondNewObjects", second.newObjects);
            result.put("voxyLoaded", FabricLoader.getInstance().isModLoaded("voxy"));
            result.put("voxyDatabaseObserved", voxyDatabaseObserved); result.put("voxyDatabaseLockUnreadable", voxyLockBlocked);
            result.put("voxyCacheExcludedFromSnapshots", cacheExcluded);
            result.put("voxyCachePreservedOnDisk", Files.isDirectory(world.resolve("voxy")));
            result.put("automaticBackupVerified", automatic != null);
            if (automatic != null) result.put("automaticSnapshot", automatic.id);
            result.put("dedicatedRegistryAbsent", !Files.exists(root.resolve("config/vanilla_chunk_backup.world.properties")));
            finish(client, result);
        } catch (Exception busy) {
            System.out.println("VCB_CLIENT_SMOKE_WAIT " + busy.getClass().getSimpleName());
        }
    }

    private static boolean cacheExcluded(Snapshot snapshot) {
        return snapshot.files.keySet().stream().noneMatch(path -> path.startsWith("voxy/"))
                && snapshot.regionFingerprints.keySet().stream().noneMatch(path -> path.startsWith("voxy/"));
    }
}

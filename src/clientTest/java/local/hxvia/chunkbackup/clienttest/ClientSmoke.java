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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Executes only in the marked synthetic client profile. No real account or user world is used. */
public final class ClientSmoke implements ClientModInitializer {
    private final Path root = FabricLoader.getInstance().getGameDir();
    private final List<Map<String, Object>> messages = new ArrayList<>();
    private final long deadline = System.currentTimeMillis() + 240_000;
    private long nextCommand;
    private boolean finished;
    private boolean hintColored;
    private boolean completionGreen;
    private boolean configured;
    private boolean configurationRequested;
    private boolean sawServer;

    @Override public void onInitializeClient() {
        if (!Files.exists(root.resolve("SYNTHETIC-CLIENT-TEST.txt"))) throw new IllegalStateException("Missing synthetic client marker");
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!message.getString().startsWith("[备份]")) return;
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
            Path marker = world.resolve(BackupStore.WORLD_MARKER);
            if (!Files.exists(marker)) return;
            String id = Files.readString(marker).trim();
            BackupStore store = new BackupStore(world, root.resolve("backups/vanilla-chunk-backup").resolve(id), id);
            List<Snapshot> snapshots = store.list();
            Snapshot first = snapshots.stream().filter(s -> s.label.equals("client-first")).findFirst().orElse(null);
            Snapshot second = snapshots.stream().filter(s -> s.label.equals("client-second")).findFirst().orElse(null);
            if (first == null) { client.player.connection.sendCommand("chunkbackup create client-first"); return; }
            if (second == null) { client.player.connection.sendCommand("chunkbackup create client-second"); return; }
            if (!completionGreen || !hintColored) return;
            if (!configurationRequested) { configurationRequested = true; client.player.connection.sendCommand("chunkbackup config manualCooldownSeconds 0"); return; }
            if (!configured) return;
            int sharedChunks;
            int referencedObjects;
            try (var lock = store.lock()) {
                first = store.load(first.id); second = store.load(second.id);
                store.verify(second);
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
            result.put("dedicatedRegistryAbsent", !Files.exists(root.resolve("config/vanilla_chunk_backup.world.properties")));
            finish(client, result);
        } catch (Exception busy) {
            System.out.println("VCB_CLIENT_SMOKE_WAIT " + busy.getClass().getSimpleName());
        }
    }
}

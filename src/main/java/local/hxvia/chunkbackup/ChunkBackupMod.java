package local.hxvia.chunkbackup;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

public final class ChunkBackupMod implements ModInitializer {
    private static BackupEngine engine;

    private static BackupEngine engine() { if (engine == null) throw new IllegalStateException("备份服务尚未就绪"); return engine; }
    private static boolean administrator(CommandSourceStack source) {
        var server = source.getServer();
        // Command metadata also evaluates requirements with a source that has no server.
        if (server != null && !server.isDedicatedServer()) {
            var owner = server.getSingleplayerProfile();
            if (owner != null && source.getPlayer() != null && owner.id().equals(source.getPlayer().getUUID())) return true;
        }
        return source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.OWNERS));
    }

    @Override public void onInitialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> handler.player.sendSystemMessage(Messages.hint()));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try { engine = new BackupEngine(server); }
            catch (Exception exception) { throw new IllegalStateException("区块备份初始化失败", exception); }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (engine != null) engine.tick(); });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (engine != null) engine.close(); });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> engine = null);
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> {
            var root = Commands.literal("chunkbackup").executes(command -> engine().status(command.getSource()));
            root.then(Commands.literal("create").executes(command -> engine().create(command.getSource(), ""))
                    .then(Commands.argument("label", StringArgumentType.greedyString()).executes(command -> engine().create(command.getSource(), StringArgumentType.getString(command, "label")))));
            root.then(Commands.literal("status").executes(command -> engine().status(command.getSource())));
            root.then(Commands.literal("list").executes(command -> engine().list(command.getSource())));
            root.then(Commands.literal("verify").requires(ChunkBackupMod::administrator)
                    .then(Commands.argument("snapshot", StringArgumentType.word()).executes(command -> engine().verify(command.getSource(), StringArgumentType.getString(command, "snapshot")))));
            var config = Commands.literal("config").requires(ChunkBackupMod::administrator);
            for (String key : java.util.List.of("intervalMinutes", "keepSnapshots", "manualCooldownSeconds", "minimumFreeSpaceMiB", "captureTimeoutSeconds", "maxRestoreChunks")) {
                config.then(Commands.literal(key).then(Commands.argument("value", IntegerArgumentType.integer()).executes(command ->
                        engine().configure(command.getSource(), key, IntegerArgumentType.getInteger(command, "value")))));
            }
            root.then(config);
            root.then(Commands.literal("prune").requires(ChunkBackupMod::administrator).executes(command -> engine().prune(command.getSource())));
            root.then(Commands.literal("restore").requires(ChunkBackupMod::administrator)
                    .then(Commands.argument("snapshot", StringArgumentType.word())
                    .then(Commands.argument("dimension", DimensionArgument.dimension())
                    .then(Commands.argument("x1", IntegerArgumentType.integer(-1_875_000, 1_874_999))
                    .then(Commands.argument("z1", IntegerArgumentType.integer(-1_875_000, 1_874_999))
                    .then(Commands.argument("x2", IntegerArgumentType.integer(-1_875_000, 1_874_999))
                    .then(Commands.argument("z2", IntegerArgumentType.integer(-1_875_000, 1_874_999)).executes(command -> {
                        try { return engine().preview(command.getSource(), StringArgumentType.getString(command, "snapshot"),
                                new Selection(DimensionArgument.getDimension(command, "dimension").dimension().identifier().toString(), IntegerArgumentType.getInteger(command, "x1"),
                                IntegerArgumentType.getInteger(command, "z1"), IntegerArgumentType.getInteger(command, "x2"), IntegerArgumentType.getInteger(command, "z2"))); }
                        catch (IllegalArgumentException exception) { BackupEngine.fail(command.getSource(), exception.getMessage()); return 0; }
                    }))))))));
            root.then(Commands.literal("confirm").requires(ChunkBackupMod::administrator)
                    .then(Commands.argument("token", StringArgumentType.word()).executes(command -> engine().confirm(command.getSource(), StringArgumentType.getString(command, "token")))));
            dispatcher.register(root);
        });
    }
}

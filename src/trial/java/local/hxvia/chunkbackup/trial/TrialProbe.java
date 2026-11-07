package local.hxvia.chunkbackup.trial;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;

/** Test-only; never included in the deliverable mod. Requires the loopback-only synthetic trial. */
public final class TrialProbe implements ModInitializer {
    @Override public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> {
            var root = Commands.literal("vcbtest").requires(source -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.OWNERS)));
            root.then(Commands.literal("asplayer").then(Commands.argument("name", StringArgumentType.word())
                    .then(Commands.argument("command", StringArgumentType.greedyString()).executes(command -> {
                        var server = command.getSource().getServer();
                        var player = server.getPlayerList().getPlayerByName(StringArgumentType.getString(command, "name"));
                        if (player == null) throw new IllegalStateException("synthetic fake player not found");
                        var source = player.createCommandSourceStack().withSource(server);
                        var node = server.getCommands().getDispatcher().getRoot().getChild("chunkbackup");
                        boolean admin = source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.OWNERS));
                        command.getSource().sendSuccess(() -> Component.literal("VCB_TEST_PLAYER_SOURCE admin=" + admin
                                + " createAllowed=" + node.getChild("create").canUse(source)
                                + " configAllowed=" + node.getChild("config").canUse(source)
                                + " restoreAllowed=" + node.getChild("restore").canUse(source)), false);
                        server.getCommands().performPrefixedCommand(source, StringArgumentType.getString(command, "command"));
                        return 1;
                    }))));
            root.then(Commands.literal("saving").executes(command -> {
                var server = command.getSource().getServer();
                StringBuilder message = new StringBuilder("VCB_TEST_SAVING autoSave=" + server.isAutoSave());
                for (var level : server.getAllLevels()) message.append(' ').append(level.dimension().identifier()).append(".noSave=").append(level.noSave);
                command.getSource().sendSuccess(() -> Component.literal(message.toString()), false); return 1;
            }));
            root.then(Commands.literal("dimension-save-off").then(Commands.argument("dimension", DimensionArgument.dimension()).executes(command -> {
                DimensionArgument.getDimension(command, "dimension").noSave = true; return 1;
            })));
            root.then(Commands.literal("inspect").executes(command -> {
                var level = command.getSource().getServer().overworld();
                StringBuilder result = new StringBuilder("VCB_TEST_WORLD");
                for (int x : new int[] {8, 24, -8}) {
                    var blockEntity = level.getBlockEntity(new BlockPos(x, 4, 8));
                    result.append(" chest").append(x).append('=');
                    if (blockEntity instanceof Container container) {
                        var stack = container.getItem(0);
                        result.append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append(':').append(stack.getCount());
                    } else result.append("none");
                }
                var poi = level.getPoiManager().getType(new BlockPos(10, 4, 8));
                result.append(" poi=").append(poi.map(holder -> holder.unwrapKey().map(key -> key.identifier().toString()).orElse("unknown")).orElse("none"));
                result.append(" selectedEntities=").append(level.getEntitiesOfClass(ArmorStand.class, new AABB(0, -64, 0, 16, 320, 16), entity -> entity.entityTags().contains("vcb_selected")).size());
                command.getSource().sendSuccess(() -> Component.literal(result.toString()), false); return 1;
            }));
            dispatcher.register(root);
        });
    }
}

package local.hxvia.chunkbackup;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

public final class Messages {
    private Messages() {}
    public static MutableComponent text(String text, ChatFormatting color) {
        return Component.literal("[备份] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text).withStyle(color));
    }
    public static Component hint() {
        return text("手动备份：", ChatFormatting.GRAY).append(Component.literal("/chunkbackup create")
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent.SuggestCommand("/chunkbackup create"))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击填入命令")))));
    }
    public static Component completed(Snapshot snapshot) {
        return text("备份完成", ChatFormatting.GREEN).append(Component.literal(" · " + SnapshotNames.display(snapshot.createdAt))
                .withStyle(style -> style.withColor(ChatFormatting.GRAY)
                        .withClickEvent(new ClickEvent.CopyToClipboard(snapshot.id))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击复制备份编号")))));
    }
    public static Component snapshot(Snapshot snapshot) {
        String kind = snapshot.reason.equals("automatic") ? "自动" : "手动";
        String label = snapshot.label.isBlank() || snapshot.reason.equals("automatic") ? "" : " · " + snapshot.label;
        return text(SnapshotNames.display(snapshot.createdAt), ChatFormatting.AQUA)
                .append(Component.literal(" · " + kind + label).withStyle(ChatFormatting.GRAY))
                .withStyle(style -> style.withClickEvent(new ClickEvent.CopyToClipboard(snapshot.id))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击复制备份编号\n" + snapshot.id))));
    }
}

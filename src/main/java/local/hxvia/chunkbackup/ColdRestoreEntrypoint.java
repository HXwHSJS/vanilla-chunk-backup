package local.hxvia.chunkbackup;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public final class ColdRestoreEntrypoint implements PreLaunchEntrypoint {
    @Override public void onPreLaunch() {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) return;
        try { ColdRestore.processStartup(FabricLoader.getInstance().getGameDir()); }
        catch (Exception exception) { throw new IllegalStateException("离线回退未完成，已阻止世界启动。请检查事务日志或用离线 CLI 恢复/取消待执行计划。", exception); }
    }
}

# 管理与恢复

## 配置

管理员为控制台、权限等级 4 的玩家，或本地单人世界主人。单人世界主人无需开启作弊即可使用本模组的管理命令。

```text
/chunkbackup config intervalMinutes 60
/chunkbackup config keepSnapshots 24
/chunkbackup config manualCooldownSeconds 0
/chunkbackup verify <快照编号>
/chunkbackup prune
```

设置手动冷却为 0 可立即取消冷却。配置命令会保存到实例目录的 `config/vanilla_chunk_backup.json`；直接编辑文件后需重启。

```json
{
  "backupDirectory": "backups/vanilla-chunk-backup",
  "intervalMinutes": 60,
  "keepSnapshots": 24,
  "manualCooldownSeconds": 0,
  "minimumFreeSpaceMiB": 2048,
  "captureTimeoutSeconds": 60,
  "maxRestoreChunks": 4096
}
```

首次启用约 15 秒后建立自动基线；之后按墙钟时间调度。手动和自动合用 24 个保留位置，频繁手动备份会缩短覆盖时间。`captureTimeoutSeconds` 限制捕获阶段，不包含之前同步保存及之后落盘、清理。

备份先同步保存世界，再由后台线程捕获区块；捕获期间会短暂阻止区块文件写入。大存档首次基线可能产生停顿，不承诺零延迟。

## 独立服务器：停服区块回退

```text
/chunkbackup restore <快照编号> minecraft:overworld 0 0 1 1
/chunkbackup confirm <预览给出的确认码>
```

四个整数是区块坐标 `x1 z1 x2 z2`，包含两端，按从小到大填写。例子选择 4 个区块。方块坐标除以 16 后向负无穷取整，例如方块 X=-1 属于区块 X=-1。默认上限为 4096 个区块。

预览校验所选对象，确认码绑定操作者并在 5 分钟后失效。确认会正常停止服务器；管理员再次启动时，在世界打开前执行回退。日志出现“停服区块回退已提交”和正常的 `Done` 后，才表示服务器已重新就绪。服务管理器是否自动重启由其自身设置决定。

所选区块同时恢复地形、容器、实体和 POI，玩家背包、成就、统计及全局数据保持当前状态。快照中不存在的所选区块会被移除。跨区块移动的实体 UUID、区域外的红石、流体或村民记忆不属于局部回退的一致性保证；需要完整旧状态时使用整档导出。

## 单人世界与离线工具

单人模式先退出到标题界面，等待世界保存并关闭，再运行离线工具；游戏内 `restore` 会提示此步骤。同一 JAR 可由 Java 25 独立运行，无需额外 Fabric 类路径。

`WORLD` 为世界目录，`REPOSITORY` 为该世界 UUID 对应的备份仓库，`SNAPSHOT` 为完整快照编号。命令中的路径含空格时加引号。

```text
java -jar MOD.jar list WORLD REPOSITORY
java -jar MOD.jar verify WORLD REPOSITORY SNAPSHOT
java -jar MOD.jar restore WORLD REPOSITORY SNAPSHOT minecraft:overworld X1 Z1 X2 Z2
java -jar MOD.jar undo WORLD REPOSITORY TRANSACTION
java -jar MOD.jar recover WORLD REPOSITORY
java -jar MOD.jar prune WORLD REPOSITORY 24
java -jar MOD.jar export REPOSITORY SNAPSHOT NEW_DIRECTORY
```

工具独占世界和仓库锁，拒绝修改运行中的世界。回退事务在 `REPOSITORY/transactions/` 保留修改前原件；`undo` 只接受回退后尚无新保存的目标文件。原件不计入 24 份快照的自动清理，验收后可另行管理。

离线回退若被中断，先运行 `recover` 成功后再进入单人世界。独立服务器会在启动前检查其已登记世界的未完成事务；客户端不使用独立服务器的全局登记文件。

`export` 将完整快照重建到尚不存在的新目录，恢复所有维度及玩家、全局文件，拒绝覆盖已有目录。先验证导出的世界，再停服保存原目录并切换。恢复副本沿用原世界 UUID，避免让两个运行实例共用同一仓库。

独立服务器另有维护命令：

```text
java -jar MOD.jar startup SERVER_DIRECTORY
java -jar MOD.jar cancel-pending SERVER_DIRECTORY
```

`cancel-pending` 在恢复未完成事务后取消待执行计划，不撤销已提交回退。维护时使用原游戏进程账户，保持文件权限。

升级 1.1 不改写旧快照。1.0 不认识新的日期编号，升级后应使用 1.1 的工具读取整个仓库，不能直接换回 1.0 继续使用。

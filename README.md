# Vanilla Chunk Backup

Minecraft **26.3 / Fabric** 的区块增量备份模组，支持独立服务器和本地单人世界。服务端和客户端使用同一个 JAR，约 69 KiB；除 Fabric API 外，无额外运行依赖。

## 玩家使用

```text
/chunkbackup create
/chunkbackup create 建造前
/chunkbackup list
/chunkbackup status
```

进入世界后会收到一行提示：**[备份] 手动备份：/chunkbackup create**。点击命令即可填入聊天框。金色标识、青色命令、绿色成功、红色错误，备份列表显示时间；点击时间可复制完整编号。

默认每小时自动备份，手动和自动合计保留最近 **24 份**。手动备份默认无冷却，管理员可以调整；同一时间处理一个任务。

每次备份记录完整世界索引，地形、实体和 POI 按区块去重，只保存新增内容；其他世界文件按文件去重。任一保留快照都可独立恢复，无需逐份叠加。

## 安装

从 [Releases](https://github.com/HXwHSJS/vanilla-chunk-backup/releases) 下载 `vanilla-chunk-backup-1.1.0+26.3.jar`，放入实例的 `mods/`，重新启动游戏或服务器。

需要 Minecraft 26.3、Fabric Loader 0.20.1 或更高版本、Fabric API 0.165.0+26.3 或更高兼容版本，以及 Java 25。其他 Minecraft 版本尚未验证。

服务器安装后，普通玩家无需安装客户端模组即可使用命令。客户端安装用于备份自己的单人世界或由本机托管的局域网世界；远程服务器的备份由服务器负责。

## 备份时间与位置

新快照文件名使用北京时间（UTC+8），例如：

```text
2026-11-08_10-30-00_123+0800-a1b2c3d4.vcb.gz
```

日期后依次是时分秒、毫秒和防重名后缀。1.0 的旧编号继续兼容，列表按实际备份时间排序。内容对象仍以哈希命名，快照文件和 `objects/` 需一起保留。

```text
实例目录/backups/vanilla-chunk-backup/<世界 UUID>/
实例目录/config/vanilla_chunk_backup.json
```

本地多个世界分别保存备份，配置在同一游戏实例内共用。

管理员配置、停服区块回退和单人世界离线恢复见 [管理说明](docs/administration.md)。构建与验证见 [开发说明](docs/development.md)，存储和事务设计见 [设计说明](docs/design.md)。第二版热回退处于准备阶段，尚未提供在线回退功能。

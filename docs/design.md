# 存储与事务设计 · schema 1

当前实现固定面向 Minecraft 26.3 / Fabric / Java 25。客户端与服务端共用捕获、索引、去重和离线恢复代码。

## 数据组织

```text
WORLD/.vanilla-chunk-backup-id              世界 UUID
REPOSITORY/repository.lock                 仓库锁
REPOSITORY/objects/HH/SHA256                不可变内容对象
REPOSITORY/snapshots/ID.vcb.gz              完整索引
REPOSITORY/transactions/restore-ID/         回退日志和修改前原件
INSTANCE/config/vanilla_chunk_backup.json  实例配置
```

每个索引独立引用所有必需对象，没有增量链。区块键由相对存储目录和区块坐标组成，地形、实体、POI 分别记录。对象保留 Minecraft 原生压缩记录，`.mcc` 外置数据归入对应区块；其他世界文件各自按内容去重。单个区块对象上限为 64 MiB。

索引使用 JDK DataStream 和 gzip。对象、索引及事务日志通过临时文件、同步落盘和原子替换提交。路径限定在各自根目录内，不接受符号链接。

1.1 的索引 ID 使用 `Asia/Shanghai` 日期、时分秒、毫秒、时区和随机后缀，仍接受旧 UTC ID。最新索引选择及保留清理由索引头的 `createdAt` 排序，避免混合文件名的字典顺序误判时间。

## 在线捕获

1. 主线程取得仓库锁并同步保存、flush 世界。
2. 记录各维度 `noSave` 原值并临时暂停保存，取得 `DiskGate` 写 stamp。
3. 主线程捕获维度映射、世界附属文件及区域文件指纹。
4. 后台单线程读取变化的区域文件，逐区块哈希去重；指纹未变时复用上一索引的条目。
5. 后台 finally 释放 gate，安排主线程恢复原保存状态，再同步对象、提交索引并清理过期内容。

RegionFile 的 `write` 和 `clear` Mixin 取得 gate 读 stamp，捕获期间等待。`StampedLock` 允许主线程取得的写 stamp 由后台线程释放。后台不等待主线程回调；停服时主线程等待后台任务完成，再恢复保存开关。

该流程可能通过同步保存、附属文件捕获和区块 I/O 等待影响 tick。超时或捕获错误不发布索引；已发布后的清理错误单独报告，避免把已保存的快照误称为捕获失败。

## 保留和验证

清理先完整读取保留索引，计算可达对象，再删除过期索引并回收未引用对象。保留索引不可读时停止删除。普通列表只读索引头；捕获只加载最新一份完整索引，清理逐份读取。

显式 `verify` 验证所有引用对象的 SHA-256；恢复和导出在读取时验证。日常捕获复用不可变对象，不每小时重复校验全部历史内容。空间预留在开始、写入过程中及发布前检查，不能保证其他进程不会同时耗尽磁盘。

## 停服回退

确认计划先持久保存，再正常关闭服务器。独立服务器 preLaunch 在世界加载前取得世界 `session.lock` 和仓库锁，校验世界 UUID、路径、`level-name` 与快照身份。客户端跳过独立服务器全局登记；单人恢复由离线 CLI 执行。

对所选维度的 region/entities/poi，把受影响的 MCA/MCC 生成到 staging，保留未选区块的原始 payload 和时间戳。修改前原件与校验值写入 before，随后推进事务：

```text
PREPARING -> PREPARED -> APPLYING -> COMMITTED
                           |
                           +-> RECOVERING -> ROLLED_BACK
COMMITTED -> UNDOING -> RECOVERING -> UNDONE
```

准备阶段中断可直接终止；应用或撤销阶段中断则按原件继续恢复。原件校验或恢复失败会阻止独立服务器打开世界。文件系统没有跨文件原子提交，完整性依赖离线锁、持久日志及启动前恢复。

整档导出只读索引和对象，在相邻临时目录重建完整世界，完成同步后原子移动到一个新目录。局部回退不解释实体 UUID 或区域外关联，也不恢复玩家、全局 SavedData；完整旧状态需整档恢复。

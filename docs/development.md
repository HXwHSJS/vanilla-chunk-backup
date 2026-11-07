# 构建与验证

使用 Python 3 和带 `jdk.compiler` 的 Java 25。构建依赖来自已安装的 Minecraft 26.3 / Fabric 实例，不下载或改写游戏文件。

从独立服务器构建：

```sh
python3 build.py --server /path/to/server --java /path/to/java25/bin/java
```

从客户端版本目录构建，该目录需包含同名版本 JSON/JAR、Fabric API 和已处理的 Fabric 模组：

```sh
python build.py --client /path/to/.minecraft/versions/PROFILE --java /path/to/java25/bin/java
```

客户端库从 `.minecraft/libraries` 读取。Windows 编译使用参数文件，避免命令行长度限制。产物在 `build/`，JAR 仅包含 `src/main/`；测试辅助源码和 Minecraft/Fabric 依赖不会打包。构建回执含本机依赖路径，不作为发布资产。

`src/test/` 为纯 JDK 合成数据核心测试，构建时自动执行。覆盖去重、索引保留、负坐标、外置区块、恢复/撤销、持久中断状态、新旧日期编号与时间排序。

## 单人客户端检查

`src/clientTest/` 是明确标记的隔离测试辅助模组，隐藏窗口且不捕获鼠标。只在全新测试实例内加载；不要把辅助 JAR 安装到玩家实例。

```sh
python tools/build_client_probe.py --java /path/to/java25/bin/java
python tools/run_client_smoke.py --client /path/to/PROFILE --java /path/to/java25/bin/java --fixture /path/to/SYNTHETIC_WORLD
```

运行器只从已安装实例读取库、资源和 Fabric API，在 `build/client-smoke-<时间>/` 复制合成世界并使用虚拟本地身份运行。检查真实玩家命令、进世界提示的颜色与点击动作、完成文本、连续备份和区块对象复用、SHA-256，以及单人主人配置权限。新生成区块可能使第二份增量字节更多，因此按共享对象验证增量，而不把“第二份必然更小”作为条件。

## 独立服务器检查

`src/trial/` 是隔离服务器的测试辅助模组，读取合成检查点并构造非 OP 玩家命令来源。构建时单独生成 `vcb-trial-probe.jar`，不属于发布资产。测试实例另需安装兼容的 Carpet，以创建合成测试玩家。

`tools/server_smoke.py --help` 给出参数。它只接受已有合成标记、合成世界名、回环监听及专用测试服务名的停止实例，需要 Linux/systemd 和相应管理权限。实例预先提供 `launch.sh`、`console.fifo` 和游戏依赖。测试会替换该测试实例中的本模组、修改合成世界、停服/启动，并最终停止测试服务。

可通过 `--watch-unit` 记录生产服务状态和 PID；这些被观察的服务不会由脚本控制。测试含普通玩家权限、零冷却、新旧索引验证、日期列表、真实区块停服回退、相邻区块保持、定时触发及整档导出。`--sha256` 必须与候选 JAR 相同。

## 验证边界

1.1.0 已通过 47 项核心检查、18 项独立服务器检查和真实单人客户端隔离检查。成品哈希与具体证据摘要见 [版本验证记录](verification-1.1.0.json)。测试单人世界的 `allowCommands` 为 false，主人仍成功执行配置命令。

合成世界通过不等于任意大型模组环境都兼容。硬件掉电、多玩家负载和跨区域实体 UUID 冲突消解没有验证。正式世界的检查限于正常启动、备份及对象校验，不在真实存档上试验回退。热回退尚未实现。

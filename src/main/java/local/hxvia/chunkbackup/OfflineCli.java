package local.hxvia.chunkbackup;

import java.nio.file.Path;

public final class OfflineCli {
    private OfflineCli() {}
    public static void main(String[] args) {
        try { run(args); }
        catch (Exception exception) { System.err.println("失败: " + exception.getMessage()); exception.printStackTrace(System.err); System.exit(1); }
    }

    private static void run(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("startup")) { ColdRestore.processStartup(Path.of(args[1])); return; }
        if (args.length == 2 && args[0].equals("cancel-pending")) { ColdRestore.cancel(Path.of(args[1])); return; }
        if (args.length == 4 && args[0].equals("export")) {
            if (!SnapshotNames.valid(args[2])) throw new IllegalArgumentException("快照编号无效");
            Path repository = Path.of(args[1]).toAbsolutePath().normalize(), output = Path.of(args[3]).toAbsolutePath().normalize();
            Snapshot header = Snapshot.header(Io.within(repository, "snapshots/" + args[2] + ".vcb.gz"));
            BackupStore store = new BackupStore(output, repository, header.worldId);
            try (var lock = store.lock()) { WorldExport.export(store, store.load(args[2]), output); }
            System.out.println("整档快照已导出到新目录: " + output); return;
        }
        if (args.length < 3) {
            System.out.println("用法（均先停服；坐标为区块坐标，范围包含两端）：\n"
                    + "  java -jar MOD.jar list WORLD REPOSITORY\n"
                    + "  java -jar MOD.jar verify WORLD REPOSITORY SNAPSHOT\n"
                    + "  java -jar MOD.jar restore WORLD REPOSITORY SNAPSHOT DIMENSION X1 Z1 X2 Z2\n"
                    + "  java -jar MOD.jar undo WORLD REPOSITORY TRANSACTION\n"
                    + "  java -jar MOD.jar recover WORLD REPOSITORY\n"
                    + "  java -jar MOD.jar prune WORLD REPOSITORY KEEP_COUNT\n"
                    + "  java -jar MOD.jar export REPOSITORY SNAPSHOT NEW_DIRECTORY\n"
                    + "  java -jar MOD.jar startup SERVER_DIRECTORY\n"
                    + "  java -jar MOD.jar cancel-pending SERVER_DIRECTORY"); return;
        }
        Path world = Path.of(args[1]).toAbsolutePath().normalize();
        String id = java.nio.file.Files.readString(world.resolve(BackupStore.WORLD_MARKER)).trim();
        BackupStore store = new BackupStore(world, Path.of(args[2]), id);
        try (var worldLock = new ColdRestore.WorldLock(world); var repositoryLock = store.lock()) {
            ColdRestore.recover(store);
            switch (args[0]) {
                case "list" -> { for (Snapshot snapshot : store.list()) System.out.println(SnapshotNames.display(snapshot.createdAt) + " +0800 | " + snapshot.id + " | " + snapshot.reason + " | " + snapshot.label); }
                case "verify" -> { if (args.length != 4) throw new IllegalArgumentException("verify 参数数目错误"); store.verify(store.load(args[3])); System.out.println("快照所有对象 SHA-256 校验通过"); }
                case "restore" -> {
                    if (args.length != 9) throw new IllegalArgumentException("restore 参数数目错误");
                    Selection selection = new Selection(args[4], Integer.parseInt(args[5]), Integer.parseInt(args[6]), Integer.parseInt(args[7]), Integer.parseInt(args[8]));
                    System.out.println("已完成停服回退: " + ColdRestore.restore(store, store.load(args[3]), selection, ColdRestore.transactionId()));
                }
                case "undo" -> { if (args.length != 4) throw new IllegalArgumentException("undo 参数数目错误"); ColdRestore.undo(store, args[3]); System.out.println("已撤销回退"); }
                case "recover" -> System.out.println("未完成事务已恢复");
                case "prune" -> { if (args.length != 4) throw new IllegalArgumentException("prune 参数数目错误"); store.prune(Integer.parseInt(args[3])); System.out.println("仓库清理完成"); }
                default -> throw new IllegalArgumentException("未知命令: " + args[0]);
            }
        }
    }
}

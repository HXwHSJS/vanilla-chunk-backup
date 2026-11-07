package local.hxvia.chunkbackup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BackupConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public String backupDirectory = "backups/vanilla-chunk-backup";
    public int intervalMinutes = 60;
    public int keepSnapshots = 24;
    public int manualCooldownSeconds = 0;
    public int minimumFreeSpaceMiB = 2048;
    public int captureTimeoutSeconds = 60;
    public int maxRestoreChunks = 4096;

    public void validate() throws IOException {
        if (backupDirectory == null || backupDirectory.isBlank()) throw new IOException("backupDirectory 不能为空");
        if (intervalMinutes < 1 || intervalMinutes > 10_080) throw new IOException("intervalMinutes 范围为 1..10080");
        if (keepSnapshots < 1 || keepSnapshots > 1000) throw new IOException("keepSnapshots 范围为 1..1000");
        if (manualCooldownSeconds < 0 || manualCooldownSeconds > 604_800) throw new IOException("manualCooldownSeconds 范围为 0..604800");
        if (minimumFreeSpaceMiB < 64 || minimumFreeSpaceMiB > 1_048_576) throw new IOException("minimumFreeSpaceMiB 范围为 64..1048576");
        if (captureTimeoutSeconds < 5 || captureTimeoutSeconds > 120) throw new IOException("captureTimeoutSeconds 范围为 5..120");
        if (maxRestoreChunks < 1 || maxRestoreChunks > 65_536) throw new IOException("maxRestoreChunks 范围为 1..65536");
    }

    public static BackupConfig load(Path path) throws IOException {
        BackupConfig config;
        try { config = Files.exists(path) ? GSON.fromJson(Files.readString(path), BackupConfig.class) : new BackupConfig(); }
        catch (RuntimeException exception) { throw new IOException("备份配置 JSON 无效", exception); }
        if (config == null) throw new IOException("备份配置不能为空");
        config.validate(); if (!Files.exists(path)) config.save(path); return config;
    }

    public void save(Path path) throws IOException { validate(); Io.atomicWrite(path, (GSON.toJson(this) + "\n").getBytes(StandardCharsets.UTF_8)); }
    public BackupConfig copy() { return GSON.fromJson(GSON.toJson(this), BackupConfig.class); }

    public void set(String name, int value) throws IOException {
        switch (name) {
            case "intervalMinutes" -> intervalMinutes = value;
            case "keepSnapshots" -> keepSnapshots = value;
            case "manualCooldownSeconds" -> manualCooldownSeconds = value;
            case "minimumFreeSpaceMiB" -> minimumFreeSpaceMiB = value;
            case "captureTimeoutSeconds" -> captureTimeoutSeconds = value;
            case "maxRestoreChunks" -> maxRestoreChunks = value;
            default -> throw new IOException("未知配置项: " + name);
        }
        validate();
    }
}

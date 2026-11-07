package local.hxvia.chunkbackup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class Io {
    private Io() {}

    public static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    public static String hash(byte[] data) { return HexFormat.of().formatHex(digest().digest(data)); }

    public static String hash(Path path) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[131072];
            for (int length; (length = input.read(buffer)) != -1;) digest.update(buffer, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static Path within(Path root, String relative) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path candidate = base.resolve(relative).normalize();
        if (relative.isBlank() || Path.of(relative).isAbsolute() || !candidate.startsWith(base) || candidate.equals(base))
            throw new IOException("路径不在目标目录内: " + relative);
        for (Path current = candidate; current != null && !current.equals(base.getParent()); current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("不处理符号链接: " + current);
            if (current.equals(base)) break;
        }
        return candidate;
    }

    public static void rejectLinks(Path path) throws IOException {
        for (Path current = path.toAbsolutePath().normalize(); current != null; current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IOException("不处理符号链接: " + current);
    }

    public static String relative(Path root, Path path) {
        return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    public static Path temporary(Path target) {
        return target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
    }

    public static void directories(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize(); rejectLinks(absolute);
        java.util.List<Path> missing = new java.util.ArrayList<>();
        for (Path current = absolute; current != null && !Files.exists(current); current = current.getParent()) missing.add(current);
        for (int i = missing.size() - 1; i >= 0; i--) {
            Path folder = missing.get(i); Files.createDirectory(folder); forceDirectory(folder.getParent());
        }
    }

    public static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    public static void forceDirectory(Path path) throws IOException {
        if (System.getProperty("os.name").startsWith("Windows")) return;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
    }

    public static void atomicMove(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        forceDirectory(target.getParent());
    }

    public static void atomicWrite(Path target, byte[] bytes) throws IOException {
        directories(target.getParent());
        Path temp = temporary(target);
        try {
            Files.write(temp, bytes, StandardOpenOption.CREATE_NEW);
            force(temp);
            atomicMove(temp, target);
        } finally { Files.deleteIfExists(temp); }
    }

    public static void copyDurable(Path source, Path target) throws IOException {
        directories(target.getParent());
        Path temp = temporary(target);
        try {
            Files.copy(source, temp);
            force(temp);
            atomicMove(temp, target);
        } finally { Files.deleteIfExists(temp); }
    }

    public static boolean regular(Path path) { return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS); }
}

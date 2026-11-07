package local.hxvia.chunkbackup;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Copies native compressed records without interpreting NBT, including external .mcc records. */
public final class RegionIo {
    private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int SECTOR = 4096;
    public static final int MAX_RECORD_BYTES = 64 * 1024 * 1024;

    private RegionIo() {}

    public record Coordinates(int x, int z) {}
    public record Record(byte[] data, int timestamp) {}
    public record Image(Path region, Map<String, Path> externalWrites, List<String> externalDeletes) {}
    @FunctionalInterface public interface Visitor { void accept(int x, int z, byte[] data) throws IOException; }

    public static Coordinates coordinates(Path path) throws IOException {
        var match = NAME.matcher(path.getFileName().toString());
        if (!match.matches()) throw new IOException("不是区域文件: " + path);
        try { return new Coordinates(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2))); }
        catch (NumberFormatException exception) { throw new IOException("区域坐标无效", exception); }
    }

    public static boolean isRegion(Path path) {
        String parent = path.getParent().getFileName().toString();
        return (parent.equals("region") || parent.equals("entities") || parent.equals("poi"))
                && NAME.matcher(path.getFileName().toString()).matches();
    }

    public static boolean isExternal(Path path) {
        String parent = path.getParent().getFileName().toString();
        return (parent.equals("region") || parent.equals("entities") || parent.equals("poi"))
                && path.getFileName().toString().matches("c\\.-?\\d+\\.-?\\d+\\.mcc");
    }

    public static void visit(Path path, Visitor visitor) throws IOException {
        Coordinates region = coordinates(path);
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            int[][] header = readHeader(file);
            for (int index = 0; index < 1024; index++) {
                int x = Math.addExact(Math.multiplyExact(region.x(), 32), index % 32);
                int z = Math.addExact(Math.multiplyExact(region.z(), 32), index / 32);
                Record record = read(file, path.getParent(), header, index, x, z);
                if (record != null) visitor.accept(x, z, record.data());
            }
        }
    }

    public static Record readChunk(Path regionPath, int x, int z) throws IOException {
        if (!Files.exists(regionPath)) return null;
        try (RandomAccessFile file = new RandomAccessFile(regionPath.toFile(), "r")) {
            return read(file, regionPath.getParent(), readHeader(file), Math.floorMod(x, 32) + 32 * Math.floorMod(z, 32), x, z);
        }
    }

    private static int[][] readHeader(RandomAccessFile file) throws IOException {
        int[][] header = new int[2][1024];
        if (file.length() == 0) return header;
        if (file.length() < 8192) throw new IOException("区域文件头不完整");
        file.seek(0);
        for (int index = 0; index < 1024; index++) header[0][index] = file.readInt();
        for (int index = 0; index < 1024; index++) header[1][index] = file.readInt();
        return header;
    }

    private static Record read(RandomAccessFile file, Path folder, int[][] header, int index, int x, int z) throws IOException {
        int offset = header[0][index];
        if (offset == 0) return null;
        int sector = offset >>> 8;
        int count = offset & 255;
        if (sector < 2 || count == 0 || (long) sector * SECTOR + 5 > file.length()) throw new IOException("区域扇区索引无效");
        file.seek((long) sector * SECTOR);
        int length = file.readInt();
        int version = file.readUnsignedByte();
        if (length < 1 || length > count * SECTOR - 4) throw new IOException("区块长度与扇区索引不符");
        byte[] data;
        if ((version & 128) != 0) {
            Path external = folder.resolve("c." + x + "." + z + ".mcc");
            long size = Files.size(external);
            if (length != 1 || size > MAX_RECORD_BYTES - 1) throw new IOException("外部区块文件大小无效");
            data = new byte[(int) size + 1]; data[0] = (byte) (version & 127);
            try (var input = Files.newInputStream(external)) {
                int used = input.readNBytes(data, 1, (int) size);
                if (used != size || input.read() != -1) throw new IOException("外部区块文件在读取时改变");
            }
        } else {
            if ((long) sector * SECTOR + 4 + length > file.length()) throw new IOException("区块数据不完整");
            data = new byte[length]; data[0] = (byte) version;
            file.readFully(data, 1, length - 1);
        }
        return new Record(data, header[1][index]);
    }

    /** All neighboring records and their timestamps survive the rewrite unchanged. Null means remove a chunk. */
    public static Image rewrite(Path source, Path stagedTarget, Map<Integer, byte[]> changes) throws IOException {
        Io.directories(stagedTarget.getParent());
        Coordinates region = coordinates(source);
        Map<String, Path> externalWrites = new LinkedHashMap<>();
        List<String> externalDeletes = new ArrayList<>();
        try (RandomAccessFile input = Files.exists(source) ? new RandomAccessFile(source.toFile(), "r") : null;
             RandomAccessFile output = new RandomAccessFile(stagedTarget.toFile(), "rw")) {
            int[][] oldHeader = input == null ? new int[2][1024] : readHeader(input);
            int[][] newHeader = new int[2][1024];
            output.setLength(8192); output.seek(8192);
            int nextSector = 2;
            for (int index = 0; index < 1024; index++) {
                int x = Math.addExact(Math.multiplyExact(region.x(), 32), index % 32);
                int z = Math.addExact(Math.multiplyExact(region.z(), 32), index / 32);
                boolean changed = changes.containsKey(index);
                Record oldRecord = !changed && input != null ? read(input, source.getParent(), oldHeader, index, x, z) : null;
                byte[] data = changed ? changes.get(index) : oldRecord == null ? null : oldRecord.data();
                String externalName = "c." + x + "." + z + ".mcc";
                if (data == null) {
                    if (changed && Files.exists(source.getParent().resolve(externalName))) externalDeletes.add(externalName);
                    continue;
                }
                if (data.length < 1 || data.length > MAX_RECORD_BYTES) throw new IOException("区块对象长度无效");
                int sectors = (int) (((long) data.length + 4 + SECTOR - 1) / SECTOR);
                boolean external = sectors >= 256;
                if (external) {
                    Path stagedExternal = stagedTarget.getParent().resolve(externalName);
                    Files.write(stagedExternal, java.util.Arrays.copyOfRange(data, 1, data.length));
                    Io.force(stagedExternal); externalWrites.put(externalName, stagedExternal); sectors = 1;
                } else if (changed && Files.exists(source.getParent().resolve(externalName))) externalDeletes.add(externalName);
                if (nextSector > 0xFFFFFF) throw new IOException("区域文件超过格式限制");
                newHeader[0][index] = (nextSector << 8) | sectors;
                newHeader[1][index] = changed ? (int) (System.currentTimeMillis() / 1000) : oldRecord.timestamp();
                output.seek((long) nextSector * SECTOR);
                output.writeInt(external ? 1 : data.length);
                if (external) output.writeByte((data[0] & 127) | 128);
                else output.write(data);
                nextSector += sectors;
            }
            output.setLength((long) nextSector * SECTOR); output.seek(0);
            for (int index = 0; index < 1024; index++) output.writeInt(newHeader[0][index]);
            for (int index = 0; index < 1024; index++) output.writeInt(newHeader[1][index]);
            output.getChannel().force(true);
        }
        return new Image(stagedTarget, externalWrites, externalDeletes);
    }
}

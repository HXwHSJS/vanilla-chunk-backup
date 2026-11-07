package local.hxvia.chunkbackup;

import java.io.IOException;

/** Coordinates are chunk coordinates, inclusive at both ends. */
public record Selection(String dimension, int x1, int z1, int x2, int z2) {
    public Selection {
        if (dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("维度 ID 无效");
        if (x1 > x2 || z1 > z2 || x1 < -1_875_000 || z1 < -1_875_000 || x2 > 1_874_999 || z2 > 1_874_999)
            throw new IllegalArgumentException("区块坐标范围无效；两个角须按从小到大填写");
    }

    public long count() { return ((long) x2 - x1 + 1) * ((long) z2 - z1 + 1); }
    public boolean contains(int x, int z) { return x >= x1 && x <= x2 && z >= z1 && z <= z2; }

    public void validate(Snapshot snapshot, int maximum) throws IOException {
        if (!snapshot.dimensions.containsKey(dimension)) throw new IOException("快照不含此维度: " + dimension);
        if (count() > maximum) throw new IOException("本次选择 " + count() + " 个区块，超过上限 " + maximum);
    }
}

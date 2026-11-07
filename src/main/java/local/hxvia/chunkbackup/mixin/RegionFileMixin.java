package local.hxvia.chunkbackup.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import local.hxvia.chunkbackup.DiskGate;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;

import java.io.IOException;
import java.nio.ByteBuffer;

@Mixin(RegionFile.class)
abstract class RegionFileMixin {
    @WrapMethod(method = "write(Lnet/minecraft/world/level/ChunkPos;Ljava/nio/ByteBuffer;)V")
    private void chunkbackup$write(ChunkPos pos, ByteBuffer buffer, Operation<Void> original) throws IOException {
        long stamp = DiskGate.beginWrite();
        try { original.call(pos, buffer); }
        finally { DiskGate.endWrite(stamp); }
    }

    @WrapMethod(method = "clear(Lnet/minecraft/world/level/ChunkPos;)V")
    private void chunkbackup$clear(ChunkPos pos, Operation<Void> original) throws IOException {
        long stamp = DiskGate.beginWrite();
        try { original.call(pos); }
        finally { DiskGate.endWrite(stamp); }
    }
}

package local.hxvia.chunkbackup.clienttest.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class NoMouseCaptureMixin {
    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void keepUserMouse(CallbackInfo callback) { callback.cancel(); }
}

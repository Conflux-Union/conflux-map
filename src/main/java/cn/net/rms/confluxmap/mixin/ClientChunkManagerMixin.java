package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.mc.snapshot.ChunkCaptureHandler;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkManager.class)
public abstract class ClientChunkManagerMixin {
    @Inject(method = "loadChunkFromPacket", at = @At("RETURN"))
    private void confluxmap$onChunkLoaded(final CallbackInfoReturnable<WorldChunk> cir) {
        final WorldChunk chunk = cir.getReturnValue();
        if (chunk != null) {
            ChunkCaptureHandler.chunkDirty(chunk.getPos().x, chunk.getPos().z);
        }
    }
}

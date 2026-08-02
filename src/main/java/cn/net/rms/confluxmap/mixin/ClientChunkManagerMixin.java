package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.mc.snapshot.ChunkCaptureHandler;
import cn.net.rms.confluxmap.mc.world.ClientWorldIdentityHandler;
import net.minecraft.client.world.ClientChunkManager;
//#if MC>=12100
//$$ import net.minecraft.util.math.ChunkPos;
//#endif
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkManager.class)
public abstract class ClientChunkManagerMixin {
    @Inject(method = "loadChunkFromPacket", at = @At("RETURN"))
    private void confluxmap$onChunkLoaded(final CallbackInfoReturnable<WorldChunk> cir) {
        final WorldChunk chunk = cir.getReturnValue();
        if (chunk != null) {
            //#if MC>=260100
            //$$ ChunkCaptureHandler.chunkDirty(chunk.getPos().x(), chunk.getPos().z());
            //$$ ClientWorldIdentityHandler.fullChunkLoaded(chunk.getPos().x(), chunk.getPos().z());
            //#else
            ChunkCaptureHandler.chunkDirty(chunk.getPos().x, chunk.getPos().z);
            ClientWorldIdentityHandler.fullChunkLoaded(chunk.getPos().x, chunk.getPos().z);
            //#endif
        }
    }

    //#if MC>=12100
    //$$ @Inject(method = "unload", at = @At("HEAD"), require = 0)
    //$$ private void confluxmap$onChunkUnloaded(final ChunkPos pos, final CallbackInfo ci) {
    //#if MC>=260100
    //$$     ClientWorldIdentityHandler.fullChunkUnloaded(pos.x(), pos.z());
    //#else
    //$$     ClientWorldIdentityHandler.fullChunkUnloaded(pos.x, pos.z);
    //#endif
    //$$ }
    //#else
    @Inject(method = "unload", at = @At("HEAD"), require = 0)
    private void confluxmap$onChunkUnloaded(final int chunkX, final int chunkZ, final CallbackInfo ci) {
        ClientWorldIdentityHandler.fullChunkUnloaded(chunkX, chunkZ);
    }
    //#endif
}

package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.server.ServerChunkDirtyHandler;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldChunk.class)
public abstract class WorldChunkMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void confluxmap$afterBlockStateChanged(
        final BlockPos pos,
        final BlockState state,
        //#if MC>=12105
        //$$ final int flags,
        //#else
        final boolean moved,
        //#endif
        final CallbackInfoReturnable<BlockState> cir
    ) {
        if (cir.getReturnValue() != null) {
            ServerChunkDirtyHandler.chunkDirty((WorldChunk) (Object) this);
        }
    }
}

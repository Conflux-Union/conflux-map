package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.mc.snapshot.ChunkCaptureHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onBlockUpdate", at = @At("TAIL"))
    private void confluxmap$onBlockUpdate(final BlockUpdateS2CPacket packet, final CallbackInfo ci) {
        ChunkCaptureHandler.blockDirty(packet.getPos().getX(), packet.getPos().getZ());
    }

    @Inject(method = "onChunkDeltaUpdate", at = @At("TAIL"))
    private void confluxmap$onChunkDeltaUpdate(final ChunkDeltaUpdateS2CPacket packet, final CallbackInfo ci) {
        packet.visitUpdates((pos, state) -> ChunkCaptureHandler.blockDirty(pos.getX(), pos.getZ()));
    }
}

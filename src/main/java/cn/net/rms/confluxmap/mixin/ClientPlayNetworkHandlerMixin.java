package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.mc.snapshot.ChunkCaptureHandler;
import cn.net.rms.confluxmap.mc.world.ClientWorldIdentityHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
//#if MC>=12100
//$$ import net.minecraft.network.packet.s2c.play.LightData;
//#else
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
//#endif
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onGameJoin", at = @At("TAIL"))
    private void confluxmap$onGameJoin(final GameJoinS2CPacket packet, final CallbackInfo ci) {
        //#if MC>=12100
        //$$ ClientWorldIdentityHandler.gameJoin(packet.commonPlayerSpawnInfo().seed());
        //#else
        ClientWorldIdentityHandler.gameJoin(packet.getSha256Seed());
        //#endif
    }

    @Inject(method = "onPlayerRespawn", at = @At("TAIL"))
    private void confluxmap$onPlayerRespawn(final PlayerRespawnS2CPacket packet, final CallbackInfo ci) {
        //#if MC>=12100
        //$$ ClientWorldIdentityHandler.respawn(packet.commonPlayerSpawnInfo().seed());
        //#else
        ClientWorldIdentityHandler.respawn(packet.getSha256Seed());
        //#endif
    }

    @Inject(method = "onBlockUpdate", at = @At("TAIL"))
    private void confluxmap$onBlockUpdate(final BlockUpdateS2CPacket packet, final CallbackInfo ci) {
        ChunkCaptureHandler.blockDirty(packet.getPos().getX(), packet.getPos().getZ());
    }

    @Inject(method = "onChunkDeltaUpdate", at = @At("TAIL"))
    private void confluxmap$onChunkDeltaUpdate(final ChunkDeltaUpdateS2CPacket packet, final CallbackInfo ci) {
        packet.visitUpdates((pos, state) -> ChunkCaptureHandler.blockDirty(pos.getX(), pos.getZ()));
    }

    //#if MC>=12103
    //$$ @Inject(method = "readLightData", at = @At("TAIL"))
    //$$ private void confluxmap$afterLightDataApplied(
    //$$     final int chunkX,
    //$$     final int chunkZ,
    //$$     final LightData lightData,
    //$$     final boolean trustEdges,
    //$$     final CallbackInfo ci
    //$$ ) {
    //$$     ChunkCaptureHandler.chunkDirty(chunkX, chunkZ);
    //$$ }
    //#elseif MC>=12100
    //$$ @Inject(method = "readLightData", at = @At("TAIL"))
    //$$ private void confluxmap$afterLightDataApplied(
    //$$     final int chunkX,
    //$$     final int chunkZ,
    //$$     final LightData lightData,
    //$$     final CallbackInfo ci
    //$$ ) {
    //$$     ChunkCaptureHandler.chunkDirty(chunkX, chunkZ);
    //$$ }
    //#else
    @Inject(method = "onLightUpdate", at = @At("TAIL"))
    private void confluxmap$afterLightDataApplied(final LightUpdateS2CPacket packet, final CallbackInfo ci) {
        ChunkCaptureHandler.chunkDirty(packet.getChunkX(), packet.getChunkZ());
    }
    //#endif
}

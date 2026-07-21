package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatClickPayload;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatCodec;
import cn.net.rms.confluxmap.mc.snapshot.ChunkCaptureHandler;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
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

    @ModifyArg(
        method = "onGameMessage",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/InGameHud;addChatMessage("
                + "Lnet/minecraft/network/MessageType;Lnet/minecraft/text/Text;Ljava/util/UUID;)V"
        ),
        index = 1
    )
    private Text confluxmap$addWaypointImportAction(final Text original) {
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return original;
        }

        final Identifier dimensionIdentifier = client.world.getRegistryKey().getValue();
        final DimensionId receivedDimension = DimensionId.of(
            dimensionIdentifier.getNamespace(), dimensionIdentifier.getPath()
        );
        final String visibleMessage = original.getString();
        final Optional<String> payload = WaypointChatClickPayload.encode(visibleMessage, receivedDimension);
        if (!payload.isPresent() || !WaypointChatCodec.parse(visibleMessage, receivedDimension).isPresent()) {
            return original;
        }

        final MutableText importAction = new TranslatableText("confluxmap.chat.waypoint.import")
            .setStyle(Style.EMPTY
                .withColor(Formatting.AQUA)
                .withUnderline(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, payload.get())));
        return new LiteralText("")
            .append(original.copy())
            .append(new LiteralText(" "))
            .append(importAction);
    }
}

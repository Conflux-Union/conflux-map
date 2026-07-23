package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatClickPayload;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatCodec;
import cn.net.rms.confluxmap.mc.snapshot.ChunkCaptureHandler;
import cn.net.rms.confluxmap.compat.Texts;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
//#if MC<12100
import net.minecraft.network.MessageType;
//#endif
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

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

    //#if MC>=12100
    //$$ @ModifyArgs(
    //$$     method = "onGameMessage",
    //$$     at = @At(
    //$$         value = "INVOKE",
    //$$         target = "Lnet/minecraft/client/network/message/MessageHandler;onGameMessage("
    //$$             + "Lnet/minecraft/text/Text;Z)V"
    //$$     )
    //$$ )
    //$$ private void confluxmap$addWaypointImportActionModern(final Args args) {
    //$$     if (args.<Boolean>get(1)) {
    //$$         return;
    //$$     }
    //$$     args.set(0, confluxmap$appendWaypointImportAction(args.get(0)));
    //$$ }
    //#else
    @ModifyArgs(
        method = "onGameMessage",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/InGameHud;addChatMessage("
                + "Lnet/minecraft/network/MessageType;Lnet/minecraft/text/Text;Ljava/util/UUID;)V"
        )
    )
    private void confluxmap$addWaypointImportActionLegacy(final Args args) {
        // GAME_INFO is the action bar: it re-renders continuously and its text is not clickable,
        // so an appended import action would only add unactionable noise there.
        if (args.<MessageType>get(0) == MessageType.GAME_INFO) {
            return;
        }
        args.set(1, confluxmap$appendWaypointImportAction(args.get(1)));
    }
    //#endif

    private Text confluxmap$appendWaypointImportAction(final Text original) {
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

        final MutableText importAction = Texts.translatable("confluxmap.chat.waypoint.import")
            .setStyle(Style.EMPTY
                .withColor(Formatting.AQUA)
                .withUnderline(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, payload.get())));
        return Texts.literal("")
            .append(original.shallowCopy())
            .append(Texts.literal(" "))
            .append(importAction);
    }
}

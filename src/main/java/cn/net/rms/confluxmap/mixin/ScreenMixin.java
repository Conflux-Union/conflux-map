package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatClickPayload;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatCodec;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.mc.ui.screen.WaypointEditScreen;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
//#if MC>=12111
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#else
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//#endif

@Mixin(Screen.class)
public abstract class ScreenMixin {
    //#if MC>=12111
    //$$ @Inject(method = "handleClickEvent", at = @At("HEAD"), cancellable = true)
    //$$ private static void confluxmap$handleWaypointImport(
    //$$     final ClickEvent clickEvent,
    //$$     final MinecraftClient client,
    //$$     final Screen screen,
    //$$     final CallbackInfo ci
    //$$ ) {
    //$$     if (confluxmap$handleWaypointImport(clickEvent, client, screen)) {
    //$$         ci.cancel();
    //$$     }
    //$$ }
    //#else
    @Inject(method = "handleTextClick", at = @At("HEAD"), cancellable = true)
    private void confluxmap$handleWaypointImport(
        final Style style,
        final CallbackInfoReturnable<Boolean> cir
    ) {
        if (style == null) {
            return;
        }
        final ClickEvent clickEvent = style.getClickEvent();
        if (confluxmap$handleWaypointImport(
            clickEvent, MinecraftClient.getInstance(), (Screen) (Object) this
        )) {
            cir.setReturnValue(true);
        }
    }
    //#endif

    /** Handles Conflux's reserved clipboard payload and reports whether vanilla must be skipped. */
    private static boolean confluxmap$handleWaypointImport(
        final ClickEvent clickEvent,
        final MinecraftClient client,
        final Screen parent
    ) {
        final String clickValue = clickEvent == null ? null : Texts.clickValue(clickEvent);
        if (clickValue == null || !WaypointChatClickPayload.hasPrivatePrefix(clickValue)) {
            return false;
        }

        // Reserved payloads never reach vanilla's clipboard or command click handling.
        if (clickEvent.getAction() != ClickEvent.Action.COPY_TO_CLIPBOARD) {
            return true;
        }

        final Optional<WaypointChatClickPayload.Decoded> decoded =
            WaypointChatClickPayload.decode(clickValue);
        if (!decoded.isPresent()) {
            return true;
        }
        final Optional<WaypointChatCodec.Candidate> candidate = WaypointChatCodec.parse(
            decoded.get().message(), decoded.get().receivedDimension()
        );
        if (!candidate.isPresent()) {
            return true;
        }

        if (client.world == null) {
            return true;
        }
        final WaypointChatCodec.Candidate waypoint = candidate.get();
        client.setScreen(WaypointEditScreen.forCreate(
            parent,
            waypoint.dimensionId(),
            waypoint.name(),
            waypoint.x(),
            waypoint.y(),
            waypoint.z()
        ));
        return true;
    }
}

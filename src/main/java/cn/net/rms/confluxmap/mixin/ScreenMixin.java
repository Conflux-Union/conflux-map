package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatClickPayload;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatCodec;
import cn.net.rms.confluxmap.mc.ui.screen.WaypointEditScreen;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Inject(method = "handleTextClick", at = @At("HEAD"), cancellable = true)
    private void confluxmap$handleWaypointImport(
        final Style style,
        final CallbackInfoReturnable<Boolean> cir
    ) {
        if (style == null) {
            return;
        }
        final ClickEvent clickEvent = style.getClickEvent();
        if (clickEvent == null || !WaypointChatClickPayload.hasPrivatePrefix(clickEvent.getValue())) {
            return;
        }

        // Reserved payloads never reach vanilla's clipboard or command click handling.
        cir.setReturnValue(true);
        if (clickEvent.getAction() != ClickEvent.Action.COPY_TO_CLIPBOARD) {
            return;
        }

        final Optional<WaypointChatClickPayload.Decoded> decoded =
            WaypointChatClickPayload.decode(clickEvent.getValue());
        if (!decoded.isPresent()) {
            return;
        }
        final Optional<WaypointChatCodec.Candidate> candidate = WaypointChatCodec.parse(
            decoded.get().message(), decoded.get().receivedDimension()
        );
        if (!candidate.isPresent()) {
            return;
        }

        final MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return;
        }
        final WaypointChatCodec.Candidate waypoint = candidate.get();
        client.setScreen(WaypointEditScreen.forCreate(
            (Screen) (Object) this,
            waypoint.dimensionId(),
            waypoint.name(),
            waypoint.x(),
            waypoint.y(),
            waypoint.z()
        ));
    }
}

package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.mc.world.ClientWorldIdentityHandler;
//#if MC>=260100
//$$ import net.minecraft.client.gui.screens.ChatScreen;
//#else
import net.minecraft.client.gui.screen.ChatScreen;
//#endif
//#if MC>=12100
//#else
import net.minecraft.client.gui.widget.TextFieldWidget;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Observes the original chat submission before vanilla normalizes or dispatches it. This injection
 * is intentionally non-cancellable: it neither changes the command nor sends a second packet.
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
    //#if MC>=12100
    //$$ @Inject(method = "sendMessage(Ljava/lang/String;Z)V", at = @At("HEAD"))
    //$$ private void confluxmap$observeSubmittedCommand(
    //$$     final String chatText,
    //$$     final boolean addToHistory,
    //$$     final CallbackInfo ci
    //$$ ) {
    //$$     ClientWorldIdentityHandler.chatSubmitted(chatText);
    //$$ }
    //#else
    @Shadow protected TextFieldWidget chatField;

    @Inject(
        method = "keyPressed(III)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/ChatScreen;sendMessage(Ljava/lang/String;)V"
        )
    )
    private void confluxmap$observeSubmittedCommand(
        final int keyCode,
        final int scanCode,
        final int modifiers,
        final CallbackInfoReturnable<Boolean> cir
    ) {
        ClientWorldIdentityHandler.chatSubmitted(chatField.getText().trim());
    }
    //#endif
}

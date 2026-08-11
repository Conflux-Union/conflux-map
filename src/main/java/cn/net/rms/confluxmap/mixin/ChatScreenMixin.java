package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.mc.world.ClientWorldIdentityHandler;
//#if MC>=260100
//$$ import net.minecraft.client.gui.screens.ChatScreen;
//#elseif MC>=11800
//$$ import net.minecraft.client.gui.screen.ChatScreen;
//#else
import net.minecraft.client.gui.screen.Screen;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes the original chat submission before vanilla normalizes or dispatches it. This injection
 * is intentionally non-cancellable: it neither changes the command nor sends a second packet.
 */
//#if MC>=11800
//$$ @Mixin(ChatScreen.class)
//#else
@Mixin(Screen.class)
//#endif
public abstract class ChatScreenMixin {
    @Inject(method = "sendMessage(Ljava/lang/String;Z)V", at = @At("HEAD"))
    private void confluxmap$observeSubmittedCommand(
        final String chatText,
        final boolean addToHistory,
        final CallbackInfo ci
    ) {
        ClientWorldIdentityHandler.chatSubmitted(chatText);
    }
}

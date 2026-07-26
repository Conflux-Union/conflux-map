package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.mc.chat.WaypointChatMessageRewriter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    @ModifyVariable(
        //#if MC>=260100
        //$$ // Official names are required in annotation strings for the unobfuscated build.
        //$$ method = "addMessage(Lnet/minecraft/network/chat/Component;"
        //$$     + "Lnet/minecraft/network/chat/MessageSignature;"
        //$$     + "Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;"
        //$$     + "Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        //#elseif MC>=12100
        //$$ method = "addMessage(Lnet/minecraft/text/Text;"
        //$$     + "Lnet/minecraft/network/message/MessageSignatureData;"
        //$$     + "Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
        //#else
        method = "addMessage(Lnet/minecraft/text/Text;)V",
        //#endif
        at = @At("HEAD"),
        argsOnly = true
    )
    private Text confluxmap$rewriteWaypointMessage(final Text original) {
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return original;
        }

        final Identifier dimensionIdentifier = client.world.getRegistryKey().getValue();
        return WaypointChatMessageRewriter.rewrite(
            original,
            DimensionId.of(dimensionIdentifier.getNamespace(), dimensionIdentifier.getPath())
        );
    }
}

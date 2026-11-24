package cn.net.rms.confluxmap.mixin;

//#if MC>=260100
//$$ import cn.net.rms.confluxmap.ConfluxMapClient;
//$$ import cn.net.rms.confluxmap.core.waypoint.library.MapScreenshotIndex;
//$$ import java.util.function.Consumer;
//$$ import net.minecraft.client.Screenshot;
//$$ import net.minecraft.network.chat.ClickEvent;
//$$ import net.minecraft.network.chat.Component;
//$$ import net.minecraft.network.chat.contents.TranslatableContents;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.ModifyVariable;
//$$
//$$ @Mixin(Screenshot.class)
//$$ public abstract class ScreenshotLocationMixin {
//$$     @ModifyVariable(method = "grab(Ljava/io/File;Ljava/lang/String;Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V",
//$$         at = @At("HEAD"), argsOnly = true)
//$$     private static Consumer<Component> confluxmap$rememberLocation(Consumer<Component> callback) {
//$$         final var app = ConfluxMapClient.get();
//$$         if (app == null || app.mapScreenshotIndex() == null) return callback;
//$$         final var session = app.gameBridge().session();
//$$         final var player = app.gameBridge().player().orElse(null);
//$$         if (!session.active() || player == null || !session.dimension().equals(player.dimension())) return callback;
//$$         final var location = new MapScreenshotIndex.Location(MapScreenshotIndex.Scope.of(session.world(), session.dimension()),
//$$             player.x(), player.y(), player.z());
//$$         final long capturedAt = System.currentTimeMillis();
//$$         return result -> {
//$$             callback.accept(result);
//$$             if (result.getContents() instanceof TranslatableContents message && message.getKey().equals("screenshot.success")
//$$                 && message.getArgs().length == 1 && message.getArgs()[0] instanceof Component fileLabel
//$$                 && fileLabel.getStyle().getClickEvent() instanceof ClickEvent.OpenFile file) {
//$$                 app.mapScreenshotIndex().save(location, file.file().toPath(), capturedAt);
//$$             }
//$$         };
//$$     }
//$$ }
//#else
import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.core.waypoint.library.MapScreenshotIndex;
import java.util.function.Consumer;
import java.nio.file.Path;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
//#if MC>=11900
//$$ import net.minecraft.text.TranslatableTextContent;
//#else
import net.minecraft.text.TranslatableText;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ScreenshotRecorder.class)
public abstract class ScreenshotLocationMixin {
//#if MC>=12106
//$$     @ModifyVariable(method = "saveScreenshot(Ljava/io/File;Ljava/lang/String;Lnet/minecraft/client/gl/Framebuffer;ILjava/util/function/Consumer;)V",
//$$         at = @At("HEAD"), argsOnly = true)
//#else
    @ModifyVariable(method = "saveScreenshot(Ljava/io/File;Ljava/lang/String;Lnet/minecraft/client/gl/Framebuffer;Ljava/util/function/Consumer;)V",
        at = @At("HEAD"), argsOnly = true)
//#endif
    private static Consumer<Text> confluxmap$rememberLocation(Consumer<Text> callback) {
        final var app = ConfluxMapClient.get();
        if (app == null || app.mapScreenshotIndex() == null) return callback;
        final var session = app.gameBridge().session();
        final var player = app.gameBridge().player().orElse(null);
        if (!session.active() || player == null || !session.dimension().equals(player.dimension())) return callback;
        final var location = new MapScreenshotIndex.Location(MapScreenshotIndex.Scope.of(session.world(), session.dimension()),
            player.x(), player.y(), player.z());
        final long capturedAt = System.currentTimeMillis();
        return result -> {
            callback.accept(result);
//#if MC>=11900
//$$             if (!(result.getContent() instanceof TranslatableTextContent message)) return;
//#else
            if (!(result instanceof TranslatableText message)) return;
//#endif
            if (!message.getKey().equals("screenshot.success") || message.getArgs().length != 1
                || !(message.getArgs()[0] instanceof Text fileLabel)) return;
            var click = fileLabel.getStyle().getClickEvent();
//#if MC>=12105
//$$             if (click instanceof ClickEvent.OpenFile file) {
//$$                 app.mapScreenshotIndex().save(location, file.file().toPath(), capturedAt);
//$$             }
//#else
            if (click != null && click.getAction() == ClickEvent.Action.OPEN_FILE) {
                app.mapScreenshotIndex().save(location, Path.of(click.getValue()), capturedAt);
            }
//#endif
        };
    }
}
//#endif

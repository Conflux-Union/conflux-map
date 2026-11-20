package cn.net.rms.confluxmap.mc.ui.screen;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
//#if MC>=260300
//$$ import java.util.ArrayList;
//$$ import java.util.Set;
//$$ import java.util.concurrent.ConcurrentHashMap;
//$$ import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//$$ import net.minecraft.client.Minecraft;
//$$ import org.lwjgl.sdl.SDLDialog;
//$$ import org.lwjgl.sdl.SDLError;
//$$ import org.lwjgl.sdl.SDL_DialogFileCallback;
//$$ import org.lwjgl.sdl.SDL_DialogFileFilter;
//$$ import org.lwjgl.system.MemoryUtil;
//$$ import org.lwjgl.system.Pointer;
//#endif

/** Minecraft sets java.awt.headless=true. Use its SDL platform, never an AWT window. */
final class WaypointImageFilePicker {
    private WaypointImageFilePicker() {}
    //#if MC>=260300
    //$$ private static final Set<Dialog> PENDING = ConcurrentHashMap.newKeySet();
    //$$ private static boolean registered;
    //$$ private static final class Dialog {
    //$$     final java.nio.ByteBuffer name = MemoryUtil.memUTF8("PNG / JPEG");
    //$$     final java.nio.ByteBuffer pattern = MemoryUtil.memUTF8("png;jpg;jpeg");
    //$$     final SDL_DialogFileFilter.Buffer filters = SDL_DialogFileFilter.calloc(1);
    //$$     final SDL_DialogFileCallback callback;
    //$$     volatile boolean complete;
    //$$     int completedTicks;
    //$$     Dialog(Consumer<List<Path>> success, Consumer<Throwable> failure) {
    //$$         filters.get(0).set(name, pattern);
    //$$         callback = SDL_DialogFileCallback.create((userdata, files, filter) -> {
    //$$             try {
    //$$                 if (files == 0) throw new IllegalStateException(SDLError.SDL_GetError());
    //$$                 List<Path> selected = new ArrayList<>();
    //$$                 for (int i = 0; ; i++) {
    //$$                     long file = MemoryUtil.memGetAddress(files + (long) i * Pointer.POINTER_SIZE);
    //$$                     if (file == 0) break;
    //$$                     selected.add(Path.of(MemoryUtil.memUTF8(file)));
    //$$                 }
    //$$                 Minecraft.getInstance().execute(() -> success.accept(List.copyOf(selected)));
    //$$             } catch (RuntimeException error) {
    //$$                 Minecraft.getInstance().execute(() -> failure.accept(error));
    //$$             } finally { complete = true; }
    //$$         });
    //$$     }
    //$$     void close() { callback.free(); filters.free(); MemoryUtil.memFree(name); MemoryUtil.memFree(pattern); }
    //$$ }
    //#endif
    static void choose(Consumer<List<Path>> success, Consumer<Throwable> failure) {
        //#if MC>=260300
        //$$ if (!registered) {
        //$$     registered = true;
        //$$     ClientTickEvents.END_CLIENT_TICK.register(client -> {
        //$$         for (Dialog dialog : List.copyOf(PENDING)) if (dialog.complete && ++dialog.completedTicks >= 2) {
        //$$             PENDING.remove(dialog); dialog.close();
        //$$         }
        //$$     });
        //$$ }
        //$$ Dialog dialog = new Dialog(success, failure);
        //$$ PENDING.add(dialog);
        //$$ try { SDLDialog.SDL_ShowOpenFileDialog(dialog.callback, 0, Minecraft.getInstance().getWindow().handle(), dialog.filters, (CharSequence) null, true); }
        //$$ catch (RuntimeException error) { PENDING.remove(dialog); dialog.close(); failure.accept(error); }
        //#else
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            final var filters = stack.pointers(stack.UTF8("*.png"), stack.UTF8("*.jpg"), stack.UTF8("*.jpeg"));
            final String selected = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(
                "PNG / JPEG", "", filters, "PNG / JPEG", true
            );
            final List<Path> paths = selected == null ? List.of()
                : java.util.Arrays.stream(selected.split("\\|")).map(Path::of).toList();
            success.accept(paths);
        } catch (RuntimeException error) {
            failure.accept(error);
        }
        //#endif
    }
}

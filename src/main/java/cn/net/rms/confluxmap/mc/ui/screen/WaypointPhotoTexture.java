package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.GuiTransforms;
import cn.net.rms.confluxmap.compat.NativeImages;
import cn.net.rms.confluxmap.core.waypoint.library.WaypointMediaService;
import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.render.RenderTargetState;
import cn.net.rms.confluxmap.mc.render.TextureBinds;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImageBackedTexture;

/** One screen-owned texture; CPU decoding is done by the media service before upload. */
public final class WaypointPhotoTexture implements AutoCloseable {
    private static final java.util.Set<WaypointPhotoTexture> LIVE = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private boolean closed;
    private final NativeImageBackedTexture texture;
    private final int width, height;
    WaypointPhotoTexture(WaypointMediaService.Pixels pixels) {
        width = pixels.width(); height = pixels.height();
        //#if MC>=12105
        //$$ texture = new NativeImageBackedTexture("Conflux Map photo", width, height, false);
        //#else
        texture = new NativeImageBackedTexture(width, height, false);
        //#endif
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            NativeImages.setArgb(texture.getImage(), x, y, pixels.argb()[y * width + x]);
        texture.upload();
        LIVE.add(this);
    }
    void draw(GuiDraw draw, int x, int y, int w, int h, float zoom) {
        if (closed) return;
        float scale = Math.min((float) w / width, (float) h / height) * zoom;
        float dw = width * scale, dh = height * scale;
        // Zoom crops UVs to the fixed image viewport, keeping controls outside the image.
        float cw = Math.min(w, dw), ch = Math.min(h, dh);
        float u = (1 - cw / dw) / 2, v = (1 - ch / dh) / 2;
        beginDraw(draw);
        GuiShapes.drawTintedQuad(draw.matrices(), x + (w - cw) / 2, y + (h - ch) / 2, cw, ch, u, v, 1 - u, 1 - v, 0xFFFFFFFF);
    }

    void draw(GuiDraw draw, int x, int y, int w, int h, float zoom, double panX, double panY, double rotation) {
        if (closed) return;
        if (panX != 0 || panY != 0 || rotation != 0) {
            drawTransformed(draw, x, y, w, h, zoom, panX, panY, (float) rotation);
            return;
        }
        draw(draw, x, y, w, h, zoom);
    }

    private void drawTransformed(GuiDraw draw, int x, int y, int w, int h, float zoom,
                                 double panX, double panY, float rotation) {
        double radians = Math.toRadians(rotation);
        double sin = Math.abs(Math.sin(radians)), cos = Math.abs(Math.cos(radians));
        float scale = (float) Math.min(w / (width * cos + height * sin), h / (height * cos + width * sin)) * zoom;
        float dw = width * scale, dh = height * scale;
        var matrices = draw.matrices();
        var model = GuiTransforms.matrixAmbient(matrices);
        int left = (int) Math.floor(x * model.scaleX() + model.translateX());
        int top = (int) Math.floor(y * model.scaleY() + model.translateY());
        int right = (int) Math.ceil((x + w) * model.scaleX() + model.translateX());
        int bottom = (int) Math.ceil((y + h) * model.scaleY() + model.translateY());
        beginDraw(draw);
        RenderTargetState.enableScissor(MinecraftClient.getInstance(), left, top, right - left, bottom - top);
        matrices.push();
        try {
            matrices.translate((float) (x + w / 2.0 + panX), (float) (y + h / 2.0 + panY), 0f);
            GuiShapes.rotateZ(matrices, rotation);
            GuiShapes.drawTintedQuad(matrices, -dw / 2, -dh / 2, dw, dh, 0, 0, 1, 1, 0xFFFFFFFF);
        } finally {
            matrices.pop();
            RenderTargetState.disableScissor();
        }
    }

    private void beginDraw(GuiDraw draw) {
        draw.flushGui();
        TextureBinds.beginTexturedQuads();
        //#if MC>=12108
        //$$ TextureBinds.bindTexture(texture.getGlTextureView());
        //#elseif MC>=12105
        //$$ TextureBinds.bindTexture(texture.getGlTexture());
        //#else
        TextureBinds.bindTexture(texture.getGlId());
        //#endif
    }
    boolean closed() { return closed; }
    public static void invalidateAll() { for (WaypointPhotoTexture photo : java.util.List.copyOf(LIVE)) photo.close(); }
    @Override public void close() { if (!closed) { closed = true; texture.close(); LIVE.remove(this); } }
}

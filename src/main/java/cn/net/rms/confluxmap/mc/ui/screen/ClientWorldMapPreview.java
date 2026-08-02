package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.NativeImages;
import cn.net.rms.confluxmap.core.cache.RegionHistoryPreviewLoader;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
//#if MC<12105
import com.mojang.blaze3d.platform.GlStateManager;
//#endif
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;

/** Read-only preview rendered directly from one profile's persisted region history. */
final class ClientWorldMapPreview implements AutoCloseable {
    enum State {
        LOADING,
        READY,
        EMPTY,
        FAILED
    }

    record Marker(double x, double y) {
    }

    private static final Pattern SAFE_STORAGE_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");
    private static final int BACKGROUND_COLOR = 0xFF10161A;

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CompletableFuture<RegionHistoryPreviewLoader.Result> loading;
    private final double markerWorldX;
    private final double markerWorldZ;
    private RegionHistoryPreviewLoader.Result result;
    private NativeImageBackedTexture texture;
    private State state = State.LOADING;
    private boolean closed;

    ClientWorldMapPreview(
        final WorldIdentity world,
        final String dimensionStorageId,
        final double markerWorldX,
        final double markerWorldZ,
        final int width,
        final int height
    ) {
        this.markerWorldX = markerWorldX;
        this.markerWorldZ = markerWorldZ;
        final Path cacheRoot = FabricLoader.getInstance().getGameDir()
            .resolve(ConfluxMapMod.ID)
            .resolve("cache");
        final MapLayer layer = layerForDimensionStorageId(dimensionStorageId);
        if (!isSafeStorageSegment(dimensionStorageId)) {
            loading = CompletableFuture.failedFuture(new IllegalArgumentException("Unsafe dimension storage id"));
            return;
        }
        final Path worldRoot = cacheRoot.resolve(world.serverId()).resolve(world.worldId()).normalize();
        final Path layerDirectory = worldRoot.resolve(dimensionStorageId).resolve(layer.cacheId()).normalize();
        if (!layerDirectory.startsWith(worldRoot)) {
            loading = CompletableFuture.failedFuture(new IllegalArgumentException("Preview path escaped world storage"));
            return;
        }
        loading = CompletableFuture.supplyAsync(() -> {
            try {
                return RegionHistoryPreviewLoader.load(
                    layerDirectory, layer.type(), width, height, cancelled::get, ConfluxMapMod.LOGGER
                );
            } catch (final java.io.IOException error) {
                throw new CompletionException(error);
            }
        }, ConfluxMapClient.get().executors().workers());
    }

    State state() {
        return state;
    }

    int exploredChunks() {
        return result == null ? 0 : result.exploredChunks();
    }

    double blocksPerPixel() {
        return result == null || result.fit() == null ? Double.NaN : result.fit().blocksPerPixel();
    }

    Marker marker() {
        if (state != State.READY || result == null || result.fit() == null
            || !Double.isFinite(markerWorldX) || !Double.isFinite(markerWorldZ)) {
            return null;
        }
        final double x = result.fit().pixelX(markerWorldX);
        final double y = result.fit().pixelZ(markerWorldZ);
        if (x < 0.0D || y < 0.0D || x >= result.width() || y >= result.height()) {
            return null;
        }
        return new Marker(x, y);
    }

    /** Render thread: completes one bounded upload and draws the already-rasterized history. */
    boolean render(final GuiDraw draw, final int x, final int y, final int width, final int height) {
        if (closed || width <= 0 || height <= 0) {
            return false;
        }
        RenderUtil.fillRect(draw.matrices(), x, y, width, height, BACKGROUND_COLOR);
        finishLoading();
        if (state != State.READY || texture == null) {
            return false;
        }
        RenderUtil.beginTexturedQuads();
        //#if MC>=12108
        //$$ RenderUtil.bindTexture(texture.getGlTextureView());
        //#elseif MC>=12105
        //$$ RenderUtil.bindTexture(texture.getGlTexture());
        //#else
        RenderUtil.bindTexture(texture.getGlId());
        //#endif
        RenderUtil.drawQuad(draw.matrices(), x, y, width, height, 0.0F, 0.0F, 1.0F, 1.0F);
        return true;
    }

    private void finishLoading() {
        if (state != State.LOADING || !loading.isDone()) {
            return;
        }
        try {
            result = loading.join();
            if (!result.hasHistory()) {
                state = State.EMPTY;
                return;
            }
            if (result.decodedRegions() == 0) {
                state = State.FAILED;
                return;
            }
            texture = createTexture(result);
            state = State.READY;
        } catch (final CancellationException error) {
            if (!closed) {
                state = State.FAILED;
            }
        } catch (final RuntimeException error) {
            state = State.FAILED;
            ConfluxMapMod.LOGGER.warn("Could not build client-world history preview ({})", error.toString());
        }
    }

    private static NativeImageBackedTexture createTexture(final RegionHistoryPreviewLoader.Result raster) {
        //#if MC>=12105
        //$$ final NativeImageBackedTexture built = new NativeImageBackedTexture(
        //$$     "Conflux Map client-world history", raster.width(), raster.height(), false
        //$$ );
        //#else
        final NativeImageBackedTexture built = new NativeImageBackedTexture(raster.width(), raster.height(), false);
        //#endif
        final NativeImage image = built.getImage();
        if (image == null) {
            built.close();
            throw new IllegalStateException("Client-world preview texture has no image");
        }
        for (int y = 0; y < raster.height(); y++) {
            final int row = y * raster.width();
            for (int x = 0; x < raster.width(); x++) {
                NativeImages.setArgb(image, x, y, raster.argb()[row + x]);
            }
        }
        built.upload();
        configureSampling(built);
        return built;
    }

    private static void configureSampling(final NativeImageBackedTexture texture) {
        //#if MC>=12111
        //$$ // Sampling is selected explicitly when the render pass binds the texture.
        //#else
        texture.setFilter(false, false);
        //#if MC>=12105
        //$$ texture.setClamp(true);
        //#else
        GlStateManager._bindTexture(texture.getGlId());
        GlStateManager._texParameter(3553, 10242, 33071);
        GlStateManager._texParameter(3553, 10243, 33071);
        //#endif
        //#endif
    }

    static MapLayer layerForDimensionStorageId(final String dimensionStorageId) {
        if (DimensionId.NETHER.fileName().equals(dimensionStorageId)) {
            return MapLayer.NETHER_CEILING;
        }
        if (DimensionId.END.fileName().equals(dimensionStorageId)) {
            return MapLayer.END_SURFACE;
        }
        return MapLayer.SURFACE;
    }

    private static boolean isSafeStorageSegment(final String value) {
        return value != null && !value.equals(".") && !value.equals("..")
            && SAFE_STORAGE_SEGMENT.matcher(value).matches();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        cancelled.set(true);
        loading.cancel(true);
        if (texture != null) {
            texture.close();
            texture = null;
        }
    }
}

package cn.net.rms.confluxmap.mc;

import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

public final class McGameBridge implements GameBridge {
    private final MinecraftClient client;
    private final SessionGuard guard;

    public McGameBridge(final MinecraftClient client, final SessionGuard guard) {
        this.client = client;
        this.guard = guard;
    }

    @Override
    public SessionGuard.Session session() {
        return guard.current();
    }

    @Override
    public Optional<PlayerView> player(final float tickDelta) {
        final ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return Optional.empty();
        }
        return viewOf(player, tickDelta);
    }

    @Override
    public Optional<PlayerView> viewpoint(final float tickDelta) {
        final ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return Optional.empty();
        }
        // Some client-side mods temporarily move the camera into a separate entity (for
        // example while the player's soul is detached from their body).  Map overlays are
        // screen-space views, so anchoring them to the player entity in that state makes the
        // map, markers, and waypoints appear to slide away from the actual view.  Vanilla
        // normally returns the player here; the fallback keeps this bridge safe during camera
        // teardown and on versions where no camera entity is available yet.
        final Entity cameraEntity = client.getCameraEntity();
        final Entity viewEntity = cameraEntity != null ? cameraEntity : player;
        return viewOf(viewEntity, tickDelta);
    }

    private Optional<PlayerView> viewOf(final Entity entity, final float tickDelta) {
        final Identifier dim = client.world.getRegistryKey().getValue();
        return Optional.of(new PlayerView(
            MathHelper.lerp(tickDelta, entity.prevX, entity.getX()),
            MathHelper.lerp(tickDelta, entity.prevY, entity.getY()),
            MathHelper.lerp(tickDelta, entity.prevZ, entity.getZ()),
            entity.getYaw(tickDelta),
            DimensionId.of(dim.getNamespace(), dim.getPath())
        ));
    }

    @Override
    public void runOnRenderThread(final Runnable task) {
        client.execute(task);
    }
}

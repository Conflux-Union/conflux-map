package cn.net.rms.confluxmap.mc;

import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Identifier;

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
    public Optional<PlayerView> player() {
        final ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return Optional.empty();
        }
        final Identifier dim = client.world.getRegistryKey().getValue();
        return Optional.of(new PlayerView(
            player.getX(), player.getY(), player.getZ(),
            player.getYaw(1.0F),
            DimensionId.of(dim.getNamespace(), dim.getPath())
        ));
    }

    @Override
    public void runOnRenderThread(final Runnable task) {
        client.execute(task);
    }
}

package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.core.trail.PlayerTrail;
import cn.net.rms.confluxmap.core.trail.PlayerTrailProjection;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.util.math.MatrixStack;

/** Draws recent player positions as small red dots with age-based fading. */
public final class PlayerTrailRenderer {
    private static final int DOT_RGB = 0x00EF4444;
    private static final int MAX_ALPHA = 220;

    private PlayerTrailRenderer() {
    }

    public static void draw(
        final MatrixStack matrices,
        final PlayerTrail trail,
        final PlayerTrailProjection projection,
        final int durationSeconds,
        final int dotSize
    ) {
        final long nowNanos = System.nanoTime();
        final long retentionNanos = TimeUnit.SECONDS.toNanos(durationSeconds);
        final List<PlayerTrail.Sample> samples = trail.snapshot(nowNanos, retentionNanos);
        final List<RenderUtil.ColoredRect> dots = new ArrayList<>(samples.size());
        for (final PlayerTrail.Sample sample : samples) {
            final PlayerTrailProjection.ScreenPoint point = projection.project(sample);
            if (!projection.visible(point, dotSize / 2.0)) {
                continue;
            }
            final long ageNanos = Math.max(0L, nowNanos - sample.recordedAtNanos());
            final double remaining = Math.max(0.0, 1.0 - ageNanos / (double) retentionNanos);
            final int alpha = Math.max(1, (int) Math.round(MAX_ALPHA * remaining));
            dots.add(new RenderUtil.ColoredRect(
                (float) point.x() - dotSize / 2f,
                (float) point.y() - dotSize / 2f,
                dotSize,
                dotSize,
                alpha << 24 | DOT_RGB
            ));
        }
        RenderUtil.fillRects(matrices, dots);
    }
}

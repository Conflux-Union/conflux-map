package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.core.portal.PortalKind;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/**
 * Portal marker icon: dark plate, kind-colored border, and a stable vanilla
 * stand-in texture. The portal blocks themselves cannot be used as icons:
 * end portals and gateways are shader-rendered (no texture), and the nether
 * portal texture is a stacked animation atlas. Stand-ins: obsidian for a
 * nether portal, the eye-filled frame for an activated end portal, and the
 * teleporting chorus fruit for a gateway. Size and opacity are caller-supplied
 * so the minimap and fullscreen map share one painter.
 */
public final class PortalMarkerRenderer {
    private static final int PLATE = 0x101010;

    private PortalMarkerRenderer() {
    }

    static int borderColor(final PortalKind kind) {
        return switch (kind) {
            case NETHER_PORTAL -> 0xFFB15ED6;
            case END_PORTAL -> 0xFF52D273;
            case END_GATEWAY -> 0xFF5BC0EB;
        };
    }

    static Identifier texture(final PortalKind kind) {
        final String path = switch (kind) {
            case NETHER_PORTAL -> "textures/block/obsidian.png";
            case END_PORTAL -> "textures/block/end_portal_frame_eye.png";
            case END_GATEWAY -> "textures/item/chorus_fruit.png";
        };
        return Ids.of("minecraft", path);
    }

    /** Draws the icon plate; {@code opacityPercent} 0-100 scales plate, border and texture together. */
    public static void draw(
        final GuiDraw draw,
        final PortalKind kind,
        final float centerX,
        final float centerY,
        final int size,
        final int opacityPercent,
        final boolean hovered
    ) {
        final int iconSize = hovered ? size + 2 : size;
        final int left = Math.round(centerX - iconSize / 2f);
        final int top = Math.round(centerY - iconSize / 2f);
        final float opacity = Math.max(0f, Math.min(100f, opacityPercent)) / 100f;
        draw.fill(left, top, left + iconSize, top + iconSize, alpha(0xE0000000 | PLATE, opacity));
        draw.fill(left + 1, top + 1, left + iconSize - 1, top + iconSize - 1,
            alpha(0xFF000000 | borderColor(kind), opacity));
        draw.fill(left + 2, top + 2, left + iconSize - 2, top + iconSize - 2,
            alpha(0xE0000000 | PLATE, opacity));
        final int inset = 3;
        final int inner = iconSize - inset * 2;
        if (inner <= 0) {
            return;
        }
        // Buffered fills queued earlier would paint over the texture; flush first
        // (same ordering note as StructureIconCatalog.draw).
        draw.flushGui();
        RenderUtil.bindTexture(MinecraftClient.getInstance(), texture(kind));
        RenderUtil.drawTintedQuad(
            draw.matrices(), left + inset, top + inset, inner, inner,
            0f, 0f, 1f, 1f, alpha(0xFFFFFFFF, opacity)
        );
    }

    private static int alpha(final int argb, final float fraction) {
        final int a = Math.round((argb >>> 24) * fraction);
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}

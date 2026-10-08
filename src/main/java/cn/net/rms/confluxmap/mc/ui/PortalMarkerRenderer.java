package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.portal.PortalKind;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/**
 * Portal marker icon: dark plate, kind-colored border, and a vanilla stand-in
 * texture unless the user configured a custom one. The portal blocks themselves
 * cannot be used as icons: end portals and gateways are shader-rendered (no
 * texture), and the nether portal texture is a stacked animation atlas.
 * Built-in stand-ins: obsidian for a nether portal, the eye-filled frame for an
 * activated end portal, and the teleporting chorus fruit for a gateway. A
 * configured texture that is invalid or absent from the loaded resource packs
 * falls back to the stand-in with one console warning per path. Size and
 * opacity are caller-supplied so the minimap and fullscreen map share one
 * painter.
 */
public final class PortalMarkerRenderer {
    private static final int PLATE = 0x101010;
    /**
     * Vanilla resource-location charset: namespace {@code [a-z0-9_.-]+}, path
     * {@code [a-z0-9_./-]+}. Validating here keeps {@link Ids#of} from throwing
     * across versions whose exception types moved.
     */
    private static final Pattern RESOURCE_LOCATION = Pattern.compile("([a-z0-9_.-]+):([a-z0-9_./-]+)");
    /** Resolved custom textures keyed by the raw configured string; empty = use the stand-in. */
    private static final Map<String, Optional<Identifier>> CONFIGURED_TEXTURES = new ConcurrentHashMap<>();
    private static final Set<String> WARNED_TEXTURES = ConcurrentHashMap.newKeySet();

    private PortalMarkerRenderer() {
    }

    static int borderColor(final PortalKind kind) {
        return switch (kind) {
            case NETHER_PORTAL -> 0xFFB15ED6;
            case END_PORTAL -> 0xFF52D273;
            case END_GATEWAY -> 0xFF5BC0EB;
        };
    }

    static Identifier defaultTexture(final PortalKind kind) {
        final String path = switch (kind) {
            case NETHER_PORTAL -> "textures/block/obsidian.png";
            case END_PORTAL -> "textures/block/end_portal_frame_eye.png";
            case END_GATEWAY -> "textures/item/chorus_fruit.png";
        };
        return Ids.of("minecraft", path);
    }

    /** Resolves the configured texture, or the stand-in when blank, invalid, or missing. */
    static Identifier texture(final PortalKind kind, final String configured) {
        if (configured == null || configured.isBlank()) {
            return defaultTexture(kind);
        }
        return CONFIGURED_TEXTURES
            .computeIfAbsent(configured, PortalMarkerRenderer::resolveConfigured)
            .orElseGet(() -> defaultTexture(kind));
    }

    private static Optional<Identifier> resolveConfigured(final String configured) {
        final String candidate = configured.indexOf(':') >= 0
            ? configured
            : "minecraft:" + configured;
        final Matcher matcher = RESOURCE_LOCATION.matcher(candidate);
        if (!matcher.matches()) {
            warnOnce(configured, "not a valid resource location");
            return Optional.empty();
        }
        final Identifier identifier = Ids.of(matcher.group(1), matcher.group(2));
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !MinecraftAccess.resourceExists(client.getResourceManager(), identifier)) {
            warnOnce(configured, "not found in the default pack or loaded resource packs");
            return Optional.empty();
        }
        return Optional.of(identifier);
    }

    private static void warnOnce(final String configured, final String reason) {
        if (WARNED_TEXTURES.add(configured)) {
            ConfluxMapMod.LOGGER.warn(
                "Portal icon texture '{}' ignored: {}; using the built-in default", configured, reason);
        }
    }

    /** Drops cached lookups so an F3+T reload can introduce the configured texture. */
    static void clearTextureCache() {
        CONFIGURED_TEXTURES.clear();
        WARNED_TEXTURES.clear();
    }

    /** Draws the icon plate; {@code opacityPercent} 0-100 scales plate, border and texture together. */
    public static void draw(
        final GuiDraw draw,
        final PortalKind kind,
        final String configuredTexture,
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
        RenderUtil.bindTexture(MinecraftClient.getInstance(), texture(kind, configuredTexture));
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

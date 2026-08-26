package cn.net.rms.confluxmap.mc.chat;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatCodec;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.text.Text;

/** Opt-in component-tree diagnostics for the issue #87 compatibility environment. */
public final class WaypointChatDiagnostics {
    // Keep the JVM diagnostic property distinct from translatable keys discovered by source scans.
    public static final String ENABLE_PROPERTY = "confluxmap." + "issue87.debug";

    private static final int MAX_TREE_DEPTH = 32;
    private static final int MAX_TREE_NODES = 256;
    private static final int MAX_VALUE_LENGTH = 8_192;
    private static final AtomicBoolean ENVIRONMENT_LOGGED = new AtomicBoolean();
    private static final AtomicLong NEXT_EVENT_ID = new AtomicLong();

    private WaypointChatDiagnostics() {
    }

    public static void outgoing(final String confluxMessage, final String xaeroMessage) {
        if (!enabled()) {
            return;
        }
        logEnvironment();
        final long eventId = NEXT_EVENT_ID.incrementAndGet();
        ConfluxMapMod.LOGGER.info(
            "[issue-87:{}] outbound waypoint share: conflux={}, xaero={}",
            eventId,
            quote(confluxMessage),
            quote(xaeroMessage)
        );
    }

    public static void rewrite(
        final Text original,
        final Text rewritten,
        final DimensionId receivedDimension,
        final Optional<WaypointChatCodec.Candidate> candidate,
        final boolean payloadAvailable
    ) {
        if (!enabled() || !candidate.isPresent()) {
            return;
        }
        logEnvironment();
        final long eventId = NEXT_EVENT_ID.incrementAndGet();
        final WaypointChatCodec.Candidate waypoint = candidate.get();
        ConfluxMapMod.LOGGER.info(
            "[issue-87:{}] waypoint rewrite: format={}, receivedDimension={}, waypointDimension={}, "
                + "payloadAvailable={}, inputVisible={}, outputVisible={}",
            eventId,
            waypoint.confluxFormat() ? "conflux" : "xaero-or-generic",
            receivedDimension,
            waypoint.dimensionId(),
            payloadAvailable,
            quote(original.getString()),
            quote(rewritten.getString())
        );
        ConfluxMapMod.LOGGER.info(
            "[issue-87:{}] input component tree:\n{}", eventId, describe(original)
        );
        ConfluxMapMod.LOGGER.info(
            "[issue-87:{}] output component tree:\n{}", eventId, describe(rewritten)
        );
    }

    static String describe(final Text root) {
        final StringBuilder result = new StringBuilder();
        appendNode(
            root,
            "root",
            0,
            new int[] {0},
            new IdentityHashMap<Text, Boolean>(),
            result
        );
        return result.toString();
    }

    private static void appendNode(
        final Text node,
        final String path,
        final int depth,
        final int[] nodeCount,
        final IdentityHashMap<Text, Boolean> visited,
        final StringBuilder result
    ) {
        if (nodeCount[0] >= MAX_TREE_NODES) {
            result.append("... node limit reached\n");
            return;
        }
        nodeCount[0]++;
        indent(result, depth);
        result.append("node=").append(path)
            .append(" component=").append(node.getClass().getName())
            .append(" content=").append(contentDescription(node))
            .append(" siblings=").append(node.getSiblings().size())
            .append(" style=").append(sanitize(node.getStyle().toString()))
            .append(" subtree=").append(quote(node.getString()))
            .append('\n');

        if (visited.put(node, Boolean.TRUE) != null) {
            indent(result, depth + 1);
            result.append("... cycle detected\n");
            return;
        }
        if (depth >= MAX_TREE_DEPTH) {
            indent(result, depth + 1);
            result.append("... depth limit reached\n");
            return;
        }
        for (int i = 0; i < node.getSiblings().size(); i++) {
            appendNode(
                node.getSiblings().get(i),
                path + "." + i,
                depth + 1,
                nodeCount,
                visited,
                result
            );
        }
    }

    private static String contentDescription(final Text node) {
        for (final String accessor : new String[] {"getContent", "getContents"}) {
            try {
                final Method getContent = node.getClass().getMethod(accessor);
                final Object content = getContent.invoke(node);
                if (content != null) {
                    return content.getClass().getName() + ":" + sanitize(content.toString());
                }
            } catch (final NoSuchMethodException ignored) {
                // Minecraft 26.1 renamed getContent() to getContents().
            } catch (final IllegalAccessException | InvocationTargetException | RuntimeException e) {
                return "<unavailable:" + e.getClass().getSimpleName() + ">";
            }
        }
        return node.getClass().getName();
    }

    private static void logEnvironment() {
        if (!ENVIRONMENT_LOGGED.compareAndSet(false, true)) {
            return;
        }
        final FabricLoader loader = FabricLoader.getInstance();
        ConfluxMapMod.LOGGER.info(
            "[issue-87] diagnostics enabled: minecraft={}, fabric-loader={}, fabric-api={}, "
                + "confluxmap={}, chat-heads={}, java={}",
            modVersion(loader, "minecraft"),
            modVersion(loader, "fabricloader"),
            modVersion(loader, "fabric-api"),
            modVersion(loader, ConfluxMapMod.ID),
            modVersion(loader, "chat_heads"),
            System.getProperty("java.version", "unknown")
        );
    }

    private static String modVersion(final FabricLoader loader, final String modId) {
        final Optional<ModContainer> container = loader.getModContainer(modId);
        return container.isPresent()
            ? container.get().getMetadata().getVersion().getFriendlyString()
            : "not-installed";
    }

    private static boolean enabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    private static void indent(final StringBuilder result, final int depth) {
        for (int i = 0; i < depth; i++) {
            result.append("  ");
        }
    }

    private static String quote(final String value) {
        return '"' + sanitize(value) + '"';
    }

    private static String sanitize(final String value) {
        if (value == null) {
            return "null";
        }
        final String escaped = value
            .replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
            .replaceAll(
                "confluxmap:waypoint-import:v1:[A-Za-z0-9_-]+",
                "confluxmap:waypoint-import:v1:<redacted>"
            );
        if (escaped.length() <= MAX_VALUE_LENGTH) {
            return escaped;
        }
        return escaped.substring(0, MAX_VALUE_LENGTH)
            + "...<truncated " + (escaped.length() - MAX_VALUE_LENGTH) + " chars>";
    }
}

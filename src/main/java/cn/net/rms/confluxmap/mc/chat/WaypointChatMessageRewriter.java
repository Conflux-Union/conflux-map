package cn.net.rms.confluxmap.mc.chat;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatClickPayload;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatCodec;
import java.util.Optional;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Rewrites recognized waypoint shares immediately before they enter the visible chat queue. */
public final class WaypointChatMessageRewriter {
    private WaypointChatMessageRewriter() {
    }

    public static Text rewrite(final Text original, final DimensionId receivedDimension) {
        final String visibleMessage = original.getString();
        final Optional<WaypointChatCodec.Candidate> parsed = WaypointChatCodec.parse(
            visibleMessage, receivedDimension
        );
        final Optional<String> payload = WaypointChatClickPayload.encode(
            visibleMessage, receivedDimension
        );
        if (!parsed.isPresent() || !payload.isPresent()) {
            return original;
        }

        final WaypointChatCodec.Candidate candidate = parsed.get();
        final MutableText visible = candidate.confluxFormat()
            ? Texts.literal(WaypointChatCodec.formatCompactLabel(
                candidate, dimensionLabel(candidate.dimensionId())
            ))
            : Texts.literal("").append(original.shallowCopy());
        final MutableText importAction = Texts.translatable("confluxmap.chat.waypoint.import")
            .setStyle(Style.EMPTY
                .withColor(Formatting.AQUA)
                .withUnderline(true)
                .withClickEvent(Texts.copyToClipboard(payload.get())));
        return visible.append(Texts.literal(" ")).append(importAction);
    }

    private static String dimensionLabel(final DimensionId dimension) {
        if (dimension.equals(DimensionId.OVERWORLD)) {
            return Texts.translatable("confluxmap.dimension.overworld").getString();
        }
        if (dimension.equals(DimensionId.NETHER)) {
            return Texts.translatable("confluxmap.dimension.the_nether").getString();
        }
        if (dimension.equals(DimensionId.END)) {
            return Texts.translatable("confluxmap.dimension.the_end").getString();
        }
        return dimension.path();
    }
}

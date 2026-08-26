package cn.net.rms.confluxmap.mc.chat;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatClickPayload;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatCodec;
import java.util.Optional;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
//#if MC>=12109
//$$ import net.minecraft.text.TranslatableTextContent;
//#endif
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
            ? compactConfluxMessage(original, visibleMessage, candidate)
            : Texts.literal("").append(original.shallowCopy());
        final MutableText importAction = Texts.translatable("confluxmap.chat.waypoint.import")
            .setStyle(Style.EMPTY
                .withColor(Formatting.AQUA)
                .withUnderline(true)
                .withClickEvent(Texts.copyToClipboard(payload.get())));
        final Text rewritten = visible.append(Texts.literal(" ")).append(importAction);
        WaypointChatDiagnostics.rewrite(
            original, rewritten, receivedDimension, parsed, payload.isPresent()
        );
        return rewritten;
    }

    private static MutableText compactConfluxMessage(
        final Text original,
        final String visibleMessage,
        final WaypointChatCodec.Candidate candidate
    ) {
        final String compactLabel = WaypointChatCodec.formatCompactLabel(
            candidate, dimensionLabel(candidate.dimensionId())
        );
        //#if MC>=12109
        //$$ if (original.getContent() instanceof TranslatableTextContent content
        //$$     && "chat.type.text".equals(content.getKey())) {
        //$$     final Object[] originalArgs = content.getArgs();
        //$$     if (originalArgs.length >= 2) {
        //$$         final Object[] compactArgs = originalArgs.clone();
        //$$         compactArgs[1] = Texts.literal(compactLabel);
        //$$         final MutableText compact = Texts.translatable(content.getKey(), compactArgs)
        //$$             .setStyle(original.getStyle());
        //$$         for (final Text sibling : original.getSiblings()) {
        //$$             compact.append(sibling.copy());
        //$$         }
        //$$         return compact;
        //$$     }
        //$$ }
        //#endif
        return Texts.literal(WaypointChatCodec.formatCompactMessage(
            visibleMessage, candidate, dimensionLabel(candidate.dimensionId())
        ));
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

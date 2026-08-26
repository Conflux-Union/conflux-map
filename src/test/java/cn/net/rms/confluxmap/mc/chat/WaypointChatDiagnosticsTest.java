package cn.net.rms.confluxmap.mc.chat;

import static org.junit.jupiter.api.Assertions.assertTrue;
//#if MC>=12109
//$$ import static org.junit.jupiter.api.Assertions.assertFalse;
//#endif

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.model.DimensionId;
//#if MC>=12109
//$$ import net.minecraft.component.type.ProfileComponent;
//#endif
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
//#if MC>=12109
//$$ import net.minecraft.text.object.PlayerTextObjectContents;
//#endif
import net.minecraft.util.Formatting;
import org.junit.jupiter.api.Test;

final class WaypointChatDiagnosticsTest {
    @Test
    void describesNestedComponentBoundariesStylesAndEscapedText() {
        final MutableText child = Texts.translatable("confluxmap.dimension.overworld")
            .formatted(Formatting.AQUA);
        final Text tree = Texts.literal("root\n").append(child);

        final String description = WaypointChatDiagnostics.describe(tree);

        assertTrue(description.contains("node=root "));
        assertTrue(description.contains("node=root.0 "));
        assertTrue(description.contains("siblings=1"));
        assertTrue(description.contains("root\\nconfluxmap.dimension.overworld"));
        assertTrue(description.contains("AQUA") || description.contains("aqua"));
    }

    //#if MC>=12109
    //$$ @Test
    //$$ void preservesChatHeadsPlayerSpriteWhileCompactingConfluxMessage() {
    //$$     final Text sender = Texts.literal("")
    //$$         .append(Text.object(new PlayerTextObjectContents(
    //$$             ProfileComponent.ofDynamic("Alice"), true
    //$$         )))
    //$$         .append(Texts.literal("Alice"));
    //$$     final Text original = Texts.translatable(
    //$$         "chat.type.text",
    //$$         sender,
    //$$         Texts.literal(
    //$$             "<Alice> [Conflux Map] Home | minecraft:overworld | X: 1, Y: 64, Z: 2"
    //$$         )
    //$$     );
    //$$
    //$$     final Text rewritten = WaypointChatMessageRewriter.rewrite(
    //$$         original, DimensionId.OVERWORLD
    //$$     );
    //$$     final String inputTree = WaypointChatDiagnostics.describe(original);
    //$$     final String outputTree = WaypointChatDiagnostics.describe(rewritten);
    //$$
    //$$     assertTrue(inputTree.contains("ObjectTextContent"));
    //$$     assertTrue(inputTree.contains("PlayerTextObjectContents"));
    //$$     assertTrue(outputTree.contains("ObjectTextContent"));
    //$$     assertTrue(outputTree.contains("PlayerTextObjectContents"));
    //$$     assertTrue(rewritten.getString().contains("Home(1,64,2,"));
    //$$     assertFalse(rewritten.getString().contains("[Conflux Map]"));
    //$$ }
    //#endif
}

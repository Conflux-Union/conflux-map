package cn.net.rms.confluxmap.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.mc.ui.screen.ConfigScreen;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
//#if MC>=11802
//$$ import com.terraformersmc.modmenu.api.ConfigScreenFactory;
//$$ import com.terraformersmc.modmenu.api.ModMenuApi;
//#else
import io.github.prospector.modmenu.api.ConfigScreenFactory;
import io.github.prospector.modmenu.api.ModMenuApi;
//#endif
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.junit.jupiter.api.Test;

final class ModMenuIntegrationTest {
    @Test
    void factoryCreatesConfigScreenThatReturnsToItsParent() throws Exception {
        final Field instance = ConfluxMapClient.class.getDeclaredField("instance");
        instance.setAccessible(true);
        final ConfluxMapClient previous = (ConfluxMapClient) instance.get(null);
        instance.set(null, new ConfluxMapClient());
        //#if MC>=12111
        //$$ final Field minecraftInstance = MinecraftClient.class.getDeclaredField("instance");
        //$$ minecraftInstance.setAccessible(true);
        //$$ final MinecraftClient previousMinecraft = (MinecraftClient) minecraftInstance.get(null);
        //$$ minecraftInstance.set(null, allocate(MinecraftClient.class));
        //#endif
        try {
            final ModMenuApi integration = new ModMenuIntegration();
            final ConfigScreenFactory<?> factory = integration.getModConfigScreenFactory();
            final Screen parent = allocate(ParentScreen.class);

            final Screen screen = factory.create(parent);

            final ConfigScreen configScreen = assertInstanceOf(ConfigScreen.class, screen);
            final Field parentField = ConfigScreen.class.getDeclaredField("parent");
            parentField.setAccessible(true);
            assertSame(parent, parentField.get(configScreen));
        } finally {
            //#if MC>=12111
            //$$ minecraftInstance.set(null, previousMinecraft);
            //#endif
            instance.set(null, previous);
        }
    }

    @Test
    void packagedMetadataRegistersOptionalModMenuEntrypoint() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("fabric.mod.json")) {
            assertNotNull(input, "packaged Fabric metadata");
            final JsonObject metadata = new JsonParser()
                .parse(new InputStreamReader(input, StandardCharsets.UTF_8))
                .getAsJsonObject();
            final JsonObject entrypoints = metadata.getAsJsonObject("entrypoints");
            assertNotNull(entrypoints.getAsJsonArray("modmenu"), "Mod Menu entrypoint");

            assertEquals(
                "cn.net.rms.confluxmap.compat.ModMenuIntegration",
                entrypoints.getAsJsonArray("modmenu")
                    .get(0)
                    .getAsString()
            );
            final JsonObject suggests = metadata.getAsJsonObject("suggests");
            assertNotNull(suggests.get("modmenu"), "optional Mod Menu dependency");
            assertEquals("*", suggests.get("modmenu").getAsString());
        }
    }

    private static final class ParentScreen extends Screen {
        private ParentScreen() {
            super(Texts.literal("parent"));
        }
    }

    private static <T> T allocate(final Class<T> type) throws Exception {
        final Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        final Field theUnsafe = unsafeType.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return type.cast(
            unsafeType.getMethod("allocateInstance", Class.class).invoke(theUnsafe.get(null), type)
        );
    }
}

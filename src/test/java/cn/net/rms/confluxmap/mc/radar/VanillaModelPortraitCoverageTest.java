package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.CreeperEntityModel;
import org.junit.jupiter.api.Test;

/**
 * Walks every vanilla entity model class the running version ships, rebuilds its part tree from
 * the class' own model data, and proves the tree resolves a face-like part with valid projected
 * geometry.
 *
 * <p>The class list is read off the Minecraft jar rather than hard-coded, so a version that adds,
 * renames or restructures a mob model fails here instead of silently degrading that species to a
 * category dot or producing unusable atlas geometry. Only model data and CPU-side projection are
 * involved: no registries, render thread or GL context.
 */
final class VanillaModelPortraitCoverageTest {
    private static final String MODEL_SUFFIX = "Model";
    private static final String ENTITY_MODEL_SUFFIX = "EntityModel";

    /**
     * Models of things the radar never draws a face for: vehicles, projectiles, block entities, and
     * the equipment or effect layers that render on top of a mob rather than being one. Adding a
     * name here is a deliberate statement that it is not a living entity, because everything not
     * listed has to produce a portrait.
     */
    private static final Set<String> NOT_LIVING = Set.of(
        "ArrowEntityModel",
        "BannerBlockModel",
        "BannerEntityModel",
        "BedEntityModel",
        "BellBlockEntityModel",
        "BoatEntityModel",
        "BookModel",
        "ChestBoatEntityModel",
        "ChestEntityModel",
        "ChestRaftEntityModel",
        "ConduitEntityModel",
        "DecoratedPotEntityModel",
        "DragonHeadEntityModel",
        "ElytraEntityModel",
        "EvokerFangsEntityModel",
        "HangingSignBlockEntityModel",
        "HappyGhastHarnessEntityModel",
        "LeashKnotEntityModel",
        "LlamaSpitEntityModel",
        "MinecartEntityModel",
        "PiglinHeadEntityModel",
        "PlayerCapeModel",
        "RaftEntityModel",
        "ShieldEntityModel",
        "ShulkerBulletEntityModel",
        "SignBlockEntityModel",
        "SkullEntityModel",
        "SkullBlockEntityModel",
        "SpectralArrowEntityModel",
        "StingerModel",
        "TridentEntityModel",
        "TridentRiptideEntityModel",
        "WindChargeEntityModel"
    );

    private static final class FactoryFixture {
        private FactoryFixture() {
        }

        static TexturedModelData getTexturedModelData() {
            return null;
        }

        static TexturedModelData getOverlayTexturedModelData() {
            return null;
        }
    }

    @Test
    void primaryModelFactoryWinsRegardlessOfReflectionOrder() throws Exception {
        final Method primary = FactoryFixture.class.getDeclaredMethod("getTexturedModelData");
        final Method overlay = FactoryFixture.class.getDeclaredMethod("getOverlayTexturedModelData");

        assertSame(primary, primaryFactory(List.of(overlay, primary)));
        assertSame(primary, primaryFactory(List.of(primary, overlay)));
    }

    @Test
    void everyVanillaModelResolvesAFaceLikePart() {
        final List<String> unresolved = new ArrayList<>();
        vanillaRoots().forEach((name, root) -> {
            if (EntityHeadGeometry.selectFromRoot(root, entityTypeOf(name)).isEmpty()) {
                unresolved.add(name + " " + partNames(root));
            }
        });

        assertEquals(List.of(), unresolved, "these models would fall back to a category dot");
    }

    @Test
    void everyVanillaModelProducesValidPortraitGeometry() {
        final List<String> invalid = new ArrayList<>();
        vanillaRoots().forEach((name, root) -> {
            final String entityType = entityTypeOf(name);
            final List<ModelPart> parts = EntityHeadGeometry.selectFromRoot(root, entityType);
            if (parts.isEmpty()) {
                return;
            }
            final float[] geometry = EntityHeadGeometry.project(parts, entityType, 0, 0);
            if (geometry.length == 0 || hasNonFiniteCoordinate(geometry)) {
                invalid.add(name);
            }
        });

        assertEquals(List.of(), invalid, "these models produced invalid portrait geometry");
    }

    private static boolean hasNonFiniteCoordinate(final float[] geometry) {
        for (final float value : geometry) {
            if (!Float.isFinite(value)) {
                return true;
            }
        }
        return false;
    }

    /** Simple class name to part tree for every model class that can build its own model data. */
    private static Map<String, ModelPart> vanillaRoots() {
        final Map<String, ModelPart> roots = new TreeMap<>();
        for (final Class<?> model : modelClasses()) {
            final String name = model.getSimpleName();
            if (NOT_LIVING.contains(name) || Modifier.isAbstract(model.getModifiers())) {
                continue;
            }
            final ModelPart root = buildRoot(model);
            if (root != null) {
                roots.put(name, root);
            }
        }
        if (roots.isEmpty()) {
            throw new IllegalStateException("Found no vanilla entity models to check");
        }
        return roots;
    }

    /**
     * Builds a model's part tree through its own static model-data factory. A model without one, or
     * one whose factory needs game state a unit test has no registries for, is skipped: those are
     * data-driven or block-backed models rather than the mob trees this covers.
     */
    private static ModelPart buildRoot(final Class<?> model) {
        final Method factory = primaryFactory(Arrays.asList(model.getDeclaredMethods()));
        if (factory == null) {
            return null;
        }
        final Object[] arguments = new Object[factory.getParameterCount()];
        for (int i = 0; i < arguments.length; i++) {
            arguments[i] = defaultArgument(factory.getParameterTypes()[i]);
            if (arguments[i] == null) {
                return null;
            }
        }
        try {
            factory.setAccessible(true);
            return ((TexturedModelData) factory.invoke(null, arguments)).createModel();
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Method primaryFactory(final Collection<Method> methods) {
        return methods.stream()
            .filter(method -> Modifier.isStatic(method.getModifiers()))
            .filter(method -> method.getReturnType() == TexturedModelData.class)
            .filter(VanillaModelPortraitCoverageTest::supportsFactory)
            .min(Comparator
                .comparing((Method method) -> !method.getName().equals("getTexturedModelData"))
                .thenComparingInt(Method::getParameterCount)
                .thenComparing(Method::getName)
                .thenComparing(method -> Arrays.toString(method.getParameterTypes())))
            .orElse(null);
    }

    private static boolean supportsFactory(final Method factory) {
        for (final Class<?> parameter : factory.getParameterTypes()) {
            if (defaultArgument(parameter) == null) {
                return false;
            }
        }
        return true;
    }

    private static Object defaultArgument(final Class<?> type) {
        if (type == Dilation.class) {
            return Dilation.NONE;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == boolean.class) {
            return false;
        }
        return null;
    }

    private static List<Class<?>> modelClasses() {
        final String packagePath = CreeperEntityModel.class.getPackageName().replace('.', '/') + '/';
        final List<Class<?>> classes = new ArrayList<>();
        try (ZipFile jar = new ZipFile(minecraftJar().toFile())) {
            final Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                final String entry = entries.nextElement().getName();
                if (!entry.startsWith(packagePath) || !entry.endsWith(".class") || entry.contains("$")) {
                    continue;
                }
                final String className = entry.substring(0, entry.length() - ".class".length())
                    .replace('/', '.');
                try {
                    classes.add(Class.forName(
                        className, false, VanillaModelPortraitCoverageTest.class.getClassLoader()
                    ));
                } catch (final ClassNotFoundException | LinkageError ignored) {
                    // A class the test classpath cannot resolve carries no model tree to check.
                }
            }
        } catch (final Exception e) {
            throw new IllegalStateException("Could not read the Minecraft jar", e);
        }
        return classes;
    }

    private static Path minecraftJar() throws Exception {
        return Path.of(
            CreeperEntityModel.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        );
    }

    /**
     * Guesses the entity id a model belongs to from its class name. Only the selector's
     * species-specific rules read the id, so a name that does not map cleanly still gets checked
     * through the generic part-name rules.
     */
    private static String entityTypeOf(final String className) {
        String name = className;
        if (name.endsWith(ENTITY_MODEL_SUFFIX)) {
            name = name.substring(0, name.length() - ENTITY_MODEL_SUFFIX.length());
        } else if (name.endsWith(MODEL_SUFFIX)) {
            name = name.substring(0, name.length() - MODEL_SUFFIX.length());
        }
        final StringBuilder id = new StringBuilder("minecraft:");
        for (int i = 0; i < name.length(); i++) {
            final char character = name.charAt(i);
            if (Character.isUpperCase(character) && i > 0) {
                id.append('_');
            }
            id.append(Character.toLowerCase(character));
        }
        return id.toString().toLowerCase(Locale.ROOT);
    }

    /** Part names of a failing tree, so the report names the model shape, not only the class. */
    private static String partNames(final ModelPart root) {
        final List<String> names = new ArrayList<>();
        collectNames(root, "", names);
        return names.toString();
    }

    private static void collectNames(
        final ModelPart part,
        final String path,
        final List<String> names
    ) {
        for (final Map.Entry<String, ModelPart> child : EntityHeadGeometry.children(part).entrySet()) {
            final String childPath = path.isEmpty() ? child.getKey() : path + "/" + child.getKey();
            names.add(childPath);
            collectNames(child.getValue(), childPath, names);
        }
    }
}

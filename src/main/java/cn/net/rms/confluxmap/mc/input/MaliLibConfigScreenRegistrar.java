package cn.net.rms.confluxmap.mc.input;

import cn.net.rms.confluxmap.ConfluxMapMod;
//#if MC>=12101
//$$ import fi.dy.masa.malilib.gui.GuiBase;
//$$ import fi.dy.masa.malilib.registry.Registry;
//$$ import fi.dy.masa.malilib.util.data.ModInfo;
//#endif
//#if MC<12101
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
//#endif
import java.util.function.Supplier;

final class MaliLibConfigScreenRegistrar {
    //#if MC<12101
    private static final String REGISTRY_CLASS = "fi.dy.masa.malilib.registry.Registry";
    private static final String MOD_INFO_CLASS = "fi.dy.masa.malilib.util.data.ModInfo";
    //#endif

    private MaliLibConfigScreenRegistrar() {
    }

    static boolean register(final Supplier<?> screenFactory) {
        //#if MC>=12101
        //$$ Registry.CONFIG_SCREEN.registerConfigScreenFactory(
        //$$     new ModInfo(ConfluxMapMod.ID, "Conflux Map", () -> (GuiBase) screenFactory.get())
        //$$ );
        //$$ return true;
        //#else
        return registerLegacy(screenFactory, MaliLibConfigScreenRegistrar.class.getClassLoader());
        //#endif
    }

    //#if MC<12101
    static boolean registerLegacy(final Supplier<?> screenFactory, final ClassLoader classLoader) {
        final Class<?> registryOwner;
        try {
            registryOwner = Class.forName(REGISTRY_CLASS, true, classLoader);
        } catch (final ClassNotFoundException e) {
            return false;
        } catch (final LinkageError | RuntimeException e) {
            warnAndKeepShortcut(e);
            return false;
        }

        try {
            final Class<?> modInfoType = Class.forName(MOD_INFO_CLASS, true, classLoader);
            final Field registryField = registryOwner.getField("CONFIG_SCREEN");
            final Object registry = registryField.get(null);
            final Constructor<?> modInfoConstructor = modInfoType.getConstructor(
                String.class,
                String.class,
                Supplier.class
            );
            final Object modInfo = modInfoConstructor.newInstance(
                ConfluxMapMod.ID,
                "Conflux Map",
                screenFactory
            );
            final Method registerMethod = registry.getClass().getMethod(
                "registerConfigScreenFactory",
                modInfoType
            );
            registerMethod.invoke(registry, modInfo);
            return true;
        } catch (final ReflectiveOperationException | LinkageError | RuntimeException e) {
            warnAndKeepShortcut(e);
            return false;
        }
    }

    private static void warnAndKeepShortcut(final Throwable failure) {
        ConfluxMapMod.LOGGER.warn(
            "MaliLib exposes a config-screen registry but Conflux Map could not register with it; "
                + "keeping the compatibility shortcut",
            failure
        );
    }
    //#endif
}

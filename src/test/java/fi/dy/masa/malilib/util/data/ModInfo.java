//#if MC<12101
package fi.dy.masa.malilib.util.data;

import java.util.function.Supplier;

public final class ModInfo {
    private final String modId;
    private final String modName;
    private final Supplier<?> configScreenSupplier;

    public ModInfo(
        final String modId,
        final String modName,
        final Supplier<?> configScreenSupplier
    ) {
        this.modId = modId;
        this.modName = modName;
        this.configScreenSupplier = configScreenSupplier;
    }

    public String getModId() {
        return modId;
    }

    public String getModName() {
        return modName;
    }

    public Supplier<?> getConfigScreenSupplier() {
        return configScreenSupplier;
    }
}
//#endif

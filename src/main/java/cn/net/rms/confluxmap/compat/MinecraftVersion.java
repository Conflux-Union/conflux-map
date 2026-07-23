package cn.net.rms.confluxmap.compat;

/** Compile-time Minecraft release identity for the active version subproject. */
public final class MinecraftVersion {
    private MinecraftVersion() {
    }

    public static String current() {
        //#if MC>=12111
        //$$ return "1.21.11";
        //#elseif MC>=12108
        //$$ return "1.21.8";
        //#elseif MC>=12105
        //$$ return "1.21.5";
        //#elseif MC>=12104
        //$$ return "1.21.4";
        //#elseif MC>=12103
        //$$ return "1.21.3";
        //#elseif MC>=12101
        //$$ return "1.21.1";
        //#else
        return "1.17.1";
        //#endif
    }
}

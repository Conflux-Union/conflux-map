package cn.net.rms.confluxmap.compat;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

/** Default-preserving reads across the optional NBT API introduced in 1.21.5. */
public final class Nbts {
    private Nbts() {
    }

    public static boolean hasCompound(final NbtCompound compound, final String key) {
        //#if MC>=12105
        //$$ return compound.getCompound(key).isPresent();
        //#else
        return compound.contains(key, 10);
        //#endif
    }

    public static NbtCompound compound(final NbtCompound compound, final String key) {
        //#if MC>=12105
        //$$ return compound.getCompound(key).orElseGet(NbtCompound::new);
        //#else
        return compound.contains(key, 10) ? compound.getCompound(key) : new NbtCompound();
        //#endif
    }

    public static String string(final NbtCompound compound, final String key) {
        //#if MC>=12105
        //$$ return compound.getString(key, "");
        //#else
        return compound.getString(key);
        //#endif
    }

    public static int integer(final NbtCompound compound, final String key) {
        //#if MC>=12105
        //$$ return compound.getInt(key, 0);
        //#else
        return compound.getInt(key);
        //#endif
    }

    public static int byteValue(final NbtCompound compound, final String key) {
        //#if MC>=12105
        //$$ return compound.getByte(key, (byte) 0);
        //#else
        return compound.getByte(key);
        //#endif
    }

    public static long longValue(final NbtCompound compound, final String key) {
        //#if MC>=12105
        //$$ return compound.getLong(key, 0L);
        //#else
        return compound.getLong(key);
        //#endif
    }

    public static long[] longArray(final NbtCompound compound, final String key) {
        //#if MC>=12105
        //$$ return compound.getLongArray(key).orElseGet(() -> new long[0]);
        //#else
        return compound.getLongArray(key);
        //#endif
    }

    public static int[] intArray(final NbtCompound compound, final String key) {
        //#if MC>=12105
        //$$ return compound.getIntArray(key).orElseGet(() -> new int[0]);
        //#else
        return compound.getIntArray(key);
        //#endif
    }

    public static NbtList list(final NbtCompound compound, final String key, final int elementType) {
        //#if MC>=12105
        //$$ return compound.getList(key).orElseGet(NbtList::new);
        //#else
        return compound.contains(key, 9) ? compound.getList(key, elementType) : new NbtList();
        //#endif
    }

    public static NbtCompound compound(final NbtList list, final int index) {
        //#if MC>=12105
        //$$ return list.getCompound(index).orElseGet(NbtCompound::new);
        //#else
        return list.getCompound(index);
        //#endif
    }

    public static String string(final NbtList list, final int index) {
        //#if MC>=12105
        //$$ return list.getString(index, "");
        //#else
        return list.getString(index);
        //#endif
    }
}

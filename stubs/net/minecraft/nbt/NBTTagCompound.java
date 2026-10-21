package net.minecraft.nbt;

/**
 * 0.2.8 signature stub (SRG names); the real MC class is used at runtime.
 * Names + descriptors verified against Forge's official deobfuscation_data-1.7.10.lzma
 * (CSV line: "MD: dh/f (Ljava/lang/String;)I net/minecraft/nbt/NBTTagCompound/func_74762_e (Ljava/lang/String;)I")
 * AND against PR 4.12.44's own bytecode (Share/IntegratedCircuit call func_74771_c as (String)B).
 *   func_82580_o  = removeTag(String)V
 *   func_150297_b = hasTag(String, int)Z
 *   func_74773_a  = setByteArray(String, byte[])V
 *   func_74762_e  = getInteger(String)I   <- 0.2.8 FIX: 0.2.7 wrongly declared
 *                                            func_74771_c as int; func_74771_c is
 *                                            really getByte(String)B, so every
 *                                            getInteger call site died with
 *                                            NoSuchMethodError at runtime.
 *   func_74771_c  = getByte(String)B
 *   func_74779_i  = getString(String)Ljava/lang/String;
 *   func_74778_a  = setString(String, String)V
 *   func_150295_c = getTagList(String, int)Lnet/minecraft/nbt/NBTTagList;
 */
public class NBTTagCompound {
    public NBTTagCompound() {
    }

    public void func_82580_o(String key) {
    }

    public boolean func_150297_b(String key, int type) {
        return false;
    }

    public NBTTagList func_150295_c(String key, int type) {
        return null;
    }

    public void func_74773_a(String key, byte[] value) {
    }

    public void func_74778_a(String key, String value) {
    }

    public int func_74762_e(String key) {
        return 0;
    }

    public byte func_74771_c(String key) {
        return (byte)0;
    }

    public String func_74779_i(String key) {
        return null;
    }

    /** 0.3.8 (Blueprints): setInteger(String, int)V - from the SRG member dump. */
    public void func_74768_a(String key, int value) {
    }

    /** 0.3.8 (Blueprints dead-signal check): getByteArray(String)[B. */
    public byte[] func_74770_j(String key) {
        return null;
    }

    /**
     * 0.4.9 (Timer state reset): getLong(String)J.
     * Verified with mcref/ask.py against the real client jar:
     * {@code method long func_74763_f(java.lang.String) (Ljava/lang/String;)J}.
     * A missing key returns 0 -- that is exactly what turned a blueprint without
     * {@code pelapsed} into a crash, see {@link #func_74772_a}.
     */
    public long func_74763_f(String key) {
        return 0L;
    }

    /**
     * 0.4.9 (Timer state reset): setLong(String, long)V.
     * Verified with mcref/ask.py: {@code method void func_74772_a(java.lang.String, long) (Ljava/lang/String;J)V}.
     */
    public void func_74772_a(String key, long value) {
    }

    /**
     * 0.4.9 (held-chip guard): getCompoundTag(String)Lnet/minecraft/nbt/NBTTagCompound;.
     * Verified with mcref/ask.py: {@code method dh func_74775_l(java.lang.String) (Ljava/lang/String;)Ldh;}.
     * Returns a null compound when the key is missing or holds another type.
     */
    public NBTTagCompound func_74775_l(String key) {
        return null;
    }
}

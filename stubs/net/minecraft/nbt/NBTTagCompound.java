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

    public int func_74762_e(String key) {
        return 0;
    }

    public byte func_74771_c(String key) {
        return (byte)0;
    }

    public String func_74779_i(String key) {
        return null;
    }
}

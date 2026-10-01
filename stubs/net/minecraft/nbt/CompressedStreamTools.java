package net.minecraft.nbt;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;

/**
 * 0.2.7 signature stub (SRG names); real MC class is used at runtime.
 * func_74798_a = compress(NBTTagCompound) -> gzip bytes
 * func_74794_a = read(DataInputStream) -> NBTTagCompound (throws IOException)
 * 0.3.8 (Blueprints) adds the two file helpers; descriptors taken from the SRG dump:
 *   func_74795_b(Lnet/minecraft/nbt/NBTTagCompound;Ljava/io/File;)V   = writeCompressed(tag, file)
 *   func_74797_a(Ljava/io/File;)Lnet/minecraft/nbt/NBTTagCompound;    = readCompressed(file)
 */
public final class CompressedStreamTools {
    private CompressedStreamTools() {
    }

    public static byte[] func_74798_a(NBTTagCompound tag) {
        return null;
    }

    public static NBTTagCompound func_74794_a(DataInputStream in) throws IOException {
        return null;
    }

    public static void func_74795_b(NBTTagCompound tag, File file) {
    }

    public static NBTTagCompound func_74797_a(File file) {
        return null;
    }
}

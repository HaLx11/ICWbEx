package net.minecraft.client;

import java.io.File;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiScreen;

public class Minecraft {
    public static Minecraft func_71410_x() {
        return null;
    }

    public void func_147108_a(GuiScreen screen) {
    }

    public File field_71412_D;

    /**
     * thePlayer. The declared type must be exactly what the client jar stores,
     * because the compiled getfield carries the type in its descriptor — anything
     * else is a NoSuchFieldError at runtime. Checked against the vanilla jar:
     * field h of bao has descriptor Lbjk;, and bjk is
     * net/minecraft/client/entity/EntityClientPlayerMP.
     */
    public EntityClientPlayerMP field_71439_g;
}

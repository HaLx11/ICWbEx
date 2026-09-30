package mrtjp.projectred.fabrication;

import mrtjp.core.vec.Size;

public class IntegratedCircuit {
    public Size size() {
        return null;
    }

    public void size_$eq(Size size) {
    }

    public void removePart(int x, int y) {
    }

    public void refreshErrors() {
    }

    // --- added for Blueprints (v0.2.6); descriptors from real PR jar javap ---
    public scala.collection.mutable.Map parts() {
        return null;
    }

    public void save(net.minecraft.nbt.NBTTagCompound tag) {
    }

    public void load(net.minecraft.nbt.NBTTagCompound tag) {
    }

    // --- added for 0.2.7 (Replay/Sync/Clipboard); descriptors from real PR 4.12.44 jar javap ---
    public CircuitPart getPart(int x, int y) {
        return null;
    }

    public void setPart(int x, int y, CircuitPart part) {
    }

    public String name() {
        return null;
    }

    public void name_$eq(String s) {
    }

    public WorldCircuit network() {
        return null;
    }
}

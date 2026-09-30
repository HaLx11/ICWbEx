/*
 * ICWbEx v0.2.7: rebuilt from CFR decompilation of the 0.2.1 jar.
 * Change vs 0.2.1: stamp() no longer overwrites cells that hold a bundled cable
 * (the pasted content must not affect existing bundled cables - overwriting one
 * would replace its signal state and poison the whole-circuit desc). Skipped
 * cells are counted in lastSkippedBundled so the GUI can tell the player.
 */
package icwbe;

import icwbe.Blueprints;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import mrtjp.core.vec.Size;
import mrtjp.projectred.fabrication.BundledCableICPart;
import mrtjp.projectred.fabrication.CircuitPart;
import mrtjp.projectred.fabrication.IntegratedCircuit;
import mrtjp.projectred.fabrication.TConnectableICPart;
import net.minecraft.nbt.NBTTagCompound;

public final class Clipboard {
    /** cells skipped by the last stamp() because a bundled cable occupies them. */
    public int lastSkippedBundled;

    private final List<Entry> entries = new ArrayList<Entry>();
    private int w;
    private int h;

    private Clipboard() {
    }

    public static long key(int n, int n2) {
        return (long)n << 32 | (long)n2 & 0xFFFFFFFFL;
    }

    public static int kx(long l) {
        return (int)(l >> 32);
    }

    public static int ky(long l) {
        return (int)l;
    }

    public static CircuitPart partAt(IntegratedCircuit integratedCircuit, int n, int n2) {
        if (integratedCircuit == null || n < 0 || n2 < 0) {
            return null;
        }
        Size size = integratedCircuit.size();
        if (n >= size.width() || n2 >= size.height()) {
            return null;
        }
        try {
            return integratedCircuit.getPart(n, n2);
        }
        catch (Throwable throwable) {
            return null;
        }
    }

    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    public int size() {
        return this.entries.size();
    }

    public Entry get(int n) {
        return this.entries.get(n);
    }

    public int width() {
        return this.w;
    }

    public int height() {
        return this.h;
    }

    public static Clipboard grab(IntegratedCircuit integratedCircuit, Collection<Long> collection) {
        Blueprints.ensureCaches(integratedCircuit);
        return Clipboard.grabPatched(integratedCircuit, collection);
    }

    private static Clipboard grabPatched(IntegratedCircuit integratedCircuit, Collection<Long> collection) {
        Clipboard clipboard = new Clipboard();
        int n = Integer.MAX_VALUE;
        int n2 = Integer.MAX_VALUE;
        for (long l : collection) {
            int n3;
            int n4 = Clipboard.kx(l);
            if (Clipboard.partAt(integratedCircuit, n4, n3 = Clipboard.ky(l)) == null) continue;
            if (n4 < n) {
                n = n4;
            }
            if (n3 >= n2) continue;
            n2 = n3;
        }
        if (n == Integer.MAX_VALUE) {
            return clipboard;
        }
        int n5 = n;
        int n6 = n2;
        for (long l : collection) {
            int n7;
            int n8 = Clipboard.kx(l);
            CircuitPart circuitPart = Clipboard.partAt(integratedCircuit, n8, n7 = Clipboard.ky(l));
            if (circuitPart == null) continue;
            NBTTagCompound nBTTagCompound = new NBTTagCompound();
            try {
                circuitPart.save(nBTTagCompound);
            }
            catch (Throwable throwable) {
                continue;
            }
            Clipboard.sanitizeCopyTag(nBTTagCompound);
            clipboard.entries.add(new Entry(n8 - n, n7 - n2, circuitPart.id(), nBTTagCompound));
            if (n8 > n5) {
                n5 = n8;
            }
            if (n7 <= n6) continue;
            n6 = n7;
        }
        clipboard.w = n5 - n + 1;
        clipboard.h = n6 - n2 + 1;
        return clipboard;
    }

    static void sanitizeCopyTag(NBTTagCompound nBTTagCompound) {
        try {
            nBTTagCompound.func_82580_o("connMap");
            if (nBTTagCompound.func_150297_b("signal", 7)) {
                nBTTagCompound.func_74773_a("signal", new byte[16]);
            } else if (nBTTagCompound.func_150297_b("signal", 1)) {
                nBTTagCompound.func_82580_o("signal");
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    public int stamp(IntegratedCircuit integratedCircuit, int n, int n2) {
        this.lastSkippedBundled = 0;
        int n3 = 0;
        ArrayList<CircuitPart> arrayList = new ArrayList<CircuitPart>(this.entries.size());
        for (Entry entry : this.entries) {
            int n4 = n + entry.dx;
            int n5 = n2 + entry.dy;
            if (n4 < 0 || n5 < 0) continue;
            try {
                CircuitPart target = Clipboard.partAt(integratedCircuit, n4, n5);
                if (target instanceof BundledCableICPart) {
                    ++this.lastSkippedBundled;
                    continue;
                }
                CircuitPart circuitPart = CircuitPart.createPart((int)entry.id);
                if (circuitPart == null) continue;
                circuitPart.load(entry.data);
                integratedCircuit.setPart(n4, n5, circuitPart);
                arrayList.add(circuitPart);
                ++n3;
            }
            catch (Throwable throwable) {}
        }
        this.relink(integratedCircuit, arrayList);
        return n3;
    }

    private void relink(IntegratedCircuit integratedCircuit, List<CircuitPart> list) {
        for (CircuitPart circuitPart : list) {
            try {
                if (!(circuitPart instanceof TConnectableICPart)) continue;
                ((TConnectableICPart)circuitPart).updateConns();
            }
            catch (Throwable throwable) {}
        }
    }

    public static final class Entry {
        public final int dx;
        public final int dy;
        public final int id;
        public final NBTTagCompound data;

        Entry(int n, int n2, int n3, NBTTagCompound nBTTagCompound) {
            this.dx = n;
            this.dy = n2;
            this.id = n3;
            this.data = nBTTagCompound;
        }
    }
}

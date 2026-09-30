package icwbe;

import codechicken.lib.data.MCDataOutput;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import mrtjp.core.vec.Point;
import mrtjp.core.vec.Size;
import mrtjp.projectred.fabrication.BundledCableICPart;
import mrtjp.projectred.fabrication.CircuitOp;
import mrtjp.projectred.fabrication.CircuitOp$;
import mrtjp.projectred.fabrication.CircuitPart;
import mrtjp.projectred.fabrication.IntegratedCircuit;
import mrtjp.projectred.fabrication.InsulatedWireICPart;
import mrtjp.projectred.fabrication.OpWire;
import mrtjp.projectred.fabrication.TConnectableICPart;
import mrtjp.projectred.fabrication.TileICWorkbench;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Human-style replay: pushes the client-side circuit to the server one part at a
 * time, using the very same packets the vanilla GUI sends when a player places
 * parts by hand.
 *
 * Why: ProjectRed's "whole circuit" sync (TileICWorkbench.sendNewICToServer ->
 * IntegratedCircuit.writeDesc/readDesc) transports bundled-cable signals as a
 * packed bitmask. BundledCommons.unpackDigital(signal, 0) returns null for an
 * all-zero signal, so any bundled cable that arrives through that path gets a
 * null signal on the receiving side - which later NPEs inside PR (osig[i]) or
 * blows up NBT serialisation. Placing parts one by one never touches that path:
 * every wire is created fresh server-side by the op packet, and every gate is
 * created by an id + desc packet, so no null signal can ever be produced.
 *
 * Packet layout (client -> server, tile packet "IC stream"):
 *   case 1: [partId][x][y][desc...]         create a part with full desc state
 *   case 2: [x][y]                          remove a part
 *   case 3: [opId][op payload...]           run a CircuitOp (wires, erase, ...)
 * Multiple commands may share one stream; readICStream loops until 0xFF.
 *
 * 0.2.8 notes: 0.2.7 never got a single wire op onto the wire - two wrapper
 * classes were compiled against stub signatures that did not match the runtime
 * jars (MCDataOutput stubbed as a class though the real one is an interface ->
 * IncompatibleClassChangeError; NBT getInteger stubbed as func_74771_c though
 * that is really getByte -> NoSuchMethodError). The orphaned stream key byte
 * that getICStreamOf() writes before any payload then sat in the tile's
 * client-side buffer, was flushed by the vanilla every-tick update, and made
 * the server parse the 0xFF terminator as an op id -> CircuitOpDefs.apply(255)
 * -> null -> NPE kick on the next load. The stubs are fixed (verified against
 * javap of the runtime jars and Forge's official deobfuscation data) and Sync
 * now resets the stream buffers after any failure (see Sync.toServer).
 */
public final class Replay {

    /**
     * Wire op lookup. Coloured wires share ONE part id (colour is a field, set by the
     * op's constructor), so the key must include the colour tag - otherwise every
     * pasted cable would silently come out in whichever colour registered last.
     */
    private static final Map<Integer, OpWire> WIRE_OPS = new HashMap<Integer, OpWire>();
    private static boolean wireOpsReady;

    /** colour tag used for parts that carry no colour at all. */
    private static final int NO_COLOUR = 0xFF;

    /** Fields that are derived at runtime and must not force a re-place. */
    private static final String[] RUNTIME_KEYS = new String[]{"connMap", "signal", "schedTime"};

    private Replay() {
    }

    private static int wireKey(int partId, int colourTag) {
        return (partId & 0xFF) << 8 | (colourTag & 0xFF);
    }

    private static int colourTag(CircuitPart part) {
        try {
            if (part instanceof BundledCableICPart) {
                return ((BundledCableICPart)part).colour() & 0xFF;
            }
            if (part instanceof InsulatedWireICPart) {
                return ((InsulatedWireICPart)part).colour() & 0xFF;
            }
        }
        catch (Throwable ignored) {
        }
        return NO_COLOUR;
    }

    private static int wireKeyOf(CircuitPart part) {
        return wireKey(part.id(), colourTag(part));
    }

    private static void ensureWireOps() {
        if (wireOpsReady) {
            return;
        }
        wireOpsReady = true;
        for (int i = 0; i < 256; ++i) {
            try {
                CircuitOp op = CircuitOp$.MODULE$.getOperation(i);
                if (!(op instanceof OpWire)) {
                    continue;
                }
                CircuitPart probe = ((OpWire)op).createPart();
                if (probe == null) {
                    continue;
                }
                WIRE_OPS.put(Integer.valueOf(wireKeyOf(probe)), (OpWire)op);
            }
            catch (Throwable ignored) {
                // out of range / not creatable - skip
            }
        }
    }

    private static long key(int x, int y) {
        return (long)x << 32 | (long)(y & 0xFFFF);
    }

    private static int keyX(long k) {
        return (int)(k >> 32);
    }

    private static int keyY(long k) {
        return (int)(k & 0xFFFFL);
    }

    private static NBTTagCompound copyOf(NBTTagCompound tag) {
        try {
            byte[] raw = CompressedStreamTools.func_74798_a(tag);
            GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw));
            return CompressedStreamTools.func_74794_a(new DataInputStream(gz));
        }
        catch (Throwable t) {
            return tag;
        }
    }

    private static void stripRuntime(NBTTagCompound tag) {
        for (int i = 0; i < RUNTIME_KEYS.length; ++i) {
            tag.func_82580_o(RUNTIME_KEYS[i]);
        }
    }

    /** true when the part carries the same persistent configuration as the tag. */
    private static boolean samePart(CircuitPart part, NBTTagCompound oldTag) {
        try {
            NBTTagCompound now = new NBTTagCompound();
            part.save(now);
            NBTTagCompound then = copyOf(oldTag);
            stripRuntime(now);
            stripRuntime(then);
            return Arrays.equals(CompressedStreamTools.func_74798_a(now), CompressedStreamTools.func_74798_a(then));
        }
        catch (Throwable t) {
            return false;
        }
    }

    private static Map<Long, NBTTagCompound> index(NBTTagCompound snapshot) {
        Map<Long, NBTTagCompound> map = new HashMap<Long, NBTTagCompound>();
        if (snapshot == null) {
            return map;
        }
        try {
            NBTTagList list = snapshot.func_150295_c("parts", 10);
            int n = list.func_74745_c();
            for (int i = 0; i < n; ++i) {
                NBTTagCompound t = list.func_150305_b(i);
                if (t == null) {
                    continue;
                }
                int x = t.func_74762_e("xpos") & 0xFF;
                int y = t.func_74762_e("ypos") & 0xFF;
                map.put(Long.valueOf(key(x, y)), t);
            }
        }
        catch (Throwable ignored) {
            // treat as empty
        }
        return map;
    }

    private static NBTTagCompound snapshot(IntegratedCircuit ic) {
        NBTTagCompound tag = new NBTTagCompound();
        ic.save(tag);
        return tag;
    }

    /** true when the board size of the snapshot differs from the live circuit. */
    public static boolean sizeDiffers(NBTTagCompound before, IntegratedCircuit ic) {
        if (before == null || ic == null) {
            return false;
        }
        try {
            Size size = ic.size();
            return (before.func_74762_e("sw") & 0xFF) != size.width()
                || (before.func_74762_e("sh") & 0xFF) != size.height();
        }
        catch (Throwable t) {
            return false;
        }
    }

    /** true when the circuit contains at least one bundled cable (the only part whose
     *  desc cannot be trusted on an unpatched server). */
    public static boolean hasBundledCable(IntegratedCircuit ic) {
        if (ic == null) {
            return false;
        }
        try {
            Size size = ic.size();
            int w = size.width();
            int h = size.height();
            for (int y = 0; y < h; ++y) {
                for (int x = 0; x < w; ++x) {
                    if (ic.getPart(x, y) instanceof BundledCableICPart) {
                        return true;
                    }
                }
            }
        }
        catch (Throwable ignored) {
        }
        return false;
    }

    /** true when the circuit carries the same name as the snapshot. */
    public static boolean sameName(NBTTagCompound before, IntegratedCircuit ic) {
        if (before == null || ic == null) {
            return false;
        }
        try {
            String a = before.func_74779_i("name");
            String b = ic.name();
            return a == null ? b == null : a.equals(b);
        }
        catch (Throwable t) {
            return false;
        }
    }

    /** Recomputes connection masks of the local copy so the GUI keeps rendering links. */
    private static void refreshLocal(IntegratedCircuit ic) {
        try {
            Size size = ic.size();
            int w = size.width();
            int h = size.height();
            for (int y = 0; y < h; ++y) {
                for (int x = 0; x < w; ++x) {
                    CircuitPart part = ic.getPart(x, y);
                    if (part instanceof TConnectableICPart) {
                        try {
                            ((TConnectableICPart)part).updateConns();
                        }
                        catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
        catch (Throwable ignored) {
        }
    }

    private static void writeWireOp(TileICWorkbench tile, IntegratedCircuit ic, OpWire op, int x0, int x1, int y) {
        // 0.2.8: the server resolves op ids through CircuitOpDefs.apply(id).getOp(),
        // which has no bounds check - an id outside the registered 0..60 range makes
        // apply() return null and the server NPEs. Never emit such an id.
        int opId = op.id();
        if (opId < 0 || opId > 255) {
            return;
        }
        MCDataOutput out = tile.getICStreamOf(3);
        out.writeByte(opId);
        op.writeOp(ic, new Point(x0, y), new Point(x1, y), out);
    }

    /**
     * @param before    snapshot of the client circuit as it was before the edit that
     *                  just happened, or null when unknown.
     * @param descFirst when true an EMPTY whole-circuit desc is sent first (this is
     *                  the only way to transport the board size and the circuit
     *                  name) and everything is replayed afterwards. Because that
     *                  packet carries no part at all it can never funnel a bundled
     *                  cable through the upstream unpackDigital(null) bug.
     * @return true when a whole-circuit desc was sent (caller must drop its
     *         snapshot cache: the server echoes an empty board back to us).
     */
    public static boolean push(TileICWorkbench tile, IntegratedCircuit ic, NBTTagCompound before, boolean descFirst) {
        if (tile == null || ic == null) {
            return false;
        }
        ensureWireOps();

        Size size = ic.size();
        int w = size.width();
        int h = size.height();
        if (w <= 2 || h <= 2) {
            return false;
        }

        int ow = before == null ? w : (before.func_74762_e("sw") & 0xFF);
        int oh = before == null ? h : (before.func_74762_e("sh") & 0xFF);
        boolean resized = ow != w || oh != h;

        List<int[]> removes = new ArrayList<int[]>();
        List<CircuitPart> gates = new ArrayList<CircuitPart>();
        Map<Long, Integer> wires = new HashMap<Long, Integer>();

        if (before == null || resized || descFirst) {
            // Server contents unknown / size or name must change: rebuild it all.
            if (resized || descFirst) {
                IntegratedCircuit blank = new IntegratedCircuit();
                blank.name_$eq(ic.name());
                blank.size_$eq(new Size(w, h));
                tile.sendNewICToServer(blank);
            }
            else {
                // Same size, unknown contents: clear every inner cell transparently
                // (no whole-circuit packet, so the client copy stays untouched).
                for (int y = 1; y < h - 1; ++y) {
                    for (int x = 1; x < w - 1; ++x) {
                        removes.add(new int[]{x, y});
                    }
                }
            }
            for (int y = 0; y < h; ++y) {
                for (int x = 0; x < w; ++x) {
                    CircuitPart part = ic.getPart(x, y);
                    if (part != null) {
                        collect(part, gates, wires);
                    }
                }
            }
        }
        else {
            // Exact diff against the last synced state.
            Map<Long, NBTTagCompound> old = index(before);
            for (int y = 0; y < h; ++y) {
                for (int x = 0; x < w; ++x) {
                    CircuitPart part = ic.getPart(x, y);
                    NBTTagCompound oldTag = old.remove(Long.valueOf(key(x, y)));
                    if (part == null) {
                        if (oldTag != null) {
                            removes.add(new int[]{x, y});
                        }
                        continue;
                    }
                    if (oldTag != null && samePart(part, oldTag)) {
                        continue;
                    }
                    if (oldTag != null && WIRE_OPS.containsKey(Integer.valueOf(wireKeyOf(part)))) {
                        removes.add(new int[]{x, y});   // wire ops need a free cell
                    }
                    collect(part, gates, wires);
                }
            }
            for (Long k : old.keySet()) {
                removes.add(new int[]{keyX(k.longValue()), keyY(k.longValue())});
            }
        }

        // ---- emit, in the order a careful player would: clear, gates, wires ----
        for (int[] c : removes) {
            MCDataOutput out = tile.getICStreamOf(2);
            out.writeByte(c[0]);
            out.writeByte(c[1]);
        }
        for (CircuitPart part : gates) {
            MCDataOutput out = tile.getICStreamOf(1);
            out.writeByte(part.id());
            out.writeByte(part.x());
            out.writeByte(part.y());
            part.writeDesc(out);
        }
        // wires last: server-side onAdded() recomputes their connMap and notifies
        // neighbouring gates, which fixes the gate masks case-1 leaves at zero.
        Map<Long, TreeMap<Integer, Integer>> runs = new HashMap<Long, TreeMap<Integer, Integer>>();
        for (Map.Entry<Long, Integer> e : wires.entrySet()) {
            int x = keyX(e.getKey().longValue());
            int y = keyY(e.getKey().longValue());
            long gk = (long)e.getValue().intValue() << 32 | (long)(y & 0xFFFF);
            TreeMap<Integer, Integer> xs = runs.get(Long.valueOf(gk));
            if (xs == null) {
                xs = new TreeMap<Integer, Integer>();
                runs.put(Long.valueOf(gk), xs);
            }
            xs.put(Integer.valueOf(x), e.getValue());
        }
        for (Map.Entry<Long, TreeMap<Integer, Integer>> e : runs.entrySet()) {
            int id = (int)(e.getKey().longValue() >> 32);
            int y = (int)(e.getKey().longValue() & 0xFFFFL);
            OpWire op = WIRE_OPS.get(Integer.valueOf(id));
            if (op == null) {
                continue;
            }
            int start = -1;
            int prev = -2;
            for (Integer xi : e.getValue().keySet()) {
                int x = xi.intValue();
                if (start >= 0 && x == prev + 1) {
                    prev = x;
                    continue;
                }
                if (start >= 0) {
                    writeWireOp(tile, ic, op, start, prev, y);
                }
                start = x;
                prev = x;
            }
            if (start >= 0) {
                writeWireOp(tile, ic, op, start, prev, y);
            }
        }
        tile.flushICStream();

        refreshLocal(ic);
        return resized || descFirst;
    }

    /**
     * Re-places only the given wire parts server-side. Used by Sync tier 3 right
     * after a cable-free whole desc: the desc can never carry a bundled cable, so
     * the server gets its cables back through the very ops the vanilla GUI uses -
     * each placement runs server-side onAdded(), which recomputes the signal (a
     * null signal can never be created) and streams partAdded packets to watchers.
     *
     * Each list element is Object[]{Integer x, Integer y, CircuitPart part}.
     */
    public static void pushWireParts(TileICWorkbench tile, IntegratedCircuit ic, List<Object[]> cables) {
        if (tile == null || ic == null || cables == null || cables.isEmpty()) {
            return;
        }
        ensureWireOps();
        Map<Long, TreeMap<Integer, Integer>> runs = new HashMap<Long, TreeMap<Integer, Integer>>();
        for (Object[] c : cables) {
            CircuitPart part = (CircuitPart)c[2];
            int wk = wireKeyOf(part);
            if (!WIRE_OPS.containsKey(Integer.valueOf(wk))) {
                continue;
            }
            int x = ((Integer)c[0]).intValue();
            int y = ((Integer)c[1]).intValue();
            long gk = (long)wk << 32 | (long)(y & 0xFFFF);
            TreeMap<Integer, Integer> xs = runs.get(Long.valueOf(gk));
            if (xs == null) {
                xs = new TreeMap<Integer, Integer>();
                runs.put(Long.valueOf(gk), xs);
            }
            xs.put(Integer.valueOf(x), Integer.valueOf(wk));
        }
        for (Map.Entry<Long, TreeMap<Integer, Integer>> e : runs.entrySet()) {
            int id = (int)(e.getKey().longValue() >> 32);
            int y = (int)(e.getKey().longValue() & 0xFFFFL);
            OpWire op = WIRE_OPS.get(Integer.valueOf(id));
            if (op == null) {
                continue;
            }
            int start = -1;
            int prev = -2;
            for (Integer xi : e.getValue().keySet()) {
                int x = xi.intValue();
                if (start >= 0 && x == prev + 1) {
                    prev = x;
                    continue;
                }
                if (start >= 0) {
                    writeWireOp(tile, ic, op, start, prev, y);
                }
                start = x;
                prev = x;
            }
            if (start >= 0) {
                writeWireOp(tile, ic, op, start, prev, y);
            }
        }
        tile.flushICStream();
    }

    private static void collect(CircuitPart part, List<CircuitPart> gates, Map<Long, Integer> wires) {
        int wk = wireKeyOf(part);
        if (WIRE_OPS.containsKey(Integer.valueOf(wk))) {
            wires.put(Long.valueOf(key(part.x(), part.y())), Integer.valueOf(wk));
        }
        else {
            gates.add(part);
        }
    }
}

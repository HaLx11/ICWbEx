package icwbe;

import codechicken.lib.data.MCDataOutput;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import net.minecraft.client.Minecraft;
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
 *
 * 0.3.2 notes - the silent half of that same NBT trap: 0.2.8 "fixed" the stub by
 * declaring func_74762_e (getInteger) and switching these call sites to it, so
 * the NoSuchMethodError went away - but IntegratedCircuit.save writes name/sw/sh
 * and every part's id/xpos/ypos as NBT **bytes** (javap: i2b + func_74774_a;
 * func_74771_c on the read side). NBTTagCompound.getInteger demands an Int tag,
 * so on real data it does not throw - it returns **0**. Every diff base therefore
 * collapsed to a single part at (0,0) and every size comparison read
 * "before == 0x0", i.e. "different from the live board". Consequences in the
 * field: every edit took the resize/dangerous branch, sent a blank whole-circuit
 * desc that wiped the server board, and re-placed everything; the server echoes
 * those descs straight back, so the client copy got replaced by the blank board,
 * the server's per-part frames then found nothing on the client (thousands of
 * "client part stream couldnt find part"), and the fallback built a running
 * sequential gate with subID 0 -> "Invalid gate subID: 0" -> hard disconnect
 * (2026-10-01, D:/AI/gtnh文档/ICWbEx-devpack-0.3.0/memory/2026-10-01.md). All
 * three call sites now use func_74771_c (getByte), which is what the data is.
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

    /**
     * 0.3.6 - IDENTITY ONLY. Every cell the diff reports as "changed" is torn down
     * and rebuilt (phase 1 removes it, phase 2 re-creates it), and ProjectRed's own
     * readPartStream fallback turns a state frame that lands on a just-removed part
     * into "Invalid gate subID: 0" (see stageEdit). So the comparison must look at
     * NOTHING but what the player configured.
     *
     * 0.3.5 tried a blacklist of "runtime" keys and it was not enough: a 0.3.5 field
     * log still showed a board compared against ITSELF reporting 72 changed cells
     * (every combinational gate) and 18 others - the parts' own derived fields
     * (state, connMap, schedTime, shape, pmax, freq, ...) drift while the circuit
     * runs, and one of them was still in the comparison. The lists below are
     * therefore an ALLOW list per part id: anything not named there cannot make a
     * cell look edited, including fields nobody has looked at yet.
     *
     * What is left is exactly the persistent configuration. The wiring (connMap),
     * the bundled signals and every live state are recomputed by ProjectRed on both
     * sides (the wire ops call onAdded server-side, refreshLocal client-side).
     * Types matter: the byte keys must be read with func_74771_c (getByte), the int
     * ones with func_74762_e (getInteger) - see the 0.3.2 note above.
     */
    private static final String[] ID_KEYS_MANUAL = new String[]{"on"};              // torch/lever/button
    private static final String[] ID_KEYS_WIRE = new String[]{"colour"};            // wires and cables
    private static final String[] ID_KEYS_GATE = new String[]{"subID", "orient"};   // IO + combinational + array
    private static final String[] ID_KEYS_SEQ =
        new String[]{"subID", "orient", "max", "inc", "dec"};                       // sequential gates
    private static final String[] ID_KEYS_INT = new String[]{"max", "inc", "dec"};

    private static String[] identityKeys(int id) {
        switch (id) {
            case 0:
                return new String[0];
            case 1:
            case 2:
                return ID_KEYS_MANUAL;
            case 3:
            case 4:
            case 5:
                return ID_KEYS_WIRE;
            case 8:
                return ID_KEYS_SEQ;
            default:
                return ID_KEYS_GATE;   // 6 IO, 7 combinational, 9 array
        }
    }

    private static boolean isIntKey(String key) {
        for (int i = 0; i < ID_KEYS_INT.length; ++i) {
            if (ID_KEYS_INT[i].equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Structural keys added by IntegratedCircuit.save around CircuitPart.save's own
     * fields; ignored when comparing a live part with a snapshot entry (see
     * stripRuntime).
     */
    private static final String[] FRAME_KEYS = new String[]{"id", "xpos", "ypos"};

    /** id of the inert "parking" part: a bare alloy wire, plugged to nothing. */
    private static final int INERT_WIRE_ID = 3;

    private static CircuitPart inertWire;

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

    /**
     * The list of identity keys this cell's two versions disagree on, for the log.
     * Empty = same part as far as the sync is concerned.
     */
    private static String differingKeys(CircuitPart part, NBTTagCompound oldTag) {
        try {
            int id = part.id();
            if ((oldTag.func_74771_c("id") & 0xFF) != (id & 0xFF)) {
                return "id";
            }
            NBTTagCompound now = new NBTTagCompound();
            part.save(now);
            String[] keys = identityKeys(id);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < keys.length; ++i) {
                if (!keyEquals(now, oldTag, keys[i])) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(keys[i]);
                }
            }
            return sb.toString();
        }
        catch (Throwable t) {
            return "?";
        }
    }

    private static boolean keyEquals(NBTTagCompound a, NBTTagCompound b, String key) {
        if (isIntKey(key)) {
            // max/inc/dec are TAG_Int (Counter.save) - getInteger is the right reader here.
            return a.func_74762_e(key) == b.func_74762_e(key);
        }
        return (a.func_74771_c(key) & 0xFF) == (b.func_74771_c(key) & 0xFF);
    }

    /** true when the part carries the same persistent configuration as the tag. */
    private static boolean samePart(CircuitPart part, NBTTagCompound oldTag) {
        try {
            int id = part.id();
            if ((oldTag.func_74771_c("id") & 0xFF) != (id & 0xFF)) {
                return false;   // different part type: identity differs, no need to look further
            }
            NBTTagCompound now = new NBTTagCompound();
            part.save(now);
            String[] keys = identityKeys(id);
            for (int i = 0; i < keys.length; ++i) {
                if (!keyEquals(now, oldTag, keys[i])) {
                    return false;
                }
            }
            return true;
        }
        catch (Throwable t) {
            return false;
        }
    }

    /** part id stored in a snapshot entry, or -1. */
    private static int idOf(NBTTagCompound tag) {
        try {
            return tag.func_74771_c("id") & 0xFF;
        }
        catch (Throwable t) {
            return -1;
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
                int x = t.func_74771_c("xpos") & 0xFF;
                int y = t.func_74771_c("ypos") & 0xFF;
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

    /**
     * 0.3.0: snapshot WITHOUT touching Sync's diff base. Sync.stageEdit needs the
     * "before" tag to stay frozen at the pre-edit board while it inspects the
     * already-applied edit, and UndoBuffer.snap would overwrite it via recordLast.
     */
    public static NBTTagCompound snapPure(IntegratedCircuit ic) {
        return snapshot(ic);
    }

    /**
     * 0.3.0: coordinates whose part must be REMOVED before the edit can be staged
     * safely - cells that existed in {@code oTag} but are gone in the live circuit,
     * plus cells whose part changed identity. The two-phase apply (Sync.stageEdit)
     * sends exactly these removals first, waits for the server's removal echoes to
     * converge both sides on the intermediate board, and only then applies the
     * edited board and replays the creations - so there is never a moment where
     * the server streams a state frame for a part the client no longer has (the
     * field-verified trigger of "Invalid gate subID: 0").
     */
    public static List<int[]> planRemovals(NBTTagCompound oTag, IntegratedCircuit icT) {
        List<int[]> out = new ArrayList<int[]>();
        if (oTag == null || icT == null) {
            return out;
        }
        try {
            Map<Long, NBTTagCompound> old = index(oTag);
            Size size = icT.size();
            int logged = 0;
            for (int y = 0; y < size.height(); ++y) {
                for (int x = 0; x < size.width(); ++x) {
                    CircuitPart part = icT.getPart(x, y);
                    NBTTagCompound oldTag = old.remove(Long.valueOf(key(x, y)));
                    if (part == null) {
                        // 0.3.7 - the cell the board USED to have and no longer does.
                        // This case used to fall out of the loop WITHOUT being planned,
                        // so a board that had just been cleared (or a blueprint that
                        // shrank) produced an EMPTY removal list: stageEdit then took
                        // its "pure addition / nothing changed" branch, the server was
                        // never told to drop those parts, and the two sides stayed
                        // divergent - the server kept streaming state frames for parts
                        // our copy no longer had, which is the one thing PR's
                        // readPartStream fallback cannot survive. That is the 10:15
                        // field log: board emptied, removals=0, and the next frame for a
                        // timer (id 8, key>10) disconnected the client.
                        if (oldTag != null) {
                            out.add(new int[]{x, y});
                        }
                        continue;
                    }
                    if (oldTag == null) {
                        continue;
                    }
                    if (!samePart(part, oldTag)) {
                        out.add(new int[]{x, y});
                        // 0.3.6 diagnostics: if a cell is torn down, say exactly why.
                        // The identity allow list is short, so this can only name a
                        // field that really is configuration - if it ever names
                        // something that plainly is not, that is the bug.
                        if (logged < 6) {
                            ++logged;
                            System.out.println("[ICWbEx] diff: cell (" + x + "," + y + ") id "
                                + part.id() + " differs on [" + differingKeys(part, oldTag) + "]");
                        }
                    }
                }
            }
            for (Long k : old.keySet()) {
                out.add(new int[]{keyX(k.longValue()), keyY(k.longValue())});
            }
        }
        catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 0.3.0: emit ONLY case-2 removal ops (no creations, no desc). The vanilla
     * server answers every removal with its own partRemoved echo, which is what
     * walks the client copy down to the same intermediate board.
     */
    public static void sendRemovals(TileICWorkbench tile, List<int[]> removals) {
        if (tile == null || removals == null || removals.isEmpty()) {
            return;
        }
        for (int[] c : removals) {
            MCDataOutput out = tile.getICStreamOf(2);
            out.writeByte(c[0]);
            out.writeByte(c[1]);
        }
        tile.flushICStream();
    }

    /** true when the board size of the snapshot differs from the live circuit. */
    public static boolean sizeDiffers(NBTTagCompound before, IntegratedCircuit ic) {
        if (before == null || ic == null) {
            return false;
        }
        try {
            Size size = ic.size();
            return (before.func_74771_c("sw") & 0xFF) != size.width()
                || (before.func_74771_c("sh") & 0xFF) != size.height();
        }
        catch (Throwable t) {
            return false;
        }
    }

    /**
     * 0.3.5: every cell of a snapshot board - used when a resize throws the whole
     * board away (the workbench may only change size through a whole desc, so some
     * stateful part has to be parked for that too).
     */
    public static List<int[]> allCells(NBTTagCompound before) {
        List<int[]> out = new ArrayList<int[]>();
        if (before == null) {
            return out;
        }
        try {
            Map<Long, NBTTagCompound> old = index(before);
            for (Long k : old.keySet()) {
                out.add(new int[]{keyX(k.longValue()), keyY(k.longValue())});
            }
        }
        catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 0.3.5: the subset of {@code candidates} whose part at that cell can emit a
     * state frame with a key above 10.
     *
     * Why this list exists: ProjectRed streams a part's live state as
     * [partId][x][y][key][payload...], and both "removing a part" and "replacing it
     * with another part type" make the client's copy of that cell disappear for
     * exactly one tick - the tick whose frames are already in flight. readPartStream
     * then finds no part (or a part of another id), logs "client part stream couldnt
     * find part" and falls back to CircuitPart.createPart(id) + read(). That fallback
     * is harmless for wires and simple parts, but for a COMPLEX gate a key above 10
     * runs assertLogic() on a part that was never given a subID -> Sequential/IO
     * gate logic create(0) -> IllegalArgumentException: Invalid gate subID: 0 ->
     * hard disconnect (field evidence: DIM180, 2026-10-01, both the 32x32 swarm board
     * and the 16x16 test boards).
     *
     * Only gates with live state emit those keys: Counter/Sequencer/Timer/StateCell/
     * Synchronizer/SRLatch/ToggleLatch (keys 11-14) and the analog/bundled IO (key
     * 12). Wires, cables, torches, levers, buttons and plain combinational gates stay
     * at keys 1-5, and a throwaway recreate of those never throws - which is why
     * they may be removed directly.
     *
     * We are conservative here: every gate part (id >= 6) counts.
     */
    public static List<int[]> dangerousCells(NBTTagCompound before, List<int[]> candidates) {
        List<int[]> out = new ArrayList<int[]>();
        if (before == null || candidates == null) {
            return out;
        }
        try {
            Map<Long, NBTTagCompound> old = index(before);
            for (int[] c : candidates) {
                NBTTagCompound tag = old.get(Long.valueOf(key(c[0], c[1])));
                if (tag != null && idOf(tag) >= 6) {
                    out.add(c);
                }
            }
        }
        catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 0.3.5: replace the part of every given cell with a bare alloy wire of our own.
     *
     * This is step 1 of a safe removal. The packet is the ordinary case-1 "create
     * part here" record, which the server applies with setPart_do + readDesc - and,
     * crucially, WITHOUT echoing anything back to us (sendPartAdded is only called by
     * setPart, not by the read path). So while the server's cell becomes a dead wire,
     * our own copy keeps the original part and keeps absorbing the frames the server
     * emits for the old part during that very tick.
     *
     * An alloy wire with connMap 0 is inert: nothing connects to it, its signal never
     * changes, so it never streams a frame. One or two ticks later the cell can be
     * removed with the ordinary case-2 op and no state frame can be in flight for it
     * any more - the client copy is allowed to go away, which is the whole point.
     */
    public static void sendDegrade(TileICWorkbench tile, List<int[]> cells) {
        if (tile == null || cells == null || cells.isEmpty()) {
            return;
        }
        CircuitPart wire = inertWire();
        if (wire == null) {
            return;
        }
        for (int[] c : cells) {
            MCDataOutput out = tile.getICStreamOf(1);
            out.writeByte(wire.id());
            out.writeByte(c[0]);
            out.writeByte(c[1]);
            wire.writeDesc(out);
        }
        tile.flushICStream();
    }

    private static CircuitPart inertWire() {
        if (inertWire == null) {
            try {
                // id 3 == AlloyWireICPart (CircuitPartDefs order); its whole desc is
                // a single connMap byte, and a fresh instance's connMap is 0.
                inertWire = CircuitPart.createPart(INERT_WIRE_ID);
            }
            catch (Throwable t) {
                System.err.println("[ICWbEx] could not build the parking wire:");
                t.printStackTrace();
            }
        }
        return inertWire;
    }

    /**
     * 0.3.6: re-create, LOCALLY ONLY, every part the committed board has at a cell
     * the live circuit no longer holds (or holds with another part id).
     *
     * Phase 1's removals are answered by the server with partRemoved echoes that trim
     * our copy, and a late echo can land after phase 2 has already re-applied the
     * target locally. The creations we send are case-1 ops, which the server never
     * echoes, so nothing would put those parts back and the client would be left
     * missing exactly the parts the server still has - the one state ProjectRed's
     * readPartStream fallback cannot survive (it rebuilds them with subID 0 ->
     * "Invalid gate subID: 0" as soon as a counter/timer next changes state, which can
     * be seconds later - the 09:57 0.3.5 field log). No packet is sent: the server
     * already holds the board, this only re-aligns our own copy.
     */
    public static int repairLocal(IntegratedCircuit ic, NBTTagCompound board) {
        if (ic == null || board == null) {
            return 0;
        }
        int fixed = 0;
        try {
            NBTTagList list = board.func_150295_c("parts", 10);
            if (list == null) {
                return 0;
            }
            int n = list.func_74745_c();
            for (int i = 0; i < n; ++i) {
                NBTTagCompound t = list.func_150305_b(i);
                if (t == null) {
                    continue;
                }
                int x = t.func_74771_c("xpos") & 0xFF;
                int y = t.func_74771_c("ypos") & 0xFF;
                int id = t.func_74771_c("id") & 0xFF;
                CircuitPart have = ic.getPart(x, y);
                if (have != null && (have.id() & 0xFF) == id) {
                    continue;
                }
                CircuitPart part = CircuitPart.createPart(id);
                if (part == null) {
                    continue;
                }
                ic.setPart(x, y, part);
                part.load(t);
                ++fixed;
            }
        }
        catch (Throwable t) {
            System.err.println("[ICWbEx] local repair failed:");
            t.printStackTrace();
        }
        return fixed;
    }

    // ---- 0.3.7: blueprint safety net ----------------------------------------
    //
    // The blueprint panel's "save" writes straight into blueprints/<name>.icbp, so
    // whatever board the workbench happens to hold at that moment replaces the good
    // blueprint forever. That is exactly how the 32x32 BEC swarm board was lost on
    // 2026-10-01: the client had just been emptied by a sync bug, and the next save
    // stored that empty board under the old name (blueprints/BEC蜂群选择器.icbp went
    // from 371 parts to 0). One plain file copy per game session - taken the first
    // time an ICWbEx screen draws, i.e. before the player can overwrite anything -
    // makes that costless: <game>/icwbe_blueprint_backups/<timestamp>/<name>.icbp,
    // newest 20 kept.
    private static boolean backupTried;

    public static void backupBlueprintsOnce() {
        if (backupTried) {
            return;
        }
        backupTried = true;
        try {
            Minecraft mc = Minecraft.func_71410_x();
            if (mc == null || mc.field_71412_D == null) {
                return;
            }
            File live = new File(mc.field_71412_D, "blueprints");
            if (!live.isDirectory()) {
                return;
            }
            File[] files = live.listFiles();
            if (files == null) {
                return;
            }
            int n = 0;
            for (int i = 0; i < files.length; ++i) {
                if (isBlueprint(files[i])) {
                    ++n;
                }
            }
            if (n == 0) {
                return;
            }
            File root = new File(mc.field_71412_D, "icwbe_blueprint_backups");
            File dir = new File(root, new SimpleDateFormat("yyyy-MM-dd_HHmmss").format(new Date()));
            if (!dir.mkdirs()) {
                return;
            }
            for (int i = 0; i < files.length; ++i) {
                if (isBlueprint(files[i])) {
                    copyFile(files[i], new File(dir, files[i].getName()));
                }
            }
            pruneBackups(root, 20);
            System.out.println("[ICWbEx] blueprint backup: " + n + " file(s) -> " + dir.getPath());
        }
        catch (Throwable t) {
            // never let housekeeping disturb the game
            System.err.println("[ICWbEx] blueprint backup skipped: " + t);
        }
    }

    private static boolean isBlueprint(File f) {
        return f != null && f.isFile() && f.getName().toLowerCase().endsWith(".icbp");
    }

    private static void copyFile(File from, File to) {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(from);
            out = new FileOutputStream(to);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
        }
        catch (Throwable ignored) {
        }
        finally {
            if (in != null) {
                try {
                    in.close();
                }
                catch (Throwable ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                }
                catch (Throwable ignored) {
                }
            }
        }
    }

    /** keeps the newest {@code keep} timestamp folders (their names sort by time). */
    private static void pruneBackups(File root, int keep) {
        File[] dirs = root.listFiles();
        if (dirs == null || dirs.length <= keep) {
            return;
        }
        Arrays.sort(dirs);
        for (int i = 0; i < dirs.length - keep; ++i) {
            File[] inner = dirs[i].listFiles();
            if (inner != null) {
                for (int j = 0; j < inner.length; ++j) {
                    inner[j].delete();
                }
            }
            dirs[i].delete();
        }
    }

    /** true when the circuit contains at least one bundled cable (the only part whose
     *  desc cannot be trusted on an unpatched server). */    public static boolean hasBundledCable(IntegratedCircuit ic) {
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
        return push(tile, ic, before, descFirst, true);
    }

    /**
     * @param emitRemoves 0.3.6 - when false the planned removals are NOT emitted.
     *        Phase 2 uses that: phase 1 has already emptied every cell whose part
     *        must go, and a removal op the server still applies answers with a
     *        partRemoved echo that trims OUR copy - while the matching creation
     *        (case-1 op) is never echoed back, so the client would lose that part
     *        for good and the server's next state frame for it would hit the
     *        readPartStream fallback ("Invalid gate subID: 0" one timer tick
     *        later - the 09:57 0.3.5 field log). A wire op overwrites the cell
     *        anyway (OpWire.readOp -> IntegratedCircuit.setPart), and a gate is
     *        created by setPart_do, so nothing needs the cell emptied.
     */
    public static boolean push(TileICWorkbench tile, IntegratedCircuit ic, NBTTagCompound before,
            boolean descFirst, boolean emitRemoves) {
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

        int ow = before == null ? w : (before.func_74771_c("sw") & 0xFF);
        int oh = before == null ? h : (before.func_74771_c("sh") & 0xFF);
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
        if (emitRemoves) {
            for (int[] c : removes) {
                MCDataOutput out = tile.getICStreamOf(2);
                out.writeByte(c[0]);
                out.writeByte(c[1]);
            }
        }
        else if (!removes.isEmpty()) {
            System.out.println("[ICWbEx] phase2: " + removes.size()
                + " cell(s) left for phase 1 to have emptied (no removals re-sent)");
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

/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  cpw.mods.fml.relauncher.Side
 *  cpw.mods.fml.relauncher.SideOnly
 *  mrtjp.core.gui.MCButtonNode
 *  mrtjp.core.gui.TNode
 *  mrtjp.core.vec.Point
 *  mrtjp.core.vec.Size
 *  mrtjp.projectred.fabrication.CircuitOp
 *  mrtjp.projectred.fabrication.GuiICWorkbench
 *  mrtjp.projectred.fabrication.IntegratedCircuit
 *  mrtjp.projectred.fabrication.PrefboardNode
 *  mrtjp.projectred.fabrication.TileICWorkbench
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.FontRenderer
 *  net.minecraft.client.gui.GuiScreen
 *  org.lwjgl.input.Keyboard
 *  org.lwjgl.input.Mouse
 *  scala.Function0
 */
package icwbe;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import icwbe.Blueprints;
import icwbe.Clipboard;
import icwbe.GuiBlueprint;
import icwbe.Lang;
import icwbe.ScalaH;
import icwbe.Sync;
import icwbe.Theme;
import icwbe.UndoBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import mrtjp.core.gui.MCButtonNode;
import mrtjp.core.gui.TNode;
import mrtjp.core.vec.Point;
import mrtjp.core.vec.Size;
import mrtjp.projectred.fabrication.BundledCableICPart;
import mrtjp.projectred.fabrication.CircuitOp;
import mrtjp.projectred.fabrication.CircuitOpErase;
import mrtjp.projectred.fabrication.CircuitPart;
import mrtjp.projectred.fabrication.GuiICWorkbench;
import mrtjp.projectred.fabrication.IntegratedCircuit;
import mrtjp.projectred.fabrication.NewICNode;
import mrtjp.projectred.fabrication.PrefboardNode;
import mrtjp.projectred.fabrication.TileICWorkbench;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.StatCollector;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import scala.Function0;

@SideOnly(value=Side.CLIENT)
public class GuiICWbEx
extends GuiICWorkbench {
    private static final int BTN_X = 272;
    private static final int BTN_Y = 26;
    private static final int BTN_W = 44;
    private static final int BTN_H = 12;
    private static final int HINT_X = 262;
    private static final int HINT_Y = 44;
    private static final int HINT_W = 62;
    private static final int HINT_MAX_LINES = 7;
    private static final int LINE_H = 9;
    /**
     * 0.4.7: the header strip is exactly the area above the board viewport, which the
     * vanilla puts at y=18 ({@code ClipNode(7,18,252x197)}), so two 9px lines fit and
     * nothing more. They start at y=1 and end at y=18 - the board's own top edge - so
     * the strip can never overlap it. (Drawing order forces this too: {@code drawBack}
     * runs before the child nodes, so anything below y=18 would be painted over.)
     */
    private static final int STATUS_Y = 1;
    /**
     * 0.4.7: how long a status message stays up, in ticks (20 per second). It was a flat
     * 110 (5.5s), which is not enough to read the longer messages this mod produces -
     * and those are exactly the ones worth reading, because they explain what the paste
     * just did. The base is 10s and the bonus adds 0.2s per character up to 25s more, so
     * a long notice gets about 20-30s and a one-word one does not linger.
     */
    private static final int STATUS_TTL_BASE = 200;
    private static final int STATUS_TTL_BONUS_CAP = 500;
    /** at most this many extra fragments after the primary message. */
    private static final int STATUS_MAX_EXTRAS = 2;
    /** the vanilla machine title, which the header strip has to start after. */
    private static final String VANILLA_TITLE_KEY = "gui.projectred.integration.icblock|0.title";
    private static final int TXT_HINT = -11908526;
    private static final int TXT_STATUS = -16097672;
    private static final int TXT_ERROR = -6676705;
    private static final int PER_CELL_DRAW_LIMIT = 600;
    /**
     * 0.4.7: the controls page. A column narrower than this wraps Chinese into less
     * than a dozen characters per line, which reads worse than the taller page it
     * was meant to avoid - so the page would rather shrink its line height than add
     * another column.
     */
    private static final int HELP_MIN_COL_W = 96;
    /** the hairline under a section heading, a half-opaque {@link Theme#ACCENT}. */
    private static final int HELP_RULE = 0x6600CAF2;
    /** 0.4.7: the help page's scrollbar, drawn only when the page is taller than the panel. */
    private static final int HELP_SCROLL_TRACK = 0x30FFFFFF;
    private static final int HELP_SCROLL_THUMB = 0x90A0A8B4;
    /** lines moved per wheel notch. */
    private static final int HELP_SCROLL_STEP = 3;
    /**
     * 0.4.5: the vanilla eraser's "these cells are going away" preview. Deliberately a
     * red danger tint, so it cannot be mistaken for the cyan copy/selection marquee.
     */
    private static final int ERASE_FILL = 0x50FF3B30;
    private static final int ERASE_BORDER = 0xFFFF5555;
    private final TileICWorkbench tile;
    private final UndoBuffer undo = new UndoBuffer();
    private final Set<Long> selected = new LinkedHashSet<Long>();
    private Clipboard clip;
    private boolean pasteMode;
    /**
     * 0.4.7: non-null while pasting a clip that carries an IO - the origins that keep
     * every IO on the board's ring. The marquee is snapped to the nearest of these
     * (see {@link #snappedOrigin}) instead of following the pointer freely.
     */
    private int[] ioOrigins;
    private int dragX0;
    private int dragY0;
    private int dragX1;
    private int dragY1;
    private boolean dragging;
    private int mouseX;
    private int mouseY;
    private int tick;
    private boolean mmbWasDown;
    private String status = "";
    private boolean statusIsError;
    private int statusTtl;
    private boolean helpVisible;
    /**
     * 0.4.7: how far the controls page is scrolled, in lines. The page is built to fit
     * when it can, but a narrow GUI can leave it taller than the window - and the old
     * fallback drew every line anyway, so the text ran off the bottom of the panel.
     */
    private int helpScroll;
    private MCButtonNode bpBtn;
    /**
     * 0.4.3: a marquee that came from the VANILLA eraser tool, which has to end in a
     * safe delete (see eraserMode / mouseReleased_Impl).
     */
    private boolean eraseDrag;
    /**
     * 0.4.6: this marquee was abandoned because the board moved underneath it.
     *
     * <p>The box is recorded in root/screen space, so it only means anything while the
     * board stays where it was. Shift + left drag is the VANILLA board pan ({@code
     * PanNode.dragTestFunction} = left shift, driven from its {@code frameUpdate}; our
     * {@code intercepting()} only switches off the prefboard, never PanNode, so the pan
     * really happens) - and dragging then released a marquee over cells that had slid
     * away, deleting the wrong parts. The same goes for any pan or zoom that lands
     * between press and release.
     */
    private boolean gestureAborted;
    private int gesturePanX;
    private int gesturePanY;
    private double gestureCellPx;
    /**
     * 0.3.9: the vanilla "new IC" dialogs we have already re-routed (see addChild).
     * Identity-based: the dialog is created fresh every time it is opened.
     */
    private final Set<NewICNode> newICPatch = java.util.Collections
        .newSetFromMap(new java.util.IdentityHashMap<NewICNode, Boolean>());

    public GuiICWbEx(TileICWorkbench tileICWorkbench) {
        super(tileICWorkbench);
        this.tile = tileICWorkbench;
    }

    public TileICWorkbench tile() {
        return this.tile;
    }

    public void onCircuitReloaded() {
        this.selected.clear();
        this.clip = null;
        this.pasteMode = false;
        this.dragging = false;
        IntegratedCircuit integratedCircuit = this.ic();
        if (integratedCircuit != null) {
            this.undo.reset(integratedCircuit);
        }
    }

    private IntegratedCircuit ic() {
        return this.tile == null ? null : this.tile.circuit();
    }

    public void onAddedToParent_Impl() {
        IntegratedCircuit integratedCircuit;
        Lang.refresh();
        super.onAddedToParent_Impl();
        if (this.bpBtn == null) {
            this.bpBtn = new MCButtonNode();
            this.bpBtn.text_$eq(Lang.t("btn.bp"));
            this.bpBtn.size_$eq(new Size(44, 12));
            this.bpBtn.position_$eq(new Point(272, 26));
            this.bpBtn.clickDelegate_$eq((Function0)new ScalaH.Act(){

                @Override
                public void run() {
                    GuiICWbEx.this.openBlueprints();
                }
            });
            this.addChild((TNode)this.bpBtn);
        }
        if ((integratedCircuit = this.ic()) != null) {
            this.undo.reset(integratedCircuit);
        }
    }

    private void openBlueprints() {
        Minecraft.func_71410_x().func_147108_a((GuiScreen)new GuiBlueprint(this.tile, this));
    }

    /**
     * 0.3.9: 原版的"新建 IC"对话框（NewICNode）一旦被加到界面里，就把它的
     * {@code completionDelegate} 换成我们的安全实现。
     *
     * <h3>为什么必须换</h3>
     * 原版的完成动作（反汇编 {@code GuiICWorkbench$$anonfun$onAddedToParent_Impl$8$$anonfun$apply$mcV$sp$1}）是：
     * <pre>
     *   IntegratedCircuit ic = new IntegratedCircuit();
     *   ic.name = nic.getName();
     *   ic.size = nic.selectedBoardSize().$times(16);
     *   tile.sendNewICToServer(ic);      // ← 一张原始整板 desc
     * </pre>
     * 而整板 desc 会被服务器立刻回显，客户端 {@code readDesc} 会<b>整块替换本地板</b>——
     * 如果这一刻服务器还在为旧板发逐元件状态帧，那些帧就会落到空格子上，PR 兜底
     * {@code createPart(id)} 建出 subID=0 的零件，序列/计时门的下一个 key&gt;10 帧直接
     * {@code IllegalArgumentException: Invalid gate subID: 0} 断连（2026-10-01 11:51 现场：
     * 用户在 371 件板上开了这个对话框，崩在 (1,1)/(1,2)/(2,5)… 那一圈计时/IO 门上）。
     * 另外服务器只在 {@code hasBP} 为真时才接受整板 desc，所以在别的时机点"确定"会
     * 静默丢弃、看起来"不生效"。
     *
     * <p>这里把完成动作换掉之后，重置走的是与"蓝图载入"完全相同的两阶段路径：
     * 先停泊还会发帧的门 → 发空白 desc（带目标名字与尺寸）→ 收敛后再应用目标板。
     * 尺寸与名字都按用户在对话框里选的那样，行为与原版一致，只是不再裸发 desc。
     */
    @Override
    public void addChild(TNode tNode) {
        super.addChild(tNode);
        if (tNode instanceof NewICNode) {
            this.rerouteNewIC((NewICNode)tNode);
        }
    }

    private void rerouteNewIC(final NewICNode newICNode) {
        if (!this.newICPatch.add(newICNode)) {
            return;
        }
        newICNode.completionDelegate_$eq((Function0)new ScalaH.Act() {
            @Override
            public void run() {
                GuiICWbEx.this.newBoardSafely(newICNode);
            }
        });
        System.out.println("[ICWbEx] vanilla new-IC dialog re-routed through the two-phase path");
    }

    /**
     * 原版"新建 IC"的安全版：把选中的尺寸/名字做成一张空板，应用到本地后交给
     * {@link Sync} 走两阶段提交（停泊 → 空白 desc → 重建）。服务器那边该有的
     * {@code hasBP} 前提与原版一致（没有插蓝图时原版连对话框都不会出现）。
     */
    private void newBoardSafely(NewICNode newICNode) {
        IntegratedCircuit integratedCircuit = this.ic();
        if (integratedCircuit == null || this.tile == null) {
            return;
        }
        if (!this.tile.hasBP()) {
            // 与原版同一个前提：没有 IC 蓝图/板时服务器会丢弃整板 desc，这里直接讲清楚
            this.say(Lang.t("st.newic_nobp"), true);
            return;
        }
        try {
            String string = newICNode.getName();
            if (string == null) {
                string = "";
            }
            Size size = newICNode.selectedBoardSize().$times(16);
            IntegratedCircuit integratedCircuit2 = new IntegratedCircuit();
            integratedCircuit2.name_$eq(string);
            integratedCircuit2.size_$eq(size);
            NBTTagCompound nBTTagCompound = new NBTTagCompound();
            integratedCircuit2.save(nBTTagCompound);
            // 先落到本地，再让 Sync 接管（它认得"尺寸变了"这种情况：会先停泊、再发空白 desc）
            integratedCircuit.load(nBTTagCompound);
            integratedCircuit.refreshErrors();
            Sync.toServer(this.tile, integratedCircuit);
            this.onCircuitReloaded();
            this.say(Lang.t("st.newic", string, String.valueOf(size.width()), String.valueOf(size.height())));
            System.out.println("[ICWbEx] new board \"" + string + "\" " + size.width() + "x" + size.height()
                + " (two-phase path, no raw desc)");
        }
        catch (Throwable throwable) {
            this.say(String.valueOf(throwable), true);
            throwable.printStackTrace();
        }
    }

    private int panOriginX() {
        TNode tNode;
        int n = 0;
        TNode tNode2 = tNode = this.pref() == null ? null : this.pref().parent();
        while (tNode != null && !tNode.isRoot()) {
            n += tNode.position().x();
            tNode = tNode.parent();
        }
        return n;
    }

    private int panOriginY() {
        TNode tNode;
        int n = 0;
        TNode tNode2 = tNode = this.pref() == null ? null : this.pref().parent();
        while (tNode != null && !tNode.isRoot()) {
            n += tNode.position().y();
            tNode = tNode.parent();
        }
        return n;
    }

    private double cellPx() {
        PrefboardNode prefboardNode = this.pref();
        return prefboardNode == null ? 0.0 : (double)prefboardNode.sizeMult() * prefboardNode.scale();
    }

    private int gridXAt(int n) {
        double d = this.cellPx();
        PrefboardNode prefboardNode = this.pref();
        if (prefboardNode == null || d <= 0.0) {
            return 0;
        }
        return (int)Math.floor((double)(n - this.panOriginX() - prefboardNode.position().x()) / d);
    }

    private int gridYAt(int n) {
        double d = this.cellPx();
        PrefboardNode prefboardNode = this.pref();
        if (prefboardNode == null || d <= 0.0) {
            return 0;
        }
        return (int)Math.floor((double)(n - this.panOriginY() - prefboardNode.position().y()) / d);
    }

    private int rootXOfCell(int n) {
        PrefboardNode prefboardNode = this.pref();
        if (prefboardNode == null) {
            return 0;
        }
        return (int)Math.round((double)(this.panOriginX() + prefboardNode.position().x()) + (double)n * this.cellPx());
    }

    private int rootYOfCell(int n) {
        PrefboardNode prefboardNode = this.pref();
        if (prefboardNode == null) {
            return 0;
        }
        return (int)Math.round((double)(this.panOriginY() + prefboardNode.position().y()) + (double)n * this.cellPx());
    }

    private int rootX(Point point) {
        return point.x() - this.position().x();
    }

    private int rootY(Point point) {
        return point.y() - this.position().y();
    }

    private static boolean ctrlHeld() {
        return Keyboard.isKeyDown((int)29) || Keyboard.isKeyDown((int)157);
    }

    private static boolean shiftHeld() {
        return Keyboard.isKeyDown((int)42) || Keyboard.isKeyDown((int)54);
    }

    private boolean intercepting() {
        // 0.4.6: while shift is held we must NOT take the mouse. Shift + left drag is the
        // VANILLA board pan (PanNode.dragTestFunction = left shift; only the prefboard is
        // switched off by applyIntercept, never PanNode), so keeping the eraser in charge
        // there is what produced a marquee over a board that had already slid away.
        return GuiICWbEx.ctrlHeld() || this.pasteMode || this.helpVisible
            || (this.eraserMode() && !GuiICWbEx.shiftHeld());
    }

    /**
     * 0.4.3: true while the player has the VANILLA eraser selected in the toolbar.
     *
     * <h3>Why we take it over</h3>
     * The vanilla tools apply their op straight to the local board and put the op packet
     * on the wire, with none of the staging ICWbEx uses. Erasing therefore re-opens the
     * exact window the two-phase commit exists to close: our copy already lost the part
     * while the server still has it (or vice versa) and keeps streaming that part's state
     * frames. For a wire that is harmless, but erase a part next to a sequential gate and
     * PR's readPartStream fallback re-creates the gate with subID 0 - the next key>10
     * frame is an "Invalid gate subID: 0" disconnect (2026-10-01 12:44 field case: a
     * vanilla erase 28s after loading a board killed the client).
     *
     * <p>So while the eraser is selected we take the mouse away from the prefboard
     * ({@link #intercepting}) and treat the gesture as a selection: click or drag a box,
     * release, and the cells are deleted through {@link #deleteKeys} - the same staged
     * path the selection keys use (parking included), which cannot leave the two sides
     * disagreeing. Nothing else about the eraser changes: pick it as usual, and one click
     * erases one cell exactly as before.
     */
    private boolean eraserMode() {
        PrefboardNode prefboardNode = this.pref();
        if (prefboardNode == null) {
            return false;
        }
        try {
            return prefboardNode.currentOp() instanceof CircuitOpErase;
        }
        catch (Throwable t) {
            return false;
        }
    }

    public void frameUpdate_Impl(Point point, float f) {
        super.frameUpdate_Impl(point, f);
        ++this.tick;
        Sync.pump();
        if (this.statusTtl > 0) {
            --this.statusTtl;
        }
        this.mouseX = this.rootX(point);
        this.mouseY = this.rootY(point);
        this.applyIntercept();
        this.pollMiddleClick(point);
        IntegratedCircuit integratedCircuit = this.ic();
        if (integratedCircuit != null && !this.dragging) {
            // 0.4.4: the vanilla tools write through the server, so the board can change
            // without one of our commits running. Keep the diff base level with it.
            Sync.followLocalBoard(this.tile, integratedCircuit);
            if (Sync.echoHold() || Sync.busy()) {
                // An echo or a staged two-phase edit may still rebuild the client
                // board; do not record that transient state into the undo buffer.
            }
            else if (Sync.consumeRebase()) {
                // Echo settled: re-baseline the undo buffer on the converged board.
                Blueprints.ensureCaches(integratedCircuit);
                this.undo.reset(integratedCircuit);
            }
            else if (this.tick % 8 == 0) {
                Blueprints.ensureCaches(integratedCircuit);
                this.undo.commitIfChanged(integratedCircuit);
            }
        }
    }

    private void applyIntercept() {
        boolean bl;
        PrefboardNode prefboardNode = this.pref();
        if (prefboardNode == null) {
            return;
        }
        boolean bl2 = bl = !this.intercepting();
        if (prefboardNode.userInteractionEnabled() != bl) {
            prefboardNode.userInteractionEnabled_$eq(bl);
        }
    }

    private void pollMiddleClick(Point point) {
        boolean bl = Mouse.isButtonDown((int)2);
        boolean bl2 = bl && !this.mmbWasDown;
        this.mmbWasDown = bl;
        if (!bl2 || this.intercepting()) {
            return;
        }
        PrefboardNode prefboardNode = this.pref();
        if (prefboardNode == null || prefboardNode.parent() == null) {
            return;
        }
        Point point2 = prefboardNode.parent().convertPointFromScreen(new Point(point.x(), point.y()));
        if (!prefboardNode.rayTest(point2)) {
            return;
        }
        prefboardNode.doPickOp();
        CircuitOp circuitOp = prefboardNode.currentOp();
        if (circuitOp == null) {
            this.say(Lang.t("st.pick_none"), true);
        } else {
            this.say(Lang.t("st.picked", GuiICWbEx.opName(circuitOp)));
        }
    }

    private static String opName(CircuitOp circuitOp) {
        if (circuitOp == null) {
            return "";
        }
        try {
            String string = circuitOp.getOpName();
            if (string != null && string.length() > 0) {
                return string;
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        return "#" + circuitOp.id();
    }

    public boolean mouseClicked_Impl(Point point, int n, boolean bl) {
        int n2 = this.rootX(point);
        int n3 = this.rootY(point);
        if (this.helpVisible) {
            this.helpVisible = false;
            return true;
        }
        if (this.pasteMode) {
            if (n == 0) {
                // 0.4.7: same snapped origin the preview drew, so what you saw is
                // where it goes (and an IO paste can never land off the border).
                int[] origin = this.snappedOrigin(this.gridXAt(n2), this.gridYAt(n3));
                this.stampAt(origin[0], origin[1]);
            } else {
                this.pasteMode = false;
                this.say(Lang.t("st.paste_cancel"));
            }
            return true;
        }
        if (GuiICWbEx.ctrlHeld()) {
            if (n == 0) {
                this.dragX0 = this.dragX1 = n2;
                this.dragY0 = this.dragY1 = n3;
                this.dragging = true;
                this.beginGesture();
            } else {
                this.selected.clear();
            }
            return true;
        }
        if (this.eraserMode()) {
            // 0.4.3: the vanilla eraser -> marquee delete. Left drag (or a plain click,
            // which is a 1-cell marquee) collects cells; release deletes them safely.
            // 0.4.5: the cells that are about to go are highlighted while you drag, and
            // the hovered one is outlined before you press (drawSelectionOverlay).
            // 0.4.6: NOT while shift is held - that is the vanilla board pan (see
            // gestureAborted / beginGesture), and starting an erase there is how the
            // marquee ended up deleting cells the board had already slid past.
            if (n == 0 && !GuiICWbEx.shiftHeld()) {
                this.dragX0 = this.dragX1 = n2;
                this.dragY0 = this.dragY1 = n3;
                this.dragging = true;
                this.eraseDrag = true;
                this.beginGesture();
                return true;
            }
            return false;
        }
        return false;
    }

    /**
     * 0.4.6: remember where the board was when a screen-space marquee started, so the
     * release can tell whether the box still covers the cells the player drew it over.
     */
    private void beginGesture() {
        this.gestureAborted = false;
        PrefboardNode prefboardNode = this.pref();
        if (prefboardNode == null) {
            this.gesturePanX = 0;
            this.gesturePanY = 0;
            this.gestureCellPx = 0.0;
            return;
        }
        this.gesturePanX = prefboardNode.position().x();
        this.gesturePanY = prefboardNode.position().y();
        this.gestureCellPx = this.cellPx();
    }

    /** true when the board slid or changed zoom since {@link #beginGesture}. */
    private boolean boardMovedSinceGesture() {
        PrefboardNode prefboardNode = this.pref();
        if (prefboardNode == null) {
            return true;
        }
        return prefboardNode.position().x() != this.gesturePanX
            || prefboardNode.position().y() != this.gesturePanY
            || Math.abs(this.cellPx() - this.gestureCellPx) > 0.0001;
    }

    public boolean mouseDragged_Impl(Point point, int n, long l, boolean bl) {
        if (this.dragging) {
            if (!this.gestureAborted) {
                if (this.eraseDrag && GuiICWbEx.shiftHeld()) {
                    // 0.4.6: shift means "pan the board", not "delete a box" - the vanilla
                    // pan took this gesture over, so drop ours instead of deleting from it.
                    this.gestureAborted = true;
                }
                else if (this.boardMovedSinceGesture()) {
                    // panned (shift drag / scrollbar) or zoomed mid-marquee: the box no
                    // longer covers what was drawn over, so it is not safe to act on.
                    this.gestureAborted = true;
                }
            }
            if (this.gestureAborted) {
                return false;   // hand drag handling back to vanilla
            }
            this.dragX1 = this.rootX(point);
            this.dragY1 = this.rootY(point);
            return true;
        }
        return super.mouseDragged_Impl(point, n, l, bl);
    }

    /**
     * 0.4.5: the mouse wheel keeps working while ICWbEx has taken the mouse ({@code
     * intercepting}) - picking the eraser used to swallow it, so a board could not be
     * zoomed while erasing, which is exactly when you want to zoom.
     *
     * <p>Vanilla zooms by {@code PrefboardNode.mouseScrolled_Impl}, which needs the
     * cursor converted through two coordinate spaces; here the zoom is driven through
     * the node's own public {@code incScale}/{@code decScale} instead (same 0.2 step and
     * the same 0.5-3.0 clamp as vanilla), anchored on the board frame - no conversion to
     * get wrong. The event is only ours when the prefboard was switched off; otherwise
     * the prefboard already handled it and this method is never reached.
     */
    public boolean mouseScrolled_Impl(Point point, int n, boolean bl) {
        // 0.4.7: while the controls page is up the wheel pages it. Checked before
        // anything else, because the page is drawn over the whole window and scrolling
        // the board underneath it would do nothing visible.
        if (this.helpVisible && n != 0) {
            this.helpScroll += n > 0 ? -HELP_SCROLL_STEP : HELP_SCROLL_STEP;
            return true;
        }
        if (!this.intercepting() || this.pasteMode || this.helpVisible) {
            return super.mouseScrolled_Impl(point, n, bl);
        }
        PrefboardNode prefboardNode = this.pref();
        if (prefboardNode == null || bl || n == 0) {
            return false;
        }
        try {
            if (n > 0) {
                prefboardNode.incScale();
            }
            else {
                prefboardNode.decScale();
            }
        }
        catch (Throwable throwable) {
            return false;
        }
        return true;
    }

    public boolean mouseReleased_Impl(Point point, int n, boolean bl) {
        if (this.dragging) {
            this.dragging = false;
            this.dragX1 = this.rootX(point);
            this.dragY1 = this.rootY(point);
            if (this.gestureAborted) {
                // 0.4.6: the board was panned (shift drag) or zoomed while the box was open,
                // so the box and the cells it was drawn over no longer line up. Acting on it
                // is exactly what deleted the wrong parts, so drop the gesture and say so
                // instead of silently doing nothing.
                this.gestureAborted = false;
                this.eraseDrag = false;
                this.say(Lang.t("st.marquee_moved"), true);
                return false;
            }
            if (this.eraseDrag) {
                // 0.4.3: came from the vanilla eraser - delete the marquee through the
                // staged path instead of merely selecting it.
                this.eraseDrag = false;
                this.eraseRect(this.dragX0, this.dragY0, this.dragX1, this.dragY1);
            }
            else {
                this.applyMarquee();
            }
            return true;
        }
        return super.mouseReleased_Impl(point, n, bl);
    }

    /**
    /**
     * 0.4.3: delete every part inside a screen-space rectangle, through the staged
     * (two-phase) path. Unlike the selection marquee this INCLUDES bundled cables -
     * {@code Clipboard.grab} skips them because pasting a cable is the one thing the
     * sync cannot replay, but deleting one is fine (cables stream at keys 1-5, they are
     * never parked and never trigger the subID-0 fallback).
     */
    private void eraseRect(int rx0, int ry0, int rx1, int ry1) {
        IntegratedCircuit integratedCircuit = this.ic();
        if (integratedCircuit == null) {
            return;
        }
        int n = this.gridXAt(Math.min(rx0, rx1));
        int n2 = this.gridXAt(Math.max(rx0, rx1));
        int n3 = this.gridYAt(Math.min(ry0, ry1));
        int n4 = this.gridYAt(Math.max(ry0, ry1));
        ArrayList<Long> arrayList = new ArrayList<Long>();
        for (int i = n3; i <= n4; ++i) {
            for (int j = n; j <= n2; ++j) {
                if (Clipboard.partAt(integratedCircuit, j, i) == null) continue;
                arrayList.add(Long.valueOf(Clipboard.key(j, i)));
            }
        }
        this.deleteKeys(arrayList);
    }

    /**
     * 0.4.3: the one place that removes parts. Shared by the selection delete (Del) and
     * by the vanilla-eraser marquee so both cannot drift apart - every removal has to go
     * through Sync.toServer, which stages the dangerous cells (parks anything that can
     * stream a key>10 state frame) before the removal leaves the client.
     */
    private int deleteKeys(java.util.List<Long> keys) {
        IntegratedCircuit integratedCircuit = this.ic();
        if (integratedCircuit == null) {
            return 0;
        }
        if (keys.isEmpty()) {
            this.say(Lang.t("st.no_sel"), true);
            return 0;
        }
        // 0.3.0: refuse while a staged edit is still converging.
        if (Sync.busy()) {
            this.say(Lang.t("st.busy"), true);
            return 0;
        }
        int n = 0;
        for (Long l : keys) {
            int n2;
            int n3 = Clipboard.kx(l);
            if (Clipboard.partAt(integratedCircuit, n3, n2 = Clipboard.ky(l)) == null) continue;
            integratedCircuit.removePart(n3, n2);
            ++n;
        }
        this.selected.clear();
        if (n == 0) {
            this.say(Lang.t("st.copy_empty"), true);
            return 0;
        }
        integratedCircuit.refreshErrors();
        Sync.toServer(this.tile, integratedCircuit);
        this.undo.commitIfChanged(integratedCircuit);
        this.say(Lang.t("st.deleted", String.valueOf(n)));
        return n;
    }

    private static boolean isBundledCable(IntegratedCircuit integratedCircuit, int n, int n2) {
        try {
            return Clipboard.partAt(integratedCircuit, n, n2) instanceof BundledCableICPart;
        }
        catch (Throwable throwable) {
            return false;
        }
    }

    private void applyMarquee() {
        IntegratedCircuit integratedCircuit = this.ic();
        if (integratedCircuit == null) {
            return;
        }
        int n = this.gridXAt(this.dragX0);
        int n2 = this.gridYAt(this.dragY0);
        int n3 = this.gridXAt(this.dragX1);
        int n4 = this.gridYAt(this.dragY1);
        int n5 = Math.min(n, n3);
        int n6 = Math.max(n, n3);
        int n7 = Math.min(n2, n4);
        int n8 = Math.max(n2, n4);
        if (!GuiICWbEx.shiftHeld()) {
            this.selected.clear();
        }
        int n9 = 0;
        int skipped = 0;
        for (int i = n7; i <= n8; ++i) {
            for (int j = n5; j <= n6; ++j) {
                if (Clipboard.partAt(integratedCircuit, j, i) == null) continue;
                if (GuiICWbEx.isBundledCable(integratedCircuit, j, i)) {
                    ++skipped;
                    continue;
                }
                if (this.selected.add(Clipboard.key(j, i))) {
                    ++n9;
                }
            }
        }
        if (this.selected.isEmpty()) {
            if (skipped > 0) {
                this.say(Lang.t("st.sel_skip", "0", String.valueOf(skipped)), true);
            } else {
                this.say(Lang.t("st.copy_empty"), true);
            }
        } else if (skipped > 0) {
            this.say(Lang.t("st.sel_skip", String.valueOf(this.selected.size()), String.valueOf(skipped)));
        } else {
            this.say(Lang.t("st.sel", String.valueOf(this.selected.size())));
        }
    }

    private void doCopy() {
        IntegratedCircuit integratedCircuit = this.ic();
        if (integratedCircuit == null || this.selected.isEmpty()) {
            this.say(Lang.t("st.no_sel"), true);
            return;
        }
        Clipboard clipboard = Clipboard.grab(integratedCircuit, this.canonicalSelection(integratedCircuit));
        if (clipboard.isEmpty()) {
            this.say(Lang.t("st.copy_empty"), true);
            return;
        }
        this.clip = clipboard;
        this.pasteMode = false;
        this.say(Lang.t("st.copied", String.valueOf(this.clip.size()), String.valueOf(this.clip.width()), String.valueOf(this.clip.height())));
    }

    private void doCut() {
        this.doCopy();
        if (this.clip != null && !this.clip.isEmpty()) {
            this.doDelete();
        }
    }

    private void doPaste() {
        if (this.clip == null || this.clip.isEmpty()) {
            this.say(Lang.t("st.clip_empty"), true);
            return;
        }
        // 0.4.7: a clip that carries an IO may only be dropped where the IO lands on the
        // board's ring, so the marquee is snapped to such an origin (see snappedOrigin).
        // When no such origin exists the paste is refused here, before paste mode even
        // starts - there is no position the player could move the box to that would
        // work, so letting them try and failing on the click would just be a dead end.
        this.ioOrigins = null;
        if (this.clip.hasIO()) {
            IntegratedCircuit integratedCircuit = this.ic();
            Size size = integratedCircuit == null ? null : integratedCircuit.size();
            int[] offsets = this.clip.ioOffsets();
            int[] origins = size == null ? new int[0]
                : Grow.ioPasteOrigins(size.width(), size.height(),
                                      this.clip.width(), this.clip.height(), offsets);
            if (origins.length == 0) {
                this.say(Lang.t(Grow.hasInteriorIO(this.clip.width(), this.clip.height(), offsets)
                    ? "st.io_interior" : "st.io_no_fit",
                    String.valueOf(offsets.length / 2)), true);
                return;
            }
            this.ioOrigins = origins;
            this.say(Lang.t("st.io_snap", String.valueOf(offsets.length / 2)));
        } else {
            this.say(Lang.t("st.paste_ready"));
        }
        this.pasteMode = true;
    }

    /**
     * 0.4.7: where this paste would actually land, given the pointer.
     *
     * <p>Both the preview and the click go through here, so the box on screen is always
     * exactly where the parts will go - the same rule the erase preview follows. With a
     * clip that carries an IO the origin is pulled onto the nearest one that puts every
     * IO on the border ({@link Grow#nearestOrigin}), which is what makes the marquee
     * hug the edge. Without an IO it is the raw pointer cell, as before.
     */
    private int[] snappedOrigin(int gx, int gy) {
        if (!this.pasteMode || this.ioOrigins == null) {
            return new int[]{gx, gy};
        }
        int[] best = Grow.nearestOrigin(this.ioOrigins, gx, gy);
        return best == null ? new int[]{gx, gy} : best;
    }

    private void stampAt(int n, int n2) {
        IntegratedCircuit integratedCircuit = this.ic();
        if (integratedCircuit == null || this.clip == null || this.clip.isEmpty()) {
            this.pasteMode = false;
            return;
        }
        // 0.3.0: a staged edit is still converging - applying another one now
        // would race its second phase.
        if (Sync.busy()) {
            this.say(Lang.t("st.busy"), true);
            return;
        }
        if (n < 0 || n2 < 0) {
            this.say(Lang.t("st.paste_out"), true);
            return;
        }
        Size size = integratedCircuit.size();
        int oldW = size.width();
        int oldH = size.height();
        int[] pasted = this.clipPasted(n, n2);
        // 0.4.7: ONE planner decides both "does this paste need a bigger board" and
        // "how much bigger". It used to grow cell by cell to exactly what the paste
        // needed (producing boards like 20x17), and it only looked at the paste's
        // extent - so an IO could be left stranded in the middle of the enlarged
        // board. See Grow for the vanilla rules it mirrors.
        int[] plan = Grow.planGrowth(oldW, oldH, pasted, this.edgeIOs(integratedCircuit, oldW, oldH));
        // A clip that holds an IO is NEVER allowed to grow the board. An IO is legal
        // only on the ring (OpIOGate.canPlace = isOnBorder && !isOnEdge) and growing
        // moves the ring, so a paste aimed at putting its IO on the border stops
        // landing on it the moment the board gets bigger - which is exactly the
        // field report this rule comes from ("带着 IO 粘贴会触发扩版，IO 到不了
        // 边界"). Suppressing here rather than inside Grow keeps the planner a pure
        // "what would be legal" function and puts the intent call in the caller.
        boolean ioPaste = Grow.containsIO(pasted);
        boolean growBlocked = ioPaste && plan != null;
        if (growBlocked) {
            plan = null;
        }
        if (plan == Grow.IMPOSSIBLE) {
            this.pasteMode = false;
            this.say(Lang.t("st.too_big"), true);
            return;
        }
        boolean grew = plan != null;
        int movedIOs = 0;
        if (grew) {
            // 0.2.9: a board growth can only reach the server through a whole-circuit
            // desc (case 5), and the server throws those away while no blueprint is
            // inserted. Growing anyway would desync the sizes: the server keeps the
            // old smaller board and every op that lands past its edge trips
            // assertCoords, which aborts the whole IC stream mid-way. Refuse cleanly.
            if (!this.tile.hasBP()) {
                this.pasteMode = false;
                this.say(Lang.t("st.no_bp_grow"), true);
                return;
            }
            // Order matters. The new size goes up FIRST (setPart asserts the bounds,
            // so an IO cannot be written to a coordinate that does not exist yet),
            // then every IO that growth just pushed off the ring is walked out to the
            // new one, and only then is the paste stamped. Stamping any earlier would
            // have overwritten the IOs that had not moved yet.
            integratedCircuit.size_$eq(new Size(plan[0], plan[1]));
            movedIOs = this.moveEdgeIOs(integratedCircuit, oldW, oldH, plan[0], plan[1]);
        }
        Size finalSize = integratedCircuit.size();
        int dropped = growBlocked
            ? Grow.countOffBoard(pasted, finalSize.width(), finalSize.height()) : 0;
        // 0.4.7: the outer border is never written by a paste except with an IO gate.
        // stamp() enforces that on its own now (it needs no flag - the rule does not
        // depend on whether the clipboard holds an IO), and reports how many cells it
        // left out.
        int n5 = this.clip.stamp(integratedCircuit, n, n2);
        int skipped = this.clip.lastSkippedBundled;
        int cleared = this.clip.lastSkippedBorder;
        integratedCircuit.refreshErrors();
        Sync.toServer(this.tile, integratedCircuit);
        this.undo.commitIfChanged(integratedCircuit);
        boolean nothing = n5 == 0 && skipped == 0 && cleared == 0;
        String base;
        if (nothing) {
            base = Lang.t("st.paste_none");
        } else if (grew) {
            base = Lang.t("st.grew", String.valueOf(n5), String.valueOf(finalSize.width()),
                String.valueOf(finalSize.height()));
        } else {
            base = Lang.t("st.pasted", String.valueOf(n5));
        }
        // 0.4.7: collect the notices most-important-first and let fitStatus decide which
        // of them the header strip can actually show. They used to be appended blindly,
        // and one paste can raise three at once (about 566px of Chinese, 924px of
        // English) - which the renderer then cut off mid-sentence.
        List<String> extra = new ArrayList<String>();
        if (cleared > 0) {
            extra.add(Lang.t("st.border_cleared", String.valueOf(cleared)));
        }
        if (growBlocked) {
            extra.add(Lang.t("st.io_nogrow"));
        }
        if (skipped > 0) {
            extra.add(Lang.t("st.paste_skip", String.valueOf(n5), String.valueOf(skipped)));
        }
        if (growBlocked && dropped > 0) {
            extra.add(Lang.t("st.io_nogrow_drop", String.valueOf(dropped)));
        }
        if (grew && movedIOs > 0) {
            extra.add(Lang.t("st.grew_io", String.valueOf(movedIOs)));
        }
        this.say(this.fitStatus(base, extra), nothing || dropped > 0 || cleared > 0);
    }

    /**
     * The board cells this paste would write, as flat {@code (x, y, partId)} triples -
     * the shape {@link Grow} works in. Cells with a negative coordinate are included
     * (the planner and the drop counter both need to see them; {@code stamp} discards
     * them).
     */
    private int[] clipPasted(int n, int n2) {
        int count = this.clip.size();
        int[] pasted = new int[3 * count];
        for (int i = 0; i < count; ++i) {
            Clipboard.Entry entry = this.clip.get(i);
            pasted[i * 3] = n + entry.dx;
            pasted[i * 3 + 1] = n2 + entry.dy;
            pasted[i * 3 + 2] = (int)entry.id;
        }
        return pasted;
    }

    /**
     * Every IO that a growth would push off the ring, as flat {@code (x, y, kind)}
     * triples: kind 1 = right column, kind 2 = bottom row.
     *
     * <p>Only those two edges can strand an IO. The left column and the top row keep
     * x == 0 / y == 0, which are the ring at any size, so an IO standing there is fine
     * where it is - and one PASTED onto them cannot be rescued by growing at all (the
     * paste would have to move instead), which is why they are not trigger edges.
     */
    private int[] edgeIOs(IntegratedCircuit integratedCircuit, int w, int h) {
        int[] buf = new int[3 * (w + h)];
        int k = 0;
        for (int y = 0; y < h; ++y) {
            CircuitPart part = Clipboard.partAt(integratedCircuit, w - 1, y);
            if (part == null || !Grow.isIO(part.id())) {
                continue;
            }
            buf[k++] = w - 1;
            buf[k++] = y;
            buf[k++] = 1;
        }
        for (int x = 0; x < w; ++x) {
            CircuitPart part = Clipboard.partAt(integratedCircuit, x, h - 1);
            if (part == null || !Grow.isIO(part.id())) {
                continue;
            }
            buf[k++] = x;
            buf[k++] = h - 1;
            buf[k++] = 2;
        }
        return buf;
    }

    /**
     * Walks every IO that growth stranded out onto the new ring, and reports how many
     * moved.
     *
     * <p>ProjectRed keeps an IO legal by POSITION, not by any field of its own:
     * {@code OpIOGate.canPlace} is {@code isOnBorder && !isOnEdge}, and {@code
     * getIOSide()} is just the part's rotation. So the moment growth lands an IO in
     * the middle of a bigger board it is no longer a valid IO and the blueprint is
     * invalid - which is exactly the defect that was reported. Moving the part (rather
     * than rewriting its fields) keeps its rotation, and with it the side of the IC it
     * stand for, so the circuit's external interface is left alone.
     *
     * <p>Must run AFTER the new size is in place: {@code setPart} asserts the bounds.
     */
    private int moveEdgeIOs(IntegratedCircuit integratedCircuit, int w, int h, int newW, int newH) {
        int moved = 0;
        if (newW > w) {
            for (int y = 0; y < h; ++y) {
                CircuitPart part = Clipboard.partAt(integratedCircuit, w - 1, y);
                if (part == null || !Grow.isIO(part.id())) {
                    continue;
                }
                integratedCircuit.removePart(w - 1, y);
                integratedCircuit.setPart(newW - 1, y, part);
                ++moved;
            }
        }
        if (newH > h) {
            for (int x = 0; x < w; ++x) {
                // A corner IO is listed by both passes; the first one has already moved
                // it away, so this lookup comes back empty and it is simply skipped.
                CircuitPart part = Clipboard.partAt(integratedCircuit, x, h - 1);
                if (part == null || !Grow.isIO(part.id())) {
                    continue;
                }
                integratedCircuit.removePart(x, h - 1);
                integratedCircuit.setPart(x, newH - 1, part);
                ++moved;
            }
        }
        return moved;
    }

    private void doDelete() {
        IntegratedCircuit integratedCircuit = this.ic();
        if (integratedCircuit == null) {
            return;
        }
        // 0.4.3: same funnel as the vanilla-eraser marquee (deleteKeys).
        if (this.selected.isEmpty()) {
            this.say(Lang.t("st.no_sel"), true);
            return;
        }
        this.deleteKeys(new ArrayList<Long>(this.selected));
    }

    private void doUndo() {
        IntegratedCircuit integratedCircuit = this.ic();
        if (integratedCircuit == null) {
            return;
        }
        // 0.3.0: refuse while a staged edit is still converging.
        if (Sync.busy()) {
            this.say(Lang.t("st.busy"), true);
            return;
        }
        if (!this.undo.undo(integratedCircuit, this.tile)) {
            this.say(Lang.t("st.no_undo"), true);
            return;
        }
        this.afterHistory();
        this.say(Lang.t("st.undo"));
    }

    private void doRedo() {
        IntegratedCircuit integratedCircuit = this.ic();
        if (integratedCircuit == null) {
            return;
        }
        // 0.3.0: refuse while a staged edit is still converging.
        if (Sync.busy()) {
            this.say(Lang.t("st.busy"), true);
            return;
        }
        if (!this.undo.redo(integratedCircuit, this.tile)) {
            this.say(Lang.t("st.no_redo"), true);
            return;
        }
        this.afterHistory();
        this.say(Lang.t("st.redo"));
    }

    private void afterHistory() {
        this.selected.clear();
        this.pasteMode = false;
        IntegratedCircuit integratedCircuit = this.ic();
        if (integratedCircuit != null && !Sync.busy()) {
            // While a staged edit is converging the board is deliberately rolled
            // back to its pre-edit state; committing that would corrupt the
            // undo stack. The pump's echo freeze ends with a normal commit that
            // records the finished edit instead.
            integratedCircuit.refreshErrors();
            this.undo.commitIfChanged(integratedCircuit);
        }
    }

    private void selectAll() {
        IntegratedCircuit integratedCircuit = this.ic();
        if (integratedCircuit == null) {
            return;
        }
        this.selected.clear();
        Size size = integratedCircuit.size();
        int skipped = 0;
        for (int i = 0; i < size.height(); ++i) {
            for (int j = 0; j < size.width(); ++j) {
                if (Clipboard.partAt(integratedCircuit, j, i) == null) continue;
                if (GuiICWbEx.isBundledCable(integratedCircuit, j, i)) {
                    ++skipped;
                    continue;
                }
                this.selected.add(Clipboard.key(j, i));
            }
        }
        if (this.selected.isEmpty()) {
            this.say(Lang.t("st.no_sel"), true);
        } else if (skipped > 0) {
            this.say(Lang.t("st.sel_skip", String.valueOf(this.selected.size()), String.valueOf(skipped)));
        } else {
            this.say(Lang.t("st.sel", String.valueOf(this.selected.size())));
        }
    }

    private Set<Long> canonicalSelection(IntegratedCircuit integratedCircuit) {
        LinkedHashSet<Long> linkedHashSet = new LinkedHashSet<Long>();
        for (Long l : this.selected) {
            if (Clipboard.partAt(integratedCircuit, Clipboard.kx(l), Clipboard.ky(l)) == null) continue;
            linkedHashSet.add(l);
        }
        return linkedHashSet;
    }

    public boolean keyPressed_Impl(char c, int n, boolean bl) {
        boolean bl2;
        if (n == 29 || n == 157) {
            this.applyIntercept();
        }
        if (bl2 = GuiICWbEx.ctrlHeld()) {
            switch (n) {
                case 46: {
                    this.doCopy();
                    return true;
                }
                case 45: {
                    this.doCut();
                    return true;
                }
                case 47: {
                    this.doPaste();
                    return true;
                }
                case 30: {
                    this.selectAll();
                    return true;
                }
                case 44: {
                    this.doUndo();
                    return true;
                }
                case 21: {
                    this.doRedo();
                    return true;
                }
            }
        } else {
            if (n == 35) {
                this.helpVisible = !this.helpVisible;
                this.helpScroll = 0;   // always open on the first page
                return true;
            }
            if (n == 211 || n == 14) {
                this.doDelete();
                return true;
            }
            if (n == 1) {
                if (this.helpVisible) {
                    this.helpVisible = false;
                    this.helpScroll = 0;
                    return true;
                }
                if (this.pasteMode) {
                    this.pasteMode = false;
                    this.say(Lang.t("st.paste_cancel"));
                    return true;
                }
                if (!this.selected.isEmpty()) {
                    this.selected.clear();
                    this.say(Lang.t("st.cleared"));
                    return true;
                }
            }
        }
        return super.keyPressed_Impl(c, n, bl);
    }

    private void say(String string) {
        this.say(string, false);
    }

    private void say(String string, boolean bl) {
        this.status = string;
        this.statusIsError = bl;
        // 0.4.7: a status message is often the only explanation of what just happened
        // ("pasted 12 parts, dropped 3 that landed on the border"), so it has to stay up
        // long enough to read. 10s base, plus time in proportion to the text, capped so
        // a message never lingers after the player has moved on.
        int extra = string == null ? 0 : Math.min(STATUS_TTL_BONUS_CAP, string.length() * 4);
        this.statusTtl = STATUS_TTL_BASE + extra;
    }

    public void drawBack_Impl(Point point, float f) {
        super.drawBack_Impl(point, f);
        FontRenderer fontRenderer = this.fontRenderer();
        // 0.4.7: the transient status and the persistent hints are two different things
        // and now live in two places. The status can be very long (the longest single
        // message measures 349px in Chinese and 798px in English), so it moved to the
        // header strip, where the horizontal room is. The hints are short and
        // inherently multi-line ("selected N / Ctrl+C / Ctrl+V / Del / Esc"), which is
        // exactly what a narrow column is good at, so they stay where they were. They
        // no longer replace each other either - reading a status no longer hides the
        // hints you were following.
        if (this.statusTtl > 0 && this.status.length() > 0) {
            this.drawStatusStrip(fontRenderer, this.status, this.statusIsError);
        }
        String string;
        int n;
        if (this.pasteMode && this.clip != null) {
            string = Lang.t("hint.paste", String.valueOf(this.clip.size()));
            n = TXT_STATUS;
        } else if (!this.selected.isEmpty()) {
            string = Lang.t("hint.sel", String.valueOf(this.selected.size()));
            n = TXT_STATUS;
        } else {
            string = Lang.t("hint.idle");
            n = TXT_HINT;
        }
        this.drawWrapped(fontRenderer, string, HINT_X, HINT_Y, HINT_W, n, HINT_MAX_LINES);
    }

    /**
     * Draws the status in the header strip, up to {@link #STATUS_LINES} lines.
     *
     * <p>It starts after the vanilla machine title instead of at a fixed x, because the
     * title is localised and its width differs per language (49px in Chinese, 168px in
     * English) - a hardcoded start would either collide with it or waste the space.
     * The title is rotated into place by ProjectRed, so its width can only be measured
     * at runtime.
     */
    private void drawStatusStrip(FontRenderer fontRenderer, String text, boolean error) {
        this.drawClipped(fontRenderer, text, this.statusStripLeft(fontRenderer), STATUS_Y,
            this.statusStripWidth(fontRenderer), error ? TXT_ERROR : TXT_STATUS,
            StatusLine.LINES, LINE_H);
    }

    /**
     * x where the status may start: after the vanilla title, with a gap.
     *
     * <p>An untranslated key comes back as the key itself, which is far longer than any
     * real title - it is clamped to a third of the width so that can never leave the
     * status with no room at all.
     */
    /** width of the vanilla machine title, which the strip has to start after. */
    private int vanillaTitleWidth(FontRenderer fontRenderer) {
        String title = StatCollector.func_74838_a(VANILLA_TITLE_KEY);
        if (title == null || title.equals(VANILLA_TITLE_KEY)) {
            return 0;   // untranslated: the raw key is far longer than any real title
        }
        return fontRenderer.func_78256_a(title);
    }

    private int statusStripLeft(FontRenderer fontRenderer) {
        return StatusLine.left(this.vanillaTitleWidth(fontRenderer), this.size().width());
    }

    /** how much horizontal room the status strip has, which is what fitStatus budgets against. */
    private int statusStripWidth(FontRenderer fontRenderer) {
        return StatusLine.width(this.vanillaTitleWidth(fontRenderer), this.size().width());
    }

    /** total text width the strip can show: the width, times the lines it may use. */
    private int statusBudgetPx(FontRenderer fontRenderer) {
        return StatusLine.budget(this.vanillaTitleWidth(fontRenderer), this.size().width());
    }

    /**
     * Appends as many of {@code extra} as the strip can actually show, in the order
     * given (the caller lists them most important first).
     *
     * <p>This is the fix for the reported truncation. A single paste can raise three
     * notices at once (grown to N x M + moved K IOs + protected J cables) - about 566px
     * of Chinese and 924px of English - and the old code appended them all and then let
     * the renderer cut the result off at its line limit, mid-sentence and without a
     * trace. Now the budget is measured against the real font and the real strip, and a
     * notice that does not fit is simply left out (as a whole, never half of one).
     */
    private String fitStatus(String base, List<String> extra) {
        FontRenderer fontRenderer = this.fontRenderer();
        if (fontRenderer == null || extra.isEmpty()) {
            return base;
        }
        int[] widths = new int[extra.size()];
        for (int i = 0; i < widths.length; ++i) {
            widths[i] = fontRenderer.func_78256_a(extra.get(i));
        }
        boolean[] take = StatusLine.fitFlags(fontRenderer.func_78256_a(base), widths,
            fontRenderer.func_78256_a(" "), this.statusBudgetPx(fontRenderer), STATUS_MAX_EXTRAS);
        StringBuilder out = new StringBuilder(base);
        for (int i = 0; i < take.length; ++i) {
            if (take[i]) {
                out.append(' ').append(extra.get(i));
            }
        }
        return out.toString();
    }

    /**
     * Draws at most {@code maxLines} lines, marking the cut with "..." when the text
     * still does not fit.
     *
     * <p>An honest last resort: fitStatus keeps whole notices from being dropped, but a
     * single long message (English "the blueprint is 64x64 - insert an IC blueprint..."
     * is 798px) can still exceed a narrow strip, and stopping mid-sentence with no sign
     * of it is the behaviour that was reported as a bug.
     */
    private void drawClipped(FontRenderer fontRenderer, String text, int x, int y, int maxWidth,
                             int colour, int maxLines, int lineHeight) {
        List<String> lines = new ArrayList<String>();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); ++i) {
            char c = text.charAt(i);
            if (c == '\n') {
                lines.add(line.toString());
                line.setLength(0);
                continue;
            }
            line.append(c);
            if (fontRenderer.func_78256_a(line.toString()) <= maxWidth || line.length() <= 1) {
                continue;
            }
            char last = line.charAt(line.length() - 1);
            line.setLength(line.length() - 1);
            lines.add(line.toString());
            line.setLength(0);
            line.append(last);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        boolean clipped = lines.size() > maxLines;
        int draw = Math.min(lines.size(), maxLines);
        for (int i = 0; i < draw; ++i) {
            String text2 = lines.get(i);
            if (clipped && i == draw - 1) {
                text2 = this.ellipsize(fontRenderer, text2, maxWidth);
            }
            fontRenderer.func_78276_b(text2, x, y + i * lineHeight, colour);
        }
    }

    /** trims a line until it plus "..." fits {@code maxWidth}. */
    private String ellipsize(FontRenderer fontRenderer, String line, int maxWidth) {
        String tail = "...";
        int room = maxWidth - fontRenderer.func_78256_a(tail);
        String text = line;
        while (text.length() > 1 && fontRenderer.func_78256_a(text) > room) {
            text = text.substring(0, text.length() - 1);
        }
        return text + tail;
    }

    /**
     * 0.4.7: the controls page, rebuilt as blocks instead of one flat text run.
     *
     * <p>Four things were wrong with the old version and all four are layout, not
     * content: the section names were typed as 【...】 inside the body and drawn in the
     * same colour as everything else, so there was no hierarchy; there was no gap
     * between sections, so they ran together; everything sat in a single column that
     * used less than half the panel width (those lines are ~20 characters long); and
     * because it was one column, 30 lines did not fit and the line height was squeezed
     * to 7px - tighter than the font's own 9px - which is the "too crowded" that was
     * reported.
     *
     * <p>Now: each section is a block with a coloured heading and a rule under it, its
     * entries are indented, blocks are separated by a blank line, and the blocks are
     * dealt into as many columns as it takes to reach a readable line height (see
     * {@link HelpLayout}). Column count and line height are solved together against the
     * real panel size, so the page adapts to the GUI scale instead of overflowing.
     */
    private void drawHelp() {
        FontRenderer fontRenderer = this.fontRenderer();
        int screenW = this.size().width();
        int screenH = this.size().height();
        List<String[]> sections = HelpLayout.parse(Lang.t("help.body"));
        String foot = Lang.t("help.foot");

        int margin = 4;
        int pad = 8;
        int indent = 7;
        int colGap = 10;
        int panelW = screenW - margin * 2;
        int innerW = panelW - pad * 2;
        int titleH = Theme.TITLE_H + 6;
        int footH = 12;
        int contentTopOffset = titleH + pad;
        int avail = screenH - margin * 2 - contentTopOffset - footH - 4;

        // Pick the column count and line height together: the fewest columns that reach
        // a full font line, and only then more (and narrower) ones.
        int bestCols = 1;
        int bestLineH = -1;
        int bestGapLines = 1;
        int[] bestCounts = new int[]{sections.size()};
        for (int cols = 1; cols <= 3; ++cols) {
            int colW = (innerW - (cols - 1) * colGap) / cols;
            if (cols > 1 && colW < HELP_MIN_COL_W) {
                break;
            }
            int[] blockLines = this.helpBlockLines(fontRenderer, sections, colW, indent);
            for (int gapLines = 1; gapLines >= 0; --gapLines) {
                int[] counts = HelpLayout.distribute(blockLines, gapLines, cols);
                int maxLines = HelpLayout.maxColumnLines(blockLines, gapLines, counts);
                int lineH = HelpLayout.lineHeightFor(avail, maxLines,
                    HelpLayout.MIN_LINE_H, HelpLayout.MAX_LINE_H);
                if (lineH < 0) {
                    continue;   // tightest spacing still overflows - try the next shape
                }
                // Strictly better only: an equal line height keeps the wider column,
                // because fewer columns means longer lines, which read better.
                if (lineH > bestLineH) {
                    bestCols = cols;
                    bestLineH = lineH;
                    bestGapLines = gapLines;
                    bestCounts = counts;
                }
                break;
            }
            if (bestLineH >= HelpLayout.PREFERRED_LINE_H) {
                break;
            }
        }
        if (bestLineH < 0) {
            // Nothing fits even at the tightest readable spacing (a very small GUI).
            // Keep the readable minimum and let the panel take the whole screen; the
            // same compromise the old page made, but now with the block structure.
            bestLineH = HelpLayout.MIN_LINE_H;
            bestCols = 1;
            bestGapLines = 0;
            bestCounts = new int[]{sections.size()};
        }
        int colW = (innerW - (bestCols - 1) * colGap) / bestCols;
        int[] blockLines = this.helpBlockLines(fontRenderer, sections, colW, indent);
        int contentLines = HelpLayout.maxColumnLines(blockLines, bestGapLines, bestCounts);
        // 0.4.7 (second pass): the panel is pinned to the top of the window instead of
        // being centred, and is given the whole window height. The old version centred it
        // and clamped the height to the window, but still drew every line - so on a small
        // GUI the text ran out of the panel and off the bottom of the screen. Using the
        // full height removes the slack, and anything that still does not fit is now
        // scrolled rather than painted outside.
        int panelH = screenH - margin * 2;
        int top = margin;
        int contentTop = top + contentTopOffset;
        int contentPx = panelH - contentTopOffset - footH - 4;
        int lineH = bestLineH;
        int visible = HelpLayout.visibleLines(contentPx, lineH);
        this.helpScroll = HelpLayout.clampScroll(this.helpScroll, contentLines, visible);

        Theme.fill(0.0, 0.0, screenW, screenH, Theme.OVERLAY);
        Theme.panel(margin, top, panelW, panelH);
        Theme.header(margin + 2, top + 2, panelW - 4, titleH - 6);
        fontRenderer.func_78276_b(Lang.t("help.title"), margin + pad, top + 6, Theme.ACCENT);

        // Everything the page will show, as one entry per rendered line, so the scroll
        // offset and the clipping are applied to whole lines - nothing can be drawn
        // half in the panel and half out of it.
        List<Object[]> items = new ArrayList<Object[]>();
        int idx = 0;
        for (int c = 0; c < bestCounts.length && idx < sections.size(); ++c) {
            int x = margin + pad + c * (colW + colGap);
            int line = 0;
            for (int k = 0; k < bestCounts[c] && idx < sections.size(); ++k, ++idx) {
                String[] section = sections.get(idx);
                if (section[0].length() > 0) {
                    List<String> wrapped = this.wrapLines(fontRenderer, section[0], colW);
                    for (int i = 0; i < wrapped.size(); ++i) {
                        items.add(new Object[]{x, line++, Theme.ACCENT, wrapped.get(i)});
                    }
                    // one line unit for the rule and the breathing room under it, which
                    // helpBlockLines counts too - the two must agree or the column count
                    // and line height are chosen for a page that is not the one drawn
                    items.add(new Object[]{x, line++, HELP_RULE, null});
                }
                for (int e = 1; e < section.length; ++e) {
                    List<String> wrapped = this.wrapLines(fontRenderer, section[e], colW - indent);
                    for (int i = 0; i < wrapped.size(); ++i) {
                        items.add(new Object[]{x + indent, line++, Theme.TEXT_MUTED, wrapped.get(i)});
                    }
                }
                // No trailing gap after the last block of a column: helpBlockLines /
                // maxColumnLines count a gap only BETWEEN blocks, and if the two
                // disagreed by one line the last line could be drawn just below the
                // content area - exactly the overflow this is meant to stop.
                if (k < bestCounts[c] - 1) {
                    line += bestGapLines;
                }
            }
        }
        for (int i = 0; i < items.size(); ++i) {
            Object[] item = items.get(i);
            int at = (Integer)item[1];
            if (at < this.helpScroll || at >= this.helpScroll + visible) {
                continue;   // outside the visible window - not drawn at all
            }
            int y = contentTop + (at - this.helpScroll) * lineH;
            Object text = item[3];
            if (text == null) {
                Theme.fill((Integer)item[0], y - Math.max(2, lineH / 3), colW, 1, (Integer)item[2]);
            }
            else {
                fontRenderer.func_78276_b((String)text, (Integer)item[0], y, (Integer)item[2]);
            }
        }

        boolean scrollable = HelpLayout.scrollable(contentLines, visible);
        String footer = foot;
        if (scrollable) {
            footer = footer + "  " + Lang.t("help.scroll",
                String.valueOf(this.helpScroll + visible > contentLines ? contentLines : this.helpScroll + visible),
                String.valueOf(contentLines));
        }
        fontRenderer.func_78276_b(footer, margin + pad, top + panelH - footH, Theme.TEXT_DISABLED);
        if (scrollable) {
            this.drawHelpScrollBar(top + contentTopOffset, contentPx, contentLines, visible);
        }
    }

    /**
     * A thin scrollbar on the panel's right edge, so a page that needs scrolling says so
     * instead of looking like it simply ends.
     */
    private void drawHelpScrollBar(double top, double height, int contentLines, int visible) {
        int x = this.size().width() - 4 - 4;
        Theme.fill(x, top, 2, height, HELP_SCROLL_TRACK);
        if (contentLines <= 0) {
            return;
        }
        double thumb = Math.max(8.0, height * visible / contentLines);
        double max = HelpLayout.scrollMax(contentLines, visible);
        double pos = max <= 0 ? 0.0 : (height - thumb) * this.helpScroll / max;
        Theme.fill(x, top + pos, 2, thumb, HELP_SCROLL_THUMB);
    }

    /** Wrapped height of every block at this column width, in lines.
     *
     *  <p>Counts the heading's own line unit for the rule, because the drawing pass adds
     *  one there as well: if the two disagree, the column count and line height get chosen
     *  for a page that is not the one that ends up on screen.
     */
    private int[] helpBlockLines(FontRenderer fontRenderer, List<String[]> sections, int colW, int indent) {
        int[] out = new int[sections.size()];
        for (int i = 0; i < out.length; ++i) {
            String[] section = sections.get(i);
            int lines = 0;
            if (section[0].length() > 0) {
                lines += this.wrapLines(fontRenderer, section[0], colW).size() + 1;   // + the rule
            }
            for (int e = 1; e < section.length; ++e) {
                lines += this.wrapLines(fontRenderer, section[e], colW - indent).size();
            }
            out[i] = lines;
        }
        return out;
    }

    /**
     * Splits text into the lines it will occupy at {@code maxWidth}.
     *
     * <p>The single wrapping implementation for this GUI: {@link #wrapText} draws what
     * this returns and the help page both measures and draws through it, so the two can
     * never disagree about how tall a block is.
     */
    private List<String> wrapLines(FontRenderer fontRenderer, String text, int maxWidth) {
        List<String> out = new ArrayList<String>();
        if (text == null || text.length() == 0) {
            return out;
        }
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); ++i) {
            char c = text.charAt(i);
            if (c == '\n') {
                out.add(line.toString());
                line.setLength(0);
                continue;
            }
            line.append(c);
            if (fontRenderer.func_78256_a(line.toString()) <= maxWidth || line.length() <= 1) {
                continue;
            }
            char last = line.charAt(line.length() - 1);
            line.setLength(line.length() - 1);
            out.add(line.toString());
            line.setLength(0);
            line.append(last);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    private void drawWrapped(FontRenderer fontRenderer, String string, int n, int n2, int n3, int n4, int n5) {
        this.drawWrapped(fontRenderer, string, n, n2, n3, n4, n5, 9);
    }

    private void drawWrapped(FontRenderer fontRenderer, String string, int x, int y, int maxWidth, int colour, int maxLines, int lineHeight) {
        this.wrapText(fontRenderer, string, x, y, maxWidth, colour, lineHeight, maxLines, true);
    }

    /**
     * Draws (or only measures) the first {@code maxLines} lines of {@code text}.
     *
     * @return the number of lines the text occupies, capped at {@code maxLines}
     */
    private int wrapText(FontRenderer fontRenderer, String text, int x, int y, int maxWidth,
                         int colour, int lineHeight, int maxLines, boolean draw) {
        List<String> lines = this.wrapLines(fontRenderer, text, maxWidth);
        int n = Math.min(lines.size(), maxLines);
        if (draw) {
            for (int i = 0; i < n; ++i) {
                fontRenderer.func_78276_b(lines.get(i), x, y + i * lineHeight, colour);
            }
        }
        return n;
    }

    public void drawFront_Impl(Point point, float f) {
        super.drawFront_Impl(point, f);
        this.drawSelectionOverlay();
        if (this.helpVisible) {
            this.drawHelp();
        }
    }

    private void drawSelectionOverlay() {
        IntegratedCircuit integratedCircuit = this.ic();
        if (integratedCircuit == null || this.pref() == null) {
            return;
        }
        double d = this.cellPx();
        if (d <= 0.0) {
            return;
        }
        if (this.pasteMode && this.clip != null && !this.clip.isEmpty()) {
            // 0.4.7: the same snapped origin the click will use, so an IO paste is drawn
            // already hugging the border rather than jumping there on release.
            int[] origin = this.snappedOrigin(this.gridXAt(this.mouseX), this.gridYAt(this.mouseY));
            int n = origin[0];
            int n2 = origin[1];
            int n3 = this.rootXOfCell(n);
            int n4 = this.rootYOfCell(n2);
            int n5 = (int)Math.round((double)this.clip.width() * d);
            int n6 = (int)Math.round((double)this.clip.height() * d);
            Size size = integratedCircuit.size();
            boolean bl = n >= 0 && n2 >= 0 && n + this.clip.width() <= size.width() && n2 + this.clip.height() <= size.height();
            Theme.fill(n3, n4, n5, n6, bl ? 1073793778 : 1090479975);
            Theme.border(n3, n4, n5, n6, bl ? -16725262 : -39065);
            return;
        }
        if (this.dragging) {
            int n = Math.min(this.dragX0, this.dragX1);
            int n7 = Math.min(this.dragY0, this.dragY1);
            int n8 = Math.abs(this.dragX1 - this.dragX0);
            int n9 = Math.abs(this.dragY1 - this.dragY0);
            if (this.eraseDrag && !this.gestureAborted) {
                // 0.4.5: an eraser drag used to give no clue about what it was about to
                // delete (it just went away on release). Every cell in the box that
                // holds a part now gets the danger tint, the box itself gets the danger
                // border and the number of parts is printed inside it - so the marquee
                // says exactly what the release will do, before you let go.
                int n10 = this.tintEraseCells(integratedCircuit, n, n7, n + n8, n7 + n9);
                Theme.border(n, n7, n8, n9, ERASE_BORDER);
                this.drawEraseCount(n10, n, n7);
            }
            else {
                Theme.fill(n, n7, n8, n9, 805358322);
                Theme.border(n, n7, n8, n9, -16725262);
            }
            return;
        }
        if (this.eraserMode() && !GuiICWbEx.shiftHeld() && !this.gestureAborted) {
            // 0.4.5: with the eraser selected the part under the cursor is outlined in
            // the same danger tint, so a single click is not a blind action either.
            // 0.4.6: not while shift is held (that is the vanilla board pan).
            int n11 = this.gridXAt(this.mouseX);
            int n12 = this.gridYAt(this.mouseY);
            if (Clipboard.partAt(integratedCircuit, n11, n12) != null) {
                int n13 = this.rootXOfCell(n11);
                int n14 = this.rootYOfCell(n12);
                int n15 = (int)Math.round(d);
                Theme.fill(n13, n14, n15, n15, ERASE_FILL);
                Theme.border(n13, n14, n15, n15, ERASE_BORDER);
            }
        }
        if (this.selected.isEmpty()) {
            return;
        }
        if (this.selected.size() > 600) {
            int n;
            int n10;
            int n11 = Integer.MAX_VALUE;
            int n12 = Integer.MAX_VALUE;
            int n13 = Integer.MIN_VALUE;
            int n14 = Integer.MIN_VALUE;
            for (Long l : this.selected) {
                n10 = Clipboard.kx(l);
                n = Clipboard.ky(l);
                if (n10 < n11) {
                    n11 = n10;
                }
                if (n < n12) {
                    n12 = n;
                }
                if (n10 > n13) {
                    n13 = n10;
                }
                if (n <= n14) continue;
                n14 = n;
            }
            int n15 = this.rootXOfCell(n11);
            int n16 = this.rootYOfCell(n12);
            n10 = (int)Math.round((double)(n13 - n11 + 1) * d);
            n = (int)Math.round((double)(n14 - n12 + 1) * d);
            Theme.fill(n15, n16, n10, n, 805358322);
            Theme.border(n15, n16, n10, n, -16725262);
            return;
        }
        for (Long l : this.selected) {
            int n;
            int n17 = Clipboard.kx(l);
            if (Clipboard.partAt(integratedCircuit, n17, n = Clipboard.ky(l)) == null) continue;
            int n18 = this.rootXOfCell(n17);
            int n19 = this.rootYOfCell(n);
            int n20 = (int)Math.round(d);
            Theme.fill(n18, n19, n20, n20, 1342229234);
        }
    }

    /**
     * 0.4.5: tint every cell inside a root-space rectangle that currently holds a part,
     * and return how many that is.
     *
     * <p>The cells come from exactly the same grid conversion {@link #eraseRect} uses
     * for the real deletion (and the loop is clamped to the board, so a drag that runs
     * off the edge cannot walk a huge index range), which is what makes the preview
     * trustworthy: what is tinted is precisely what the release removes.
     */
    private int tintEraseCells(IntegratedCircuit integratedCircuit, int rx0, int ry0, int rx1, int ry1) {
        Size size = integratedCircuit.size();
        int n = Math.max(0, this.gridXAt(Math.min(rx0, rx1)));
        int n2 = Math.min(size.width() - 1, this.gridXAt(Math.max(rx0, rx1)));
        int n3 = Math.max(0, this.gridYAt(Math.min(ry0, ry1)));
        int n4 = Math.min(size.height() - 1, this.gridYAt(Math.max(ry0, ry1)));
        int n5 = (int)Math.round(this.cellPx());
        int n6 = 0;
        for (int i = n3; i <= n4; ++i) {
            for (int j = n; j <= n2; ++j) {
                if (Clipboard.partAt(integratedCircuit, j, i) == null) continue;
                Theme.fill(this.rootXOfCell(j), this.rootYOfCell(i), n5, n5, ERASE_FILL);
                ++n6;
            }
        }
        return n6;
    }

    /**
     * 0.4.5: how many parts the eraser marquee is about to remove - printed above the
     * box (or inside its top-left corner when there is no room above), so a drag that
     * is about to take out half the board says so before the button comes up.
     */
    private void drawEraseCount(int n, int n2, int n3) {
        if (n <= 0) {
            return;
        }
        FontRenderer fontRenderer = this.fontRenderer();
        if (fontRenderer == null) {
            return;
        }
        String string = Lang.t("st.erase_preview", String.valueOf(n));
        int n4 = n3 - 10 >= 1 ? n3 - 10 : n3 + 3;
        fontRenderer.func_78276_b(string, n2 + 2, n4, ERASE_BORDER);
    }
}


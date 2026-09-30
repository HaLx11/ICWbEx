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
import java.util.Set;
import mrtjp.core.gui.MCButtonNode;
import mrtjp.core.gui.TNode;
import mrtjp.core.vec.Point;
import mrtjp.core.vec.Size;
import mrtjp.projectred.fabrication.BundledCableICPart;
import mrtjp.projectred.fabrication.CircuitOp;
import mrtjp.projectred.fabrication.GuiICWorkbench;
import mrtjp.projectred.fabrication.IntegratedCircuit;
import mrtjp.projectred.fabrication.PrefboardNode;
import mrtjp.projectred.fabrication.TileICWorkbench;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
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
    private static final int TXT_HINT = -11908526;
    private static final int TXT_STATUS = -16097672;
    private static final int TXT_ERROR = -6676705;
    private static final int PER_CELL_DRAW_LIMIT = 600;
    private final TileICWorkbench tile;
    private final UndoBuffer undo = new UndoBuffer();
    private final Set<Long> selected = new LinkedHashSet<Long>();
    private Clipboard clip;
    private boolean pasteMode;
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
    private MCButtonNode bpBtn;

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
        return GuiICWbEx.ctrlHeld() || this.pasteMode || this.helpVisible;
    }

    public void frameUpdate_Impl(Point point, float f) {
        super.frameUpdate_Impl(point, f);
        ++this.tick;
        if (this.statusTtl > 0) {
            --this.statusTtl;
        }
        this.mouseX = this.rootX(point);
        this.mouseY = this.rootY(point);
        this.applyIntercept();
        this.pollMiddleClick(point);
        IntegratedCircuit integratedCircuit = this.ic();
        if (integratedCircuit != null && !this.dragging) {
            if (Sync.echoHold()) {
                // A whole-circuit desc echo may still rebuild the client board
                // (blueprint load / board growth with bundled cables); do not
                // record that transient state into the undo buffer.
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
                this.stampAt(this.gridXAt(n2), this.gridYAt(n3));
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
            } else {
                this.selected.clear();
            }
            return true;
        }
        return false;
    }

    public boolean mouseDragged_Impl(Point point, int n, long l, boolean bl) {
        if (this.dragging) {
            this.dragX1 = this.rootX(point);
            this.dragY1 = this.rootY(point);
            return true;
        }
        return super.mouseDragged_Impl(point, n, l, bl);
    }

    public boolean mouseReleased_Impl(Point point, int n, boolean bl) {
        if (this.dragging) {
            this.dragging = false;
            this.dragX1 = this.rootX(point);
            this.dragY1 = this.rootY(point);
            this.applyMarquee();
            return true;
        }
        return super.mouseReleased_Impl(point, n, bl);
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
        this.pasteMode = true;
        this.say(Lang.t("st.paste_ready"));
    }

    private void stampAt(int n, int n2) {
        boolean bl;
        IntegratedCircuit integratedCircuit = this.ic();
        if (integratedCircuit == null || this.clip == null || this.clip.isEmpty()) {
            this.pasteMode = false;
            return;
        }
        if (n < 0 || n2 < 0) {
            this.say(Lang.t("st.paste_out"), true);
            return;
        }
        Size size = integratedCircuit.size();
        int n3 = n + this.clip.width();
        int n4 = n2 + this.clip.height();
        boolean bl2 = bl = n3 > size.width() || n4 > size.height();
        if (bl) {
            integratedCircuit.size_$eq(new Size(Math.max(size.width(), n3), Math.max(size.height(), n4)));
        }
        int n5 = this.clip.stamp(integratedCircuit, n, n2);
        int skipped = this.clip.lastSkippedBundled;
        integratedCircuit.refreshErrors();
        Sync.toServer(this.tile, integratedCircuit);
        this.undo.commitIfChanged(integratedCircuit);
        if (n5 == 0 && skipped == 0) {
            this.say(Lang.t("st.paste_none"), true);
        } else {
            String base;
            if (bl) {
                Size size2 = integratedCircuit.size();
                base = Lang.t("st.grew", String.valueOf(n5), String.valueOf(size2.width()), String.valueOf(size2.height()));
            } else {
                base = Lang.t("st.pasted", String.valueOf(n5));
            }
            if (skipped > 0) {
                base = base + " " + Lang.t("st.paste_skip", String.valueOf(n5), String.valueOf(skipped));
            }
            this.say(base, n5 == 0);
        }
    }

    private void doDelete() {
        IntegratedCircuit integratedCircuit = this.ic();
        if (integratedCircuit == null) {
            return;
        }
        if (this.selected.isEmpty()) {
            this.say(Lang.t("st.no_sel"), true);
            return;
        }
        int n = 0;
        for (Long l : new ArrayList<Long>(this.selected)) {
            int n2;
            int n3 = Clipboard.kx(l);
            if (Clipboard.partAt(integratedCircuit, n3, n2 = Clipboard.ky(l)) == null) continue;
            integratedCircuit.removePart(n3, n2);
            ++n;
        }
        this.selected.clear();
        if (n == 0) {
            this.say(Lang.t("st.copy_empty"), true);
            return;
        }
        integratedCircuit.refreshErrors();
        Sync.toServer(this.tile, integratedCircuit);
        this.undo.commitIfChanged(integratedCircuit);
        this.say(Lang.t("st.deleted", String.valueOf(n)));
    }

    private void doUndo() {
        IntegratedCircuit integratedCircuit = this.ic();
        if (integratedCircuit == null || !this.undo.undo(integratedCircuit, this.tile)) {
            this.say(Lang.t("st.no_undo"), true);
            return;
        }
        this.afterHistory();
        this.say(Lang.t("st.undo"));
    }

    private void doRedo() {
        IntegratedCircuit integratedCircuit = this.ic();
        if (integratedCircuit == null || !this.undo.redo(integratedCircuit, this.tile)) {
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
        if (integratedCircuit != null) {
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
                return true;
            }
            if (n == 211 || n == 14) {
                this.doDelete();
                return true;
            }
            if (n == 1) {
                if (this.helpVisible) {
                    this.helpVisible = false;
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
        this.statusTtl = 110;
    }

    public void drawBack_Impl(Point point, float f) {
        int n;
        String string;
        super.drawBack_Impl(point, f);
        FontRenderer fontRenderer = this.fontRenderer();
        if (this.statusTtl > 0 && this.status.length() > 0) {
            string = this.status;
            n = this.statusIsError ? -6676705 : -16097672;
        } else if (this.pasteMode && this.clip != null) {
            string = Lang.t("hint.paste", String.valueOf(this.clip.size()));
            n = -16097672;
        } else if (!this.selected.isEmpty()) {
            string = Lang.t("hint.sel", String.valueOf(this.selected.size()));
            n = -16097672;
        } else {
            string = Lang.t("hint.idle");
            n = -11908526;
        }
        this.drawWrapped(fontRenderer, string, 262, 44, 62, n, 7);
    }

    private void drawHelp() {
        int n;
        FontRenderer fontRenderer = this.fontRenderer();
        int n2 = this.size().width();
        int n3 = this.size().height();
        String string = Lang.t("help.body");
        String string2 = Lang.t("help.foot");
        int n4 = 1;
        for (n = 0; n < string.length(); ++n) {
            if (string.charAt(n) != '\n') continue;
            ++n4;
        }
        int n5 = 9;
        n = n3 - 8 - 26 - 18;
        if (n4 * n5 > n) {
            n5 = Math.max(7, n / Math.max(1, n4));
        }
        int n6 = Math.min(26 + n4 * n5 + 18, n3 - 8);
        Theme.fill(0.0, 0.0, n2, n3, -1979382264);
        Theme.panel(4.0, 4.0, n2 - 8, n6);
        Theme.header(6.0, 6.0, n2 - 12, 14.0);
        fontRenderer.func_78276_b(Lang.t("help.title"), 12, 9, -16725262);
        this.drawWrapped(fontRenderer, string, 12, 26, n2 - 24, -3090208, n4 + 6, n5);
        fontRenderer.func_78276_b(string2, 12, 4 + n6 - 14, -7366493);
    }

    private void drawWrapped(FontRenderer fontRenderer, String string, int n, int n2, int n3, int n4, int n5) {
        this.drawWrapped(fontRenderer, string, n, n2, n3, n4, n5, 9);
    }

    private void drawWrapped(FontRenderer fontRenderer, String string, int n, int n2, int n3, int n4, int n5, int n6) {
        if (string == null || string.length() == 0) {
            return;
        }
        StringBuilder stringBuilder = new StringBuilder();
        int n7 = n2;
        int n8 = 0;
        for (int i = 0; i < string.length() && n8 < n5; ++i) {
            char c = string.charAt(i);
            if (c == '\n') {
                fontRenderer.func_78276_b(stringBuilder.toString(), n, n7, n4);
                stringBuilder.setLength(0);
                n7 += n6;
                ++n8;
                continue;
            }
            stringBuilder.append(c);
            if (fontRenderer.func_78256_a(stringBuilder.toString()) <= n3 || stringBuilder.length() <= 1) continue;
            char c2 = stringBuilder.charAt(stringBuilder.length() - 1);
            stringBuilder.setLength(stringBuilder.length() - 1);
            fontRenderer.func_78276_b(stringBuilder.toString(), n, n7, n4);
            stringBuilder.setLength(0);
            stringBuilder.append(c2);
            n7 += n6;
            ++n8;
        }
        if (n8 < n5 && stringBuilder.length() > 0) {
            fontRenderer.func_78276_b(stringBuilder.toString(), n, n7, n4);
        }
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
            int n = this.gridXAt(this.mouseX);
            int n2 = this.gridYAt(this.mouseY);
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
            Theme.fill(n, n7, n8, n9, 805358322);
            Theme.border(n, n7, n8, n9, -16725262);
            return;
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
}


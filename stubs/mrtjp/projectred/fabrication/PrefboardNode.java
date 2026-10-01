package mrtjp.projectred.fabrication;

import mrtjp.core.gui.TNode;
import mrtjp.core.vec.Point;

public class PrefboardNode implements TNode {
    public TNode parent() {
        return null;
    }

    public Point position() {
        return null;
    }

    public boolean isRoot() {
        return false;
    }

    public Point convertPointFromScreen(Point point) {
        return null;
    }

    public boolean rayTest(Point point) {
        return false;
    }

    public void addChild(TNode node) {
    }

    public double scale() {
        return 0.0;
    }

    public int sizeMult() {
        return 0;
    }

    public void doPickOp() {
    }

    public boolean userInteractionEnabled() {
        return false;
    }

    public void userInteractionEnabled_$eq(boolean value) {
    }

    public CircuitOp currentOp() {
        return null;
    }

    // --- added for 0.4.5 (zoom while the eraser has taken the mouse); descriptors
    // verified via javap on PR 4.12.44. Vanilla drives these from its own
    // mouseScrolled_Impl, which needs the cursor converted through two coordinate
    // spaces - calling the node's own scale steps avoids that entirely.
    public void incScale() {
    }

    public void decScale() {
    }
}

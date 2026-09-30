package mrtjp.core.gui;

import mrtjp.core.vec.Point;

public interface TNode {
    TNode parent();

    Point position();

    boolean isRoot();

    Point convertPointFromScreen(Point point);

    boolean rayTest(Point point);

    void addChild(TNode node);
}

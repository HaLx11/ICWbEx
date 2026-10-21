package mrtjp.projectred.fabrication;

/**
 * Scala trait (interface) over the four-border wire mask.
 *
 * <p>0.2.7 stub; {@code updateConns()} descriptor {@code ()Z} verified via javap on
 * PR 4.12.44 (returns boolean, not void).
 *
 * <p>0.4.9 added {@code connMap()} / {@code connMap_$eq(byte)} / {@code discover(int)}
 * so {@code Blueprints.reinitConnections()} can rebuild the mask from geometry without
 * going through {@code updateConns()} (which fires {@code onMaskChanged()} and would
 * append bytes to the tile's outgoing stream). All three are read straight off the real
 * trait bytecode:
 *
 * <pre>
 *   public byte connMap();
 *   public void connMap_$eq(byte);          // @scala.runtime.TraitSetter
 *   public boolean discover(int);           // asks the neighbour on side r to link back
 * </pre>
 */
public interface TConnectableICPart {
    boolean updateConns();

    /** Current four-border mask: bit r set == connected towards side r. */
    byte connMap();

    /** Overwrite the mask. Does not fire {@code onMaskChanged()} by itself. */
    void connMap_$eq(byte mask);

    /**
     * Test one side: is there a neighbour there, may this part connect to it, and does
     * the neighbour accept the reverse link? Note this <b>mutates the neighbour</b> when
     * it succeeds (the neighbour's {@code connect()} sets its own bit).
     */
    boolean discover(int r);
}

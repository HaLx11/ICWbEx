package scala.collection;

/** Signature stub of scala.collection.Iterator (trait -> interface).
 *  Signatures taken from scala-library used by GTNH 1.7.10; only what
 *  icwbe.Blueprints needs (enhanced-for/iterator loops over parts()). */
public interface Iterator {
    boolean hasNext();

    Object next();
}

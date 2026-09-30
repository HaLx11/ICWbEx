package scala.runtime;

/**
 * Compile-time stub only. javac needs this class resolvable when it reads the
 * real icwbe.ScalaH$Act class file (its final apply() returns BoxedUnit).
 * The real scala-library class is used at runtime.
 */
public final class BoxedUnit {
    public static final BoxedUnit UNIT = new BoxedUnit();

    private BoxedUnit() {
    }
}

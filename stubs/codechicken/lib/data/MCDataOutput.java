package codechicken.lib.data;

/**
 * 0.2.8 stub. The real MCDataOutput (CodeChickenLib, shipped inside CodeChickenCore)
 * is an INTERFACE - 0.2.7 shipped this stub as an abstract class, so every call site
 * was compiled to invokevirtual and blew up at runtime with
 * IncompatibleClassChangeError: "Found interface codechicken.lib.data.MCDataOutput,
 * but class was expected". That failure is what kept wire ops from ever reaching the
 * server in 0.2.7 (cables vanished on load and were lost from the save).
 * Verified with javap on CodeChickenCore-1.4.22 from the running instance:
 * "public interface codechicken.lib.data.MCDataOutput".
 *
 * Only the methods our own source calls are declared (writeByte); PR's own classes
 * resolve their richer calls against the real interface at runtime.
 */
public interface MCDataOutput {
    MCDataOutput writeByte(int b);
}

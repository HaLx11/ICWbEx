package cpw.mods.fml.common.gameevent;

public class TickEvent {
    public enum Phase {
        START,
        END
    }

    public static class ClientTickEvent extends TickEvent {
    }
}

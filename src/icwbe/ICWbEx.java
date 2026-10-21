/*
 * ICWbEx mod entry point.
 *
 * 0.4.9: this class used to live only in the base jar. It is now one of ours so
 * onLoadComplete can also install HeldChipGuard, which parks a running timer on
 * the client's held chip before ProjectRed loads it during placement.
 *
 * Everything else here is byte-for-byte the base jar's behaviour: same @Mod
 * attributes (acceptableRemoteVersions="*" is what lets a client-only mod join a
 * server that does not have it), same version constants, same ClientSetup call.
 * The literal "0.2.6" is rewritten to NEW_VER by build.py, so keep it.
 */
package icwbe;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.relauncher.Side;

@Mod(modid = "icwbe", name = "IC Workbench Enhanced", version = "0.2.6",
    acceptableRemoteVersions = "*", acceptedMinecraftVersions = "[1.7.10]")
public class ICWbEx {
    public static final String MODID = "icwbe";
    public static final String NAME = "IC Workbench Enhanced";
    public static final String VERSION = "0.2.6";

    @Mod.EventHandler
    public void onLoadComplete(FMLLoadCompleteEvent event) {
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) {
            return;
        }
        ClientSetup.install();
        HeldChipGuard.install();
    }
}

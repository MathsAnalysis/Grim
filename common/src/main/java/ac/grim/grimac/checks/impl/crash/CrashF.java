package ac.grim.grimac.checks.impl.crash;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.impl.verbose.VerboseCodecs;
import ac.grim.grimac.checks.type.PreViaPacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow.WindowClickType;

@CheckData(name = "CrashF", stableKey = "grim.crash.button_crash", description = "Sent an inventory click with an invalid button or slot value")
public class CrashF extends Check implements PreViaPacketReceiveListener {
    private static final Verbose V =
            Verbose.of("clickType={clicktype}, button={sint}[, slot={sint}]");

    public CrashF(GrimPlayer player) {
        super(player);
    }

    private static final com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon[] HANDLED_PACKETS = new com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon[]{
            PacketType.Play.Client.CLICK_WINDOW
    };

    @Override
    public com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon[] getHandledPacketTypes() {
        return HANDLED_PACKETS;
    }

    @Override
    public void onPreViaPacketReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.CLICK_WINDOW) {
            WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);
            WindowClickType clickType = click.getWindowClickType();
            int button = click.getButton();
            int windowId = click.getWindowId();
            int slot = click.getSlot();

            if ((clickType == WindowClickType.QUICK_MOVE || clickType == WindowClickType.SWAP) && windowId >= 0 && button < 0) {
                int clickTypeId = VerboseCodecs.enumId(clickType);
                if (flag(V.write(verbose()).uint(clickTypeId).sint(button).bool(false).sint(0))) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            } else if (windowId >= 0 && clickType == WindowClickType.SWAP && slot < 0) {
                int clickTypeId = VerboseCodecs.enumId(clickType);
                if (flag(V.write(verbose()).uint(clickTypeId).sint(button).bool(true).sint(slot))) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            }
        }
    }
}

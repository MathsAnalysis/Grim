package ac.grim.grimac.checks.impl.misc;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.PacketSendListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientKeepAlive;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerKeepAlive;

import java.util.LinkedList;

@CheckData(name = "KeepAliveOrder", stableKey = "grim.badpackets.invalid_keepalive", description = "Responded with a keepalive ID that was not sent by the server or skipped keepalives")
public class KeepAliveOrder extends Check implements PacketReceiveListener, PacketSendListener {
    private static final Verbose V = Verbose.of("[id={slong}|skipped={sint}]");

    private final LinkedList<Long> keepalives = new LinkedList<>();

    public KeepAliveOrder(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.KEEP_ALIVE) {
            keepalives.add(new WrapperPlayServerKeepAlive(event).getId());
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.KEEP_ALIVE) {
            final long id = new WrapperPlayClientKeepAlive(event).getId();

            int skipped = -1;

            for (long keepalive : keepalives) {
                if (keepalive == id) {
                    // Found the ID, remove stuff until we get to it (to stop very slow memory leaks)
                    Long data;
                    do {
                        data = keepalives.poll();
                        skipped++;
                    } while (data != null && data != id);

                    break;
                }
            }

            boolean flagged = skipped == -1
                    ? flag(V.write(verbose()).bool(true).slong(id).sint(0))
                    : skipped != 0 && flag(V.write(verbose()).bool(false).slong(id).sint(skipped));

            if (flagged && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}

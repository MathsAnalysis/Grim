package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.PacketSendListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientKeepAlive;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerKeepAlive;
import it.unimi.dsi.fastutil.longs.LongArrayList;

@CheckData(name = "BadPacketsO", stableKey = "grim.badpackets.invalid_keepalive", description = "Responded with a keepalive ID that was not sent by the server")
public class BadPacketsO extends Check implements PacketReceiveListener, PacketSendListener {

    private static final Verbose V = Verbose.of("id={slong}");
    private static final int MAX_PENDING_KEEPALIVES = 64;

    private static final PacketTypeCommon[] RECEIVE_PACKETS = new PacketTypeCommon[]{
            PacketType.Play.Client.KEEP_ALIVE
    };

    private static final PacketTypeCommon[] SEND_PACKETS = new PacketTypeCommon[]{
            PacketType.Play.Server.KEEP_ALIVE
    };

    private final LongArrayList keepalives = new LongArrayList();

    public BadPacketsO(GrimPlayer player) {
        super(player);
    }

    @Override
    public PacketTypeCommon[] getHandledPacketTypes() {
        return RECEIVE_PACKETS;
    }

    @Override
    public PacketTypeCommon[] getHandledSendPacketTypes() {
        return SEND_PACKETS;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.KEEP_ALIVE) {
            if (keepalives.size() >= MAX_PENDING_KEEPALIVES) {
                keepalives.removeElements(0, keepalives.size() - (MAX_PENDING_KEEPALIVES - 1));
            }
            keepalives.add(new WrapperPlayServerKeepAlive(event).getId());
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.KEEP_ALIVE) {
            final long id = new WrapperPlayClientKeepAlive(event).getId();

            int index = keepalives.indexOf(id);
            if (index != -1) {
                keepalives.removeElements(0, index + 1);
                return;
            }

            if (flag(V.write(verbose()).slong(id)) && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}

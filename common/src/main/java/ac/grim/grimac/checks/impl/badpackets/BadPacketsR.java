package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;

@CheckData(name = "BadPacketsR", stableKey = "grim.badpackets.position_starvation", description = "Stopped sending position updates while still responding to transactions", decay = 0.25, experimental = true)
public class BadPacketsR extends Check implements PacketReceiveListener {

    private static final Verbose V = Verbose.of("time={ulong}ms, lst={ulong}ms, positions={uint}");

    private static final PacketTypeCommon[] HANDLED_PACKETS = new PacketTypeCommon[]{
            PacketType.Play.Client.PONG,
            PacketType.Play.Client.WINDOW_CONFIRMATION,
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
            PacketType.Play.Client.PLAYER_POSITION,
            PacketType.Play.Client.STEER_VEHICLE,
            PacketType.Play.Client.VEHICLE_MOVE
    };

    private int positions;
    private long clock;
    private long lastTransTime;
    private int oldTransId;

    public BadPacketsR(final GrimPlayer player) {
        super(player);
    }

    @Override
    public PacketTypeCommon[] getHandledPacketTypes() {
        return HANDLED_PACKETS;
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        final PacketTypeCommon packetType = event.getPacketType();

        if (isTransaction(packetType) && player.packetStateData.lastTransactionPacketWasValid) {
            long ms = (player.getPlayerClockAtLeast() - clock) / 1000000L;
            long diff = (System.currentTimeMillis() - lastTransTime);
            if (diff > 2000 && ms > 2000) {
                if (positions == 0 && clock != 0 && player.cameraEntity.isSelf() && !player.compensatedEntities.self.isDead) {
                    flag(V.write(verbose()).ulong(ms).ulong(diff).uint(positions));
                } else {
                    reward();
                }
                player.compensatedEntities.entitiesRemovedThisTick.clear();
                player.compensatedWorld.removeInvalidPistonLikeStuff(oldTransId);
                positions = 0;
                clock = player.getPlayerClockAtLeast();
                lastTransTime = System.currentTimeMillis();
                oldTransId = player.lastTransactionSent.get();
            }
        }

        if ((packetType == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION || packetType == PacketType.Play.Client.PLAYER_POSITION) && !player.inVehicle()) {
            positions++;
        } else if ((packetType == PacketType.Play.Client.STEER_VEHICLE || packetType == PacketType.Play.Client.VEHICLE_MOVE) && player.inVehicle()) {
            positions++;
        }
    }
}

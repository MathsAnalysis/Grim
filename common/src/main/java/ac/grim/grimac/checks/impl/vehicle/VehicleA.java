package ac.grim.grimac.checks.impl.vehicle;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PreViaPacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSteerVehicle;

@CheckData(name = "VehicleA", stableKey = "grim.vehicle.impossible_input", description = "Impossible input values")
public class VehicleA extends Check implements PreViaPacketReceiveListener {
    private static final Verbose V = Verbose.of("forwards={f32}, sideways={f32}");

    public VehicleA(GrimPlayer player) {
        super(player);
    }

    private static final com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon[] HANDLED_PACKETS = new com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon[]{
            PacketType.Play.Client.STEER_VEHICLE
    };

    @Override
    public com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon[] getHandledPacketTypes() {
        return HANDLED_PACKETS;
    }

    @Override
    public void onPreViaPacketReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.STEER_VEHICLE) {
            final WrapperPlayClientSteerVehicle packet = new WrapperPlayClientSteerVehicle(event);

            if (Math.abs(packet.getForward()) > 0.98f || Math.abs(packet.getSideways()) > 0.98f) {
                if (flag(V.write(verbose()).f32(packet.getForward()).f32(packet.getSideways())) && shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            }
        }
    }
}

package ac.grim.grimac.checks.impl.timer;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketSendListener;
import ac.grim.grimac.checks.type.PreViaPacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;

@CheckData(name = "BlinkB", stableKey = "grim.timer.blink_b", description = "Did not send a position update within the position reminder interval", experimental = true, setback = 0)
public class BlinkB extends Check implements PreViaPacketReceiveListener, PacketSendListener {
    private static final Verbose V = Verbose.of("ticks={uint}, windows={uint}");

    private static final int MAX_TICKS = 22;
    private static final int FLAG_WINDOWS = 2;
    private static final long WINDOW_EXPIRE_MS = 60000;
    private static final int VEHICLE_GRACE_TICKS = 5;
    private static final int LOAD_TIMEOUT_TICKS = 80;
    private static final long JOIN_GRACE_MS = 5000;

    private int ticks;
    private int windows;
    private long lastWindowTime;
    private int ticksSinceVehicle = VEHICLE_GRACE_TICKS;
    private boolean awaitingLoad = true;
    private int loadTransaction;
    private int ticksSinceLoadAcknowledged;

    public BlinkB(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_4);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (player.disableGrim) return;

        if (event.getPacketType() == PacketType.Play.Server.RESPAWN || event.getPacketType() == PacketType.Play.Server.JOIN_GAME) {
            awaitingLoad = true;
            loadTransaction = player.lastTransactionSent.get();
            ticksSinceLoadAcknowledged = 0;
            ticks = 0;
        }
    }

    private static final PacketTypeCommon[] HANDLED_PACKETS = new PacketTypeCommon[]{
            PacketType.Play.Client.PLAYER_LOADED,
            PacketType.Play.Client.PLAYER_POSITION,
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
            PacketType.Play.Client.CLIENT_TICK_END
    };

    @Override
    public PacketTypeCommon[] getHandledPacketTypes() {
        return HANDLED_PACKETS;
    }

    @Override
    public void onPreViaPacketReceive(PacketReceiveEvent event) {
        if (player.disableGrim) return;

        PacketTypeCommon packetType = event.getPacketType();
        if (packetType == PacketType.Play.Client.PLAYER_LOADED) {
            if (player.lastTransactionReceived.get() > loadTransaction) {
                awaitingLoad = false;
            }
            return;
        }

        if (packetType == PacketType.Play.Client.PLAYER_POSITION || packetType == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            closeWindow(event);
            return;
        }

        if (packetType != PacketType.Play.Client.CLIENT_TICK_END) return;

        if (awaitingLoad && player.lastTransactionReceived.get() > loadTransaction
                && ++ticksSinceLoadAcknowledged > LOAD_TIMEOUT_TICKS) {
            awaitingLoad = false;
        }

        if (player.inVehicle()) {
            ticksSinceVehicle = 0;
        } else if (ticksSinceVehicle < VEHICLE_GRACE_TICKS) {
            ticksSinceVehicle++;
        }

        if (isExempt()) {
            ticks = 0;
            return;
        }

        ticks++;
    }

    private boolean isExempt() {
        return awaitingLoad
                || System.currentTimeMillis() - player.joinTime < JOIN_GRACE_MS
                || !player.cameraEntity.isSelf()
                || ticksSinceVehicle < VEHICLE_GRACE_TICKS
                || player.gamemode == GameMode.SPECTATOR
                || player.compensatedEntities.self.isDead
                || !player.getSetbackTeleportUtil().pendingTeleports.isEmpty()
                || player.getSetbackTeleportUtil().insideUnloadedChunk();
    }

    private void closeWindow(PacketReceiveEvent event) {
        int windowTicks = ticks;
        ticks = 0;

        if (windowTicks <= MAX_TICKS) return;

        long now = System.currentTimeMillis();
        if (now - lastWindowTime > WINDOW_EXPIRE_MS) {
            windows = 0;
        }
        lastWindowTime = now;

        if (++windows >= FLAG_WINDOWS) {
            flagWithSetback(V.write(verbose()).uint(windowTicks).uint(windows));
            windows = 0;
            if (shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}

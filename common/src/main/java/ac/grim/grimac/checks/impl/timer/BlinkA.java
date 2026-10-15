package ac.grim.grimac.checks.impl.timer;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PrePredictionPacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;

@CheckData(name = "BlinkA", stableKey = "grim.timer.blink_a", description = "Sent more movement packets than elapsed time allows since the last transaction", experimental = true, setback = 0)
public class BlinkA extends Check implements PrePredictionPacketReceiveListener {
    private static final Verbose V = Verbose.of("received={uint}, elapsed={f64:%.2f}, tolerance={f64:%.2f}, buffer={uint}");

    private static final long JOIN_GRACE_MS = 5000;
    private static final long SKIPPED_EXEMPT_MS = 1000;
    private static final double LONG_WINDOW_TOLERANCE = 0.05;
    private static final double BURST_SLACK = 13;
    private static final int FLAG_BUFFER = 3;

    private boolean anchored;
    private long anchor;
    private int received;
    private int lastTransactionReceived;
    private int buffer;
    private boolean flaggedThisWindow;
    private long exemptUntil;

    public BlinkA(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPrePredictionPacketReceive(PacketReceiveEvent event) {
        if (player.disableGrim) return;

        PacketTypeCommon packetType = event.getPacketType();
        if (isTransaction(packetType)) {
            if (player.packetStateData.lastTransactionPacketWasValid) {
                onValidTransaction();
            }
            return;
        }

        if (!isTickPacket(packetType)) return;

        received++;

        if (!anchored || player.lastTransactionSent.get() == 0) return;

        long now = System.currentTimeMillis();
        if (now - player.joinTime < JOIN_GRACE_MS || now < exemptUntil) return;

        double elapsedTicks = (System.nanoTime() - anchor) / 50e6;
        if (!exceedsBurstBound(received, elapsedTicks)) return;

        buffer++;
        if (buffer >= FLAG_BUFFER) {
            if (!flaggedThisWindow) {
                flaggedThisWindow = true;
                flagWithSetback(V.write(verbose()).uint(received).f64(elapsedTicks).f64(LONG_WINDOW_TOLERANCE * elapsedTicks).uint(buffer));
            } else {
                setbackIfAboveSetbackVL();
            }

            if (shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }

    private void onValidTransaction() {
        int transactionReceived = player.lastTransactionReceived.get();
        if (anchored && transactionReceived - lastTransactionReceived > 1) {
            exemptUntil = System.currentTimeMillis() + SKIPPED_EXEMPT_MS;
        }

        lastTransactionReceived = transactionReceived;
        anchor = player.getPlayerClockAtLeast();
        anchored = true;
        received = 0;
        buffer = Math.max(0, buffer - 1);
        flaggedThisWindow = false;
    }

    private boolean exceedsBurstBound(int received, double elapsedTicks) {
        return received > elapsedTicks + BURST_SLACK + LONG_WINDOW_TOLERANCE * elapsedTicks;
    }
}

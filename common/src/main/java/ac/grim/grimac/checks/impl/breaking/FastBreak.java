package ac.grim.grimac.checks.impl.breaking;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.impl.verbose.VerboseCodecs;
import ac.grim.grimac.checks.type.BlockBreakListener;
import ac.grim.grimac.checks.type.PreViaPacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockBreak;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.nmsutil.BlockBreakSpeed;
import ac.grim.grimac.utils.viaversion.ViaVersionUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

import java.util.Set;

@CheckData(name = "FastBreak", stableKey = "grim.breaking.fast_break", description = "Breaking blocks too quickly")
public class FastBreak extends Check implements BlockBreakListener, PreViaPacketReceiveListener {

    private static final Verbose V =
            Verbose.of("[delay={ulong}ms|diff={f64:%.1f}ms, balance={f64:%.1f}ms], type={block}");

    private static final PacketTypeCommon[] HANDLED_PACKETS = new PacketTypeCommon[]{
            PacketType.Play.Client.PLAYER_FLYING,
            PacketType.Play.Client.PLAYER_POSITION,
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
            PacketType.Play.Client.PLAYER_ROTATION,
            PacketType.Play.Client.ANIMATION,
            PacketType.Play.Client.PUNCH
    };

    private static final Set<StateType> EXEMPT_STATES = Set.of();
    private final boolean clientOlderThanServer = PacketEvents.getAPI().getServerManager().getVersion().getProtocolVersion() > player.getClientVersion().getProtocolVersion();

    Vector3i targetBlockPosition;
    double maximumBlockDamage;
    long lastFinishBreak;
    boolean lastWasInstabreak;
    long startBreak;

    double blockBreakBalance;
    double blockDelayBalance;

    public FastBreak(GrimPlayer player) {
        super(player);
    }

    @Override
    public PacketTypeCommon[] getHandledPacketTypes() {
        return HANDLED_PACKETS;
    }

    @Override
    public void onBlockBreak(BlockBreak blockBreak) {
        if (blockBreak.action == DiggingAction.CANCELLED_DIGGING) {
            targetBlockPosition = null;
            return;
        }

        if (blockBreak.action == DiggingAction.START_DIGGING) {
            if (!ViaVersionUtil.isAvailable) {
                final WrappedBlockState defaultState = WrappedBlockState.getDefaultState(player.getClientVersion(), blockBreak.block.getType());
                if (defaultState.getType() == StateTypes.AIR || EXEMPT_STATES.contains(defaultState.getType())) {
                    return;
                }
            }

            WrappedBlockState block = clientOlderThanServer ? WrappedBlockState.getByGlobalId(player.getClientVersion(), player.getViaTranslatedClientBlockID(blockBreak.block.getGlobalId())) : blockBreak.block;

            startBreak = System.currentTimeMillis() - (targetBlockPosition == null ? 50 : 0);
            targetBlockPosition = blockBreak.position;

            maximumBlockDamage = BlockBreakSpeed.getBlockDamage(player, block);
            boolean isInstaBreak = maximumBlockDamage >= 1.0;

            double breakDelay = System.currentTimeMillis() - lastFinishBreak;

            if (lastWasInstabreak || isInstaBreak) {
                blockDelayBalance *= 0.9;
            } else if (lastFinishBreak > 0) {
                if (breakDelay >= 275) {
                    blockDelayBalance *= 0.9;
                } else {
                    blockDelayBalance += 300 - breakDelay;
                }

                if (blockDelayBalance > 1000) {
                    int type = VerboseCodecs.block(blockBreak.block.getType(), player.getClientVersion());
                    if (flag(V.write(verbose()).bool(true).ulong((long) breakDelay).f64(0).f64(0).sint(type)) && shouldModifyPackets()) {
                        blockBreak.cancel();
                    }
                }
            }

            if (isInstaBreak) {
                lastFinishBreak = System.currentTimeMillis();
                lastWasInstabreak = true;
                targetBlockPosition = null;
            }

            clampBalance();
        }

        if (blockBreak.action == DiggingAction.FINISHED_DIGGING && targetBlockPosition != null) {
            boolean isInstaBreak = maximumBlockDamage >= 1.0;
            if (!isInstaBreak) {
                double predictedTime = Math.ceil(1 / maximumBlockDamage) * 50;
                double realTime = System.currentTimeMillis() - startBreak;
                double diff = predictedTime - realTime;

                clampBalance();

                if (diff < 25) {
                    blockBreakBalance *= 0.9;
                } else {
                    blockBreakBalance += diff;
                }

                if (blockBreakBalance > 1000) {
                    int type = VerboseCodecs.block(blockBreak.block.getType(), player.getClientVersion());
                    if (flag(V.write(verbose()).bool(false).ulong(0).f64(diff).f64(blockBreakBalance).sint(type)) && shouldModifyPackets()) {
                        blockBreak.cancel();
                    }
                }
            } else {
                blockBreakBalance *= 0.9;
            }

            lastFinishBreak = startBreak = System.currentTimeMillis();
            lastWasInstabreak = isInstaBreak;
            targetBlockPosition = null;
        }
    }

    @Override
    public void onPreViaPacketReceive(PacketReceiveEvent event) {
        boolean flying = WrapperPlayClientPlayerFlying.isFlying(event.getPacketType());
        if ((flying || (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9) && isAnimation(event.getPacketType()))) && targetBlockPosition != null) {
            maximumBlockDamage = Math.max(maximumBlockDamage, BlockBreakSpeed.getBlockDamage(player, player.compensatedWorld.getBlock(targetBlockPosition)));
        }
    }

    private void clampBalance() {
        double balance = Math.max(1000, (player.getTransactionPing()));
        blockBreakBalance = GrimMath.clamp(blockBreakBalance, -balance, balance);
        blockDelayBalance = GrimMath.clamp(blockDelayBalance, -balance, balance);
    }
}

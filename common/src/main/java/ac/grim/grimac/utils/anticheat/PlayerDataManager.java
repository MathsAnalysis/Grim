package ac.grim.grimac.utils.anticheat;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.event.events.GrimJoinEvent;
import ac.grim.grimac.api.event.events.GrimQuitEvent;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.player.PlatformPlayerCache;
import ac.grim.grimac.utils.chunks.ChunkSectionCache;
import ac.grim.grimac.utils.reflection.GeyserUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.player.User;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataManager {

    private static final class Channels {
        static final GrimJoinEvent.Channel JOIN = GrimAPI.INSTANCE.getEventBus().get(GrimJoinEvent.class);
        static final GrimQuitEvent.Channel QUIT = GrimAPI.INSTANCE.getEventBus().get(GrimQuitEvent.class);
    }

    private final Set<User> exemptUsers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<User, GrimPlayer> playerDataMap = new ConcurrentHashMap<>();

    public boolean isExemptUser(@Nullable User user) {
        return user != null && exemptUsers.contains(user);
    }

    public void exemptUser(@Nullable User user) {
        if (user == null) return;
        exemptUsers.add(user);
    }

    public boolean clearExemptions(@Nullable User user) {
        if (user == null) return false;
        return exemptUsers.remove(user);
    }

    @Nullable
    public GrimPlayer getPlayer(final @NotNull UUID uuid) {
        Object channel = PacketEvents.getAPI().getProtocolManager().getChannel(uuid);
        if (channel == null) return null;
        User user = PacketEvents.getAPI().getProtocolManager().getUser(channel);
        if (user == null) return null;
        return getPlayer(user);
    }

    @Nullable
    public GrimPlayer getPlayer(final @NotNull User user) {
        @Nullable GrimPlayer player = playerDataMap.get(user);
        if (player != null && player.platformPlayer != null && player.platformPlayer.isExternalPlayer())
            return null;
        return player;
    }

    public boolean shouldCheck(@NotNull User user) {
        if (isExemptUser(user)) return false;
        if (!ChannelHelper.isOpen(user.getChannel())) return false;

        if (user.getUUID() != null) {
            if (GeyserUtil.isBedrockPlayer(user.getUUID())) {
                exemptUser(user);
                return false;
            }

            GrimPlayer grimPlayer = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(user);
            if (grimPlayer != null && grimPlayer.hasPermission("grim.exempt")) {
                exemptUser(user);
                return false;
            }

            if (user.getUUID().toString().startsWith("00000000-0000-0000-0009")) {
                exemptUser(user);
                return false;
            }
        }

        return true;
    }

    public void addUser(final @NotNull User user) {
        if (shouldCheck(user)) {
            GrimPlayer player = new GrimPlayer(user);
            playerDataMap.put(user, player);
            Channels.JOIN.fire(player);
        }
    }

    public GrimPlayer remove(final @NotNull User user) {
        GrimPlayer removed = playerDataMap.remove(user);
        if (removed != null) {
            removed.runSafely(() -> {
                ChunkSectionCache.releaseSharedSections(removed.compensatedWorld);
                removed.compensatedWorld.chunks.clear();
                removed.compensatedWorld.activePistons.clear();
                removed.compensatedWorld.openShulkerBoxes.clear();
                removed.compensatedEntities.entityMap.clear();
                removed.compensatedEntities.serverPositionsMap.clear();
                removed.compensatedEntities.profiles.clear();
                removed.compensatedEntities.entitiesRemovedThisTick.clear();
                removed.transactionsSent.clear();
                removed.didWeSendThatTrans.clear();
                removed.blockHistory.clear();
                removed.placeUseItemPackets.clear();
                removed.queuedBreaks.clear();
                removed.pendingRotations.clear();
                removed.movementThisTick.clear();
            });
        }
        return removed;
    }

    public void onDisconnect(User user) {
        GrimPlayer grimPlayer = remove(user);
        if (grimPlayer != null) Channels.QUIT.fire(grimPlayer);
        clearExemptions(user);

        UUID uuid = user.getProfile().getUUID();

        GrimAPI.INSTANCE.getDataStoreLifecycle().liveWriteHooks()
                .onQuitFromUserDisconnect(user, grimPlayer, System.currentTimeMillis());
        if (uuid != null) {
            GrimAPI.INSTANCE.getDataStoreLifecycle().playerToggleStore().evict(uuid);
        }

        if (uuid == null)
            return;

        PlatformPlayer quittingPlayer = PlatformPlayerCache.getInstance().getPlayer(uuid);
        if (quittingPlayer != null) {
            GrimAPI.INSTANCE.getAlertManager().handlePlayerQuit(quittingPlayer);
        }

        GrimAPI.INSTANCE.getSpectateManager().onQuit(uuid);
        GrimAPI.INSTANCE.getPlatformPlayerFactory().invalidatePlayer(uuid);
    }

    public Collection<GrimPlayer> getEntries() {
        return playerDataMap.values();
    }

    public int size() {
        return playerDataMap.size();
    }
}

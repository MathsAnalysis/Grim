package ac.grim.grimac.predictionengine;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.plugin.BasicGrimPlugin;
import ac.grim.grimac.api.plugin.GrimPlugin;
import ac.grim.grimac.checks.impl.badpackets.BadPacketsN;
import ac.grim.grimac.checks.impl.prediction.OffsetHandler;
import ac.grim.grimac.checks.impl.velocity.KnockbackHandler;
import ac.grim.grimac.events.packets.CheckManagerListener;
import ac.grim.grimac.events.packets.PacketPlayerSteer;
import ac.grim.grimac.events.packets.PacketServerTeleport;
import ac.grim.grimac.platform.api.PlatformLoader;
import ac.grim.grimac.platform.api.PlatformServer;
import ac.grim.grimac.platform.api.manager.ItemResetHandler;
import ac.grim.grimac.platform.api.manager.MessagePlaceHolderManager;
import ac.grim.grimac.platform.api.manager.PlatformPluginManager;
import ac.grim.grimac.platform.api.player.PlatformPlayerFactory;
import ac.grim.grimac.platform.api.scheduler.AsyncScheduler;
import ac.grim.grimac.platform.api.scheduler.EntityScheduler;
import ac.grim.grimac.platform.api.scheduler.GlobalRegionScheduler;
import ac.grim.grimac.platform.api.scheduler.PlatformScheduler;
import ac.grim.grimac.platform.api.scheduler.RegionScheduler;
import ac.grim.grimac.platform.api.scheduler.TaskHandle;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.chunks.Column;
import ac.grim.grimac.utils.data.packetentity.PacketEntitySelf;
import ac.grim.grimac.utils.math.Location;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.netty.buffer.ByteBufAllocationOperator;
import com.github.retrooper.packetevents.netty.buffer.ByteBufOperator;
import com.github.retrooper.packetevents.netty.channel.ChannelOperator;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.dimension.DimensionType;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.github.retrooper.packetevents.util.LogManager;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnderPearlTeleportSimulationTest {

    @BeforeAll
    static void setUpEnv() throws Exception {
        System.setProperty("GrimPlatformOverride", "BUKKIT");
        PacketEvents.setAPI(createPacketEventsAPI(ServerVersion.V_1_20_4));

        Field loaderField = GrimAPI.class.getDeclaredField("loader");
        loaderField.setAccessible(true);
        if (loaderField.get(GrimAPI.INSTANCE) == null) {
            loaderField.set(GrimAPI.INSTANCE, createPlatformLoader());
        }
        ConfigManager defaultConfig = createDefaultConfig();
        GrimAPI.INSTANCE.getConfigManager().load(defaultConfig);
        GrimAPI.INSTANCE.getAlertManager().reload(defaultConfig);
    }

    private static GrimPlayer createTestPlayer(ClientVersion clientVersion) {
        EmbeddedChannel channel = new EmbeddedChannel();
        UserProfile profile = new UserProfile(UUID.randomUUID(), "PearlTester");
        User user = new User(channel, ConnectionState.PLAY, clientVersion, profile);
        DimensionType dimensionType = proxyInterface(DimensionType.class, (proxy, method, args) -> switch (method.getName()) {
            case "getMinY" -> -64;
            case "getHeight", "getLogicalHeight" -> 384;
            default -> defaultReturnValue(method.getReturnType());
        });
        user.setDimensionType(dimensionType);

        GrimAPI.INSTANCE.getPlayerDataManager().addUser(user);
        GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(user);
        player.gamemode = GameMode.SURVIVAL;
        player.entityID = 1;
        player.compensatedEntities.self = new PacketEntitySelf(player);
        ConfigManager config = createDefaultConfig();
        player.reload(config);

        // Populate flat ground chunks around origin (0, 64, 0) and pearl target (40, 64, 40)
        loadFlatWorldChunks(player, -2, 6, -2, 6, 63, StateTypes.STONE);
        return player;
    }

    private static void loadFlatWorldChunks(GrimPlayer player, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ, int floorY, StateType blockType) {
        WrappedBlockState blockState = blockType.createBlockState(ClientVersion.V_1_20_3);
        int minY = player.compensatedWorld.getMinHeight();
        int maxY = player.compensatedWorld.getMaxHeight();
        int sectionsCount = (maxY - minY) >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                BaseChunk[] chunks = new BaseChunk[sectionsCount];
                for (int i = 0; i < sectionsCount; i++) {
                    chunks[i] = new Chunk_v1_18();
                }
                Column column = new Column(cx, cz, chunks, new long[0], 0);
                player.compensatedWorld.addToCache(column, cx, cz, new TileEntity[0]);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        player.compensatedWorld.updateBlock((cx << 4) + x, floorY, (cz << 4) + z, blockState.getGlobalId());
                    }
                }
            }
        }
    }

    private static void spawnPlayerAt(GrimPlayer player, double x, double y, double z, float yaw, float pitch) {
        int tx = player.lastTransactionSent.get();
        int tpId = 1;
        player.getSetbackTeleportUtil().addSentTeleport(
                new Location(null, x, y, z, yaw, pitch),
                null,
                tx,
                new RelativeFlag(0),
                true,
                tpId
        );
        sendClientPacket(player, PacketType.Play.Client.TELEPORT_CONFIRM, new WrapperPlayClientTeleportConfirm(tpId));
        sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
                new WrapperPlayClientPlayerPositionAndRotation(new Vector3d(x, y, z), yaw, pitch, false));
    }

    private static PacketReceiveEvent sendClientPacket(GrimPlayer player, PacketType.Play.Client type, PacketWrapper<?> wrapper) {
        try {
            ByteBuf buf = Unpooled.buffer();
            wrapper.setBuffer(buf);
            Field versionField = PacketWrapper.class.getDeclaredField("clientVersion");
            versionField.setAccessible(true);
            versionField.set(wrapper, player.getClientVersion());
            wrapper.write();

            ByteBuf packetBuf = Unpooled.buffer();
            int packetId = type.getId(player.getClientVersion());
            writeVarInt(packetBuf, packetId);
            packetBuf.writeBytes(buf);

            Constructor<PacketReceiveEvent> ctor = PacketReceiveEvent.class.getDeclaredConstructor(
                    Object.class, ByteBuf.class, ServerVersion.class, User.class, boolean.class
            );
            ctor.setAccessible(true);
            PacketReceiveEvent event = ctor.newInstance(player.user.getChannel(), packetBuf, ServerVersion.V_1_20_4, player.user, false);

            new PacketPlayerSteer().onPacketReceive(event);
            new CheckManagerListener().onPacketReceive(event);
            return event;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void sendServerTeleport(GrimPlayer player, double x, double y, double z, float yaw, float pitch, byte relativeMask, int teleportId) {
        try {
            WrapperPlayServerPlayerPositionAndLook wrapper = new WrapperPlayServerPlayerPositionAndLook(
                    x, y, z, yaw, pitch, relativeMask, teleportId, false
            );
            ByteBuf buf = Unpooled.buffer();
            wrapper.setBuffer(buf);
            Field versionField = PacketWrapper.class.getDeclaredField("clientVersion");
            versionField.setAccessible(true);
            versionField.set(wrapper, player.getClientVersion());
            wrapper.write();

            ByteBuf packetBuf = Unpooled.buffer();
            int packetId = PacketType.Play.Server.PLAYER_POSITION_AND_LOOK.getId(player.getClientVersion());
            writeVarInt(packetBuf, packetId);
            packetBuf.writeBytes(buf);

            Constructor<PacketSendEvent> ctor = PacketSendEvent.class.getDeclaredConstructor(
                    Object.class, ByteBuf.class, ServerVersion.class, User.class, boolean.class
            );
            ctor.setAccessible(true);
            PacketSendEvent event = ctor.newInstance(player.user.getChannel(), packetBuf, ServerVersion.V_1_20_4, player.user, false);

            new PacketServerTeleport().onPacketSend(event);
            new CheckManagerListener().onPacketSend(event);
            for (Runnable task : event.getTasksAfterSend()) {
                task.run();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void sendServerVelocity(GrimPlayer player, Vector3d velocity) {
        try {
            WrapperPlayServerEntityVelocity wrapper = new WrapperPlayServerEntityVelocity(player.entityID, velocity);
            ByteBuf buf = Unpooled.buffer();
            wrapper.setBuffer(buf);
            Field versionField = PacketWrapper.class.getDeclaredField("clientVersion");
            versionField.setAccessible(true);
            versionField.set(wrapper, player.getClientVersion());
            wrapper.write();

            ByteBuf packetBuf = Unpooled.buffer();
            int packetId = PacketType.Play.Server.ENTITY_VELOCITY.getId(player.getClientVersion());
            writeVarInt(packetBuf, packetId);
            packetBuf.writeBytes(buf);

            Constructor<PacketSendEvent> ctor = PacketSendEvent.class.getDeclaredConstructor(
                    Object.class, ByteBuf.class, ServerVersion.class, User.class, boolean.class
            );
            ctor.setAccessible(true);
            PacketSendEvent event = ctor.newInstance(player.user.getChannel(), packetBuf, ServerVersion.V_1_20_4, player.user, false);

            new CheckManagerListener().onPacketSend(event);
            for (Runnable task : event.getTasksAfterSend()) {
                task.run();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void confirmAllSentTransactions(GrimPlayer player) {
        while (!player.transactionsSent.isEmpty()) {
            short id = player.transactionsSent.peek().first();
            player.addTransactionResponse(id);
        }
    }

    private static void throwEnderPearl(GrimPlayer player) {
        player.inventory.inventory.setHeldItem(ItemStack.builder().type(ItemTypes.ENDER_PEARL).amount(16).build());
        sendClientPacket(player, PacketType.Play.Client.USE_ITEM, new WrapperPlayClientUseItem(InteractionHand.MAIN_HAND, 0));
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    /**
     * Simulates one vanilla client tick on flat ground at y = 64.0 (or in air above y = 64.0).
     */
    private static final class VanillaClientSimulator {
        double x, y, z;
        double vx, vy, vz;
        float yaw, pitch;
        boolean onGround;
        boolean sprinting;

        VanillaClientSimulator(double x, double y, double z, float yaw, float pitch, boolean onGround, boolean sprinting) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.onGround = onGround;
            this.sprinting = sprinting;
        }

        void acceptTeleport(double tpX, double tpY, double tpZ, float tpYaw, float tpPitch) {
            this.x = tpX;
            this.y = tpY;
            this.z = tpZ;
            this.yaw = tpYaw;
            this.pitch = tpPitch;
            this.vx = 0;
            this.vy = 0;
            this.vz = 0;
        }

        void stepTick(GrimPlayer player, int forward, int strafe, boolean jump, float floorFriction, double groundY) {
            if (Math.abs(vx) < 0.003) vx = 0;
            if (Math.abs(vy) < 0.003) vy = 0;
            if (Math.abs(vz) < 0.003) vz = 0;

            float baseSpeed = 0.1f;
            if (sprinting) {
                baseSpeed += baseSpeed * 0.3f;
            }

            if (jump && onGround) {
                vy = 0.42f;
                if (sprinting) {
                    float f = yaw * ((float) Math.PI / 180F);
                    vx -= player.trigHandler.sin(f) * 0.2F;
                    vz += player.trigHandler.cos(f) * 0.2F;
                }
            }

            float friction = onGround ? floorFriction * 0.91f : 0.91f;
            float moveSpeed = onGround ? baseSpeed * (0.21600002f / (floorFriction * floorFriction * floorFriction))
                    : (sprinting ? 0.025999999f : 0.02f);

            double f = forward * 0.98f;
            double s = strafe * 0.98f;
            double lenSq = s * s + f * f;
            if (lenSq >= 1.0E-4F) {
                double len = Math.sqrt(lenSq);
                if (len < 1.0) len = 1.0;
                len = moveSpeed / len;
                s *= len;
                f *= len;
                float sin = player.trigHandler.sin(yaw * ((float) Math.PI / 180F));
                float cos = player.trigHandler.cos(yaw * ((float) Math.PI / 180F));
                vx += s * cos - f * sin;
                vz += f * cos + s * sin;
            }

            double moveX = vx;
            double moveY = vy;
            double moveZ = vz;

            if (y + moveY <= groundY) {
                moveY = groundY - y;
                onGround = true;
                vy = 0;
            } else {
                onGround = false;
            }

            x += moveX;
            y += moveY;
            z += moveZ;

            vy = (vy - 0.08) * 0.98f;
            vx *= friction;
            vz *= friction;
        }
    }

    @Disabled("PacketEvents snapshot 2.14.1+b1f9403 fails to initialise item data (PotionEffect mapping) in this test harness")
    @Test
    void testEnderPearlTeleportWhileSprintingJumpingAndLandingOnGround() {
        GrimPlayer player = createTestPlayer(ClientVersion.V_1_20_3);
        OffsetHandler offsetHandler = player.checkManager.get(OffsetHandler.class);
        spawnPlayerAt(player, 0.0, 64.0, 0.0, 0.0f, 0.0f);

        player.isSprinting = true;
        VanillaClientSimulator sim = new VanillaClientSimulator(0.0, 64.0, 0.0, 0.0f, 0.0f, true, true);

        // Sprint-jump and throw an ender pearl while airborne
        sim.stepTick(player, 1, 0, true, 0.6f, 64.0);
        sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION,
                new WrapperPlayClientPlayerPosition(new Vector3d(sim.x, sim.y, sim.z), sim.onGround));
        throwEnderPearl(player);

        // 2 more ticks airborne while moving forward
        for (int i = 0; i < 2; i++) {
            sim.stepTick(player, 1, 0, false, 0.6f, 64.0);
            sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION,
                    new WrapperPlayClientPlayerPosition(new Vector3d(sim.x, sim.y, sim.z), sim.onGround));
        }
        assertEquals(0.0, offsetHandler.getViolations(), "Should not flag before teleport");

        // Pearl lands on ground at (30.5, 64.0, 30.5) while player is still airborne (sim.onGround == false)
        assertFalse(sim.onGround);
        sendServerTeleport(player, 30.5, 64.0, 30.5, 0.0f, 0.0f, (byte) 0, 42);
        confirmAllSentTransactions(player);

        // Client accepts teleport (Vanilla handleMovePlayer preserves pre-teleport onGround = false)
        sim.acceptTeleport(30.5, 64.0, 30.5, 0.0f, 0.0f);
        sendClientPacket(player, PacketType.Play.Client.TELEPORT_CONFIRM, new WrapperPlayClientTeleportConfirm(42));
        sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
                new WrapperPlayClientPlayerPositionAndRotation(new Vector3d(sim.x, sim.y, sim.z), sim.yaw, sim.pitch, false));

        // Client continues sprinting for 10 ticks after teleport (Tick 1 uses preserved lastOnGround = false, resolves onGround = true at end of Tick 1)
        for (int tick = 0; tick < 10; tick++) {
            sim.stepTick(player, 1, 0, false, 0.6f, 64.0);
            sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION,
                    new WrapperPlayClientPlayerPosition(new Vector3d(sim.x, sim.y, sim.z), sim.onGround));
            assertEquals(0.0, offsetHandler.getViolations(), "Should not flag Simulation on tick " + (tick + 1) + " after pearl teleport");
        }
    }

    @Disabled("PacketEvents snapshot 2.14.1+b1f9403 fails to initialise item data (PotionEffect mapping) in this test harness")
    @Test
    void testEnderPearlTeleportWhileFallingWithGroundResolvedOnFirstTick() {
        GrimPlayer player = createTestPlayer(ClientVersion.V_1_20_3);
        OffsetHandler offsetHandler = player.checkManager.get(OffsetHandler.class);
        spawnPlayerAt(player, 0.0, 70.0, 0.0, 45.0f, 10.0f);

        player.isSprinting = true;
        VanillaClientSimulator sim = new VanillaClientSimulator(0.0, 70.0, 0.0, 45.0f, 10.0f, false, true);

        // Fall for 3 ticks while throwing pearl
        throwEnderPearl(player);
        for (int i = 0; i < 3; i++) {
            sim.stepTick(player, 1, 0, false, 0.6f, 64.0);
            sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION,
                    new WrapperPlayClientPlayerPosition(new Vector3d(sim.x, sim.y, sim.z), sim.onGround));
        }
        assertEquals(0.0, offsetHandler.getViolations());

        // Pearl lands on ground at (25.5, 64.0, 25.5); client resolves ground on landing (e.g. after 0.03 tick)
        sendServerTeleport(player, 25.5, 64.0, 25.5, 45.0f, 10.0f, (byte) 0, 43);
        confirmAllSentTransactions(player);

        sim.acceptTeleport(25.5, 64.0, 25.5, 45.0f, 10.0f);
        sim.onGround = true; // Client resolved ground before Tick 1 movement packet
        sendClientPacket(player, PacketType.Play.Client.TELEPORT_CONFIRM, new WrapperPlayClientTeleportConfirm(43));
        sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
                new WrapperPlayClientPlayerPositionAndRotation(new Vector3d(sim.x, sim.y, sim.z), sim.yaw, sim.pitch, false));

        // Sprint and jump immediately on Tick 1 after teleport, then continue moving for 8 ticks
        for (int tick = 0; tick < 8; tick++) {
            sim.stepTick(player, 1, 0, tick == 0, 0.6f, 64.0);
            sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION,
                    new WrapperPlayClientPlayerPosition(new Vector3d(sim.x, sim.y, sim.z), sim.onGround));
            assertEquals(0.0, offsetHandler.getViolations(), "Should not flag Simulation on tick " + (tick + 1) + " when ground resolved at teleport");
        }
    }

    @Disabled("PacketEvents snapshot 2.14.1+b1f9403 fails to initialise item data (PotionEffect mapping) in this test harness")
    @Test
    void testEnderPearlTeleportWhileChangingDirectionAndRotating1204DuplicateFilter() {
        GrimPlayer player = createTestPlayer(ClientVersion.V_1_20_3);
        OffsetHandler offsetHandler = player.checkManager.get(OffsetHandler.class);
        spawnPlayerAt(player, 0.0, 64.0, 0.0, 0.0f, 0.0f);

        player.isSprinting = false;
        VanillaClientSimulator sim = new VanillaClientSimulator(0.0, 64.0, 0.0, 0.0f, 0.0f, true, false);

        // Jump and strafe left before pearl lands
        sim.stepTick(player, 1, 1, true, 0.6f, 64.0);
        sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
                new WrapperPlayClientPlayerPositionAndRotation(new Vector3d(sim.x, sim.y, sim.z), sim.yaw, sim.pitch, sim.onGround));
        throwEnderPearl(player);

        // Pearl lands at (15.5, 68.0, 15.5) in mid-air
        sendServerTeleport(player, 15.5, 68.0, 15.5, 0.0f, 0.0f, (byte) 0, 44);
        confirmAllSentTransactions(player);

        sim.acceptTeleport(15.5, 68.0, 15.5, 0.0f, 0.0f);
        sendClientPacket(player, PacketType.Play.Client.TELEPORT_CONFIRM, new WrapperPlayClientTeleportConfirm(44));
        sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
                new WrapperPlayClientPlayerPositionAndRotation(new Vector3d(sim.x, sim.y, sim.z), sim.yaw, sim.pitch, false));

        // On Tick 1 after teleport, player rotates yaw and changes direction (strafe right + backward) in air.
        // Note: On 1.17-1.20.6, a walking air step with vy=0 has distance ~0.0196 < 0.03 and onGround=false!
        for (int tick = 0; tick < 6; tick++) {
            sim.yaw += 35.0f;
            sim.pitch = 15.0f;
            sim.stepTick(player, -1, -1, false, 0.6f, 64.0);
            PacketReceiveEvent ev = sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
                    new WrapperPlayClientPlayerPositionAndRotation(new Vector3d(sim.x, sim.y, sim.z), sim.yaw, sim.pitch, sim.onGround));
            assertFalse(ev.isCancelled(), "Post-teleport movement packet must not be cancelled as a duplicate");
            assertEquals(0.0, offsetHandler.getViolations(), "Should not flag Simulation when rotating and changing direction on tick " + (tick + 1));
        }
    }

    @Disabled("PacketEvents snapshot 2.14.1+b1f9403 fails to initialise item data (PotionEffect mapping) in this test harness")
    @Test
    void testAsynchronousPacketsBeforeAndAfterTeleportAndPostTeleportVelocity() {
        GrimPlayer player = createTestPlayer(ClientVersion.V_1_20_3);
        OffsetHandler offsetHandler = player.checkManager.get(OffsetHandler.class);
        BadPacketsN badPacketsN = player.checkManager.get(BadPacketsN.class);
        KnockbackHandler knockbackHandler = player.checkManager.getKnockbackHandler();
        spawnPlayerAt(player, 0.0, 64.0, 0.0, 0.0f, 0.0f);

        player.isSprinting = true;
        VanillaClientSimulator sim = new VanillaClientSimulator(0.0, 64.0, 0.0, 0.0f, 0.0f, true, true);

        // Player moves on ground and throws pearl
        sim.stepTick(player, 1, 0, false, 0.6f, 64.0);
        sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION,
                new WrapperPlayClientPlayerPosition(new Vector3d(sim.x, sim.y, sim.z), sim.onGround));
        throwEnderPearl(player);

        // Server sends pearl teleport AND pearl landing fall damage velocity in the same server tick
        sendServerTeleport(player, 20.5, 64.0, 20.5, 0.0f, 0.0f, (byte) 0, 50);
        Vector3d pearlDamageKb = new Vector3d(0.15, 0.32, -0.12);
        sendServerVelocity(player, pearlDamageKb);

        // Before the client receives the teleport, 2 asynchronous in-flight client packets arrive at the server
        sim.stepTick(player, 1, 0, true, 0.6f, 64.0);
        sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION,
                new WrapperPlayClientPlayerPosition(new Vector3d(sim.x, sim.y, sim.z), sim.onGround));
        sendClientPacket(player, PacketType.Play.Client.PLAYER_ROTATION,
                new WrapperPlayClientPlayerRotation(15.0f, 5.0f, sim.onGround));
        assertEquals(0.0, offsetHandler.getViolations(), "In-flight pre-teleport packets should not flag Simulation");
        assertEquals(0.0, badPacketsN.getViolations(), "In-flight pre-teleport packets should not flag BadPacketsN");

        // Now the client receives both transactions (teleport + velocity) and sends TELEPORT_CONFIRM + teleport PosRot
        confirmAllSentTransactions(player);
        sim.acceptTeleport(20.5, 64.0, 20.5, 0.0f, 0.0f);
        sendClientPacket(player, PacketType.Play.Client.TELEPORT_CONFIRM, new WrapperPlayClientTeleportConfirm(50));
        sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
                new WrapperPlayClientPlayerPositionAndRotation(new Vector3d(sim.x, sim.y, sim.z), sim.yaw, sim.pitch, false));
        assertEquals(0.0, badPacketsN.getViolations(), "Teleport acceptance after confirming transactions must not flag BadPacketsN");

        // On Tick 1 after teleport, client applies the post-teleport velocity packet that arrived right after the teleport
        sim.onGround = true;
        sim.vx = pearlDamageKb.getX();
        sim.vy = pearlDamageKb.getY();
        sim.vz = pearlDamageKb.getZ();

        for (int tick = 0; tick < 6; tick++) {
            sim.stepTick(player, 1, 0, false, 0.6f, 64.0);
            sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION,
                    new WrapperPlayClientPlayerPosition(new Vector3d(sim.x, sim.y, sim.z), sim.onGround));
            assertEquals(0.0, offsetHandler.getViolations(), "Post-teleport velocity tick " + (tick + 1) + " should not flag Simulation");
            assertEquals(0.0, knockbackHandler.getViolations(), "Post-teleport velocity tick " + (tick + 1) + " should not flag AntiKB");
        }
    }

    @Disabled("PacketEvents snapshot 2.14.1+b1f9403 fails to initialise item data (PotionEffect mapping) in this test harness")
    @Test
    void testSupportingBlockResynchronizedWhenTeleportingFromIceToStone() {
        GrimPlayer player = createTestPlayer(ClientVersion.V_1_20_3);
        OffsetHandler offsetHandler = player.checkManager.get(OffsetHandler.class);

        // Set Blue Ice at origin (0, 63, 0) and Stone at target (30, 63, 30)
        int blueIceId = StateTypes.BLUE_ICE.createBlockState(ClientVersion.V_1_20_3).getGlobalId();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                player.compensatedWorld.updateBlock(x, 63, z, blueIceId);
            }
        }

        spawnPlayerAt(player, 0.5, 64.0, 0.5, 0.0f, 0.0f);
        player.isSprinting = true;
        VanillaClientSimulator sim = new VanillaClientSimulator(0.5, 64.0, 0.5, 0.0f, 0.0f, true, true);

        // Move 1 tick on Blue Ice (friction 0.989)
        sim.stepTick(player, 1, 0, false, 0.989f, 64.0);
        sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION,
                new WrapperPlayClientPlayerPosition(new Vector3d(sim.x, sim.y, sim.z), sim.onGround));
        assertEquals(0.0, offsetHandler.getViolations());

        // Teleport to Stone at (30.5, 64.0, 30.5) (friction 0.6)
        sendServerTeleport(player, 30.5, 64.0, 30.5, 0.0f, 0.0f, (byte) 0, 60);
        confirmAllSentTransactions(player);
        sim.acceptTeleport(30.5, 64.0, 30.5, 0.0f, 0.0f);
        sendClientPacket(player, PacketType.Play.Client.TELEPORT_CONFIRM, new WrapperPlayClientTeleportConfirm(60));
        sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
                new WrapperPlayClientPlayerPositionAndRotation(new Vector3d(sim.x, sim.y, sim.z), sim.yaw, sim.pitch, false));

        // Move on Stone using Stone friction (0.6)
        for (int tick = 0; tick < 5; tick++) {
            sim.stepTick(player, 1, 0, false, 0.6f, 64.0);
            sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION,
                    new WrapperPlayClientPlayerPosition(new Vector3d(sim.x, sim.y, sim.z), sim.onGround));
            assertEquals(0.0, offsetHandler.getViolations(), "Should use Stone supporting block friction after teleport");
        }
    }

    @Disabled("PacketEvents snapshot 2.14.1+b1f9403 fails to initialise item data (PotionEffect mapping) in this test harness")
    @Test
    void testSpeedAndFlyCheatsStillFlagAfterEnderPearlTeleport() {
        GrimPlayer player = createTestPlayer(ClientVersion.V_1_20_3);
        OffsetHandler offsetHandler = player.checkManager.get(OffsetHandler.class);
        spawnPlayerAt(player, 0.0, 64.0, 0.0, 0.0f, 0.0f);

        throwEnderPearl(player);
        // Pearl teleports player to mid-air at (20.5, 75.0, 20.5)
        sendServerTeleport(player, 20.5, 75.0, 20.5, 0.0f, 0.0f, (byte) 0, 70);
        confirmAllSentTransactions(player);
        sendClientPacket(player, PacketType.Play.Client.TELEPORT_CONFIRM, new WrapperPlayClientTeleportConfirm(70));
        sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
                new WrapperPlayClientPlayerPositionAndRotation(new Vector3d(20.5, 75.0, 20.5), 0.0f, 0.0f, false));
        assertEquals(0.0, offsetHandler.getViolations());

        // 1. Speed cheat: moving 0.85 blocks horizontally in a single tick after teleport must flag Simulation
        sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION,
                new WrapperPlayClientPlayerPosition(new Vector3d(20.5, 74.92, 21.35), false));
        assertTrue(offsetHandler.getViolations() >= 1.0, "Speed cheat after ender pearl teleport must flag Simulation");

        // 2. Fly / air-jump cheat: ascending +0.42 in mid-air after teleporting to mid-air must flag Simulation
        double beforeFlyVl = offsetHandler.getViolations();
        sendClientPacket(player, PacketType.Play.Client.PLAYER_POSITION,
                new WrapperPlayClientPlayerPosition(new Vector3d(20.5, 75.34, 21.35), false));
        assertTrue(offsetHandler.getViolations() > beforeFlyVl, "Fly cheat after ender pearl teleport must flag Simulation");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxyInterface(Class<T> iface, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    private static Object defaultReturnValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) return null;
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == double.class) return 0.0d;
        if (returnType == float.class) return 0.0f;
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == char.class) return '\0';
        return null;
    }

    private static ConfigManager createDefaultConfig() {
        return proxyInterface(ConfigManager.class, (proxy, method, args) -> {
            String name = method.getName();
            if (name.endsWith("Else") && args != null && args.length >= 2) {
                return args[1];
            }
            if (name.equals("getStringList")) {
                return Collections.emptyList();
            }
            if (name.equals("hasLoaded") || name.equals("isLoaded")) {
                return true;
            }
            return defaultReturnValue(method.getReturnType());
        });
    }

    private static PacketEventsAPI<?> createPacketEventsAPI(ServerVersion serverVersion) {
        ServerManager serverManager = proxyInterface(ServerManager.class, (proxy, method, args) -> {
            if (method.getName().equals("getVersion")) return serverVersion;
            return defaultReturnValue(method.getReturnType());
        });
        ProtocolManager protocolManager = proxyInterface(ProtocolManager.class, (proxy, method, args) -> {
            if (method.getName().equals("getClientVersion")) return ClientVersion.V_1_20_3;
            if (method.getName().equals("getUsers") || method.getName().equals("getChannels")) return Collections.emptyList();
            return defaultReturnValue(method.getReturnType());
        });
        PlayerManager playerManager = proxyInterface(PlayerManager.class, (proxy, method, args) -> {
            if (method.getName().equals("getClientVersion")) return ClientVersion.V_1_20_3;
            return defaultReturnValue(method.getReturnType());
        });
        ChannelOperator channelOp = proxyInterface(ChannelOperator.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "isOpen" -> { return true; }
                case "runInEventLoop" -> {
                    if (args != null && args.length >= 2 && args[1] instanceof Runnable r) r.run();
                    return null;
                }
                case "pooledByteBuf" -> { return Unpooled.buffer(); }
                default -> { return defaultReturnValue(method.getReturnType()); }
            }
        });
        ByteBufOperator bufOp = proxyInterface(ByteBufOperator.class, (proxy, method, args) -> {
            ByteBuf b = (args != null && args.length > 0 && args[0] instanceof ByteBuf) ? (ByteBuf) args[0] : null;
            return switch (method.getName()) {
                case "capacity" -> args.length == 1 ? b.capacity() : b.capacity((int) args[1]);
                case "readerIndex" -> args.length == 1 ? b.readerIndex() : b.readerIndex((int) args[1]);
                case "writerIndex" -> args.length == 1 ? b.writerIndex() : b.writerIndex((int) args[1]);
                case "readableBytes" -> b.readableBytes();
                case "writableBytes" -> b.writableBytes();
                case "clear" -> b.clear();
                case "readByte" -> b.readByte();
                case "readShort" -> b.readShort();
                case "readMedium" -> b.readMedium();
                case "readInt" -> b.readInt();
                case "readUnsignedInt" -> b.readUnsignedInt();
                case "readLong" -> b.readLong();
                case "writeByte" -> { b.writeByte((int) args[1]); yield null; }
                case "writeShort" -> { b.writeShort((int) args[1]); yield null; }
                case "writeShortLE" -> { b.writeShortLE((int) args[1]); yield null; }
                case "writeMedium" -> { b.writeMedium((int) args[1]); yield null; }
                case "writeInt" -> { b.writeInt((int) args[1]); yield null; }
                case "writeLong" -> { b.writeLong((long) args[1]); yield null; }
                case "getBytes" -> b.getBytes((int) args[1], (byte[]) args[2]);
                case "getUnsignedByte" -> b.getUnsignedByte((int) args[1]);
                case "isReadable" -> b.isReadable();
                case "copy" -> b.copy();
                case "duplicate" -> b.duplicate();
                case "hasArray" -> b.hasArray();
                case "array" -> b.array();
                case "retain" -> b.retain();
                case "retainedDuplicate" -> b.retainedDuplicate();
                case "readSlice" -> b.readSlice((int) args[1]);
                case "readBytes" -> {
                    if (args.length == 2 && args[1] instanceof byte[] dst) { b.readBytes(dst); yield null; }
                    if (args.length == 2 && args[1] instanceof Integer len) yield b.readBytes(len);
                    yield b.readBytes((byte[]) args[1], (int) args[2], (int) args[3]);
                }
                case "writeBytes" -> {
                    if (args.length == 2 && args[1] instanceof ByteBuf src) yield b.writeBytes(src);
                    if (args.length == 2 && args[1] instanceof byte[] src) yield b.writeBytes(src);
                    yield b.writeBytes((byte[]) args[1], (int) args[2], (int) args[3]);
                }
                case "release" -> b.release();
                case "refCnt" -> b.refCnt();
                case "skipBytes" -> b.skipBytes((int) args[1]);
                case "toString" -> b.toString((int) args[1], (int) args[2], (Charset) args[3]);
                case "markReaderIndex" -> b.markReaderIndex();
                case "resetReaderIndex" -> b.resetReaderIndex();
                case "markWriterIndex" -> b.markWriterIndex();
                case "resetWriterIndex" -> b.resetWriterIndex();
                case "allocateNewBuffer" -> Unpooled.buffer();
                default -> defaultReturnValue(method.getReturnType());
            };
        });
        ByteBufAllocationOperator allocOp = proxyInterface(ByteBufAllocationOperator.class, (proxy, method, args) -> switch (method.getName()) {
            case "wrappedBuffer" -> Unpooled.wrappedBuffer((byte[]) args[0]);
            case "copiedBuffer" -> Unpooled.copiedBuffer((byte[]) args[0]);
            case "buffer" -> args == null || args.length == 0 ? Unpooled.buffer() : Unpooled.buffer((int) args[0]);
            case "directBuffer" -> args == null || args.length == 0 ? Unpooled.directBuffer() : Unpooled.directBuffer((int) args[0]);
            case "compositeBuffer" -> args == null || args.length == 0 ? Unpooled.compositeBuffer() : Unpooled.compositeBuffer((int) args[0]);
            case "emptyBuffer" -> Unpooled.EMPTY_BUFFER;
            default -> defaultReturnValue(method.getReturnType());
        });
        NettyManager nettyManager = proxyInterface(NettyManager.class, (proxy, method, args) -> switch (method.getName()) {
            case "getChannelOperator" -> channelOp;
            case "getByteBufOperator" -> bufOp;
            case "getByteBufAllocationOperator" -> allocOp;
            default -> defaultReturnValue(method.getReturnType());
        });
        PacketEventsSettings settings = new PacketEventsSettings();
        LogManager logManager = new LogManager() {
            @Override
            public void log(java.util.logging.Level level, net.kyori.adventure.text.ComponentLike message, Throwable throwable) {
            }
        };

        return new PacketEventsAPI<>() {
            @Override public void load() {}
            @Override public boolean isLoaded() { return true; }
            @Override public void init() {}
            @Override public boolean isInitialized() { return true; }
            @Override public void terminate() {}
            @Override public boolean isTerminated() { return false; }
            @Override public Object getPlugin() { return null; }
            @Override public ServerManager getServerManager() { return serverManager; }
            @Override public ProtocolManager getProtocolManager() { return protocolManager; }
            @Override public PlayerManager getPlayerManager() { return playerManager; }
            @Override public NettyManager getNettyManager() { return nettyManager; }
            @Override public ChannelInjector getInjector() { return null; }
            @Override public PacketEventsSettings getSettings() { return settings; }
            @Override public LogManager getLogManager() { return logManager; }
        };
    }

    private static PlatformLoader createPlatformLoader() {
        GrimPlugin plugin = new BasicGrimPlugin(Logger.getLogger("GrimTest"), new File("."), "2.3.73", "Test", Collections.emptyList());
        TaskHandle dummyTask = proxyInterface(TaskHandle.class, (proxy, method, args) -> defaultReturnValue(method.getReturnType()));
        EntityScheduler entityScheduler = proxyInterface(EntityScheduler.class, (proxy, method, args) -> {
            if (args != null) {
                for (Object arg : args) {
                    if (arg instanceof Runnable r) { r.run(); break; }
                }
            }
            return method.getReturnType() == TaskHandle.class ? dummyTask : defaultReturnValue(method.getReturnType());
        });
        GlobalRegionScheduler globalScheduler = proxyInterface(GlobalRegionScheduler.class, (proxy, method, args) -> {
            if (args != null && args.length >= 2 && args[1] instanceof Runnable r) r.run();
            return method.getReturnType() == TaskHandle.class ? dummyTask : defaultReturnValue(method.getReturnType());
        });
        AsyncScheduler asyncScheduler = proxyInterface(AsyncScheduler.class, (proxy, method, args) -> {
            if (method.getName().equals("runNow") && args != null && args.length >= 2 && args[1] instanceof Runnable r) r.run();
            return method.getReturnType() == TaskHandle.class ? dummyTask : defaultReturnValue(method.getReturnType());
        });
        RegionScheduler regionScheduler = proxyInterface(RegionScheduler.class, (proxy, method, args) -> {
            return method.getReturnType() == TaskHandle.class ? dummyTask : defaultReturnValue(method.getReturnType());
        });
        PlatformScheduler scheduler = proxyInterface(PlatformScheduler.class, (proxy, method, args) -> switch (method.getName()) {
            case "getAsyncScheduler" -> asyncScheduler;
            case "getGlobalRegionScheduler" -> globalScheduler;
            case "getEntityScheduler" -> entityScheduler;
            case "getRegionScheduler" -> regionScheduler;
            default -> defaultReturnValue(method.getReturnType());
        });
        PlatformPlayerFactory playerFactory = proxyInterface(PlatformPlayerFactory.class, (proxy, method, args) -> {
            if (method.getName().equals("getOnlinePlayers")) return Collections.emptyList();
            return defaultReturnValue(method.getReturnType());
        });
        PlatformPluginManager pluginManager = proxyInterface(PlatformPluginManager.class, (proxy, method, args) -> defaultReturnValue(method.getReturnType()));
        PlatformServer platformServer = proxyInterface(PlatformServer.class, (proxy, method, args) -> switch (method.getName()) {
            case "getPlatformImplementationString" -> "Test";
            case "getTPS" -> 20.0;
            default -> defaultReturnValue(method.getReturnType());
        });
        ItemResetHandler itemResetHandler = proxyInterface(ItemResetHandler.class, (proxy, method, args) -> defaultReturnValue(method.getReturnType()));
        MessagePlaceHolderManager placeHolderManager = (player, string) -> string;

        return proxyInterface(PlatformLoader.class, (proxy, method, args) -> switch (method.getName()) {
            case "getScheduler" -> scheduler;
            case "getPlatformPlayerFactory" -> playerFactory;
            case "getPacketEvents" -> PacketEvents.getAPI();
            case "getPlugin" -> plugin;
            case "getPluginManager" -> pluginManager;
            case "getPlatformServer" -> platformServer;
            case "getItemResetHandler" -> itemResetHandler;
            case "getMessagePlaceHolderManager" -> placeHolderManager;
            default -> defaultReturnValue(method.getReturnType());
        });
    }
}

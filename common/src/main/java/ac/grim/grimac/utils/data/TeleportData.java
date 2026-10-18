package ac.grim.grimac.utils.data;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.math.Vector3dm;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.util.Vector3d;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@AllArgsConstructor
@Getter
public class TeleportData {
    private final Vector3d location;
    private final float yaw;
    private final float pitch;
    @Nullable
    private final Vector3d velocity;
    private final RelativeFlag flags;
    @Setter
    private int transaction;
    @Setter
    private int teleportId;

    public void modifyVector(@NotNull GrimPlayer player, Vector3dm vector) {
        final boolean isStupidTeleportSystem = player.supportsEndTick();
        if (!isStupidTeleportSystem) {
            if (!isRelativeX()) {
                vector.setX(0);
            }

            if (!isRelativeY()) {
                vector.setY(0);
                player.lastWasClimbing = 0;
                player.canSwimHop = false;
            }

            if (!isRelativeZ()) {
                vector.setZ(0);
            }
        } else {
            Vector3d vel = velocity != null ? velocity : Vector3d.zero();
            if (isRelativeDeltaX()) {
                vector.setX(vector.getX() + vel.getX());
            } else {
                vector.setX(vel.getX());
            }

            if (isRelativeDeltaY()) {
                vector.setY(vector.getY() + vel.getY());
            } else {
                vector.setY(vel.getY());
                player.lastWasClimbing = 0;
                player.canSwimHop = false;
            }

            if (isRelativeDeltaZ()) {
                vector.setZ(vector.getZ() + vel.getZ());
            } else {
                vector.setZ(vel.getZ());
            }
        }
    }

    public boolean isRelativeVelocity() {
        return isRelativeDeltaX() || isRelativeDeltaY() || isRelativeDeltaZ();
    }

    public boolean isRelativeDeltaX() {
        return flags.has(RelativeFlag.DELTA_X);
    }

    public boolean isRelativeDeltaY() {
        return flags.has(RelativeFlag.DELTA_Y);
    }

    public boolean isRelativeDeltaZ() {
        return flags.has(RelativeFlag.DELTA_Z);
    }

    public boolean isRelativePos() {
        return isRelativeX() || isRelativeY() || isRelativeZ();
    }

    public boolean isRelativeX() {
        return flags.has(RelativeFlag.X);
    }

    public boolean isRelativeY() {
        return flags.has(RelativeFlag.Y);
    }

    public boolean isRelativeZ() {
        return flags.has(RelativeFlag.Z);
    }

    public boolean isRelativeYaw() {
        return flags.has(RelativeFlag.YAW);
    }

    public boolean isRelativePitch() {
        return flags.has(RelativeFlag.PITCH);
    }
}

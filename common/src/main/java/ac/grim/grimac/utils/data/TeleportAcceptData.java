package ac.grim.grimac.utils.data;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TeleportAcceptData {
    public static final TeleportAcceptData EMPTY = new TeleportAcceptData();

    private boolean isTeleport;
    private SetBackData setback;
    private TeleportData teleportData;
}

package ti4.message.componentsV2;

import lombok.experimental.UtilityClass;
import ti4.game.Player;

@UtilityClass
public class CleanerUi {

    public static boolean isOn(Player player) {
        return player != null && !player.isDummy() && player.getUserSettings().isPrefersCleanerUi();
    }
}

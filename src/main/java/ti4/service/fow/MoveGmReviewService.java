package ti4.service.fow;

import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import ti4.discord.interactions.buttons.Buttons;
import ti4.discord.interactions.routing.ButtonHandler;
import ti4.game.Game;
import ti4.game.Player;
import ti4.game.Tile;
import ti4.helpers.ButtonHelper;
import ti4.helpers.RegexHelper;
import ti4.message.MessageHelper;
import ti4.service.regex.RegexService;
import ti4.service.tactical.TacticalActionDisplacementService;
import ti4.service.tactical.TacticalActionOutputService;
import ti4.service.tactical.TacticalActionService;

/**
 * Gates a tactical-action move that would exceed a unit's move value into a system the mover cannot
 * currently see under Fog of War. Instead of silently allowing or blocking it, a GM is asked to
 * Accept, Deny, or manually resolve ("Other") the move.
 */
public final class MoveGmReviewService {
    private MoveGmReviewService() {}

    public static void requestReview(Game game, Player player, Tile destinationTile) {
        postGmButtons(game, player, destinationTile);
        MessageHelper.sendMessageToChannel(
                player.getCorrectChannel(),
                "Your move is being reviewed by the GM before it can be finalized. Please wait.");
    }

    private static void postGmButtons(Game game, Player player, Tile destinationTile) {
        String suffix = player.getFaction() + "_" + destinationTile.getPosition();
        List<Button> buttons = new ArrayList<>(List.of(
                Buttons.green("gmMoveAccept_" + suffix, "Accept"),
                Buttons.red("gmMoveDeny_" + suffix, "Deny"),
                Buttons.gray("gmMoveOther_" + suffix, "Other")));
        MessageHelper.sendMessageToChannelWithButtons(
                GMService.getGMChannel(game),
                player.getRepresentationUnfoggedNoPing() + " tried to move units into "
                        + destinationTile.getPosition()
                        + ", which exceeds their move value into a system they have not discovered. "
                        + "Please Accept, Deny, or resolve manually with Other - " + GMService.gmPing(game),
                buttons);
    }

    @ButtonHandler("gmMoveAccept_")
    public static void acceptMove(Game game, ButtonInteractionEvent event, Player gm, String buttonID) {
        withTargetPlayerAndTile(game, gm, buttonID, "gmMoveAccept_", (player, tile) -> {
            ButtonHelper.deleteAllButtons(event);
            TacticalActionService.resumeFinishMovement(game, player, tile);
        });
    }

    @ButtonHandler("gmMoveDeny_")
    public static void denyMove(Game game, ButtonInteractionEvent event, Player gm, String buttonID) {
        withTargetPlayerAndTile(game, gm, buttonID, "gmMoveDeny_", (player, tile) -> {
            ButtonHelper.deleteAllButtons(event);
            TacticalActionDisplacementService.reverseAllUnitMovement(game, player);
            MessageHelper.sendMessageToChannel(
                    player.getCorrectChannel(),
                    "Your GM denied that move; units have been put back where they started."
                            + " You are still activated into that system - use \"Activate a different system\""
                            + " if you want to target somewhere else, or adjust which units you're moving"
                            + " before trying again.");
            TacticalActionOutputService.refreshButtonsAndMessageForChoosingTile(null, game, player);
        });
    }

    @ButtonHandler("gmMoveOther_")
    public static void otherMove(Game game, ButtonInteractionEvent event, Player gm, String buttonID) {
        withTargetPlayerAndTile(game, gm, buttonID, "gmMoveOther_", (player, tile) -> {
            ButtonHelper.deleteAllButtons(event);
            MessageHelper.sendMessageToChannel(
                    player.getCorrectChannel(),
                    "Your GM is reviewing that move manually. Please wait for instructions.");
            // Re-post the Accept/Deny/Other buttons so the move can still be resolved later - the
            // move stays staged (uncommitted) until a GM actually Accepts or Denies it.
            postGmButtons(game, player, tile);
        });
    }

    private interface ReviewAction {
        void run(Player player, Tile tile);
    }

    private static void withTargetPlayerAndTile(
            Game game, Player gm, String buttonID, String prefix, ReviewAction action) {
        if (!gm.isGM()) return;
        String regex = prefix + RegexHelper.factionRegex(game) + "_" + RegexHelper.posRegex(game, "pos");
        RegexService.runMatcher(regex, buttonID, matcher -> {
            Player player = game.getPlayerFromColorOrFaction(matcher.group("faction"));
            Tile tile = game.getTileByPosition(matcher.group("pos"));
            if (player == null || tile == null) return;
            action.run(player, tile);
        });
    }
}

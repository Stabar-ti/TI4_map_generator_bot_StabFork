package ti4.discord.interactions.commands.fow;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import ti4.discord.interactions.commands.GameStateSubcommand;
import ti4.game.Game;
import ti4.message.MessageHelper;
import ti4.service.fow.milty.FowMiltyService;

/**
 * {@code /fow milty} — starts a Fog-of-War specific milty draft from the GM room.
 *
 * <p>GM-only. Requires FoW mode and a set map template with a pre-built galaxy. All further
 * interaction happens through the GM setup controls and per-player private prompts.
 */
class MiltyDraftSub extends GameStateSubcommand {

    public MiltyDraftSub() {
        super("milty", "Start a Fog of War milty draft (GM only)", true, true);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        Game game = getGame();
        if (!game.isFowMode()) {
            MessageHelper.replyToMessage(event, "This command is only available in Fog of War games.");
            return;
        }
        if (!game.getPlayersWithGMRole().contains(getPlayer())) {
            MessageHelper.replyToMessage(event, "You are not GM in this game.");
            return;
        }
        if (FowMiltyService.startSetup(game, event)) {
            MessageHelper.replyToMessage(event, "FoW milty draft setup posted in the GM room.");
        } else {
            MessageHelper.replyToMessage(event, "Could not start the FoW milty draft — see the reason above.");
        }
    }
}

package ti4.discord.interactions.buttons.handlers.strategycard;

import java.util.List;
import lombok.experimental.UtilityClass;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import ti4.discord.interactions.buttons.Buttons;
import ti4.discord.interactions.routing.ButtonHandler;
import ti4.game.Game;
import ti4.game.Player;
import ti4.helpers.ButtonHelper;
import ti4.message.MessageHelper;
import ti4.service.button.ReactionService;
import ti4.service.payment.PaymentPanel;

@UtilityClass
class LeadershipStrategyCardButtonHandler {

    private static final String SPEND_FOR_TOKENS = "**Spending influence for command tokens**";

    @ButtonHandler("leadershipExhaust")
    public static void leadershipExhaust(ButtonInteractionEvent event, Player player, Game game) {
        ReactionService.addReaction(event, game, player);
        String message = player.getRepresentationUnfogged() + ", please choose the planets you wish to exhaust.";
        List<Button> buttons = ButtonHelper.getExhaustButtonsWithTG(game, player, "inf");
        Button doneExhausting = Buttons.red("deleteButtons_leadership", "Done Exhausting Planets");
        buttons.add(doneExhausting);
        MessageChannel channel = game.isFowMode() ? player.getPrivateChannel() : player.getCardsInfoThread();
        if (!PaymentPanel.post(channel, player, game, "### Leadership", SPEND_FOR_TOKENS, buttons)) {
            MessageHelper.sendMessageToChannelWithButtons(channel, message, buttons);
        }
    }
}

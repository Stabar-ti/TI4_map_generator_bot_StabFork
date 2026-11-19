package ti4.service.strategycard;

import java.util.List;
import java.util.function.UnaryOperator;
import lombok.experimental.UtilityClass;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import ti4.discord.interactions.buttons.Buttons;
import ti4.game.Game;
import ti4.game.Player;
import ti4.helpers.ButtonHelper;
import ti4.helpers.Helper;
import ti4.helpers.StringHelper;
import ti4.message.componentsV2.PanelEdits;
import ti4.service.payment.PaymentPanel;

@UtilityClass
public class LeadershipPanel {

    public static final int TOKENS = 9007;
    public static final String DONE_GAINING = "Done Gaining Command Tokens";
    private static final String RESET_SPEND_PREFIX = "resetSpend_";

    public static boolean post(
            MessageChannel channel, Player player, Game game, String header, List<Button> paymentButtons) {
        return PaymentPanel.postPayment(
                channel, player, game, header, TOKENS, tokensText(player, game), paymentButtons);
    }

    public static boolean isPanel(Message message) {
        return PanelEdits.isV2(message) && PaymentPanel.hasSlot(message.getComponentTree(), TOKENS);
    }

    public static List<Button> gainButtons(Player player) {
        String prefix = player.factionButtonChecker();
        return List.of(
                Buttons.green(prefix + "increase_tactic_cc", "Gain 1 Tactic Token"),
                Buttons.green(prefix + "increase_fleet_cc", "Gain 1 Fleet Token"),
                Buttons.green(prefix + "increase_strategy_cc", "Gain 1 Strategy Token"),
                Buttons.red(prefix + "deleteButtons_leadership", DONE_GAINING),
                Buttons.gray(prefix + "resetCCs", "Reset Command Tokens"));
    }

    public static boolean refreshTokens(ButtonInteractionEvent event, Player player, Game game) {
        if (event == null || !isPanel(event.getMessage())) {
            return false;
        }
        String tokens = tokensText(player, game);
        PaymentPanel.editContainer(event, container -> withTokens(container, tokens));
        return true;
    }

    public static boolean showGain(ButtonInteractionEvent event, Player player, Game game, String spentText) {
        if (!isPanel(event.getMessage())) {
            return false;
        }
        String tokens = tokensText(player, game);
        List<ActionRow> rows = PaymentPanel.rows(PaymentPanel.prepare(event.getChannel(), gainButtons(player)));
        PaymentPanel.editContainer(event, container -> gainView(container, tokens, spentText, rows));
        return true;
    }

    public static boolean finish(ButtonInteractionEvent event, Player player, Game game) {
        if (!isPanel(event.getMessage())) {
            return false;
        }
        String tokens = tokensText(player, game);
        PaymentPanel.editContainer(event, container -> PaymentPanel.closed(withTokens(container, tokens)));
        return true;
    }

    public static UnaryOperator<Container> restoredView(Player player, Game game, List<Button> savedButtons) {
        String tokens = tokensText(player, game);
        List<ActionRow> rows = PaymentPanel.rows(savedButtons);
        if (isPaying(savedButtons)) {
            String spent = Helper.buildSpentThingsMessage(player, game, PaymentPanel.payType(savedButtons));
            return container -> PaymentPanel.payView(withTokens(container, tokens), spent, rows);
        }
        return container -> PaymentPanel.withAccent(PanelEdits.withRows(withTokens(container, tokens), rows), false);
    }

    static Container gainView(Container container, String tokens, String spentText, List<ActionRow> rows) {
        Container paid = PaymentPanel.withSpent(
                withTokens(container, tokens), PaymentPanel.paymentText(PaymentPanel.PAID, spentText));
        return PaymentPanel.withAccent(PanelEdits.withRows(paid, rows), false);
    }

    static boolean isPaying(List<Button> buttons) {
        return buttons.stream()
                .map(Button::getCustomId)
                .anyMatch(id -> id != null && id.startsWith(RESET_SPEND_PREFIX));
    }

    static String tokensText(Player player, Game game) {
        String original = game.getStoredValue("originalCCsFor" + player.getFaction());
        String current = player.getCCRepresentation();
        String line = original.isEmpty() || original.equals(current)
                ? "**Command tokens** " + current
                : "**Command tokens** " + original + " → " + current + " · net gain **"
                        + ButtonHelper.checkNetGain(player, original) + "**";
        int inReinforcements = player.getCommandTokenLimit() - Helper.getCCCount(game, player.getColor());
        return line + "\n-# " + StringHelper.pluralize(inReinforcements, "command token") + " in reinforcements";
    }

    private static Container withTokens(Container container, String tokens) {
        return PaymentPanel.withSlotText(container, TOKENS, tokens);
    }
}

package ti4.service.strategycard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ti4.discord.interactions.buttons.Buttons;
import ti4.game.Game;
import ti4.game.Player;
import ti4.service.payment.PaymentPanel;
import ti4.testUtils.BaseTi4Test;

class LeadershipPanelTest extends BaseTi4Test {

    private Game game;
    private Player sol;

    @BeforeEach
    void setUp() {
        game = new Game();
        game.setName("leadership-panel-test");
        sol = game.addPlayer("sol-id", "sol");
        sol.setFaction("sol");
        sol.setColor("orange");
        sol.setTacticalCC(3);
        sol.setFleetCC(3);
        sol.setStrategicCC(2);
        game.setStoredValue("originalCCsFor" + sol.getFaction(), "3/3/2");
    }

    @Test
    void tokensLineShowsTheChangeOnlyOnceSomethingWasGained() {
        assertThat(LeadershipPanel.tokensText(sol, game)).startsWith("**Command tokens** 3/3/2\n-# ");

        sol.setFleetCC(4);
        sol.setStrategicCC(3);

        assertThat(LeadershipPanel.tokensText(sol, game))
                .startsWith("**Command tokens** 3/3/2 → 3/4/3 · net gain **2**")
                .endsWith("in reinforcements");
    }

    @Test
    void gainViewSwapsPaymentRowsForTokenButtonsAndKeepsTheReceipt() {
        List<ActionRow> paymentRows = PaymentPanel.rows(
                List.of(Buttons.green("spend_meer_inf", "Meer"), Buttons.gray("resetSpend_inf", "Reset")));
        Container paying = PaymentPanel.payView(
                Container.of(TextDisplay.of("tokens").withUniqueId(LeadershipPanel.TOKENS)), "spent", paymentRows);
        List<ActionRow> gainRows = PaymentPanel.rows(LeadershipPanel.gainButtons(sol));

        Container gaining = LeadershipPanel.gainView(paying, "new tokens", "Meer", gainRows);

        List<String> ids = gaining.getComponents().stream()
                .filter(ActionRow.class::isInstance)
                .flatMap(row -> ((ActionRow) row).getButtons().stream())
                .map(Button::getCustomId)
                .toList();
        assertThat(ids).noneMatch(id -> id.contains("spend"));
        assertThat(ids).anyMatch(id -> id.endsWith("increase_fleet_cc"));
        assertThat(texts(gaining)).contains("new tokens", PaymentPanel.paymentText(PaymentPanel.PAID, "Meer"));
    }

    @Test
    void undoRestoresThePaymentOnlyWhenTheSavedRowsWereStillPaying() {
        assertThat(LeadershipPanel.isPaying(List.of(Buttons.gray("resetSpend_inf", "Reset"))))
                .isTrue();
        assertThat(LeadershipPanel.isPaying(LeadershipPanel.gainButtons(sol))).isFalse();
    }

    private static List<String> texts(Container container) {
        return container.getComponents().stream()
                .filter(TextDisplay.class::isInstance)
                .map(child -> ((TextDisplay) child).getContent())
                .toList();
    }
}

package ti4.service.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import org.junit.jupiter.api.Test;
import ti4.discord.interactions.buttons.Buttons;

class PaymentPanelTest {

    @Test
    void payTypeComesFromTheResetButtonThatEveryPaymentCarries() {
        assertThat(PaymentPanel.payType(List.of(
                        Buttons.green("spend_lodor_inftech", "Lodor"),
                        Buttons.gray("resetSpend_inftech", "Reset Spent Planets and Trade Goods"))))
                .isEqualTo("inftech");
        // A build view has no payment buttons yet; resources is the safe default.
        assertThat(PaymentPanel.payType(List.of(Buttons.green("place_dd_101", "Produce"))))
                .isEqualTo("res");
    }

    @Test
    void pagingKeepsThePayTypeSoTurningThePageRedrawsTheSamePayment() {
        List<Button> buttons = new ArrayList<>(IntStream.range(0, 30)
                .mapToObj(i -> Buttons.green("spend_p" + i + "_restech", "P" + i))
                .toList());
        buttons.add(Buttons.gray("resetSpend_restech", "Reset"));
        buttons.add(Buttons.red("deleteButtons_technology", "Done Exhausting Planets"));

        List<String> ids = PaymentPanel.firstPaymentPage(buttons).stream()
                .map(Button::getCustomId)
                .toList();

        // SpendingButtonHandler.turnSpendPage reads "restech" back out of this id.
        assertThat(ids).contains("spendPage_restech_page1", "resetSpend_restech", "deleteButtons_technology");
    }

    @Test
    void paymentOnlyPanelOpensWithItsPurposeAndThePaymentSoFar() {
        List<MessageTopLevelComponent> panel = PaymentPanel.compose(
                "### Scoring an objective",
                null,
                PaymentPanel.COST,
                "**Sway the Council**",
                "nothing spent",
                List.of(Buttons.green("spend_meer_both", "Meer"), Buttons.gray("resetSpend_both", "Reset")),
                false);

        List<Integer> slots = MessageComponentTree.of(panel).findAll(TextDisplay.class).stream()
                .map(TextDisplay::getUniqueId)
                .toList();
        assertThat(slots).contains(PaymentPanel.COST, PaymentPanel.SPENT);
    }

    @Test
    void undoMarkerNamesThePanelKindAndTheMessageItRestores() {
        String marker = PaymentPanel.undoMarker("tech", "123");

        assertThat(PaymentPanel.isUndoMarkerFor(marker, "123")).isTrue();
        assertThat(PaymentPanel.isUndoMarkerFor(marker, "23")).isFalse();
        assertThat(PaymentPanel.isUndoMarkerFor("Updated", "123")).isFalse();
    }
}

package ti4.service.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import org.junit.jupiter.api.Test;
import ti4.discord.interactions.buttons.Buttons;
import ti4.helpers.Helper;
import ti4.message.componentsV2.ComponentPanel;
import ti4.service.payment.PaymentPanel;

class BuildPanelTest {

    private static final String SPENT =
            "Stabar" + Helper.SPENT_THINGS_LEAD + "> Jord\nfor a total spend of 4 resources.";

    @Test
    void spendableListShrinksToPlanetTotalsButKeepsTradeGoodsAndExtras() {
        String spendable =
                "You have available to you to spend: <:a:1> Arretze 2/0, <:h:2> Hercant 1/1, and 3 trade goods."
                        + " You also have _Sarween Tools_. As such, you may spend up to 7 during the PRODUCTION.";
        assertThat(BuildPanel.spendableSummary(2, 3, 1, spendable))
                .isEqualTo("2 planets ready (3 resources, 1 influence) and 3 trade goods."
                        + " You also have _Sarween Tools_. As such, you may spend up to 7 during the PRODUCTION.");

        assertThat(BuildPanel.spendableSummary(
                        0, 0, 0, "You have available to you to spend:  No readied planets and 3 trade goods."))
                .isEqualTo("No planets ready and 3 trade goods.");
    }

    @Test
    void composesHeaderInfoProducedAndTheButtonsInsideOneContainer() {
        List<Button> buttons = buttons(7);

        List<MessageTopLevelComponent> panel =
                BuildPanel.compose("### Build in 101", "info", BuildPanel.NOTHING_PRODUCED, buttons, false);

        assertThat(panel).hasSize(1);
        List<? extends ContainerChildComponent> children = container(panel).getComponents();
        assertThat(text(children.get(0))).isEqualTo("### Build in 101");
        assertThat(children.get(1)).isInstanceOf(Separator.class);
        assertThat(children.get(2).getUniqueId()).isEqualTo(PaymentPanel.INFO);
        assertThat(children.get(3).getUniqueId()).isEqualTo(PaymentPanel.INFO_DIVIDER);
        assertThat(children.get(4).getUniqueId()).isEqualTo(BuildPanel.PRODUCED);
        // Every id unchanged, in order, so the existing handlers keep routing.
        assertThat(buttonIds(container(panel))).containsExactlyElementsOf(ids(buttons));
    }

    @Test
    void blankInfoLeavesOnlyTheProducedSlotAboveTheButtons() {
        Container container = container(BuildPanel.compose("h", " ", "produced", buttons(1), false));

        assertThat(container.getComponents()).hasSize(4);
        assertThat(text(container.getComponents().get(2))).isEqualTo("produced");
    }

    @Test
    void accentWarnsWhenOverTheProductionLimit() {
        assertThat(container(BuildPanel.compose("h", "i", "p", buttons(1), false))
                        .getAccentColorRaw())
                .isEqualTo(ComponentPanel.ACCENT);
        assertThat(container(BuildPanel.compose("h", "i", "p", buttons(1), true))
                        .getAccentColorRaw())
                .isEqualTo(ComponentPanel.WARNING_ACCENT);
    }

    @Test
    void returnsNothingWhenThePanelWouldNotFitSoTheCallerFallsBackToClassicMessages() {
        // Container + header + divider + info + divider + produced = 6, then each row costs 1 + its buttons.
        assertThat(BuildPanel.compose("h", "i", "p", buttons(25), false)).isNotEmpty();
        assertThat(BuildPanel.compose("h", "i", "p", buttons(35), false)).isEmpty();
        assertThat(BuildPanel.compose("h", "i".repeat(4000), "p", buttons(1), false))
                .isEmpty();
    }

    @Test
    void payViewAddsThePaymentBelowProducedAndSwapsTheButtons() {
        Container build = container(BuildPanel.compose("h", "info", "produced", buttons(3), true));
        List<Button> payButtons = List.of(Buttons.green("spend_jord_res", "Jord"));

        Container pay = PaymentPanel.payView(build, SPENT, List.of(ActionRow.of(payButtons)));

        List<? extends ContainerChildComponent> children = pay.getComponents();
        assertThat(children.get(4).getUniqueId()).isEqualTo(BuildPanel.PRODUCED);
        assertThat(children.get(5).getUniqueId()).isEqualTo(PaymentPanel.SPENT_DIVIDER);
        assertThat(text(children.get(6)))
                .isEqualTo(PaymentPanel.PAYMENT + "\n> Jord\nfor a total spend of 4 resources.");
        assertThat(buttonIds(pay)).containsExactly("spend_jord_res");
        assertThat(pay.getAccentColorRaw()).isEqualTo(ComponentPanel.ACCENT);

        // A second spend replaces the slot instead of adding another one.
        Container respent =
                PaymentPanel.withPayment(pay, PaymentPanel.PAYMENT, SPENT + "\n" + Helper.NOT_ENOUGH_SPENT_WARNING);
        assertThat(respent.getComponents()).hasSameSizeAs(children);
        assertThat(respent.getAccentColorRaw()).isEqualTo(ComponentPanel.WARNING_ACCENT);
    }

    @Test
    void receiptDropsInfoAndButtonsAndKeepsWhatWasBuiltAndPaid() {
        Container build = container(BuildPanel.compose("h", "info", "produced", buttons(3), true));

        Container receipt = PaymentPanel.receipt(build, SPENT);

        assertThat(receipt.getComponents())
                .extracting(ContainerChildComponent::getUniqueId)
                .doesNotContain(PaymentPanel.INFO, PaymentPanel.INFO_DIVIDER)
                .contains(BuildPanel.PRODUCED, PaymentPanel.SPENT);
        assertThat(buttonIds(receipt)).isEmpty();
        assertThat(text(receipt.getComponents().getLast())).startsWith(PaymentPanel.PAID);
        assertThat(receipt.getAccentColorRaw()).isEqualTo(ComponentPanel.ACCENT);
    }

    @Test
    void manyPlanetsArePagedWhileResetDoneAndUndoStayOnEveryPage() {
        List<Button> planets = IntStream.range(0, 36)
                .mapToObj(i -> Buttons.green("spend_p" + i + "_res", "P" + i))
                .toList();
        List<Button> pinned = List.of(
                Buttons.gray("resetSpend_res", "Reset"),
                Buttons.red("deleteButtons_tacticalAction", "Done Exhausting Planets"),
                Buttons.gray("ultimateUndo_7", "UNDO"));
        List<Button> all = new ArrayList<>(planets);
        all.addAll(pinned);

        List<Button> page = PaymentPanel.firstPaymentPage(all);

        assertThat(page).hasSizeLessThanOrEqualTo(25);
        assertThat(ids(page)).containsAll(ids(pinned)).contains("spend_p0_res", "spendPage_res_page1");
        // The page id must parse back in SpendingButtonHandler.turnSpendPage: whatIsItFor "res", page 1.
        assertThat(ids(page)).doesNotContain("spend_p35_res");
        assertThat(BuildPanel.compose("h", "i", "p", page, false)).isNotEmpty();
    }

    private static List<Button> buttons(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> Buttons.green("place_dd_10" + i, "Produce " + i))
                .toList();
    }

    private static List<String> ids(List<Button> buttons) {
        return buttons.stream().map(Button::getCustomId).toList();
    }

    private static List<String> buttonIds(Container container) {
        return container.getComponents().stream()
                .filter(ActionRow.class::isInstance)
                .flatMap(row -> ((ActionRow) row).getButtons().stream())
                .map(Button::getCustomId)
                .toList();
    }

    private static Container container(List<MessageTopLevelComponent> panel) {
        return (Container) panel.getFirst();
    }

    private static String text(ContainerChildComponent component) {
        return ((TextDisplay) component).getContent();
    }
}

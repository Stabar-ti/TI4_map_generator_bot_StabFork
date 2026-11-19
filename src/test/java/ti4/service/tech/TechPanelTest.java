package ti4.service.tech;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ti4.discord.interactions.buttons.Buttons;
import ti4.game.Game;
import ti4.game.Player;
import ti4.game.Tile;
import ti4.model.TechnologyModel.TechnologyType;
import ti4.service.payment.PaymentPanel;
import ti4.testUtils.BaseTi4Test;

class TechPanelTest extends BaseTi4Test {

    private Game game;
    private Player jolnar;

    @BeforeEach
    void setUp() {
        game = new Game();
        game.setName("tech-panel-test");
        // 19 Wellon (cybernetic), 31 Lazar (cybernetic) + Sakulag, 24 Mehar Xull (warfare), 26 Lodor (none).
        String[][] tiles = {{"19", "101"}, {"31", "102"}, {"24", "103"}, {"26", "104"}};
        for (String[] tile : tiles) {
            game.setTile(new Tile(tile[0], tile[1]));
        }
        jolnar = game.addPlayer("jolnar-id", "jolnar");
        jolnar.setFaction("jolnar");
        jolnar.setColor("blue");
        // Added directly, skipping addPlanet/addTech side effects (lore, unit upgrades).
        jolnar.getPlanets().addAll(List.of("wellon", "lazar", "sakulag", "meharxull", "lodor"));
        jolnar.getTechs().addAll(List.of("dd2", "st", "nm", "amd", "sdn", "gd"));
        jolnar.getExhaustedTechs().add("sdn");
    }

    @Test
    void tooManyTechsForOnePanelFallBackToTheTypeChooserWhichAlwaysFits() {
        List<Button> manyTechs = java.util.stream.IntStream.range(0, 30)
                .mapToObj(n -> Buttons.gray("FFCC_jolnar_getTech_t" + n, "Technology " + n))
                .toList();
        assertThat(TechPanel.compose(jolnar, game, TechPanel.PICK_HINT, manyTechs))
                .isEmpty();

        List<Button> types =
                ListTechService.techTypeButtons(TechnologyType.mainFive, "FFCC_jolnar_getAllTechOfType_", "");
        assertThat(types).hasSize(5);
        assertThat(types.getFirst().getCustomId()).isEqualTo("FFCC_jolnar_getAllTechOfType_propulsion");
        assertThat(TechPanel.compose(jolnar, game, TechPanel.TOO_MANY_HINT, types))
                .isNotEmpty();
    }

    @Test
    void ownedTechsAreGroupedByColorInTheUsualOrderWithExhaustedOnesStruckThrough() {
        String owned = TechPanel.ownedText(jolnar);

        assertThat(owned.split("\n"))
                .containsExactly(
                        "**Technologies**",
                        TechnologyType.PROPULSION.emoji() + " **2** Antimass Deflectors, Gravity Drive",
                        TechnologyType.BIOTIC.emoji() + " **1** Neural Motivator",
                        TechnologyType.CYBERNETIC.emoji() + " **2** Sarween Tools, ~~Scanlink Drone Network~~",
                        TechnologyType.UNITUPGRADE.emoji() + " **1** Destroyer II");
    }

    @Test
    void skipsCountReadyPlanetsAndExhaustedOnesOnlyWithPsychoarchaeology() {
        jolnar.getExhaustedPlanets().add("lazar");

        String skips = TechPanel.skipsText(jolnar, game);
        assertThat(skips).contains("×1 (Wellon)").contains("×1 (Mehar Xull)").doesNotContain("Lazar");

        jolnar.getTechs().add("pa");
        assertThat(TechPanel.skipsText(jolnar, game)).contains("×2 (Wellon, Lazar)");
    }

    @Test
    void noSkipPlanetsSaysSo() {
        jolnar.getPlanets().clear();
        jolnar.getPlanets().add("lodor");

        assertThat(TechPanel.skipsText(jolnar, game)).startsWith("-# No tech skips available.");
    }

    @Test
    void undoRestoresThePayViewOnlyWhenThePaymentButtonsWereSaved() {
        Container pick = pickPanel();
        List<Button> payButtons = List.of(
                Buttons.red("spend_wellon_restech", "Wellon"),
                Buttons.gray("resetSpend_restech", "Reset Spent Planets and Trade Goods"),
                Buttons.red(TechPanel.PAY_DONE_ID, "Done Exhausting Planets"));

        Container pay = TechPanel.restoredView(jolnar, game, payButtons).apply(pick);
        assertThat(ids(pay)).contains(PaymentPanel.SPENT);

        Container backToPick = TechPanel.restoredView(
                        jolnar, game, List.of(Buttons.green("getTech_gd", "Gravity Drive")))
                .apply(pay);
        assertThat(ids(backToPick)).doesNotContain(PaymentPanel.SPENT, PaymentPanel.SPENT_DIVIDER);
        assertThat(slot(backToPick, TechPanel.RESEARCH)).isEqualTo(TechPanel.PICK_HINT);
    }

    private Container pickPanel() {
        return (Container) PaymentPanel.compose(
                        TechPanel.HEADER,
                        TechPanel.info(jolnar, game),
                        TechPanel.RESEARCH,
                        TechPanel.PICK_HINT,
                        List.of(Buttons.green("getTech_gd", "Gravity Drive")),
                        false)
                .getFirst();
    }

    private static List<Integer> ids(Container container) {
        return container.getComponents().stream()
                .map(ContainerChildComponent::getUniqueId)
                .toList();
    }

    private static String slot(Container container, int uniqueId) {
        return container.getComponents().stream()
                .filter(child -> child.getUniqueId() == uniqueId)
                .map(child -> ((TextDisplay) child).getContent())
                .findFirst()
                .orElse(null);
    }
}

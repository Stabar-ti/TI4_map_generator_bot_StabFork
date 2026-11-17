package ti4.helpers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ti4.game.Game;
import ti4.game.Tile;
import ti4.testUtils.BaseTi4Test;

class SpendOrderTest extends BaseTi4Test {

    // Listed in "acquisition" order; resources/influence: lodor 3/1, quann 2/1, vefutii 2/2, thibah 1/1,
    // arinam 1/2, meer 0/4.
    private static final List<String> GAINED = List.of("thibah", "meer", "vefutii", "arinam", "quann", "lodor");

    private Game game;

    @BeforeEach
    void setUp() {
        game = new Game();
        game.setName("spend-order-test");
        String[][] tiles = {{"26", "101"}, {"25", "102"}, {"20", "103"}, {"21", "104"}, {"37", "105"}};
        for (String[] tile : tiles) {
            game.setTile(new Tile(tile[0], tile[1]));
        }
    }

    @Test
    void resourcePaymentsPutTheMostResourceLeaningPlanetsFirst() {
        // vefutii and thibah both lean 0; the one worth more resources comes first.
        assertThat(sorted(false, false)).containsExactly("lodor", "quann", "vefutii", "thibah", "arinam", "meer");
    }

    @Test
    void influencePaymentsFlipTheOrder() {
        assertThat(sorted(true, false)).containsExactly("meer", "arinam", "vefutii", "thibah", "quann", "lodor");
    }

    @Test
    void countingTheHigherValueSortsByThatValueAndKeepsAcquisitionOrderOnTies() {
        // Emergency Appropriations / Wildlife Preservation: meer 4, lodor 3, vefutii 2 = quann 2, arinam 2, thibah 1.
        assertThat(sorted(false, true)).containsExactly("meer", "lodor", "vefutii", "arinam", "quann", "thibah");
    }

    @Test
    void homePlanetsAreMarkedOnTheirSpendButton() {
        assertThat(Helper.spendLabel("lodor", game, Set.of("lodor"))).endsWith(" (home)");
        assertThat(Helper.spendLabel("quann", game, Set.of("lodor"))).doesNotContain("(home)");
    }

    private List<String> sorted(boolean payingInfluence, boolean countsHigherValue) {
        List<String> planets = new ArrayList<>(GAINED);
        planets.sort(Helper.spendOrder(game, payingInfluence, countsHigherValue));
        return planets;
    }
}

package ti4.helpers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ti4.game.Game;
import ti4.game.Player;
import ti4.game.Tile;
import ti4.testUtils.BaseTi4Test;

class ProductionLimitTest extends BaseTi4Test {

    private Game game;
    private Player sol;

    @BeforeEach
    void setUp() {
        game = new Game();
        game.setName("production-limit-test");
        // An empty-space tile (no planets, no space dock) so the build has a place but no PRODUCTION.
        game.setTile(new Tile("41", "101"));
        sol = game.addPlayer("sol-id", "sol");
        sol.setFaction("sol");
        sol.setColor("blue");
        sol.addOwnedUnitByID("destroyer");
        sol.addOwnedUnitByID("fighter");
    }

    @Test
    void limitLabelsAndThresholdMatchTheProducedUnitsText() {
        ProductionLimit production = new ProductionLimit(3, false);
        ProductionLimit bellum = new ProductionLimit(2, true);

        assertThat(production.label()).isEqualTo("PRODUCTION");
        assertThat(bellum.label()).isEqualTo("_Bellum Gloriosum_");
        // The limit itself is still allowed; only going past it warns.
        assertThat(production.isExceededBy(3)).isFalse();
        assertThat(production.isExceededBy(4)).isTrue();
    }

    @Test
    void producedPlacesKeepsFirstSeenOrderWithoutDuplicates() {
        List<String> places =
                ProductionLimit.producedPlaces(List.of("dd_101_space", "gf_101_jord", "ff_101_space", "dd_202_space"));

        assertThat(places).containsExactly("101_space", "101_jord", "202_space");
    }

    @Test
    void neverExceededWithAtMostOneUnitOrOutsideTheActiveSystem() {
        assertThat(ProductionLimit.isExceeded(sol, game)).isFalse();

        sol.produceUnit("dd_101_space");
        assertThat(ProductionLimit.isExceeded(sol, game)).isFalse();

        // No active system, so no PRODUCTION limit applies at all (e.g. a Construction or agent build).
        sol.produceUnit("dd_101_space");
        sol.produceUnit("dd_101_space");
        assertThat(ProductionLimit.isExceeded(sol, game)).isFalse();
    }

    @Test
    void producedUnitsTextWithoutAnActiveSystemStillReportsTheTotal() {
        sol.produceUnit("dd_101_space");
        sol.produceUnit("dd_101_space");

        String text = Helper.buildProducedUnitsMessage(sol, game);

        // Same sentence the classic build message has always ended with outside the active system.
        assertThat(text).contains("Producing a total of 2 units for a total cost of 2 resources.");
        assertThat(text).doesNotContain("limit");
    }
}

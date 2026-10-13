package ti4.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ti4.game.Game;
import ti4.game.Tile;
import ti4.testUtils.BaseTi4Test;

class SectorBordersTest extends BaseTi4Test {

    private static final String EMPTY = "19";
    private static final String EDGE = "101";
    private static final String OUTSIDE = "201";

    private Game game;
    private Set<String> shown;
    private Set<String> known;
    private int towardsOutside;

    // Sector "home" is 000 and its first ring; 201 sits just north of 101 in a second, assigned sector.
    @BeforeEach
    void setUp() {
        game = new Game();
        game.newGameSetup();
        game.setName("sector-borders-test");
        game.setFowMode(true);
        MapFrame.positionsWithin("000", 1).forEach(position -> game.setTile(new Tile(EMPTY, position)));
        game.setTile(new Tile(EMPTY, OUTSIDE));
        MapSegment.put(game, new MapSegment("home", "000", 1));
        MapSegment.assign(game, "north", Set.of(OUTSIDE));
        shown = MapSegment.find(game, "home").orElseThrow().positions();
        known = new HashSet<>(game.getTileMap().keySet());
        towardsOutside = PositionMapper.getAdjacentTilePositions(EDGE).indexOf(OUTSIDE);
    }

    @Test
    void knownNeighbourInAnotherSectorGetsAnArrow() {
        assertThat(towardsOutside).isNotNegative();

        Map<String, Map<Integer, String>> arrows = SectorBorders.find(game, shown, known);

        assertThat(arrows).isEqualTo(Map.of(EDGE, Map.of(towardsOutside, OUTSIDE)));
    }

    // Leak guard: a system the viewer never saw must not be hinted at by an arrow.
    @Test
    void unknownNeighbourGetsNoArrow() {
        known.remove(OUTSIDE);

        assertThat(SectorBorders.find(game, shown, known)).isEmpty();
    }

    @Test
    void neighbourInsideTheShownSectorGetsNoArrow() {
        Set<String> wider = new HashSet<>(shown);
        wider.add(OUTSIDE);

        assertThat(SectorBorders.find(game, wider, known)).isEmpty();
    }

    // The override already draws its own arrow on that edge.
    @Test
    void edgeWithAnAdjacencyOverrideGetsNoSecondArrow() {
        game.addAdjacentTileOverride(EDGE, towardsOutside, OUTSIDE);

        assertThat(SectorBorders.find(game, shown, known)).doesNotContainKey(EDGE);
    }

    @Test
    void borderAnomalyThatBlocksTheEdgeRemovesTheArrow() {
        game.addBorderAnomaly(EDGE, towardsOutside, "spatial_tear");

        assertThat(SectorBorders.find(game, shown, known)).isEmpty();
    }

    @Test
    void unknownSourceTileDrawsNoArrow() {
        known.remove(EDGE);

        assertThat(SectorBorders.find(game, shown, known)).isEmpty();
    }
}

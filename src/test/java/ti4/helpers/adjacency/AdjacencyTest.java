package ti4.helpers.adjacency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ti4.game.Game;
import ti4.game.persistence.TestGameHarness;
import ti4.model.MapPositionMapper;
import ti4.testUtils.BaseTi4Test;

class AdjacencyTest extends BaseTi4Test {

    private static final String HOLDER = "101";

    // A gravity wave on the holder blocks entering the holder only, so the link runs holder -> neighbour.
    @Test
    void gravityWaveMakesTheHolderReachTheNeighbourOneWay() {
        try (var harness = TestGameHarness.fromSourceGame("game-with-border-anomalies")) {
            Game game = harness.load();
            String neighbour = gravityWaveNeighbour(game);
            Adjacency adjacency = Adjacency.of(game);

            assertThat(adjacency.from(HOLDER)).contains(neighbour);
            assertThat(adjacency.from(neighbour)).doesNotContain(HOLDER);
            assertThat(adjacency.into(neighbour)).contains(HOLDER);
            assertThat(adjacency.into(HOLDER)).doesNotContain(neighbour);
            assertThat(adjacency.either(neighbour)).contains(HOLDER);
            assertThat(adjacency.either(HOLDER)).contains(neighbour);
        }
    }

    @Test
    void selfIsOnlyIncludedWhenAsked() {
        try (var harness = TestGameHarness.fromSourceGame("game-with-border-anomalies")) {
            Game game = harness.load();
            Adjacency adjacency = Adjacency.of(game);

            assertThat(adjacency.from(HOLDER)).doesNotContain(HOLDER);
            assertThat(adjacency.withSelf().from(HOLDER)).contains(HOLDER);
            assertThat(adjacency.withSelf().into(HOLDER)).contains(HOLDER);
            assertThat(adjacency.isAdjacent(HOLDER, HOLDER)).isTrue();
        }
    }

    // Callers add to the returned sets, so a query must never hand out its cached set.
    @Test
    void changingAResultDoesNotChangeTheNextOne() {
        try (var harness = TestGameHarness.fromSourceGame("game-with-border-anomalies")) {
            Game game = harness.load();
            Adjacency adjacency = Adjacency.of(game);
            int size = adjacency.from(HOLDER).size();

            adjacency.from(HOLDER).add("not-a-tile");
            adjacency.into(HOLDER).clear();

            assertThat(adjacency.from(HOLDER)).hasSize(size).doesNotContain("not-a-tile");
            assertThat(adjacency.into(HOLDER)).isNotEmpty();
        }
    }

    private static String gravityWaveNeighbour(Game game) {
        List<String> neighbours = MapPositionMapper.getAdjacentTilePositions(HOLDER);
        for (int direction = 0; direction < neighbours.size(); direction++) {
            String neighbour = neighbours.get(direction);
            if (game.getTileByPosition(neighbour) != null && game.getAdjacentTileOverride(HOLDER, direction) == null) {
                game.setBorderAnomalies(new ArrayList<>());
                game.addBorderAnomaly(HOLDER, direction, "gravity_wave");
                return neighbour;
            }
        }
        throw new AssertionError("Test map has no plain neighbour for " + HOLDER);
    }
}

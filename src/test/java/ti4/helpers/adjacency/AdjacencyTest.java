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
            game.setOneWayAdjacency(true);
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

    // With one-way adjacency off (the default outside fog), a one-way link only restricts movement.
    @Test
    void offModeKeepsMovementOneWayAndEverythingElseBothWays() {
        try (var harness = TestGameHarness.fromSourceGame("game-with-border-anomalies")) {
            Game game = harness.load();
            game.setFowMode(false);
            String neighbour = gravityWaveNeighbour(game);

            assertThat(Adjacency.of(game).forMovement().from(neighbour)).doesNotContain(HOLDER);
            assertThat(Adjacency.of(game).forMovement().into(HOLDER)).doesNotContain(neighbour);
            for (Adjacency adjacency :
                    List.of(Adjacency.of(game).forEffects(), Adjacency.of(game).forVision())) {
                assertThat(adjacency.from(neighbour)).contains(HOLDER);
                assertThat(adjacency.into(HOLDER)).contains(neighbour);
                assertThat(adjacency.isAdjacent(neighbour, HOLDER)).isTrue();
            }
        }
    }

    @Test
    void fogFollowsDirectionEvenWithTheToggleOff() {
        try (var harness = TestGameHarness.fromSourceGame("game-with-border-anomalies")) {
            Game game = harness.load();
            game.setFowMode(true);
            game.setOneWayAdjacency(false);
            String neighbour = gravityWaveNeighbour(game);

            assertThat(Adjacency.of(game).forEffects().from(neighbour)).doesNotContain(HOLDER);
            assertThat(Adjacency.of(game).forVision().from(neighbour)).doesNotContain(HOLDER);
        }
    }

    // A border that blocks both ways stays blocked in off mode: the union of two missing links is still missing.
    @Test
    void offModeKeepsTwoWayBlocks() {
        try (var harness = TestGameHarness.fromSourceGame("game-with-border-anomalies")) {
            Game game = harness.load();
            game.setFowMode(false);
            String neighbour = gravityWaveNeighbour(game);
            game.setBorderAnomalies(new ArrayList<>());
            game.addBorderAnomaly(HOLDER, directionOf(neighbour), "spatial_tear");

            assertThat(Adjacency.of(game).forEffects().from(HOLDER)).doesNotContain(neighbour);
            assertThat(Adjacency.of(game).forEffects().from(neighbour)).doesNotContain(HOLDER);
        }
    }

    // Hyperlane tiles are not systems: they stay out of reach in off mode, and from/into still mirror each other.
    @Test
    void offModeIsSymmetricBetweenSystemsAndLeavesHyperlaneTilesEmpty() {
        try (var harness = TestGameHarness.fromSourceGame("pbd15036")) {
            Game game = harness.load();
            game.setFowMode(false);
            Adjacency adjacency = Adjacency.of(game).forEffects();
            List<String> positions = AdjacencyScenarios.positions(game);
            List<String> problems = new ArrayList<>();
            for (String a : positions) {
                boolean aIsHyperlane = game.getTileByPosition(a).getTileModel().isHyperlane();
                if (aIsHyperlane && !adjacency.from(a).isEmpty()) problems.add(a + " is a hyperlane tile with links");
                for (String b : positions) {
                    boolean bIsHyperlane =
                            game.getTileByPosition(b).getTileModel().isHyperlane();
                    if (adjacency.from(a).contains(b) != adjacency.into(b).contains(a)) {
                        problems.add(a + "->" + b + " from/into disagree");
                    }
                    if (!aIsHyperlane
                            && !bIsHyperlane
                            && adjacency.from(a).contains(b)
                                    != adjacency.from(b).contains(a)) {
                        problems.add(a + "<->" + b + " not symmetric");
                    }
                }
            }
            assertThat(problems).isEmpty();
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

    private static int directionOf(String neighbour) {
        return MapPositionMapper.getAdjacentTilePositions(HOLDER).indexOf(neighbour);
    }
}

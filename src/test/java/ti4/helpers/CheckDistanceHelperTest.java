package ti4.helpers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ti4.discord.JdaService;
import ti4.game.Game;
import ti4.game.Player;
import ti4.game.Tile;
import ti4.helpers.Units.UnitType;
import ti4.image.Mapper;
import ti4.image.PositionMapper;
import ti4.testUtils.BaseTi4Test;

/**
 * Regression guards for {@code getShortestVisibleDistance}: the hop count a player could work out
 * themselves using only territory they've actually explored, used to give a non-leaking distance
 * reminder for moves into undiscovered Fog-of-War systems.
 */
class CheckDistanceHelperTest extends BaseTi4Test {

    private Game game;
    private Player player;
    private Tile sourceTile;
    private Tile midTile;
    private Tile destinationTile;

    @BeforeEach
    void setUp() {
        JdaService.testingMode = true;
        JdaService.jda = org.mockito.Mockito.mock(JDA.class);

        game = new Game();
        game.setName("test-game");
        player = game.addPlayer("test-user-id", "winnu");
        player.setFaction("winnu");
        player.setColor("red");

        // A real 2-hop chain from the standard hex map: source -> mid -> destination.
        String sourcePos = "000";
        List<String> ring1 = PositionMapper.getAdjacentTilePositions(sourcePos);
        String midPos = ring1.stream()
                .filter(p -> p != null && !"x".equals(p))
                .findFirst()
                .orElseThrow();
        List<String> ring2 = PositionMapper.getAdjacentTilePositions(midPos);
        String destPos = ring2.stream()
                .filter(p -> p != null && !"x".equals(p) && !p.equals(sourcePos) && !ring1.contains(p))
                .findFirst()
                .orElseThrow();

        sourceTile = new Tile("18", sourcePos);
        midTile = new Tile("19", midPos);
        destinationTile = new Tile("20", destPos);
        game.setTile(sourceTile);
        game.setTile(midTile);
        game.setTile(destinationTile);

        // Player has units on the source tile, so it (and its immediate neighbors) are visible.
        sourceTile.addUnit(Constants.SPACE, new Units.UnitKey(UnitType.Carrier, "red"), 1);
    }

    @Test
    void sameTile_isZero() {
        assertThat(CheckDistanceHelper.getShortestVisibleDistance(
                        game, player, sourceTile.getPosition(), sourceTile.getPosition()))
                .isZero();
    }

    @Test
    void destinationReachableThroughVisibleTerritory_returnsRealHopCount() {
        // The mid tile is adjacent to the player's own tile, so it's visible - the path through it
        // to the destination is fully verifiable from explored territory.
        assertThat(CheckDistanceHelper.getShortestVisibleDistance(
                        game, player, sourceTile.getPosition(), destinationTile.getPosition()))
                .isEqualTo(2);
    }

    @Test
    void destinationOnlyReachableThroughUndiscoveredTerritory_isUnknown() {
        // Nothing marks the mid tile as visible to the player (no units, no CC), so the only path to
        // the destination runs through undiscovered territory - the player can't verify any route.
        Player lonelyPlayer = game.addPlayer("other-user-id", "arborec");
        lonelyPlayer.setFaction("arborec");
        lonelyPlayer.setColor("blue");

        assertThat(CheckDistanceHelper.getShortestVisibleDistance(
                        game, lonelyPlayer, sourceTile.getPosition(), destinationTile.getPosition()))
                .isEqualTo(100);
    }

    @Test
    void destinationVisibleViaCommandCounter_shortcutsThroughIt() {
        // Marking the mid tile itself visible via CC (not just adjacency) should still let the BFS
        // route through it.
        midTile.addCC(Mapper.getSweepID(player.getColor()));
        assertThat(CheckDistanceHelper.getShortestVisibleDistance(
                        game, player, sourceTile.getPosition(), destinationTile.getPosition()))
                .isEqualTo(2);
    }

    @Test
    void nebulaWaypoint_blocksPathEvenThoughVisible() {
        // The mid tile is visible (adjacent to the player's own tile) but is a nebula, which blocks
        // continuing movement through it - the same real distance-calculation rule a normal
        // pre-movement distance check applies, not just raw grid adjacency.
        midTile.addToken("token_nebula_async.png", Constants.SPACE);
        assertThat(CheckDistanceHelper.getShortestVisibleDistance(
                        game, player, sourceTile.getPosition(), destinationTile.getPosition()))
                .isEqualTo(100);
    }
}

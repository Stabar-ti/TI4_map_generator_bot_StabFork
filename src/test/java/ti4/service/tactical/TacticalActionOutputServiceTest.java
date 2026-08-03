package ti4.service.tactical;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ti4.discord.JdaService;
import ti4.game.Game;
import ti4.game.Player;
import ti4.game.Tile;
import ti4.helpers.Units;
import ti4.helpers.Units.UnitState;
import ti4.helpers.Units.UnitType;
import ti4.image.Mapper;
import ti4.image.PositionMapper;
import ti4.service.option.FOWOptionService.FOWOption;
import ti4.testUtils.BaseTi4Test;

/**
 * Regression guards for the Fog-of-War gate on {@code anyMoveExceedsRangeIntoUnseenDestination}: a
 * move that exceeds a unit's move value must only be flagged for GM review when the destination is a
 * system the mover has not actually discovered AND the {@code MOVE_RANGE_GM_REVIEW} FoW option is
 * enabled. A visible destination, a non-FoW game, or the option being off should never trip the
 * gate, matching the unchanged behavior of the inline "(distance exceeds move value...)" warning
 * text for those cases.
 */
class TacticalActionOutputServiceTest extends BaseTi4Test {

    private Game game;
    private Player player;
    private Tile sourceTile;
    private Tile midTile;
    private Tile destinationTile;
    private Tile farTile;

    @BeforeEach
    void setUp() {
        JdaService.testingMode = true;
        JdaService.jda = org.mockito.Mockito.mock(JDA.class);

        game = new Game();
        game.setName("test-game");
        player = game.addPlayer("test-user-id", "winnu");
        player.setFaction("winnu");
        player.setColor("red");
        player.addOwnedUnitByID("carrier");

        // A real 2-hop chain from the standard hex map: source -> mid -> destination. Carriers have
        // move value 1, so a move from source straight to destination (distance 2) exceeds range.
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
        List<String> ring3 = PositionMapper.getAdjacentTilePositions(destPos);
        String farPos = ring3.stream()
                .filter(p -> p != null
                        && !"x".equals(p)
                        && !p.equals(sourcePos)
                        && !p.equals(midPos)
                        && !ring1.contains(p)
                        && !ring2.contains(p))
                .findFirst()
                .orElseThrow();

        sourceTile = new Tile("18", sourcePos);
        midTile = new Tile("19", midPos);
        destinationTile = new Tile("20", destPos);
        farTile = new Tile("21", farPos);
        game.setTile(sourceTile);
        game.setTile(midTile);
        game.setTile(destinationTile);
        game.setTile(farTile);

        sourceTile.addUnit("space", new Units.UnitKey(UnitType.Carrier, "red"), 1);
        TacticalActionDisplacementService.moveSingleUnit(
                game, player, sourceTile, null, UnitType.Carrier, 1, UnitState.none, "red");
        // getUnitMoveValue() reads the active system off the game, matching how the real tactical
        // action flow always has it set before checking move value.
        game.setActiveSystem(destinationTile.getPosition());
    }

    @Test
    void nonFowGame_neverFlagsForReview_evenWhenOutOfRangeAndUndiscovered() {
        game.setFowMode(false);
        assertThat(TacticalActionOutputService.anyMoveExceedsRangeIntoUnseenDestination(game, player, destinationTile))
                .isFalse();
    }

    @Test
    void fowGame_optionDisabled_neverFlagsForReview_evenWhenOutOfRangeAndUndiscovered() {
        game.setFowMode(true);
        // MOVE_RANGE_GM_REVIEW defaults to off - the gate must not fire until a GM opts in.
        assertThat(TacticalActionOutputService.anyMoveExceedsRangeIntoUnseenDestination(game, player, destinationTile))
                .isFalse();
    }

    @Test
    void fowGame_optionEnabled_unseenDestination_outOfRange_flagsForReview() {
        game.setFowMode(true);
        game.setFowOption(FOWOption.MOVE_RANGE_GM_REVIEW, true);
        assertThat(TacticalActionOutputService.anyMoveExceedsRangeIntoUnseenDestination(game, player, destinationTile))
                .isTrue();
    }

    @Test
    void fowGame_optionEnabled_visibleDestination_outOfRange_doesNotFlag() {
        game.setFowMode(true);
        game.setFowOption(FOWOption.MOVE_RANGE_GM_REVIEW, true);
        // Mark the far destination visible via a command counter (e.g. a prior sweep), even though
        // it's still out of range - visibility, not distance, is what should gate the review.
        destinationTile.addCC(Mapper.getSweepID(player.getColor()));
        assertThat(TacticalActionOutputService.anyMoveExceedsRangeIntoUnseenDestination(game, player, destinationTile))
                .isFalse();
    }

    @Test
    void fowGame_optionEnabled_unseenDestination_withinRange_doesNotFlag() {
        game.setFowMode(true);
        game.setFowOption(FOWOption.MOVE_RANGE_GM_REVIEW, true);
        game.setActiveSystem(sourceTile.getPosition());
        // Move value comfortably covers a same-tile "move", so nothing should be flagged regardless
        // of visibility.
        assertThat(TacticalActionOutputService.anyMoveExceedsRangeIntoUnseenDestination(game, player, sourceTile))
                .isFalse();
    }

    @Test
    void fowGame_optionEnabled_noVerifiablePath_flagsForReview_evenWhenTrueDistanceWouldBeInRange() {
        game.setFowMode(true);
        game.setFowOption(FOWOption.MOVE_RANGE_GM_REVIEW, true);
        // With Gravity Drive + Lightning Drives, the true distance (3, via source -> mid -> destination
        // -> far) would NOT exceed the Carrier's move value + max possible bonus (1 + 2 = 3) - the old
        // "exceeds move value" check alone would let this through. But the destination tile isn't
        // visible, and neither is the one beyond it, so the player has no explored path to verify at
        // all; that alone must still trigger review.
        player.addTech("gd");
        player.addTech("dsgledb");

        assertThat(TacticalActionOutputService.anyMoveExceedsRangeIntoUnseenDestination(game, player, farTile))
                .isTrue();
    }
}

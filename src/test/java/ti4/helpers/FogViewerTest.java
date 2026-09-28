package ti4.helpers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ti4.game.Game;
import ti4.game.Player;
import ti4.game.Tile;
import ti4.helpers.Units.UnitType;
import ti4.image.Mapper;
import ti4.model.FactionModel;
import ti4.service.fow.FogMapAccessService;
import ti4.service.fow.FogMapAccessService.Access;
import ti4.testUtils.BaseTi4Test;

class FogViewerTest extends BaseTi4Test {

    // 101 and 102 are neighbours on ring 1, both adjacent to the centre 000.
    private static final String CENTRE = "000";
    private static final String TARGET = "101";
    private static final String NEIGHBOUR = "102";

    private Game game;
    private Player red;
    private Player blue;
    private Player crimson;

    @BeforeEach
    void setUp() {
        game = new Game();
        game.newGameSetup();
        game.setName("fog-viewer-test");
        red = addPlayer("sol", "red");
        blue = addPlayer("jolnar", "blue");
        crimson = addPlayer("crimson", "green");
        game.setTile(new Tile("19", CENTRE));
        game.setTile(new Tile("20", TARGET));
        game.setTile(new Tile("21", NEIGHBOUR));
    }

    private Player addPlayer(String faction, String color) {
        FactionModel model = Mapper.getFaction(faction);
        Player player = game.addPlayer(faction + "-user", model.getFactionName());
        player.setFaction(game, faction);
        player.setColor(color);
        player.setUnitsOwned(new HashSet<>(model.getUnits()));
        return player;
    }

    private FogViewer redSees(String... positions) {
        return new FogViewer(red, Set.of(positions));
    }

    private void placePds(Player owner, String position) {
        game.getTileByPosition(position)
                .addUnit(Constants.SPACE, Units.getUnitKey(UnitType.Pds, owner.getColorID()), 1);
    }

    @Test
    void unrestrictedViewerSeesEverythingAndEveryonesStats() {
        FogViewer viewer = FogViewer.unrestricted();
        assertFalse(viewer.isRestricted());
        assertTrue(viewer.canSee(NEIGHBOUR));
        assertTrue(viewer.canSeeStats(game, blue));
    }

    @Test
    void adjacencyIsLimitedToVisiblePositions() {
        Set<String> unrestricted = FogViewer.unrestricted().adjacent(game, CENTRE, red, true);
        assertTrue(unrestricted.containsAll(List.of(CENTRE, TARGET, NEIGHBOUR)));

        assertEquals(Set.of(TARGET), redSees(TARGET).adjacent(game, CENTRE, red, true));
    }

    @Test
    void viewerWithoutPlayerSeesNothing() {
        FogViewer viewer = FogViewer.of(game, true, null);
        assertTrue(viewer.isRestricted());
        assertFalse(viewer.canSee(TARGET));
        assertFalse(viewer.canSeeStats(game, red));
    }

    @Test
    void spaceCannonOnUnseenTileIsNotCalculated() {
        placePds(red, TARGET);
        assertNull(PdsCoverageHelper.calculatePdsCoverage(game, game.getTileByPosition(TARGET), redSees(CENTRE)));
    }

    @Test
    void spaceCannonCountsViewerButSkipsPlayersWhoseStatsAreHidden() {
        placePds(red, TARGET);
        placePds(blue, TARGET);
        Tile target = game.getTileByPosition(TARGET);

        Map<String, PdsCoverage> fogged = PdsCoverageHelper.calculatePdsCoverage(game, target, redSees(TARGET));
        assertNotNull(fogged);
        assertTrue(fogged.containsKey(red.getFaction()));
        assertFalse(fogged.containsKey(blue.getFaction()));

        Map<String, PdsCoverage> gmView =
                PdsCoverageHelper.calculatePdsCoverage(game, target, FogViewer.unrestricted());
        assertTrue(gmView.containsKey(blue.getFaction()));
    }

    @Test
    void legacySpaceCannonCallStaysDisabledInFog() {
        placePds(red, TARGET);
        game.setFowMode(true);
        assertNull(PdsCoverageHelper.calculatePdsCoverage(game, game.getTileByPosition(TARGET)));
    }

    @Test
    void exileRangeOnlyUsesVisibleDestroyers() {
        game.getTileByPosition(NEIGHBOUR)
                .addUnit(Constants.SPACE, Units.getUnitKey(UnitType.Destroyer, crimson.getColorID()), 1);
        Tile target = game.getTileByPosition(TARGET);

        assertTrue(FoWHelper.isTileInExileRange(game, target, crimson));
        assertTrue(FoWHelper.isTileInExileRange(game, target, crimson, redSees(TARGET, NEIGHBOUR)));
        // The destroyer sits in a system the viewer cannot see, so it must not reveal itself.
        assertFalse(FoWHelper.isTileInExileRange(game, target, crimson, redSees(TARGET)));
    }

    @Test
    void playersGetTheirOwnVisionWhileTheGameRuns() {
        game.setFowMode(true);
        assertEquals(Access.OWN_VISION, FogMapAccessService.access(game, red.getUserID()));
    }

    @Test
    void outsidersAreDeniedUntilTheGameHasEnded() {
        game.setFowMode(true);
        assertEquals(Access.DENIED, FogMapAccessService.access(game, "outsider"));

        game.setHasEnded(true);
        assertEquals(Access.FULL, FogMapAccessService.access(game, "outsider"));
    }

    @Test
    void playersChooseTheirMapOnceTheGameHasEnded() {
        game.setFowMode(true);
        game.setHasEnded(true);
        assertEquals(Access.ENDED_CHOICE, FogMapAccessService.access(game, red.getUserID()));
    }

    @Test
    void splitExpandsToMapAndStatsAndUnknownFallsBackToAll() {
        assertEquals(List.of(DisplayType.map, DisplayType.stats), FogMapAccessService.displayTypes("split"));
        assertEquals(List.of(DisplayType.spacecannon), FogMapAccessService.displayTypes("space_cannon_offense"));
        assertEquals(List.of(DisplayType.all), FogMapAccessService.displayTypes("nonsense"));
    }
}

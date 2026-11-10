package ti4.image.tile;

import static org.assertj.core.api.Assertions.assertThat;
import static ti4.image.tile.TileGeometry.TILE_HEIGHT;
import static ti4.image.tile.TileGeometry.TILE_PADDING;
import static ti4.image.tile.TileGeometry.TILE_WIDTH;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ti4.game.Game;
import ti4.game.Player;
import ti4.game.Tile;
import ti4.helpers.Constants;
import ti4.helpers.DisplayType;
import ti4.helpers.Units;
import ti4.helpers.Units.UnitType;
import ti4.image.io.ImageHelper;
import ti4.image.io.ImageResources;
import ti4.model.MapPositionMapper;
import ti4.service.fow.FogViewer;
import ti4.service.map.HyperlaneBridges;
import ti4.service.option.FOWOptionService.FOWOption;
import ti4.testUtils.BaseTi4Test;

class BorderDecorationsStepTest extends BaseTi4Test {

    @Test
    void fullRatNestIsTheWholeImageOnceAndAPartialOneOnlyItsEdges() {
        BufferedImage allSides = renderRatNest(List.of(0, 1, 2, 3, 4, 5));
        BufferedImage oneSide = renderRatNest(List.of(0));

        assertThat(pixels(allSides)).isEqualTo(pixels(centredImage(allSides)));
        assertThat(painted(oneSide)).isPositive().isLessThan(painted(allSides));
        assertThat(painted(renderRatNest(List.of()))).isZero();
    }

    // Rigel's planets, value circles and name banners sit on top of a border token on its own edges, so the rat nest
    // ring covers some of them on an empty system and none of them on Rigel.
    @Test
    void borderTokensPassBehindTheirOwnTilesPlanets() {
        Shape rigel = planetsAt(new Tile("76", "000"), 0, 0);
        assertThat(paintedInside(ratNestOn("46"), rigel)).isPositive();
        assertThat(paintedInside(ratNestOn("76"), rigel)).isZero();
    }

    // A token on 000's edges also passes behind the planets of the neighbour across each edge. On some edges the
    // minefield reaches into Teou'ran's shapes when the neighbour is empty; with Teou'ran there, it never does.
    @Test
    void borderTokensPassBehindTheNeighboursPlanets() {
        long overlapWhenEmpty = 0;
        for (int direction = 0; direction < 6; direction++) {
            Game game = minefieldFacing(direction);
            Shape teouran = neighbourPlanets(direction);
            assertThat(paintedInside(extras(game, "000"), teouran)).isZero();
            game.setTile(new Tile("46", neighbourOf(direction)));
            overlapWhenEmpty += paintedInside(extras(game, "000"), teouran);
        }
        assertThat(overlapWhenEmpty).isPositive();
    }

    // Fog safety: a neighbour the player has never seen draws no planets, so the token isn't cut there either.
    @Test
    void privateViewOnlyCutsAroundPlanetsThePlayerSees() {
        int direction = edgeWhereTheMinefieldReachesTeouran();
        Game game = minefieldFacing(direction);
        Player viewer = game.addPlayer("111", "sol");
        viewer.setFaction(game, "sol");
        viewer.setColor("blue");
        game.setFowMode(true);
        game.getTileByPosition("000").setTileFog(viewer, false);
        Shape teouran = neighbourPlanets(direction);

        assertThat(paintedInside(privateExtras(game, viewer, "000"), teouran)).isPositive();
        game.getTileByPosition(neighbourOf(direction)).setTileFog(viewer, false);
        assertThat(paintedInside(privateExtras(game, viewer, "000"), teouran)).isZero();
    }

    // With both tiles drawn, 000 draws the gravity wave's inner half and 101 its outer half: together exactly the token
    // 000 alone draws without a layout, and no pixel is drawn twice.
    @Test
    void bothSidesDrawTheirOwnHalfOfTheSameToken() {
        Game game = gravityWaveBetweenEmptyTiles();
        BufferedImage whole = onCanvas(game, legacyLayers(game), "000");
        TileLayers both = legacyLayers(game).drawnAt(Map.of("000", origin("000"), "101", origin("101")));

        assertThat(pixels(onCanvas(game, both, "000", "101"))).isEqualTo(pixels(whole));
        assertThat(painted(onCanvas(game, both, "000"))).isPositive().isLessThan(painted(whole));
        assertThat(painted(onCanvas(game, both, "101"))).isPositive().isLessThan(painted(whole));
    }

    // When the other side isn't drawn, the side that is draws the whole token, sticking out past the edge, with the
    // gravity wave's arrow where the holder would put it.
    @Test
    void aTileWithoutItsNeighbourDrawsTheWholeToken() {
        Game game = gravityWaveBetweenEmptyTiles();
        List<Integer> whole = pixels(onCanvas(game, legacyLayers(game), "000"));

        TileLayers holderOnly = legacyLayers(game).drawnAt(Map.of("000", origin("000")));
        TileLayers farSideOnly = legacyLayers(game).drawnAt(Map.of("101", origin("101")));
        assertThat(pixels(onCanvas(game, holderOnly, "000"))).isEqualTo(whole);
        assertThat(pixels(onCanvas(game, farSideOnly, "101"))).isEqualTo(whole);
    }

    // Fog: the player has never seen 000, which holds the token, but their carrier in 101 sees the edge.
    @Test
    void aSeenTileShowsATokenHeldByATileThePlayerNeverSaw() {
        Game game = gravityWaveBetweenEmptyTiles();
        game.setFowMode(true);
        Player viewer = game.addPlayer("111", "sol");
        viewer.setFaction(game, "sol");
        viewer.setColor("blue");
        game.getTileByPosition("101").setTileFog(viewer, false);
        game.getTileByPosition("101")
                .addUnit(Constants.SPACE, Units.getUnitKey(UnitType.Carrier, viewer.getColorID()), 1);
        TileLayers farSideOnly = TileLayers.forPreparedViewer(game, new FogViewer(game, true, viewer), DisplayType.all)
                .drawnAt(Map.of("101", origin("101")));

        assertThat(painted(onCanvas(game, farSideOnly, "101"))).isPositive();
        game.getTileByPosition("101").removeAllUnits(viewer.getColor());
        assertThat(painted(onCanvas(game, farSideOnly, "101"))).isZero();
    }

    // One-way hyperlane 000 runs N -> S: with "One-way lane hints" on, 104 (where it leads) glows at its north edge,
    // 101 (where it starts) does not, and nothing glows with the option off.
    @Test
    void oneWayLaneHintGlowsOnlyOnTheSystemTheLaneLeadsInto() {
        Game game = gameWithOneWayLaneIntoTile104(true);

        assertThat(painted(extras(game, "104"))).isZero();
        game.setFowOption(FOWOption.ONE_WAY_LANE_HINTS, true);
        assertThat(glowsAtNorthEdge(extras(game, "104"))).isTrue();
        assertThat(painted(extras(game, "101"))).isZero();
    }

    // The option is a fog option: a normal game that has it stored (e.g. converted from fog) draws nothing extra.
    @Test
    void normalGamesNeverGlowAndKeepTheirExtrasTiles() {
        Game game = gameWithOneWayLaneIntoTile104(false);
        game.setFowOption(FOWOption.ONE_WAY_LANE_HINTS, true);

        assertThat(painted(extras(game, "104"))).isZero();
        assertThat(ExtrasStepTiles.positions(game)).isEmpty();
    }

    // Fog safety: a player only gets the hint from a hyperlane they currently see.
    @Test
    void privateViewGlowsOnlyFromASeenHyperlane() {
        Game game = gameWithOneWayLaneIntoTile104(true);
        game.setFowOption(FOWOption.ONE_WAY_LANE_HINTS, true);
        Player viewer = game.addPlayer("111", "sol");
        viewer.setFaction(game, "sol");
        viewer.setColor("blue");
        game.getTileByPosition("104").setTileFog(viewer, false);
        TileLayers privateLayers =
                TileLayers.forPreparedViewer(game, new FogViewer(game, true, viewer), DisplayType.all);

        game.getTileByPosition("000").setTileFog(viewer, true);
        assertThat(painted(privateLayers.draw(game.getTileByPosition("104"), TileStep.Extras)))
                .isZero();
        game.getTileByPosition("000").setTileFog(viewer, false);
        assertThat(glowsAtNorthEdge(privateLayers.draw(game.getTileByPosition("104"), TileStep.Extras)))
                .isTrue();
    }

    // A spatial tear on the lane's exit edge blocks the route, so the system behind it must not glow; a malformed
    // hyperlane entry must not break the render either.
    @Test
    void noHintAcrossABlockedEdgeOrFromBrokenData() {
        Game game = gameWithOneWayLaneIntoTile104(true);
        game.setFowOption(FOWOption.ONE_WAY_LANE_HINTS, true);
        game.addBorderAnomaly("000", 3, "spatial_tear");
        assertThat(painted(extras(game, "104"))).isZero();

        game.getCustomHyperlaneData().put("000", "broken");
        // 104 stays in the list: it draws its half of the tear on the shared edge.
        assertThat(ExtrasStepTiles.positions(game)).containsExactly("000", "104");
        assertThat(painted(extras(game, "104"))).isZero();
    }

    // With bridges on and a lane leaving 104 to the south (207), 104 is bridged: the lane does not end there, so no
    // "a one-way lane leads in here" glow on its north edge.
    @Test
    void noHintWhereABridgeCarriesTheLaneOn() {
        Game game = gameWithOneWayLaneIntoTile104(true);
        game.setFowOption(FOWOption.ONE_WAY_LANE_HINTS, true);
        game.setTile(new Tile("hl", "207"));
        game.getCustomHyperlaneData()
                .put("207", "0,0,0,1,0,0;0,0,0,0,0,0;0,0,0,0,0,0;0,0,0,0,0,0;0,0,0,0,0,0;0,0,0,0,0,0");
        assertThat(glowsAtNorthEdge(extras(game, "104"))).isTrue();

        HyperlaneBridges.set(game, "104", "0,0,0,2,0,0;0,0,0,0,0,0;0,0,0,0,0,0;0,0,0,0,0,0;0,0,0,0,0,0;0,0,0,0,0,0");
        assertThat(painted(extras(game, "104"))).isZero();
    }

    // 104 is bridged S -> SW, so the lane arriving from 207 (south) carries on. The 000 lane arriving from the north
    // isn't in the matrix, so it still ends in 104 and keeps its glow; 207's does not.
    @Test
    void hintStaysWhereALaneEndsInABridgedSystem() {
        Game game = gameWithOneWayLaneIntoTile104(true);
        game.setFowOption(FOWOption.ONE_WAY_LANE_HINTS, true);
        game.setTile(new Tile("hl", "207"));
        game.getCustomHyperlaneData()
                .put("207", "0,0,0,0,0,0;0,0,0,0,0,0;0,0,0,0,0,0;1,0,0,0,0,0;0,0,0,0,0,0;0,0,0,0,0,0");
        HyperlaneBridges.set(game, "104", "0,0,0,0,0,0;0,0,0,0,0,0;0,0,0,0,0,0;0,0,0,0,2,0;0,0,0,0,0,0;0,0,0,0,0,0");

        BufferedImage layer = extras(game, "104");
        assertThat(glowsAtNorthEdge(layer)).isTrue();
        assertThat(layer.getRGB(TILE_PADDING + TILE_WIDTH / 2, TILE_PADDING + TILE_HEIGHT - 4) >>> 24)
                .isZero();
    }

    // 000 is bridged N -> S straight into system 104, so their shared edge is lane-only. A fog player who can't see
    // that lane (Hide unseen hyperlanes, no vision along it) gets a Spatial Tear on the edge; once the lanes are
    // shown, or in a public view, the tear goes away.
    @Test
    void laneBlockedBorderShowsATearOnlyToPlayersWhoCantSeeTheLane() {
        Game game = new Game();
        game.newGameSetup();
        game.setName("lane-blocked-test");
        game.setFowMode(true);
        game.setFowOption(FOWOption.HIDE_UNSEEN_HYPERLANES, true);
        game.setTile(new Tile("19", "000"));
        game.setTile(new Tile("20", "104"));
        HyperlaneBridges.set(game, "000", "0,0,0,2,0,0;0,0,0,0,0,0;0,0,0,0,0,0;0,0,0,0,0,0;0,0,0,0,0,0;0,0,0,0,0,0");
        Player viewer = game.addPlayer("111", "sol");
        viewer.setFaction(game, "sol");
        viewer.setColor("blue");
        game.getTileByPosition("000").setTileFog(viewer, false);
        game.getTileByPosition("104").setTileFog(viewer, false);

        assertThat(painted(privateExtras(game, viewer, "000"))).isPositive();
        assertThat(game.getBorderAnomalies()).isEmpty();
        assertThat(painted(extras(game, "000"))).isZero();

        game.setFowOption(FOWOption.HIDE_UNSEEN_HYPERLANES, false);
        assertThat(painted(privateExtras(game, viewer, "000"))).isZero();
    }

    // 000 and 104 are both bridged N -> S. The player sits in 104, so 104 shows the lane on the shared edge; 000's own
    // lanes aren't seen. The edge carries a visible lane, so neither tile may draw a tear.
    @Test
    void noTearWhereTheOtherBridgeShowsTheLane() {
        Game game = new Game();
        game.newGameSetup();
        game.setName("lane-blocked-chain-test");
        game.setFowMode(true);
        game.setFowOption(FOWOption.HIDE_UNSEEN_HYPERLANES, true);
        game.setTile(new Tile("19", "000"));
        game.setTile(new Tile("20", "104"));
        String northToSouth = "0,0,0,2,0,0;0,0,0,0,0,0;0,0,0,0,0,0;0,0,0,0,0,0;0,0,0,0,0,0;0,0,0,0,0,0";
        HyperlaneBridges.set(game, "000", northToSouth);
        HyperlaneBridges.set(game, "104", northToSouth);
        Player viewer = game.addPlayer("111", "sol");
        viewer.setFaction(game, "sol");
        viewer.setColor("blue");
        game.getTileByPosition("104")
                .addUnit(Constants.SPACE, Units.getUnitKey(UnitType.Destroyer, viewer.getColorID()), 1);
        game.getTileByPosition("000").setTileFog(viewer, false);
        game.getTileByPosition("104").setTileFog(viewer, false);

        assertThat(painted(privateExtras(game, viewer, "000"))).isZero();
    }

    private static BufferedImage privateExtras(Game game, Player viewer, String position) {
        return TileLayers.forPreparedViewer(game, new FogViewer(game, true, viewer), DisplayType.all)
                .draw(game.getTileByPosition(position), TileStep.Extras);
    }

    // Systems 101 (north) and 104 (south) around custom hyperlane 000, whose one lane runs N -> S.
    private static Game gameWithOneWayLaneIntoTile104(boolean fog) {
        Game game = new Game();
        game.newGameSetup();
        game.setName("one-way-hint-test");
        game.setFowMode(fog);
        game.setTile(new Tile("19", "101"));
        game.setTile(new Tile("20", "104"));
        game.setTile(new Tile("hl", "000"));
        game.getCustomHyperlaneData()
                .put("000", "0,0,0,1,0,0;0,0,0,0,0,0;0,0,0,0,0,0;0,0,0,0,0,0;0,0,0,0,0,0;0,0,0,0,0,0");
        return game;
    }

    private static boolean glowsAtNorthEdge(BufferedImage layer) {
        return (layer.getRGB(TILE_PADDING + TILE_WIDTH / 2, TILE_PADDING + 3) >>> 24) > 0;
    }

    private static BufferedImage extras(Game game, String position) {
        return TileLayers.of(game, null, DisplayType.all).draw(game.getTileByPosition(position), TileStep.Extras);
    }

    private static BufferedImage renderRatNest(List<Integer> directions) {
        Game game = new Game();
        game.newGameSetup();
        game.setName("border-decorations-test");
        Tile tile = new Tile("46", "101");
        game.setTile(tile);
        directions.forEach(direction -> game.addBorderAnomaly("101", direction, "ratnest"));
        return TileLayers.of(game, null, DisplayType.all).draw(tile, TileStep.Extras);
    }

    // What a single, unrotated, centred draw of the rat nest image looks like on the same layer.
    private static Game gravityWaveBetweenEmptyTiles() {
        Game game = new Game();
        game.newGameSetup();
        game.setName("border-decorations-test");
        game.setTile(new Tile("46", "000"));
        game.setTile(new Tile("47", "101"));
        game.addBorderAnomaly("000", 0, "gravity_wave");
        return game;
    }

    private static TileLayers legacyLayers(Game game) {
        return TileLayers.of(game, null, DisplayType.all);
    }

    private static Point origin(String position) {
        Point point = MapPositionMapper.getTilePosition(position);
        Point centre = MapPositionMapper.getTilePosition("000");
        return new Point(point.x - centre.x + 600, point.y - centre.y + 600);
    }

    private static BufferedImage onCanvas(Game game, TileLayers layers, String... positions) {
        BufferedImage canvas = new BufferedImage(1600, 1600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        for (String position : positions) {
            Point at = origin(position);
            layers.paint(game.getTileByPosition(position), TileStep.Extras, g, at.x, at.y);
        }
        g.dispose();
        return canvas;
    }

    private static BufferedImage ratNestOn(String tileId) {
        Game game = new Game();
        game.newGameSetup();
        game.setName("border-decorations-test");
        Tile tile = new Tile(tileId, "000");
        game.setTile(tile);
        for (int direction = 0; direction < 6; direction++) game.addBorderAnomaly("000", direction, "ratnest");
        return TileLayers.of(game, null, DisplayType.all).draw(tile, TileStep.Extras);
    }

    // Teou'ran on every side of an empty 000, with a minefield on 000's edge in the given direction.
    private static Game minefieldFacing(int direction) {
        Game game = new Game();
        game.newGameSetup();
        game.setName("border-decorations-test");
        game.setTile(new Tile("46", "000"));
        for (int side = 0; side < 6; side++) game.setTile(new Tile("28", neighbourOf(side)));
        game.addBorderAnomaly("000", direction, "minefield");
        return game;
    }

    private static String neighbourOf(int direction) {
        return MapPositionMapper.getAdjacentTilePositions("000").get(direction);
    }

    private static Shape neighbourPlanets(int direction) {
        Point here = MapPositionMapper.getTilePosition("000");
        Point there = MapPositionMapper.getTilePosition(neighbourOf(direction));
        return planetsAt(new Tile("28", neighbourOf(direction)), there.x - here.x, there.y - here.y);
    }

    private static int edgeWhereTheMinefieldReachesTeouran() {
        for (int direction = 0; direction < 6; direction++) {
            Game game = minefieldFacing(direction);
            game.setTile(new Tile("46", neighbourOf(direction)));
            if (paintedInside(extras(game, "000"), neighbourPlanets(direction)) > 0) return direction;
        }
        throw new IllegalStateException("The minefield never reaches Teou'ran");
    }

    private static Shape planetsAt(Tile tile, int dx, int dy) {
        return AffineTransform.getTranslateInstance(TILE_PADDING + dx, TILE_PADDING + dy)
                .createTransformedShape(PlanetOcclusion.of(tile.getTileModel()));
    }

    // Only pixels well inside the shape count: the clip's hard edge may round either way along the outline.
    private static long paintedInside(BufferedImage layer, Shape shape) {
        long count = 0;
        for (int y = 0; y < layer.getHeight(); y++) {
            for (int x = 0; x < layer.getWidth(); x++) {
                if (shape.contains(x - 1, y - 1, 3, 3) && (layer.getRGB(x, y) >>> 24) > 0) count++;
            }
        }
        return count;
    }

    private static BufferedImage centredImage(BufferedImage layer) {
        BufferedImage expected = new BufferedImage(layer.getWidth(), layer.getHeight(), layer.getType());
        BufferedImage ratNest = ImageHelper.read(ImageResources.border("ratnest_border.png"));
        Graphics2D g = expected.createGraphics();
        g.drawImage(
                ratNest,
                TILE_PADDING + (TILE_WIDTH - ratNest.getWidth()) / 2,
                TILE_PADDING + (TILE_HEIGHT - ratNest.getHeight()) / 2,
                null);
        g.dispose();
        return expected;
    }

    private static long painted(BufferedImage image) {
        return pixels(image).stream().filter(pixel -> (pixel >>> 24) != 0).count();
    }

    private static List<Integer> pixels(BufferedImage image) {
        return Arrays.stream(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()))
                .boxed()
                .toList();
    }
}

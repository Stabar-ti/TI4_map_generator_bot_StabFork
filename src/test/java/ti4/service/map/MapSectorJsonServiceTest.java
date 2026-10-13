package ti4.service.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ti4.discord.JdaService;
import ti4.game.Game;
import ti4.game.Tile;
import ti4.image.GalaxyNames;
import ti4.image.MapSegment;
import ti4.service.map.MapJsonIOService.MapDataIO;
import ti4.service.map.MapJsonIOService.SectorDefIO;
import ti4.service.map.MapJsonIOService.SectorIO;
import ti4.service.map.MapJsonIOService.TileIO;
import ti4.testUtils.BaseTi4Test;

/**
 * Sector and galaxy data in the map JSON. importSectors runs after every tile is placed, so these tests place
 * the tiles on the game first and then hand importSectors a MapDataIO describing the same positions - exactly
 * the state importMapFromJson is in when it reaches the sector pass.
 */
class MapSectorJsonServiceTest extends BaseTi4Test {

    private Game game;
    private MapDataIO data;
    private StringBuilder errors;

    @BeforeEach
    void setUp() {
        JdaService.testingMode = true;
        JdaService.jda = mock(JDA.class);
        game = new Game();
        game.setName("sector-json-test");
        game.setFowMode(true);
        data = new MapDataIO();
        data.setMapInfo(new ArrayList<>());
        errors = new StringBuilder();
    }

    private TileIO place(String position) {
        game.setTile(new Tile("19", position));
        TileIO tile = new TileIO();
        tile.setPosition(position);
        tile.setTileID("19");
        data.getMapInfo().add(tile);
        return tile;
    }

    private static SectorIO sector(String type, Integer distance, String name) {
        SectorIO sector = new SectorIO();
        sector.setType(type);
        sector.setDistance(distance);
        sector.setName(name);
        return sector;
    }

    private MapSegment segmentNamed(String name) {
        return MapSegment.find(game, name).orElseThrow(() -> new AssertionError("no segment " + name + ": " + errors));
    }

    @Test
    void manualCircleAndClusterBecomeStoredSegmentsCentredOnTheirSystem() {
        place("000").setSector(sector("manual_circle", 1, "core"));
        place("101");
        place("401").setSector(sector("manual_auto", 0, "rim"));
        place("402");

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(segmentNamed("core").kind()).isEqualTo(MapSegment.Kind.CIRCLE);
        assertThat(segmentNamed("core").centre()).isEqualTo("000");
        assertThat(segmentNamed("core").radius()).isEqualTo(1);
        assertThat(segmentNamed("rim").kind()).isEqualTo(MapSegment.Kind.CLUSTER);
        assertThat(segmentNamed("rim").positions()).containsExactlyInAnyOrder("401", "402");
        assertThat(errors).isEmpty();
    }

    @Test
    void sectorFieldsAreReadFromTheJsonText() {
        game.setFowMode(true);
        String json = """
                {
                  "sectorGap": 1,
                  "galaxies": { "main": "andromeda" },
                  "mapInfo": [
                    { "position": "000", "tileID": "19",
                      "sector": { "type": "manual_circle", "distance": 1, "name": "core" } },
                    { "position": "101", "tileID": "19" }
                  ]
                }
                """;

        MapJsonIOService.importMapFromJson(game, json, null);

        assertThat(segmentNamed("core").radius()).isEqualTo(1);
        assertThat(MapSegment.gap(game)).isEqualTo(1);
        assertThat(GalaxyNames.name(game, "main")).isEqualTo("andromeda");
    }

    @Test
    void aliasesAreAcceptedForTheSectorType() {
        place("000").setSector(sector("Circle", 0, "core"));

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(segmentNamed("core").kind()).isEqualTo(MapSegment.Kind.CIRCLE);
    }

    // The automatic name sits on 402, not on the cluster's anchor (401): any member system may carry it.
    @Test
    void automaticNameOnAnyMemberSystemNamesItsCluster() {
        place("000").setSector(sector("automatic", 0, ""));
        place("101");
        place("401");
        place("402").setSector(sector("automatic", 0, "far-reach"));

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(MapSegment.isAutoSectors(game)).isTrue();
        MapSegment farReach = segmentNamed("far-reach");
        assertThat(farReach.kind()).isEqualTo(MapSegment.Kind.AUTO);
        assertThat(farReach.positions()).containsExactlyInAnyOrder("401", "402");
    }

    @Test
    void automaticSystemsThatDisagreeOnTheGapUseTheLargestAndWarn() {
        place("000").setSector(sector("automatic", 1, ""));
        place("401").setSector(sector("automatic", 2, ""));

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(MapSegment.gap(game)).isEqualTo(2);
        assertThat(errors.toString()).contains("disagree");
    }

    // Scenario 3: without a gap a cluster stops at the first empty ring; sectorGap lets it jump one.
    @Test
    void sectorGapWidensAManualCluster() {
        place("000").setSector(sector("manual_auto", 0, "core"));
        place("201");
        data.setSectorGap(1);

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(MapSegment.isAutoSectors(game)).isFalse();
        assertThat(segmentNamed("core").positions()).contains("000", "201");
    }

    @Test
    void emptyManualNameGetsAWordFromTheSectorList() {
        place("000").setSector(sector("manual_circle", 0, null));

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(MapSegment.stored(game)).hasSize(1);
        assertThat(MapSegment.isValidName(MapSegment.stored(game).getFirst().name()))
                .isTrue();
        assertThat(errors).isEmpty();
    }

    @Test
    void duplicateAndInvalidNamesAreReportedAndTheRestStillImports() {
        place("000").setSector(sector("manual_circle", 0, "core"));
        place("401").setSector(sector("manual_circle", 0, "core"));
        place("404").setSector(sector("manual_circle", 0, "Not A Valid Name!"));
        place("407").setSector(sector("manual_circle", 0, "main"));
        place("410").setSector(sector("manual_circle", 0, "outer"));

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(MapSegment.stored(game).stream().map(MapSegment::name)).containsExactlyInAnyOrder("core", "outer");
        assertThat(errors.toString()).contains("401").contains("404").contains("407");
    }

    @Test
    void unknownSectorTypeIsReported() {
        place("000").setSector(sector("spiral", 0, "core"));

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(MapSegment.stored(game)).isEmpty();
        assertThat(errors.toString()).contains("spiral");
    }

    // Scenario 11: the fracture is its own built-in segment, never part of a sector.
    @Test
    void sectorOnAFracturePositionIsRejected() {
        place("frac1").setSector(sector("manual_circle", 0, "broken"));

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(MapSegment.stored(game)).isEmpty();
        assertThat(errors.toString()).contains("frac1");
    }

    // Scenario 6: an automatic cluster touching a manual sector is dropped whole, so its name has no sector.
    @Test
    void automaticNameInAClusterTouchingAManualSectorIsReportedAsUnused() {
        place("000").setSector(sector("manual_circle", 0, "core"));
        place("101").setSector(sector("automatic", 0, "edge"));
        place("102");

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(MapSegment.find(game, "edge")).isEmpty();
        assertThat(errors.toString()).contains("`edge` is not used");
    }

    @Test
    void overlappingManualSectorsAreReported() {
        place("000").setSector(sector("manual_circle", 1, "core"));
        place("101").setSector(sector("manual_circle", 1, "inner"));

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(errors.toString()).contains("`core` and `inner` share");
    }

    @Test
    void galaxyNamesApplyManuallyAndEmptyStaysAutomatic() {
        game.setFowMode(true);
        place("000");
        place("a000");
        Map<String, String> galaxies = new LinkedHashMap<>();
        galaxies.put("main", "andromeda");
        galaxies.put("a", "");
        data.setGalaxies(galaxies);

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(GalaxyNames.name(game, "main")).isEqualTo("andromeda");
        assertThat(GalaxyNames.isManual(game, "main")).isTrue();
        assertThat(GalaxyNames.isManual(game, "a")).isFalse();
        assertThat(errors).isEmpty();
    }

    // Automatic galaxy names left over from the previous map must not block a name the file assigns elsewhere.
    @Test
    void staleAutomaticGalaxyNamesDoNotBlockImportedNames() {
        game.setFowMode(true);
        place("000");
        place("a000");
        game.setStoredValue("fowGalaxyNames", "b=andromeda");
        data.setGalaxies(Map.of("a", "andromeda"));

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(GalaxyNames.name(game, "a")).isEqualTo("andromeda");
        assertThat(errors).isEmpty();
    }

    @Test
    void sectorsInANonFogGameAreStoredWithAWarning() {
        game.setFowMode(false);
        place("000").setSector(sector("manual_circle", 0, "core"));

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(MapSegment.find(game, "core")).isPresent();
        assertThat(errors.toString()).contains("only used in Fog of War");
    }

    // Scenario 12: board positions only exist in fog games, so their names are skipped elsewhere.
    @Test
    void galaxyNamesInANonFogGameAreSkippedWithAnError() {
        game.setFowMode(false);
        place("000");
        data.setGalaxies(Map.of("main", "andromeda"));

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(GalaxyNames.isManual(game, "main")).isFalse();
        assertThat(errors.toString()).contains("Fog of War");
    }

    @Test
    void fileWithoutSectorDataLeavesExistingSectorsAlone() {
        place("000");
        MapSegment.put(game, new MapSegment("core", "000", 1));

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(MapSegment.find(game, "core")).isPresent();
    }

    @Test
    void fileWithSectorDataReplacesExistingSectors() {
        place("000").setSector(sector("manual_circle", 0, "fresh"));
        MapSegment.put(game, new MapSegment("stale", "000", 1));

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(MapSegment.find(game, "stale")).isEmpty();
        assertThat(MapSegment.find(game, "fresh")).isPresent();
    }

    @Test
    void exportThenImportKeepsSectorsAndGalaxyNames() {
        game.setFowMode(true);
        place("000").setSector(sector("manual_circle", 1, "core"));
        place("101");
        place("401").setSector(sector("manual_auto", 2, "rim"));
        place("402");
        place("a000").setSector(sector("automatic", 1, "beyond"));
        place("a101");
        data.setGalaxies(Map.of("a", "andromeda"));
        MapSectorJsonService.importSectors(game, data, errors);
        assertThat(errors).isEmpty();

        MapDataIO exported = new MapDataIO();
        exported.setMapInfo(game.getTileMap().values().stream()
                .map(tile -> {
                    TileIO io = new TileIO();
                    io.setPosition(tile.getPosition());
                    io.setTileID(tile.getTileID());
                    return io;
                })
                .collect(Collectors.toCollection(ArrayList::new)));
        MapSectorJsonService.exportSectors(game, exported);

        Game reimported = new Game();
        reimported.setName("sector-json-reimport");
        reimported.setFowMode(true);
        game.getTileMap().values().forEach(tile -> reimported.setTile(new Tile(tile.getTileID(), tile.getPosition())));
        StringBuilder reimportErrors = new StringBuilder();
        MapSectorJsonService.importSectors(reimported, exported, reimportErrors);

        assertThat(reimportErrors).isEmpty();
        assertThat(describe(reimported)).isEqualTo(describe(game));
        assertThat(GalaxyNames.name(reimported, "a")).isEqualTo("andromeda");
        assertThat(MapSegment.gap(reimported)).isEqualTo(MapSegment.gap(game));
    }

    // The two examples below are copied from .idea/map-json-sectors-handoff.md; keep them in sync.
    private static final String HANDOFF_MINIMAL = """
            {
              "formatVersion": 2,
              "mapInfo": [
                { "position": "000", "tileID": "18", "sectorName": "core" },
                { "position": "101", "tileID": "19", "sectorName": "core" },
                { "position": "301", "tileID": "20", "sectorName": "rim" },
                { "position": "a000", "tileID": "39", "sectorName": "outpost" }
              ],
              "galaxies": { "a": "andromeda" },
              "defaultSector": "core"
            }
            """;

    private static final String HANDOFF_FULL = """
            {
              "formatVersion": 2,
              "mapInfo": [
                { "position": "000", "tileID": "18" },
                { "position": "101", "tileID": "19" },
                { "position": "301", "tileID": "20", "sectorName": "rim" },
                { "position": "302", "tileID": "21", "sectorName": "rim" },
                { "position": "a000", "tileID": "39" },
                { "position": "a101", "tileID": "22" }
              ],
              "sectors": [
                { "name": "core", "type": "circle", "centre": "000", "distance": 1,
                  "positions": ["000", "101", "102", "103", "104", "105", "106"],
                  "galaxy": "main", "displayName": "milky-way / core" },
                { "name": "rim", "type": "assigned",
                  "positions": ["301", "302", "303"],
                  "galaxy": "main", "displayName": "milky-way / rim" },
                { "name": "beyond", "type": "automatic", "pinned": true,
                  "positions": ["a000", "a101"],
                  "galaxy": "a", "displayName": "andromeda / beyond" }
              ],
              "autoSectors": true,
              "sectorGap": 1,
              "defaultSector": "core",
              "galaxies": { "a": "andromeda" }
            }
            """;

    private static final ObjectMapper JSON = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static SectorDefIO listed(String type, String name, String... positions) {
        SectorDefIO sector = new SectorDefIO();
        sector.setType(type);
        sector.setName(name);
        sector.setPositions(List.of(positions));
        return sector;
    }

    private static SectorDefIO centred(String type, String name, String centre, int distance) {
        SectorDefIO sector = new SectorDefIO();
        sector.setType(type);
        sector.setName(name);
        sector.setCentre(centre);
        sector.setDistance(distance);
        return sector;
    }

    private MapDataIO exportOf(Game source) {
        MapDataIO exported = new MapDataIO();
        exported.setMapInfo(source.getTileMap().values().stream()
                .map(tile -> {
                    TileIO io = new TileIO();
                    io.setPosition(tile.getPosition());
                    io.setTileID(tile.getTileID());
                    return io;
                })
                .collect(Collectors.toCollection(ArrayList::new)));
        MapSectorJsonService.exportSectors(source, exported);
        return exported;
    }

    @Test
    void handoffMinimalExampleAssignsEachTileToItsSector() {
        MapJsonIOService.importMapFromJson(game, HANDOFF_MINIMAL, null);

        assertThat(segmentNamed("core").positions()).containsExactlyInAnyOrder("000", "101");
        assertThat(segmentNamed("core").kind()).isEqualTo(MapSegment.Kind.TAGGED);
        assertThat(segmentNamed("rim").positions()).containsExactly("301");
        assertThat(segmentNamed("outpost").displayName(game)).isEqualTo("andromeda / outpost");
        assertThat(MapSegment.find(game, "board-a")).isEmpty();
        assertThat(MapSegment.defaultSegment(game).map(MapSegment::name)).contains("core");
    }

    // The full example is exactly what the bot exports for that map, so importing and exporting it is lossless.
    @Test
    void handoffFullExampleExportsItselfAgain() throws Exception {
        game.setName("handoff-full");
        MapJsonIOService.importMapFromJson(game, HANDOFF_FULL, null);

        MapDataIO exported = exportOf(game);

        JsonNode expected = JSON.readTree(HANDOFF_FULL);
        JsonNode actual = JSON.valueToTree(exported);
        for (String field :
                List.of("formatVersion", "sectors", "autoSectors", "sectorGap", "defaultSector", "galaxies")) {
            assertThat(actual.get(field)).as(field).isEqualTo(expected.get(field));
        }
        assertThat(exported.getMapInfo().stream()
                        .filter(tile -> tile.getSectorName() != null)
                        .map(TileIO::getPosition))
                .containsExactlyInAnyOrder("301", "302");
    }

    // Both were lost by v1, which hung each sector on its centre tile.
    @Test
    void twoSectorsOnOneCentreAndACircleOnAnEmptyHexSurviveARoundTrip() {
        place("000");
        place("101");
        place("1237");
        data.setSectors(List.of(
                centred("circle", "core", "000", 1),
                centred("cluster", "heart", "000", 0),
                centred("circle", "void", "1201", 1)));
        MapSectorJsonService.importSectors(game, data, errors);
        assertThat(MapSegment.stored(game).stream().map(MapSegment::name))
                .containsExactlyInAnyOrder("core", "heart", "void");

        MapDataIO exported = exportOf(game);
        Game reimported = new Game();
        reimported.setName("sector-json-reimport");
        reimported.setFowMode(true);
        game.getTileMap().values().forEach(tile -> reimported.setTile(new Tile(tile.getTileID(), tile.getPosition())));
        MapSectorJsonService.importSectors(reimported, exported, new StringBuilder());

        assertThat(describe(reimported)).isEqualTo(describe(game));
        assertThat(MapSegment.stored(reimported)).isEqualTo(MapSegment.stored(game));
    }

    @Test
    void assignedSectorCoversEmptyHexesAndExtendsACircleOfTheSameName() {
        place("000");
        place("301");
        data.setSectors(List.of(centred("circle", "core", "000", 1), listed("assigned", "core", "301", "302")));

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(segmentNamed("core").positions()).contains("000", "101", "301", "302");
        assertThat(MapSegment.tags(game)).containsOnlyKeys("301", "302");
        assertThat(errors).isEmpty();
    }

    @Test
    void tileSectorNameWinsOverTheSectorListAndWarns() {
        place("301").setSectorName("edge");
        data.setSectors(List.of(listed("assigned", "rim", "301", "302")));

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(MapSegment.tagOf(game, "301")).isEqualTo("edge");
        assertThat(MapSegment.tagOf(game, "302")).isEqualTo("rim");
        assertThat(errors.toString()).contains("overrides the `sectors` list");
    }

    @Test
    void legacyTileSectorsAreIgnoredWhenTheFileHasASectorList() {
        place("000").setSector(sector("manual_circle", 1, "old"));
        data.setSectors(List.of(centred("circle", "core", "000", 1)));

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(MapSegment.find(game, "old")).isEmpty();
        assertThat(segmentNamed("core")).isNotNull();
        assertThat(errors.toString()).contains("per-tile `sector` objects were ignored");
    }

    @Test
    void derivedBoardAndFractureEntriesAreSkippedQuietly() {
        place("a000");
        data.setSectors(List.of(listed("board", "board-a", "a000"), listed("fracture", "fracture", "frac1")));

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(MapSegment.stored(game)).isEmpty();
        assertThat(MapSegment.tags(game)).isEmpty();
        assertThat(errors).isEmpty();
    }

    @Test
    void unpinnedAutomaticEntryOnlySwitchesAutomaticSectorsOn() {
        place("000");
        SectorDefIO automatic = listed("automatic", "lyra", "000");
        automatic.setPinned(false);
        data.setSectors(List.of(automatic));

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(MapSegment.isAutoSectors(game)).isTrue();
        assertThat(MapSegment.isPinned(game, "lyra")).isFalse();
    }

    @Test
    void listedProblemsAreReportedAndTheRestImports() {
        place("000");
        data.setSectors(List.of(
                listed("spiral", "odd", "000"),
                centred("circle", "nowhere", "", 1),
                listed("assigned", "", "000"),
                listed("assigned", "rim", "frac1", "301")));

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(MapSegment.tags(game)).containsOnlyKeys("301");
        assertThat(errors.toString())
                .contains("spiral")
                .contains("needs a `centre`")
                .contains("needs a `name`")
                .contains("`frac1` cannot be part of a sector");
    }

    @Test
    void unknownDefaultSectorIsReported() {
        place("000").setSectorName("core");
        data.setDefaultSector("elsewhere");

        MapSectorJsonService.importSectors(game, data, errors);

        assertThat(MapSegment.defaultSegment(game)).isEmpty();
        assertThat(errors.toString()).contains("`defaultSector` elsewhere");
    }

    private static List<String> describe(Game game) {
        return MapSegment.all(game).stream()
                .map(segment -> segment.name() + "|" + segment.kind() + "|"
                        + segment.positions().stream().sorted().toList())
                .sorted()
                .toList();
    }
}

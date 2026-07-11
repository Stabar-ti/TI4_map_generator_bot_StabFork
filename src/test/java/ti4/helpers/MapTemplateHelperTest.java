package ti4.helpers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ti4.game.Game;
import ti4.game.Tile;
import ti4.image.Mapper;
import ti4.model.MapTemplateModel;
import ti4.model.MapTemplateModel.MapTemplateTile;
import ti4.testUtils.BaseTi4Test;

class MapTemplateHelperTest extends BaseTi4Test {

    private static Game gameWithPlaceholders() {
        Game game = new Game();
        game.setTile(new Tile(AliasHandler.resolveTile("18"), "000")); // static tile, must be ignored
        game.setTile(new Tile(AliasHandler.resolveTile("blueblank"), "301")); // player 1 home
        game.setTile(new Tile(AliasHandler.resolveTile("blue1"), "302")); // player 1, milty index 0
        game.setTile(new Tile(AliasHandler.resolveTile("blue2"), "203")); // player 1, milty index 1
        game.setTile(new Tile(AliasHandler.resolveTile("yellowblank"), "304")); // player 2 home
        game.setTile(new Tile(AliasHandler.resolveTile("yellow1"), "305")); // player 2, milty index 0
        return game;
    }

    @Test
    void deriveTemplateFromGameMapFindsOnlyPlaceholders() {
        Game game = gameWithPlaceholders();
        MapTemplateModel derived = MapTemplateHelper.deriveTemplateFromGameMap(game);

        assertThat(derived.getTemplateTiles()).hasSize(5);
        assertThat(derived.getPlayerCount()).isEqualTo(2); // "yellow" -> playerNumber 2
        assertThat(derived.getTemplateTiles()).noneMatch(t -> "000".equals(t.getPos()));

        MapTemplateTile home1 = derived.getTemplateTiles().stream()
                .filter(t -> "301".equals(t.getPos()))
                .findFirst()
                .orElseThrow();
        assertThat(home1.getPlayerNumber()).isEqualTo(1);
        assertThat(home1.getHome()).isTrue();

        MapTemplateTile slice1b = derived.getTemplateTiles().stream()
                .filter(t -> "203".equals(t.getPos()))
                .findFirst()
                .orElseThrow();
        assertThat(slice1b.getPlayerNumber()).isEqualTo(1);
        assertThat(slice1b.getMiltyTileIndex()).isEqualTo(1);
    }

    @Test
    void deriveTemplateFromGameMapOnEmptyMapIsEmpty() {
        MapTemplateModel derived = MapTemplateHelper.deriveTemplateFromGameMap(new Game());
        assertThat(derived.getTemplateTiles()).isEmpty();
    }

    @Test
    void preservedTemplateRoundTripsThroughStoredValue() {
        Game game = gameWithPlaceholders();
        MapTemplateModel derived = MapTemplateHelper.deriveTemplateFromGameMap(game);

        // simulates the MiltyService preserve branch, incl. the storedValue escape/unescape cycle
        game.setStoredValue(MapTemplateHelper.PRESERVED_MAP_STORE_KEY, MapTemplateHelper.serializeTemplate(derived));

        MapTemplateModel restored =
                MapTemplateHelper.resolveTemplate(game, MapTemplateHelper.PRESERVED_MAP_TEMPLATE_ALIAS);
        assertThat(restored).isNotNull();
        assertThat(restored.getAlias()).isEqualTo(MapTemplateHelper.PRESERVED_MAP_TEMPLATE_ALIAS);
        assertThat(restored.getPlayerCount()).isEqualTo(derived.getPlayerCount());
        assertThat(restored.getTemplateTiles())
                .hasSize(derived.getTemplateTiles().size());
        for (MapTemplateTile expected : derived.getTemplateTiles()) {
            MapTemplateTile actual = restored.getTemplateTiles().stream()
                    .filter(t -> expected.getPos().equals(t.getPos()))
                    .findFirst()
                    .orElseThrow();
            assertThat(actual.getPlayerNumber()).isEqualTo(expected.getPlayerNumber());
            assertThat(actual.getHome()).isEqualTo(expected.getHome());
            assertThat(actual.getMiltyTileIndex()).isEqualTo(expected.getMiltyTileIndex());
        }

        // home lookup works from the restored template even after placeholders would be consumed
        assertThat(MapTemplateHelper.getPlayerHomeSystemLocation(1, restored)).isEqualTo("301");
        assertThat(MapTemplateHelper.getPlayerHomeSystemLocation(2, restored)).isEqualTo("304");
    }

    @Test
    void preservedTemplateIsNeverRegisteredGlobally() {
        Game game = gameWithPlaceholders();
        MapTemplateModel derived = MapTemplateHelper.deriveTemplateFromGameMap(game);
        game.setStoredValue(MapTemplateHelper.PRESERVED_MAP_STORE_KEY, MapTemplateHelper.serializeTemplate(derived));

        // nothing global: Mapper never learns the sentinel, so pickers/autocomplete can't leak it
        assertThat(Mapper.getMapTemplate(MapTemplateHelper.PRESERVED_MAP_TEMPLATE_ALIAS))
                .isNull();
        assertThat(Mapper.getMapTemplates())
                .noneMatch(t -> MapTemplateHelper.PRESERVED_MAP_TEMPLATE_ALIAS.equals(t.getAlias()));

        // a game without the stored value cannot resolve the sentinel
        assertThat(MapTemplateHelper.resolveTemplate(new Game(), MapTemplateHelper.PRESERVED_MAP_TEMPLATE_ALIAS))
                .isNull();
    }

    @Test
    void resolveTemplateReturnsMapperTemplatesUnchanged() {
        MapTemplateModel fromMapper = Mapper.getMapTemplates().getFirst();
        assertThat(MapTemplateHelper.resolveTemplate(new Game(), fromMapper.getAlias()))
                .isSameAs(fromMapper);
    }
}

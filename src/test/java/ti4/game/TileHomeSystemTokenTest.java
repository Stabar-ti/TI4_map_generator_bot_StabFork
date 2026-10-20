package ti4.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import ti4.helpers.AliasHandler;
import ti4.helpers.Constants;
import ti4.image.Mapper;
import ti4.testUtils.BaseTi4Test;

class TileHomeSystemTokenTest extends BaseTi4Test {

    @Test
    void theHomeSystemTokenIsPlaceableViaAddToken() {
        assertTrue(Mapper.isValidToken("homesystem"));
        String tokenFileName = Mapper.getTokenID(AliasHandler.resolveToken("homesystem"));
        assertEquals(Constants.TOKEN_HOME_SYSTEM, tokenFileName);
        assertNotNull(Mapper.getTokenPath(tokenFileName), "token_homesystem.png is missing from resources/tokens");
    }

    @Test
    void theTokenMakesAnySystemAHomeSystemThatBelongsToNoPlayer() {
        Game game = new Game();
        Player arborec = addPlayer(game, "arborec", "green");
        Player jolnar = addPlayer(game, "jolnar", "red");
        Tile arborecHome = new Tile("5", "301");
        Tile tokenHome = new Tile("19", "000");
        game.setTile(arborecHome);
        game.setTile(tokenHome);
        arborec.setHomeSystemPosition(arborecHome.getPosition());

        assertFalse(tokenHome.isHomeSystem());
        assertFalse(tokenHome.isHomeSystem(game));

        tokenHome.addToken(Constants.TOKEN_HOME_SYSTEM, Constants.SPACE);

        assertTrue(tokenHome.isHomeSystem());
        assertTrue(tokenHome.isHomeSystem(game));
        // Factionless: it must never satisfy "another player's home system" (Conquer the Weak, Supremacy, ...)
        assertFalse(tokenHome.isOtherPlayersHomeSystem(game, arborec));
        assertFalse(tokenHome.isOtherPlayersHomeSystem(game, jolnar));

        assertTrue(arborecHome.isOtherPlayersHomeSystem(game, jolnar));
        assertFalse(arborecHome.isOtherPlayersHomeSystem(game, arborec));
    }

    private static Player addPlayer(Game game, String faction, String color) {
        Player player = new Player(faction, faction, game);
        player.setFaction(faction);
        player.setColor(color);
        var players = new LinkedHashMap<>(game.getPlayers());
        players.put(faction, player);
        game.setPlayers(players);
        return player;
    }
}

package ti4.image.tile;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import ti4.game.Game;
import ti4.game.Tile;
import ti4.helpers.DisplayType;
import ti4.testUtils.BaseTi4Test;

class BorderDecorationsStepTest extends BaseTi4Test {

    @Test
    void wholeHexAnomalyIsDrawnOnceUnrotatedWhateverSidesHoldIt() {
        BufferedImage oneSide = renderRatNest(List.of(0));
        BufferedImage allSides = renderRatNest(List.of(0, 1, 2, 3, 4, 5));

        assertThat(pixels(allSides)).isEqualTo(pixels(oneSide));
        assertThat(pixels(oneSide)).isNotEqualTo(pixels(renderRatNest(List.of())));
    }

    private static BufferedImage renderRatNest(List<Integer> directions) {
        Game game = new Game();
        game.newGameSetup();
        game.setName("border-decorations-test");
        Tile tile = new Tile("19", "101");
        game.setTile(tile);
        directions.forEach(direction -> game.addBorderAnomaly("101", direction, "ratnest"));
        return TileLayers.of(game, null, DisplayType.all).draw(tile, TileStep.Extras);
    }

    private static List<Integer> pixels(BufferedImage image) {
        return Arrays.stream(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()))
                .boxed()
                .toList();
    }
}

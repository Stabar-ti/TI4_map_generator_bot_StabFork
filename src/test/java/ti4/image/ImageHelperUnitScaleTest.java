package ti4.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import ti4.ResourceHelper;
import ti4.game.Tile;
import ti4.helpers.Storage;
import ti4.testUtils.BaseTi4Test;

// SliceImageGeneratorService used to pass every slice image through ImageHelper.scale(image, 1.0f) and then draw
// the result onto the main slice canvas. These tests prove that dropping the scale call changes no pixel: both for
// images built the way slice images are (tile PNGs with alpha, fog overlays, translucent fills, outlined text) and,
// as a stronger check, for raw semi-transparent pixels that were never composited.
class ImageHelperUnitScaleTest extends BaseTi4Test {

    @Test
    void drawingAUnitScaledImageOntoACanvasMatchesDrawingTheOriginal() {
        BufferedImage rawTranslucent = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(42);
        for (int x = 0; x < 200; x++) {
            for (int y = 0; y < 200; y++) {
                rawTranslucent.setRGB(x, y, random.nextInt());
            }
        }

        for (BufferedImage source : List.of(rawTranslucent, buildSliceLikeImage())) {
            BufferedImage direct = drawOnCanvas(source);
            BufferedImage viaScale = drawOnCanvas(ImageHelper.scale(source, 1.0f));
            assertSamePixels(direct, viaScale);
        }
    }

    @Test
    void unitScaleKeepsEveryPixelOfASliceLikeImage() {
        BufferedImage sliceLike = buildSliceLikeImage();

        BufferedImage scaled = ImageHelper.scale(sliceLike, 1.0f);

        assertSamePixels(sliceLike, scaled);
    }

    @Test
    void unitScaleKeepsEveryPixelOfAFoggedSliceLikeImage() {
        BufferedImage sliceLike = buildSliceLikeImage();
        Graphics2D graphics = sliceLike.createGraphics();
        BufferedImage fogFilter = readTile(new Tile("19", "000").getFowTilePath(null));
        graphics.drawImage(fogFilter, 0, 0, null);
        graphics.drawImage(fogFilter, 150, 90, null);
        graphics.dispose();

        BufferedImage scaled = ImageHelper.scale(sliceLike, 1.0f);

        assertSamePixels(sliceLike, scaled);
    }

    private static BufferedImage drawOnCanvas(BufferedImage source) {
        BufferedImage canvas = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = canvas.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return canvas;
    }

    private static BufferedImage readTile(String path) {
        BufferedImage tile = ImageHelper.read(path);
        assertThat(tile).as("tile image %s", path).isNotNull();
        return tile;
    }

    private static BufferedImage buildSliceLikeImage() {
        BufferedImage image = new BufferedImage(600, 520, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.drawImage(readTile(ResourceHelper.getTileFile("00_green.png")), 150, 0, null);
        graphics.drawImage(readTile(new Tile("19", "000").getTilePath()), 0, 90, null);
        graphics.drawImage(readTile(new Tile("26", "000").getTilePath()), 300, 90, null);
        graphics.drawImage(readTile(new Tile("39", "000").getTilePath()), 150, 180, null);

        graphics.setColor(new Color(0, 0, 0, 128));
        graphics.fillRoundRect(40, 40, 40, 40, 40, 40);
        graphics.setColor(Color.black);
        graphics.setStroke(new BasicStroke(4.0f));
        graphics.fillRoundRect(100, 40, 40, 40, 40, 40);

        graphics.setFont(Storage.getFont50());
        DrawingUtil.superDrawString(
                graphics,
                "7/5",
                300,
                300,
                Color.white,
                MapGenerator.HorizontalAlign.Center,
                null,
                new BasicStroke(9.0f),
                Color.black);
        DrawingUtil.superDrawString(
                graphics,
                "(4/3+2)",
                300,
                360,
                Color.white,
                MapGenerator.HorizontalAlign.Center,
                null,
                new BasicStroke(9.0f),
                Color.black);
        graphics.dispose();
        return image;
    }

    private static void assertSamePixels(BufferedImage expected, BufferedImage actual) {
        assertThat(actual.getWidth()).isEqualTo(expected.getWidth());
        assertThat(actual.getHeight()).isEqualTo(expected.getHeight());
        int width = expected.getWidth();
        int height = expected.getHeight();
        int[] expectedPixels = expected.getRGB(0, 0, width, height, null, 0, width);
        int[] actualPixels = actual.getRGB(0, 0, width, height, null, 0, width);
        assertThat(actualPixels).isEqualTo(expectedPixels);
    }
}

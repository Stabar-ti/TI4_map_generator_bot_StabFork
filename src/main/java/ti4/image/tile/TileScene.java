package ti4.image.tile;

import java.awt.Point;
import java.util.Map;
import javax.annotation.Nullable;
import ti4.game.Game;
import ti4.game.Player;
import ti4.game.Tile;
import ti4.helpers.DisplayType;
import ti4.service.fow.FogViewer;

record TileScene(
        Game game,
        @Nullable DisplayType displayType,
        FogViewer viewer,
        RenderWarnings warnings,
        HyperlaneView hyperlanes,
        Map<String, Point> layout) {

    TileScene(Game game, @Nullable DisplayType displayType, FogViewer viewer, RenderWarnings warnings) {
        this(game, displayType, viewer, warnings, new HyperlaneView(game, viewer));
    }

    TileScene(
            Game game,
            @Nullable DisplayType displayType,
            FogViewer viewer,
            RenderWarnings warnings,
            HyperlaneView hyperlanes) {
        this(game, displayType, viewer, warnings, hyperlanes, Map.of());
    }

    boolean knowsLayout() {
        return !layout.isEmpty();
    }

    @Nullable
    Point drawnAt(String position) {
        return layout.get(position);
    }

    boolean isPrivateView() {
        return viewer.privateView();
    }

    @Nullable
    Player fowPlayer() {
        return viewer.player();
    }

    boolean hidesFogged(Tile tile) {
        return isPrivateView() && tile.hasFog(fowPlayer());
    }

    boolean seesPosition(String position) {
        if (!isPrivateView()) {
            return true;
        }
        Tile tile = game.getTileByPosition(position);
        return tile != null && !tile.hasFog(fowPlayer());
    }

    boolean showsGeneric(@Nullable Player owner) {
        return isPrivateView() && !viewer.canSeeStats(owner);
    }
}

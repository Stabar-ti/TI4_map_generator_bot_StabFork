package ti4.image.tile;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import ti4.game.Game;
import ti4.model.MapPositionMapper;

public final class ExtrasStepTiles {

    private ExtrasStepTiles() {}

    public static Set<String> positions(Game game) {
        Set<String> positions = new HashSet<>();
        positions.addAll(adjacencyOverrideTargets(game));
        positions.addAll(borderAnomalyTiles(game));
        positions.addAll(OneWayLaneHints.tilesThatMayGlow(game));
        positions.remove(null);
        return new TreeSet<>(positions);
    }

    private static Set<String> adjacencyOverrideTargets(Game game) {
        return new HashSet<>(game.getAdjacentTileOverrides().values());
    }

    private static Set<String> borderAnomalyTiles(Game game) {
        Set<String> tiles = new HashSet<>();
        game.getBorderAnomalies().stream().filter(Objects::nonNull).forEach(border -> {
            tiles.add(border.getTile());
            List<String> neighbours = MapPositionMapper.getAdjacentTilePositions(border.getTile());
            if (border.getDirection() >= 0 && border.getDirection() < neighbours.size()) {
                tiles.add(neighbours.get(border.getDirection()));
            }
        });
        return tiles;
    }
}

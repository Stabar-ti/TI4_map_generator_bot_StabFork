package ti4.image;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import ti4.game.Game;
import ti4.helpers.FoWHelper;

final class SectorBorders {

    private SectorBorders() {}

    // TODO: adjacency is read from the live map, like SectorConnections, so a remembered target can gain or lose
    // its arrow before the player sees it again
    static Map<String, Map<Integer, String>> find(Game game, Set<String> shownPositions, Set<String> known) {
        Map<String, Map<Integer, String>> arrows = new HashMap<>();
        for (String source : shownPositions) {
            if (!known.contains(source) || game.getTileByPosition(source) == null) {
                continue;
            }
            Map<Integer, String> sourceArrows = arrowsFrom(game, source, shownPositions, known);
            if (!sourceArrows.isEmpty()) {
                arrows.put(source, sourceArrows);
            }
        }
        return arrows;
    }

    private static Map<Integer, String> arrowsFrom(
            Game game, String source, Set<String> shownPositions, Set<String> known) {
        List<String> neighbours = PositionMapper.getAdjacentTilePositions(source);
        Map<Integer, String> arrows = new TreeMap<>();
        if (neighbours == null || neighbours.size() != 6) {
            return arrows;
        }
        Set<String> reachable = null;
        for (int direction = 0; direction < 6; direction++) {
            String target = neighbours.get(direction);
            if (!crossesIntoKnownSystem(game, source, direction, target, shownPositions, known)) {
                continue;
            }
            if (reachable == null) {
                reachable = FoWHelper.traverseAdjacencies(game, false, source);
            }
            if (reachable.contains(target)) {
                arrows.put(direction, target);
            }
        }
        return arrows;
    }

    private static boolean crossesIntoKnownSystem(
            Game game, String source, int direction, String target, Set<String> shownPositions, Set<String> known) {
        return target != null
                && !"x".equals(target)
                && !shownPositions.contains(target)
                && known.contains(target)
                && game.getTileByPosition(target) != null
                && game.getAdjacentTileOverride(source, direction) == null;
    }
}

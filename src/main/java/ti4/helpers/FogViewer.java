package ti4.helpers;

import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;
import ti4.game.Game;
import ti4.game.Player;

public record FogViewer(@Nullable Player player, @Nullable Set<String> visiblePositions) {

    private static final FogViewer UNRESTRICTED = new FogViewer(null, null);

    public static FogViewer unrestricted() {
        return UNRESTRICTED;
    }

    public static FogViewer of(Game game, boolean fogged, @Nullable Player player) {
        if (!fogged) {
            return UNRESTRICTED;
        }
        if (player == null) {
            return new FogViewer(null, Set.of());
        }
        return new FogViewer(player, Set.copyOf(FoWHelper.fowFilter(game, player)));
    }

    public boolean isRestricted() {
        return visiblePositions != null;
    }

    public boolean canSee(String position) {
        return !isRestricted() || visiblePositions.contains(position);
    }

    public Set<String> adjacent(Game game, String position, @Nullable Player movingPlayer, boolean includeTile) {
        Set<String> adjacent =
                new HashSet<>(FoWHelper.getAdjacentTiles(game, position, movingPlayer, false, includeTile));
        if (isRestricted()) {
            // TODO: still links two visible tiles through an unseen hyperlane (FoW+/HIDE_MAP already block this)
            adjacent.retainAll(visiblePositions);
        }
        return adjacent;
    }

    public boolean canSeeStats(Game game, Player other) {
        if (!isRestricted()) {
            return true;
        }
        return player != null && FoWHelper.canSeeStatsOfPlayer(game, other, player);
    }
}

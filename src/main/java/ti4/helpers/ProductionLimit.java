package ti4.helpers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import ti4.discord.interactions.buttons.handlers.faction.homebrew.bluereverie.XinTechHandler;
import ti4.game.Game;
import ti4.game.Player;
import ti4.game.Tile;

public record ProductionLimit(int limit, boolean bellumGloriosum) {

    public String label() {
        return bellumGloriosum ? "_Bellum Gloriosum_" : "PRODUCTION";
    }

    public boolean isExceededBy(int unitCount) {
        return limit < unitCount;
    }

    public static boolean isExceeded(Player player, Game game) {
        int unitCount = Helper.calculateCostOfProducedUnits(player, game, false);
        if (unitCount <= 1) {
            return false;
        }
        return forProduction(player, game)
                .map(limit -> limit.isExceededBy(unitCount))
                .orElse(false);
    }

    public static Optional<ProductionLimit> forProduction(Player player, Game game) {
        return of(player, game, lastProducedTile(player, game));
    }

    public static Optional<ProductionLimit> of(Player player, Game game, Tile tile) {
        int bellumGloriosumLimit = bellumGloriosumLimit(player);
        if (bellumGloriosumLimit > 0) {
            return Optional.of(new ProductionLimit(bellumGloriosumLimit, true));
        }
        if (!producesInActiveSystem(player, game, tile) || ignoresProductionLimit(player)) {
            return Optional.empty();
        }
        return Optional.of(new ProductionLimit(systemLimit(player, game, tile), false));
    }

    public static boolean producesInActiveSystem(Player player, Game game, Tile tile) {
        Tile activeSystem = game.getTileByPosition(game.getActiveSystem());
        return activeSystem != null
                && ((tile == activeSystem && Helper.getProductionValue(player, game, tile, false) > 0)
                        || XinTechHandler.getVeiledNetworkingStarProductionValue(player, game, tile) > 0);
    }

    public static List<String> producedPlaces(Collection<String> producedUnitKeys) {
        List<String> places = new ArrayList<>();
        for (String unit : producedUnitKeys) {
            String place = unit.split("_")[1] + "_" + unit.split("_")[2];
            if (!places.contains(place)) {
                places.add(place);
            }
        }
        return places;
    }

    private static Tile lastProducedTile(Player player, Game game) {
        List<String> places = producedPlaces(player.getCurrentProducedUnits().keySet());
        if (places.isEmpty()) {
            return null;
        }
        return game.getTileByPosition(places.getLast().split("_")[0]);
    }

    private static int bellumGloriosumLimit(Player player) {
        if (!player.hasUnlockedBreakthrough("solbt")) {
            return 0;
        }
        return player.getSpentThingsThisWindow().stream()
                .map(str -> str.split("_"))
                .filter(arr -> "solBtBuild".equalsIgnoreCase(arr[0]))
                .map(arr -> arr[2])
                .map(Integer::parseInt)
                .findAny()
                .orElse(0);
    }

    private static boolean ignoresProductionLimit(Player player) {
        return player.hasUnit("arborec_mech")
                || player.hasUnit("arborec_infantry")
                || player.hasUnit("tf-lataniwarrior")
                || player.hasUnit("deepwrought_mech")
                || player.hasUnit("arborec_infantry2");
    }

    private static int systemLimit(Player player, Game game, Tile tile) {
        int limit = Math.max(
                Helper.getProductionValue(player, game, tile, false),
                XinTechHandler.getVeiledNetworkingStarProductionValue(player, game, tile));
        if (player.getSpentThingsThisWindow().contains("warmachine")) {
            limit += 4;
        }
        for (String spent : player.getSpentThingsThisWindow()) {
            if (spent.startsWith("liquidation")
                    && !spent.replace("liquidation", "").isEmpty()) {
                limit -= Integer.parseInt(spent.replace("liquidation", ""));
            }
        }
        if (game.playerHasLeaderUnlockedOrAlliance(player, "cabalcommander")) {
            limit += 2;
        }
        return limit;
    }
}

package ti4.helpers.adjacency;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import software.amazon.awssdk.utils.StringUtils;
import ti4.game.Game;
import ti4.game.Player;
import ti4.game.Tile;
import ti4.helpers.ButtonHelper;
import ti4.helpers.Constants;
import ti4.model.WormholeModel.Wormhole;

public final class WormholeRule implements AdjacencyRule {

    public static Set<String> connectionsFrom(Game game, Player player, String position) {
        Set<String> adjacent = new HashSet<>();
        new WormholeRule().from(new AdjacencyContext(game, player, AdjacencyPurpose.EFFECTS), position, adjacent);
        return adjacent;
    }

    @Override
    public void from(AdjacencyContext context, String position, Set<String> adjacent) {
        Tile tile = context.getGame().getTileByPosition(position);
        if (tile == null || tile.getTileID() == null) return;
        State state = state(context);
        Set<Wormhole> departures = state.departures(tile);
        if (departures.isEmpty()) return;
        for (Tile target : context.getGame().getTileMap().values()) {
            if (state.connects(departures, target)) {
                adjacent.add(target.getPosition());
            }
        }
    }

    @Override
    public void into(AdjacencyContext context, String position, Set<String> sources) {
        Tile target = context.getGame().getTileByPosition(position);
        if (target == null) return;
        State state = state(context);
        for (Tile source : context.getGame().getTileMap().values()) {
            if (source.getTileID() == null) continue;
            Set<Wormhole> departures = state.departures(source);
            if (!departures.isEmpty() && state.connects(departures, target)) {
                sources.add(source.getPosition());
            }
        }
    }

    private static State state(AdjacencyContext context) {
        return context.memo("wormholes", () -> new State(context));
    }

    private static boolean hasUncontrolledLegendaryPlanet(Player player, Tile tile) {
        return tile.getPlanetUnitHolders().stream()
                .anyMatch(planet -> planet.isLegendary() && !player.getPlanets().contains(planet.getName()));
    }

    private static final class State {
        private final Game game;
        private final Player player;
        private final List<String> creussFlagshipColors;
        private final boolean alphaBetaLinked;
        private final boolean sundered;
        private final boolean ghostWithoutEpsilon;
        private final boolean lazaxGateFolding;
        private final boolean lazaxGateFoldingBreakthrough;
        private final boolean twilightsFallGateFolding;
        private final boolean travelBanBlocksAlphaBeta;
        private final boolean ghostAgent;
        private final Map<String, Set<Wormhole>> departures = new HashMap<>();
        private final Map<String, List<Wormhole>> printed = new HashMap<>();

        private State(AdjacencyContext context) {
            game = context.getGame();
            player = context.getPlayer();
            creussFlagshipColors = Tile.creussFlagshipColors(game);
            alphaBetaLinked = player != null && player.hasAbility("quantum_entanglement")
                    || ButtonHelper.isLawInPlay(game, "wormhole_recon")
                    || ButtonHelper.isLawInPlay(game, "absol_recon");
            boolean activeWithSystem = context.isActivePlayer() && context.hasActivatedSystem();
            sundered = activeWithSystem && context.isMovement() && player.hasAbility("sundered");
            ghostWithoutEpsilon = player != null
                    && "ghost".equals(player.getFaction())
                    && game.getPlayerFromColorOrFaction("crimson") != null;
            lazaxGateFolding = activeWithSystem
                    && player.hasTech("lgf")
                    && !player.getPlanets().contains("mrte")
                    && !player.getPlanets().contains("mr");
            lazaxGateFoldingBreakthrough = activeWithSystem && player.hasTech("lgf_br");
            twilightsFallGateFolding = activeWithSystem && player.hasTech("tf-lazaxgatefolding");
            travelBanBlocksAlphaBeta =
                    !alphaBetaLinked && context.isMovement() && ButtonHelper.isLawInPlay(game, "travel_ban");
            ghostAgent = player != null
                    && player.isActivePlayer()
                    && !StringUtils.isEmpty(game.getStoredValue("ghostagent_active"))
                    && game.getActiveSystem().equals(game.getStoredValue("ghostagent_active"));
        }

        private Set<Wormhole> departures(Tile tile) {
            return departures.computeIfAbsent(tile.getPosition(), position -> computeDepartures(tile));
        }

        private List<Wormhole> printed(Tile tile) {
            return printed.computeIfAbsent(tile.getPosition(), position -> tile.getWormholes(creussFlagshipColors));
        }

        private boolean connects(Set<Wormhole> departures, Tile target) {
            List<Wormhole> targetWormholes = printed(target);
            boolean alphaOrBeta = departures.contains(Wormhole.ALPHA) || departures.contains(Wormhole.BETA);
            return !Collections.disjoint(departures, targetWormholes)
                    || ghostAgent && !targetWormholes.isEmpty()
                    || lazaxGateFoldingBreakthrough && alphaOrBeta && hasUncontrolledLegendaryPlanet(player, target);
        }

        private Set<Wormhole> computeDepartures(Tile tile) {
            Set<Wormhole> wormholes = EnumSet.noneOf(Wormhole.class);
            wormholes.addAll(printed(tile));

            if (sundered) {
                wormholes.retainAll(linkedOr(EnumSet.of(Wormhole.EPSILON)));
            }
            if (tile.getSpaceUnitHolder().getTokenList().contains(Constants.TOKEN_SEVERED)) {
                wormholes.retainAll(linkedOr(EnumSet.noneOf(Wormhole.class)));
            }
            if (ghostWithoutEpsilon) {
                wormholes.remove(Wormhole.EPSILON);
            }
            if (alphaBetaLinked) {
                if (wormholes.contains(Wormhole.ALPHA)) {
                    wormholes.add(Wormhole.BETA);
                } else if (wormholes.contains(Wormhole.BETA)) {
                    wormholes.add(Wormhole.ALPHA);
                }
            }
            if (lazaxGateFolding
                    && (tile.getUnitHolders().containsKey("mrte")
                            || tile.getUnitHolders().containsKey("mr"))) {
                addAlphaAndBeta(wormholes);
            }
            if (lazaxGateFoldingBreakthrough && hasUncontrolledLegendaryPlanet(player, tile)) {
                addAlphaAndBeta(wormholes);
            }
            if (twilightsFallGateFolding && hasUncontrolledLegendaryPlanet(player, tile)) {
                addAlphaAndBeta(wormholes);
            }
            if (travelBanBlocksAlphaBeta) {
                wormholes.remove(Wormhole.ALPHA);
                wormholes.remove(Wormhole.BETA);
            }
            return wormholes;
        }

        private Set<Wormhole> linkedOr(Set<Wormhole> keepers) {
            if (alphaBetaLinked) {
                keepers.add(Wormhole.ALPHA);
                keepers.add(Wormhole.BETA);
            }
            return keepers;
        }

        private static void addAlphaAndBeta(Set<Wormhole> wormholes) {
            wormholes.add(Wormhole.BETA);
            wormholes.add(Wormhole.ALPHA);
        }
    }
}

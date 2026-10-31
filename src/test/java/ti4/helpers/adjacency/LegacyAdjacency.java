package ti4.helpers.adjacency;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import software.amazon.awssdk.utils.StringUtils;
import ti4.discord.interactions.buttons.handlers.actioncards.bluereverie.FrameShiftBrActionCardHandler;
import ti4.discord.interactions.buttons.handlers.faction.homebrew.beans.natau.NatauAbilityHandler;
import ti4.discord.interactions.buttons.handlers.faction.homebrew.bluereverie.SarcosaCommanderHandler;
import ti4.game.Game;
import ti4.game.Planet;
import ti4.game.Player;
import ti4.game.Tile;
import ti4.game.UnitHolder;
import ti4.helpers.ButtonHelper;
import ti4.helpers.Constants;
import ti4.helpers.FoWHelper;
import ti4.helpers.Units.UnitKey;
import ti4.helpers.Units.UnitType;
import ti4.model.BorderAnomalyHolder;
import ti4.model.MapPositionMapper;
import ti4.model.WormholeModel.Wormhole;
import ti4.service.fow.FOWPlusService;
import ti4.service.game.MonumentsService;
import ti4.service.leader.UydaiHeroService;
import ti4.service.leader.agent.modules.homebrew.beans.NatauAgent;
import ti4.service.planet.AsgardLegendaryService;

/**
 * Frozen copy of FoWHelper.getAdjacentTiles and its helpers as they were before the Adjacency API (2026-10-30).
 * AdjacencyParityTest compares the new API against it. Do not "fix" anything here: it is the baseline.
 * Intended rule changes since then, each marked "Changed in" below:
 * - PR-M: custom links read primary -> listed for every purpose (vision already did).
 */
final class LegacyAdjacency {

    private LegacyAdjacency() {}

    public static Set<String> getAdjacentTiles(
            Game game, String position, Player player, boolean toShow, boolean includeTile, boolean forDistance) {
        if (FOWPlusService.isVoid(game, position)) return new HashSet<>();

        Set<String> adjacentPositions = traverseAdjacencies(game, false, position);

        // Changed in PR-M: was listed -> primary for movement and effects, primary -> listed for vision only.
        adjacentPositions.addAll(game.getCustomAdjacentTiles().getOrDefault(position, List.of()));

        Set<String> wormholeAdjacencies = getWormholeAdjacencies(game, position, player, false, forDistance);
        adjacentPositions.addAll(wormholeAdjacencies);

        Set<String> otherAdjacencies = getNonWormholeAdjacencies(game, player, position);
        adjacentPositions.addAll(otherAdjacencies);
        AsgardLegendaryService.addBifrostBridgeAdjacencies(game, player, position, adjacentPositions);

        if (forDistance) {
            NatauAbilityHandler.addChaosTurbineAdjacencies(game, player, position, adjacentPositions);
        }

        if (player != null
                && (game.playerHasLeaderUnlockedOrAlliance(player, "celdauricommander")
                        || player.hasTech("tf-starbasewebway"))
                && player == game.getActivePlayer()
                && forDistance
                && !game.getCurrentActiveSystem().isEmpty()
                && ButtonHelper.getTilesOfPlayersSpecificUnits(game, player, UnitType.Spacedock)
                        .contains(game.getTileByPosition(position))) {

            for (Tile tile : ButtonHelper.getTilesOfPlayersSpecificUnits(game, player, UnitType.Spacedock)) {
                if (tile.getPosition().equalsIgnoreCase(position)) {
                    continue;
                }
                adjacentPositions.add(tile.getPosition());
            }
        }

        if (player != null && MonumentsService.isMonumentOnBoard(game, player, "saar_monument")) {
            Tile monumentTile = MonumentsService.getMonumentTile(game, player, "saar_monument");
            String spaceDockPosition = game.getStoredValue("saarMonumentSpaceDock_" + player.getFaction());
            Tile spaceDockTile = game.getTileByPosition(spaceDockPosition);

            if (monumentTile != null
                    && spaceDockTile != null
                    && ButtonHelper.getTilesOfPlayersSpecificUnits(game, player, UnitType.Spacedock)
                            .contains(spaceDockTile)
                    && (position.equals(monumentTile.getPosition()) || position.equals(spaceDockPosition))) {
                adjacentPositions.add(
                        position.equals(monumentTile.getPosition()) ? spaceDockPosition : monumentTile.getPosition());
            }
        }

        if (player != null && MonumentsService.isMonumentOnBoard(game, player, "ghemina_monument")) {
            Tile monumentTile = MonumentsService.getMonumentTile(game, player, "ghemina_monument");

            if (monumentTile != null) {
                Set<String> matchingStructureSystems = game.getTileMap().values().stream()
                        .filter(tile -> tile.getUnitHolders().values().stream()
                                .flatMap(holder -> holder.getUnitKeysForPlayer(player).stream())
                                .filter(unitKey -> player.getUnitFromUnitKey(unitKey) != null)
                                .filter(unitKey ->
                                        player.getUnitFromUnitKey(unitKey).getIsStructure())
                                .map(UnitKey::unitType)
                                .distinct()
                                .anyMatch(unitType -> tile.getUnitHolders().values().stream()
                                                .mapToInt(holder -> holder.getUnitCount(unitType, player))
                                                .sum()
                                        >= 2))
                        .map(Tile::getPosition)
                        .collect(Collectors.toSet());

                if (position.equals(monumentTile.getPosition())) {
                    adjacentPositions.addAll(matchingStructureSystems);
                } else if (matchingStructureSystems.contains(position)) {
                    adjacentPositions.add(monumentTile.getPosition());
                }
            }
        }

        if (forDistance && player != null && MonumentsService.isMonumentOnBoard(game, player, "nivyn_monument")) {
            Tile monumentTile = MonumentsService.getMonumentTile(game, player, "nivyn_monument");
            Tile woundTile = game.getTileMap().values().stream()
                    .filter(tile -> tile.getSpaceUnitHolder().getTokenList().contains("token_ds_wound.png"))
                    .findFirst()
                    .orElse(null);

            if (monumentTile != null && woundTile != null) {
                if (position.equals(monumentTile.getPosition())) {
                    adjacentPositions.add(woundTile.getPosition());
                } else if (position.equals(woundTile.getPosition())) {
                    adjacentPositions.add(monumentTile.getPosition());
                }
            }
        }

        // If player has ghoti commander, is active player and has activated a system
        if (player != null
                && game.playerHasLeaderUnlockedOrAlliance(player, "ghoticommander")
                && player == game.getActivePlayer()
                && !game.getCurrentActiveSystem().isEmpty()) {
            Set<Player> playersToCheck = new HashSet<>();
            playersToCheck.add(player);
            if (game.isAllianceMode()) {
                playersToCheck.addAll(game.getRealPlayers().stream()
                        .filter(alliancePlayer -> player.getAllianceMembers().contains(alliancePlayer.getFaction()))
                        .collect(Collectors.toSet()));
            }

            // Check that they or their alliance have units in any empty system to be able to see the other empties as
            // adjacencies
            Set<Tile> emptyTiles = FoWHelper.getEmptyTiles(game);
            boolean containsUnits =
                    emptyTiles.stream().anyMatch(tile -> playersToCheck.stream().anyMatch(tile::containsPlayersUnits));
            if (containsUnits
                    && game.getTileByPosition(position).getPlanetUnitHolders().isEmpty()) {
                adjacentPositions.addAll(
                        emptyTiles.stream().map(Tile::getPosition).collect(Collectors.toSet()));
            }
        }

        addObsidianMirrorAdjacencies(game, player, position, adjacentPositions);
        if (!toShow) {
            UydaiHeroService.addAdjacencies(game, player, position, adjacentPositions);
        }

        if (player != null) {
            for (String tilePosition : game.getTileMap().keySet()) {
                if (SarcosaCommanderHandler.treatsAsAdjacent(game, player, position, tilePosition)) {
                    adjacentPositions.add(tilePosition);
                }
                if (NatauAgent.treatsAsAdjacent(game, player, position, tilePosition)) {
                    adjacentPositions.add(tilePosition);
                }
            }
        }

        if (includeTile) {
            adjacentPositions.add(position);
        } else {
            adjacentPositions.remove(position);
        }
        return adjacentPositions;
    }

    private static Set<String> getNonWormholeAdjacencies(Game game, Player player, String position) {
        Set<String> adjacentPositions = new HashSet<>();
        Set<Tile> allTiles = new HashSet<>(game.getTileMap().values());
        Tile tile = game.getTileByPosition(position);
        if (tile == null) {
            return adjacentPositions;
        }

        Set<Feature> adjToFeatures = EnumSet.noneOf(Feature.class);
        if (tile.hasEgress()) adjToFeatures.add(Feature.ingress);

        if (tile.hasIngress()) adjToFeatures.add(Feature.egress);

        if (game.isCosmicPhenomenaeMode()) {
            if (tile.isScar(game)) {
                adjToFeatures.add(Feature.scar);
            }
        }

        if (game.getActivePlayer() != null
                && game.getActivePlayer().hasUnlockedBreakthrough("nivynbt")
                && tile.isScar(game)) {
            adjToFeatures.add(Feature.egress);
        }

        if (game.getActivePlayer() != null
                && (game.getActivePlayer().hasTech("tf-fraactalspikedrives")
                        || game.getActivePlayer().hasTech("fsd"))
                && !tile.getWormholes(game).isEmpty()) {
            adjToFeatures.add(Feature.egress);
        }

        if (game.getActivePlayer() != null
                && FrameShiftBrActionCardHandler.isActive(game, game.getActivePlayer())
                && !tile.getWormholes(game).isEmpty()) {
            adjToFeatures.add(Feature.egress);
        }

        for (UnitHolder unitHolder : tile.getUnitHolders().values()) {
            for (String token : unitHolder.getTokenList()) {
                switch (token) {
                    case Constants.TOKEN_BREACH_ACTIVE -> adjToFeatures.add(Feature.breach);
                    case Constants.TOKEN_INGRESS -> adjToFeatures.add(Feature.egress);
                    case Constants.TOKEN_EGRESS -> adjToFeatures.add(Feature.ingress);
                }
            }
        }

        if (player != null
                && game.playerHasLeaderUnlockedOrAlliance(player, "nataucommander")
                && tile.isAnomaly(game, player)) {
            adjToFeatures.add(Feature.egress);
        }

        for (Tile t : allTiles) {
            if (adjToFeatures.contains(Feature.egress) && t.hasEgress()) {
                adjacentPositions.add(t.getPosition());
                continue;
            }
            if (adjToFeatures.contains(Feature.ingress) && t.hasIngress()) {
                adjacentPositions.add(t.getPosition());
                continue;
            }
            if (game.getActivePlayer() != null
                    && FrameShiftBrActionCardHandler.isActive(game, game.getActivePlayer())
                    && adjToFeatures.contains(Feature.ingress)
                    && !t.getWormholes(game).isEmpty()) {
                adjacentPositions.add(t.getPosition());
                continue;
            }
            if (game.isCosmicPhenomenaeMode()) {
                if (adjToFeatures.contains(Feature.scar) && t.isScar(game)) {
                    adjacentPositions.add(t.getPosition());
                    continue;
                }
            }
            for (UnitHolder unitHolder : t.getUnitHolders().values()) {
                for (String token : unitHolder.getTokenList()) {
                    if (adjToFeatures.contains(Feature.breach) && Constants.TOKEN_BREACH_ACTIVE.equals(token)) {
                        adjacentPositions.add(t.getPosition());
                        break;
                    }
                    if (adjToFeatures.contains(Feature.ingress) && Constants.TOKEN_INGRESS.equals(token)) {
                        adjacentPositions.add(t.getPosition());
                        break;
                    }
                    if (adjToFeatures.contains(Feature.egress) && Constants.TOKEN_EGRESS.equals(token)) {
                        adjacentPositions.add(t.getPosition());
                        break;
                    }
                }
            }
        }
        return adjacentPositions;
    }

    public static Set<String> traverseAdjacencies(Game game, boolean naturalMapOnly, String position) {
        return traverseAdjacencies(game, naturalMapOnly, position, -1, new HashSet<>());
    }

    /**
     * Return a list of tile positions that are adjacent to a source position either
     * directly or via hyperlanes
     * <p>
     * Does not traverse wormholes
     */
    static Set<String> traverseAdjacencies(
            Game game, boolean naturalMapOnly, String position, Integer sourceDirection, Set<String> exploredSet) {
        Set<String> tiles = new HashSet<>();
        if (exploredSet.contains(position + sourceDirection)) {
            // We already explored this tile from this direction!
            return tiles;
        }
        // mark the tile as explored
        exploredSet.add(position + sourceDirection);

        Tile currentTile = game.getTileByPosition(position);
        if (currentTile == null) {
            // could not load the requested tile
            return tiles;
        }

        List<Boolean> hyperlaneData = currentTile.getHyperlaneData(sourceDirection, game);
        if (hyperlaneData != null && hyperlaneData.isEmpty() && !naturalMapOnly) {
            // We could not load the hyperlane data correctly, quit
            return tiles;
        }

        // we are allowed to see this tile
        tiles.add(position);

        if ((hyperlaneData == null || naturalMapOnly) && sourceDirection != -1) {
            // do not explore non-hyperlanes except for your starting space
            return tiles;
        }

        List<String> directlyAdjacentTiles = MapPositionMapper.getAdjacentTilePositions(position);
        if (directlyAdjacentTiles == null || directlyAdjacentTiles.size() != 6) {
            // adjacency file for this tile is not filled in
            return tiles;
        }

        // for each adjacent tile...
        for (int i = 0; i < 6; i++) {
            int dirFrom = (i + 3) % 6;
            String position_ = directlyAdjacentTiles.get(i);
            boolean borderBlocked = false;
            for (BorderAnomalyHolder b : game.getBorderAnomalies()) {
                if (b == null || b.getTile() == null) continue;
                if (b.getTile().equals(position) && b.getDirection() == i && b.blocksAdjacencyOut()
                        || b.getTile().equals(position_) && b.getDirection() == dirFrom && b.blocksAdjacencyIn()) {
                    borderBlocked = true;
                }
            }
            if (borderBlocked && !naturalMapOnly) continue;

            String override = game.getAdjacentTileOverride(position, i);
            if (override != null) {
                if (naturalMapOnly) continue;
                position_ = override;
            }

            if ("x".equals(position_) || (hyperlaneData != null && !hyperlaneData.isEmpty() && !hyperlaneData.get(i))) {
                // the hyperlane doesn't exist & doesn't go that direction, skip.
                continue;
            }

            if (!FOWPlusService.shouldTraverseAdjacency(game, position_, dirFrom)) {
                continue;
            }

            // explore that tile now!
            int direcetionFrom = naturalMapOnly ? -2 : dirFrom;
            Set<String> newTiles = traverseAdjacencies(game, naturalMapOnly, position_, direcetionFrom, exploredSet);
            tiles.addAll(newTiles);
        }
        return tiles;
    }

    private static Set<String> getWormholeAdjacencies(
            Game game, String position, Player player, boolean neighbors, boolean forDistance) {
        Set<String> adjacentPositions = new HashSet<>();
        Tile tile = game.getTileByPosition(position);
        if (tile == null || tile.getTileID() == null) {
            return adjacentPositions;
        }

        boolean wh_recon = ButtonHelper.isLawInPlay(game, "wormhole_recon");
        boolean absol_recon = ButtonHelper.isLawInPlay(game, "absol_recon");
        List<String> creussFlagshipColors = Tile.creussFlagshipColors(game);
        Set<Wormhole> wormholes = EnumSet.noneOf(Wormhole.class);
        wormholes.addAll(tile.getWormholes(creussFlagshipColors));

        boolean hasQuantumEntanglement = player != null && player.hasAbility("quantum_entanglement");
        boolean alphaBetaLinked = hasQuantumEntanglement || wh_recon || absol_recon;

        if (player != null
                && player.hasAbility("sundered")
                && player == game.getActivePlayer()
                && forDistance
                && !game.getCurrentActiveSystem().isEmpty()) {
            Set<Wormhole> keepers = EnumSet.of(Wormhole.EPSILON);
            if (alphaBetaLinked) {
                keepers.addAll(Set.of(Wormhole.ALPHA, Wormhole.BETA));
            }
            wormholes.retainAll(keepers);
        }

        if (tile.getSpaceUnitHolder().getTokenList().contains(Constants.TOKEN_SEVERED)) {
            Set<Wormhole> keepers = EnumSet.noneOf(Wormhole.class);
            if (alphaBetaLinked) {
                keepers.addAll(Set.of(Wormhole.ALPHA, Wormhole.BETA));
            }
            wormholes.retainAll(keepers);
        }

        if (player != null
                && "ghost".equals(player.getFaction())
                && game.getPlayerFromColorOrFaction("crimson") != null) {
            wormholes.remove(Wormhole.EPSILON);
        }

        if (alphaBetaLinked) {
            if (wormholes.contains(Wormhole.ALPHA)) {
                wormholes.add(Wormhole.BETA);
            } else if (wormholes.contains(Wormhole.BETA)) {
                wormholes.add(Wormhole.ALPHA);
            }
        }

        if (player != null
                && player == game.getActivePlayer()
                && !game.getCurrentActiveSystem().isEmpty()
                && player.hasTech("lgf")
                && !player.getPlanets().contains("mrte")
                && !player.getPlanets().contains("mr")
                && (tile.getUnitHolders().containsKey("mrte")
                        || tile.getUnitHolders().containsKey("mr"))) {
            wormholes.add(Wormhole.BETA);
            wormholes.add(Wormhole.ALPHA);
        }

        boolean hasLazaxGateFoldingBr = player != null
                && player == game.getActivePlayer()
                && !game.getCurrentActiveSystem().isEmpty()
                && player.hasTech("lgf_br");
        if (hasLazaxGateFoldingBr && hasUncontrolledLegendaryPlanet(player, tile)) {
            wormholes.add(Wormhole.BETA);
            wormholes.add(Wormhole.ALPHA);
        }

        if (player != null
                && player == game.getActivePlayer()
                && !game.getCurrentActiveSystem().isEmpty()
                && player.hasTech("tf-lazaxgatefolding")) {
            boolean hasUncontrolledLeg = false;
            for (Planet planet : tile.getPlanetUnitHolders()) {
                if (planet.isLegendary() && !player.getPlanets().contains(planet.getName())) {
                    hasUncontrolledLeg = true;
                }
            }
            if (hasUncontrolledLeg) {
                wormholes.add(Wormhole.BETA);
                wormholes.add(Wormhole.ALPHA);
            }
        }
        if (!alphaBetaLinked && forDistance && ButtonHelper.isLawInPlay(game, "travel_ban") && !neighbors) {
            wormholes.remove(Wormhole.ALPHA);
            wormholes.remove(Wormhole.BETA);
        }

        if (wormholes.isEmpty()) {
            return adjacentPositions;
        }

        boolean ghostAgent = player != null
                && player.isActivePlayer()
                && !StringUtils.isEmpty(game.getStoredValue("ghostagent_active"))
                && game.getActiveSystem().equals(game.getStoredValue("ghostagent_active"));
        boolean alphaOrBeta = wormholes.contains(Wormhole.ALPHA) || wormholes.contains(Wormhole.BETA);
        for (Tile target : game.getTileMap().values()) {
            List<Wormhole> targetWormholes = target.getWormholes(creussFlagshipColors);
            if (!Collections.disjoint(wormholes, targetWormholes)
                    || ghostAgent && !targetWormholes.isEmpty()
                    || hasLazaxGateFoldingBr && alphaOrBeta && hasUncontrolledLegendaryPlanet(player, target)) {
                adjacentPositions.add(target.getPosition());
            }
        }
        return adjacentPositions;
    }

    private static boolean hasUncontrolledLegendaryPlanet(Player player, Tile tile) {
        return tile.getPlanetUnitHolders().stream()
                .anyMatch(planet -> planet.isLegendary() && !player.getPlanets().contains(planet.getName()));
    }

    private static void addObsidianMirrorAdjacencies(
            Game game, Player player, String position, Set<String> adjacentPositions) {
        if (game == null || player == null || position == null || !player.hasUnit("oblivion_flagship")) {
            return;
        }

        Tile queriedTile = game.getTileByPosition(position);
        if (queriedTile == null || !queriedTile.getPlanetUnitHolders().isEmpty()) {
            return;
        }

        Set<String> flagshipPositions = game.getTileMap().values().stream()
                .filter(tile -> tile.getPlanetUnitHolders().isEmpty())
                .filter(tile -> tile.getSpaceUnitHolder().getUnitCount(UnitType.Flagship, player) > 0)
                .map(Tile::getPosition)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        boolean flagshipIsMoving = false;
        for (var entry : game.getTacticalActionDisplacement().entrySet()) {
            boolean containsFlagship = entry.getValue().entrySet().stream()
                    .filter(unit -> player.unitBelongsToPlayer(unit.getKey()))
                    .filter(unit -> unit.getKey().unitType() == UnitType.Flagship)
                    .anyMatch(unit ->
                            unit.getValue().stream().mapToInt(Integer::intValue).sum() > 0);
            if (!containsFlagship) {
                continue;
            }

            flagshipIsMoving = true;
            int holderSeparator = entry.getKey().indexOf('-');
            String origin =
                    holderSeparator < 0 ? entry.getKey() : entry.getKey().substring(0, holderSeparator);
            Tile originTile = game.getTileByPosition(origin);
            if (originTile != null && originTile.getPlanetUnitHolders().isEmpty()) {
                flagshipPositions.add(origin);
            }
        }
        if (flagshipIsMoving) {
            Tile activeTile = game.getTileByPosition(game.getActiveSystem());
            if (activeTile != null && activeTile.getPlanetUnitHolders().isEmpty()) {
                flagshipPositions.add(activeTile.getPosition());
            }
        }
        if (flagshipPositions.isEmpty()) {
            return;
        }

        if (flagshipPositions.contains(position)) {
            game.getTileMap().values().stream()
                    .filter(tile -> !tile.isFracture())
                    .filter(tile -> tile.getPlanetUnitHolders().isEmpty())
                    .map(Tile::getPosition)
                    .forEach(adjacentPositions::add);
        } else {
            Tile currentTile = game.getTileByPosition(position);
            boolean inFracture = currentTile != null ? currentTile.isFracture() : position.startsWith("frac");
            if (!inFracture) {
                adjacentPositions.addAll(flagshipPositions);
            }
        }
    }

    private enum Feature {
        ingress,
        egress,
        scar,
        breach
    }
}

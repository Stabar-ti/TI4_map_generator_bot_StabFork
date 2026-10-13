package ti4.service.map;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;
import ti4.game.Game;
import ti4.image.BoardPosition;
import ti4.image.GalaxyNames;
import ti4.image.MapFrame;
import ti4.image.MapSegment;
import ti4.service.map.MapJsonIOService.MapDataIO;
import ti4.service.map.MapJsonIOService.SectorDefIO;
import ti4.service.map.MapJsonIOService.SectorIO;
import ti4.service.map.MapJsonIOService.TileIO;

@UtilityClass
class MapSectorJsonService {

    static final int FORMAT_VERSION = 2;

    enum SectorType {
        AUTOMATIC("automatic", "auto"),
        MANUAL_AUTO("cluster", "manual_auto"),
        MANUAL_CIRCLE("circle", "manual_circle"),
        ASSIGNED("assigned", "member"),
        BOARD("board", "board"),
        FRACTURE("fracture", "fracture");

        private final String jsonName;
        private final String alias;

        SectorType(String jsonName, String alias) {
            this.jsonName = jsonName;
            this.alias = alias;
        }

        @Nullable
        static SectorType parse(@Nullable String raw) {
            if (raw == null) {
                return null;
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            for (SectorType type : values()) {
                if (type.jsonName.equals(normalized) || type.alias.equals(normalized)) {
                    return type;
                }
            }
            return null;
        }

        boolean isTileDefinition() {
            return this == AUTOMATIC || this == MANUAL_AUTO || this == MANUAL_CIRCLE;
        }
    }

    private record SectorEntry(String position, SectorType type, int distance, String name) {}

    static boolean hasSectorData(MapDataIO mapData) {
        boolean anyTileSector = mapData.getMapInfo() != null
                && mapData.getMapInfo().stream()
                        .anyMatch(tile -> tile.getSector() != null || StringUtils.isNotBlank(tile.getSectorName()));
        boolean anyGalaxy =
                mapData.getGalaxies() != null && !mapData.getGalaxies().isEmpty();
        return anyTileSector
                || anyGalaxy
                || hasSectorList(mapData)
                || mapData.getSectorGap() != null
                || mapData.getAutoSectors() != null
                || StringUtils.isNotBlank(mapData.getDefaultSector());
    }

    private static boolean hasSectorList(MapDataIO mapData) {
        return mapData.getSectors() != null && !mapData.getSectors().isEmpty();
    }

    static void importSectors(Game game, MapDataIO mapData, StringBuilder errors) {
        if (!hasSectorData(mapData)) {
            return;
        }
        MapSegment.clearAll(game);
        GalaxyNames.clearNames(game);

        boolean sectorList = hasSectorList(mapData);
        List<SectorEntry> entries = sectorList ? List.of() : readEntries(game, mapData, errors);
        if (sectorList && hasLegacyTileSectors(mapData)) {
            errors.append("- sectors: the file has a top-level `sectors` list, so the per-tile `sector` objects"
                    + " were ignored.\n");
        }
        if (!game.isFowMode()
                && (sectorList
                        || hasTileSectorNames(mapData)
                        || !entries.isEmpty()
                        || mapData.getSectorGap() != null)) {
            errors.append("- sectors: map sectors are only used in Fog of War games; they were stored but have no"
                    + " effect in this game.\n");
        }
        applyGap(game, mapData, entries, errors);
        if (Boolean.TRUE.equals(mapData.getAutoSectors())) {
            MapSegment.setAutoSectors(game, true);
        }
        Set<String> takenNames = new HashSet<>(Set.of(MapSegment.MAIN, MapSegment.FRACTURE));
        if (sectorList) {
            applySectorList(game, mapData.getSectors(), takenNames, errors);
        } else {
            applyManualSectors(game, entries, takenNames, errors);
            applyAutomaticSectors(game, entries, takenNames, errors);
        }
        applyTileSectorNames(game, mapData, errors);
        applyDefaultSector(game, mapData.getDefaultSector(), errors);
        applyGalaxies(game, mapData.getGalaxies(), errors);
        reportOverlappingManualSectors(game, errors);
        reportUnusedAutomaticNames(game, errors);
    }

    private static boolean hasLegacyTileSectors(MapDataIO mapData) {
        return mapData.getMapInfo() != null && mapData.getMapInfo().stream().anyMatch(tile -> tile.getSector() != null);
    }

    private static boolean hasTileSectorNames(MapDataIO mapData) {
        return mapData.getMapInfo() != null
                && mapData.getMapInfo().stream().anyMatch(tile -> StringUtils.isNotBlank(tile.getSectorName()));
    }

    private static void applySectorList(
            Game game, List<SectorDefIO> sectors, Set<String> takenNames, StringBuilder errors) {
        Map<SectorType, List<SectorDefIO>> byType = new LinkedHashMap<>();
        for (SectorDefIO sector : sectors) {
            SectorType type = SectorType.parse(sector.getType());
            if (type == null) {
                listError(
                        errors,
                        sector,
                        "unknown type `" + sector.getType()
                                + "` (use assigned, circle, cluster or automatic; board and fracture are export-only)");
                continue;
            }
            byType.computeIfAbsent(type, key -> new ArrayList<>()).add(sector);
        }
        Set<String> namesToAvoid = new HashSet<>(takenNames);
        sectors.stream()
                .map(MapSectorJsonService::nameOf)
                .filter(name -> !name.isEmpty())
                .forEach(namesToAvoid::add);
        for (SectorType type : List.of(SectorType.MANUAL_CIRCLE, SectorType.MANUAL_AUTO)) {
            for (SectorDefIO sector : byType.getOrDefault(type, List.of())) {
                applyDefinedSector(game, sector, type == SectorType.MANUAL_AUTO, takenNames, namesToAvoid, errors);
            }
        }
        for (SectorDefIO sector : byType.getOrDefault(SectorType.AUTOMATIC, List.of())) {
            applyListedAutomaticSector(game, sector, takenNames, errors);
        }
        for (SectorDefIO sector : byType.getOrDefault(SectorType.ASSIGNED, List.of())) {
            applyAssignedSector(game, sector, errors);
        }
    }

    private static void applyDefinedSector(
            Game game,
            SectorDefIO sector,
            boolean cluster,
            Set<String> takenNames,
            Set<String> namesToAvoid,
            StringBuilder errors) {
        String centre = StringUtils.trimToEmpty(sector.getCentre()).toLowerCase(Locale.ROOT);
        if (centre.isEmpty()) {
            listError(errors, sector, "a " + (cluster ? "cluster" : "circle") + " needs a `centre` position");
            return;
        }
        if (!MapSegment.canJoinSector(centre)) {
            listError(errors, sector, "`" + centre + "` cannot be the centre of a sector");
            return;
        }
        String name = nameOf(sector).isEmpty() ? MapSegment.freeSectorName(game, centre, namesToAvoid) : nameOf(sector);
        namesToAvoid.add(name);
        if (takenNames.contains(name)) {
            listError(errors, sector, "the name `" + name + "` is already used; skipped");
            return;
        }
        int distance = sector.getDistance() == null ? 0 : sector.getDistance();
        String problem = MapSegment.validate(game, name, centre, distance, cluster);
        if (problem != null) {
            listError(errors, sector, problem);
            return;
        }
        MapSegment.put(
                game, cluster ? MapSegment.cluster(name, centre, distance) : new MapSegment(name, centre, distance));
        takenNames.add(name);
    }

    private static void applyListedAutomaticSector(
            Game game, SectorDefIO sector, Set<String> takenNames, StringBuilder errors) {
        MapSegment.setAutoSectors(game, true);
        String name = nameOf(sector);
        if (Boolean.FALSE.equals(sector.getPinned()) || name.isEmpty()) {
            return;
        }
        Set<String> positions = usablePositions(sector, errors);
        if (positions.isEmpty()) {
            listError(errors, sector, "an automatic name needs at least one usable position");
            return;
        }
        if (takenNames.contains(name)) {
            listError(errors, sector, "the name `" + name + "` is already used by a manual sector");
            return;
        }
        String problem = MapSegment.putPin(game, name, positions);
        if (problem != null) {
            listError(errors, sector, problem);
            return;
        }
        takenNames.add(name);
    }

    private static void applyAssignedSector(Game game, SectorDefIO sector, StringBuilder errors) {
        String name = nameOf(sector);
        if (name.isEmpty()) {
            listError(errors, sector, "an assigned sector needs a `name`");
            return;
        }
        Set<String> positions = usablePositions(sector, errors);
        if (positions.isEmpty()) {
            listError(errors, sector, "an assigned sector needs at least one usable position");
            return;
        }
        String problem = MapSegment.assign(game, name, positions);
        if (problem != null) {
            listError(errors, sector, problem);
        }
    }

    private static Set<String> usablePositions(SectorDefIO sector, StringBuilder errors) {
        Set<String> positions = new LinkedHashSet<>();
        if (sector.getPositions() == null) {
            return positions;
        }
        for (String raw : sector.getPositions()) {
            String position = StringUtils.trimToEmpty(raw).toLowerCase(Locale.ROOT);
            if (MapSegment.canJoinSector(position)) {
                positions.add(position);
            } else {
                listError(errors, sector, "`" + position + "` cannot be part of a sector; skipped");
            }
        }
        return positions;
    }

    private static void applyTileSectorNames(Game game, MapDataIO mapData, StringBuilder errors) {
        if (mapData.getMapInfo() == null) {
            return;
        }
        for (TileIO tile : mapData.getMapInfo()) {
            String name = StringUtils.trimToEmpty(tile.getSectorName()).toLowerCase(Locale.ROOT);
            String position = StringUtils.trimToEmpty(tile.getPosition()).toLowerCase(Locale.ROOT);
            if (name.isEmpty() || game.getTileByPosition(position) == null) {
                continue;
            }
            if (!MapSegment.canJoinSector(position)) {
                sectorError(errors, position, "fracture and corner systems cannot be part of a sector");
                continue;
            }
            String listed = MapSegment.tagOf(game, position);
            if (listed != null && !listed.equals(name)) {
                sectorError(
                        errors,
                        position,
                        "`sectorName` " + name + " overrides the `sectors` list, which assigned it to " + listed);
            }
            String problem = MapSegment.assign(game, name, List.of(position));
            if (problem != null) {
                sectorError(errors, position, problem);
            }
        }
    }

    private static void applyDefaultSector(Game game, @Nullable String rawName, StringBuilder errors) {
        String name = StringUtils.trimToEmpty(rawName).toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return;
        }
        if (MapSegment.find(game, name).isEmpty()) {
            errors.append("- sectors: `defaultSector` ").append(name).append(" is not a sector on this map.\n");
            return;
        }
        MapSegment.setDefault(game, name);
    }

    private static String nameOf(SectorDefIO sector) {
        return StringUtils.trimToEmpty(sector.getName()).toLowerCase(Locale.ROOT);
    }

    private static void listError(StringBuilder errors, SectorDefIO sector, String reason) {
        errors.append("- sector `")
                .append(StringUtils.defaultIfBlank(sector.getName(), "(unnamed)"))
                .append("`: ")
                .append(reason)
                .append('\n');
    }

    private static List<SectorEntry> readEntries(Game game, MapDataIO mapData, StringBuilder errors) {
        List<SectorEntry> entries = new ArrayList<>();
        if (mapData.getMapInfo() == null) {
            return entries;
        }
        for (TileIO tile : mapData.getMapInfo()) {
            SectorIO sector = tile.getSector();
            if (sector == null || game.getTileByPosition(tile.getPosition()) == null) {
                continue;
            }
            String position = tile.getPosition();
            if (!MapSegment.canJoinSector(position)) {
                sectorError(errors, position, "fracture and corner systems cannot be part of a sector");
                continue;
            }
            SectorType type = SectorType.parse(sector.getType());
            if (type == null || !type.isTileDefinition()) {
                sectorError(
                        errors,
                        position,
                        "unknown type `" + sector.getType() + "` (use automatic, manual_auto or manual_circle; for"
                                + " membership use `sectorName`)");
                continue;
            }
            int distance = sector.getDistance() == null ? 0 : sector.getDistance();
            String name = StringUtils.trimToEmpty(sector.getName()).toLowerCase(Locale.ROOT);
            entries.add(new SectorEntry(position, type, distance, name));
        }
        return entries;
    }

    private static void applyGap(Game game, MapDataIO mapData, List<SectorEntry> entries, StringBuilder errors) {
        Set<Integer> automaticGaps = entries.stream()
                .filter(entry -> entry.type() == SectorType.AUTOMATIC)
                .map(SectorEntry::distance)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (mapData.getSectorGap() != null) {
            MapSegment.setGap(game, mapData.getSectorGap());
            if (automaticGaps.stream().anyMatch(gap -> !gap.equals(mapData.getSectorGap()))) {
                errors.append("- sectors: `sectorGap` ")
                        .append(MapSegment.gap(game))
                        .append(" overrides the distances on automatic systems.\n");
            }
            return;
        }
        if (automaticGaps.isEmpty()) {
            return;
        }
        int largest = automaticGaps.stream().max(Integer::compare).orElse(0);
        MapSegment.setGap(game, largest);
        if (automaticGaps.size() > 1) {
            errors.append("- sectors: automatic systems disagree on the gap ")
                    .append(automaticGaps)
                    .append("; the gap is game-wide, so ")
                    .append(MapSegment.gap(game))
                    .append(" is used.\n");
        }
    }

    private static void applyManualSectors(
            Game game, List<SectorEntry> entries, Set<String> takenNames, StringBuilder errors) {
        Set<String> namesToAvoid = new HashSet<>(takenNames);
        entries.stream().map(SectorEntry::name).filter(name -> !name.isEmpty()).forEach(namesToAvoid::add);
        for (SectorEntry entry : entries) {
            if (entry.type() == SectorType.AUTOMATIC) {
                continue;
            }
            String name = entry.name().isEmpty()
                    ? MapSegment.freeSectorName(game, entry.position(), namesToAvoid)
                    : entry.name();
            namesToAvoid.add(name);
            if (takenNames.contains(name)) {
                sectorError(errors, entry.position(), "the name `" + name + "` is already used; skipped");
                continue;
            }
            boolean cluster = entry.type() == SectorType.MANUAL_AUTO;
            String problem = MapSegment.validate(game, name, entry.position(), entry.distance(), cluster);
            if (problem != null) {
                sectorError(errors, entry.position(), problem);
                continue;
            }
            MapSegment.put(
                    game,
                    cluster
                            ? MapSegment.cluster(name, entry.position(), entry.distance())
                            : new MapSegment(name, entry.position(), entry.distance()));
            takenNames.add(name);
        }
    }

    private static void applyAutomaticSectors(
            Game game, List<SectorEntry> entries, Set<String> takenNames, StringBuilder errors) {
        List<SectorEntry> automatic = entries.stream()
                .filter(entry -> entry.type() == SectorType.AUTOMATIC)
                .toList();
        if (automatic.isEmpty()) {
            return;
        }
        MapSegment.setAutoSectors(game, true);
        Map<String, Set<String>> positionsByName = new LinkedHashMap<>();
        for (SectorEntry entry : automatic) {
            if (!entry.name().isEmpty()) {
                positionsByName
                        .computeIfAbsent(entry.name(), name -> new HashSet<>())
                        .add(entry.position());
            }
        }
        positionsByName.forEach((name, positions) -> {
            String anyPosition = positions.iterator().next();
            if (takenNames.contains(name)) {
                sectorError(errors, anyPosition, "the name `" + name + "` is already used by a manual sector");
                return;
            }
            String problem = MapSegment.putPin(game, name, positions);
            if (problem != null) {
                sectorError(errors, anyPosition, problem);
                return;
            }
            takenNames.add(name);
        });
    }

    private static void applyGalaxies(Game game, @Nullable Map<String, String> galaxies, StringBuilder errors) {
        if (galaxies == null || galaxies.isEmpty()) {
            GalaxyNames.ensureAssigned(game);
            return;
        }
        if (!game.isFowMode()) {
            errors.append("- galaxies: extra galaxies need a Fog of War game; galaxy names were skipped.\n");
            return;
        }
        List<String> inUse = GalaxyNames.inUse(game);
        galaxies.forEach((rawId, rawName) -> {
            String id = StringUtils.trimToEmpty(rawId).toLowerCase(Locale.ROOT);
            String name = StringUtils.trimToEmpty(rawName).toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                GalaxyNames.resetToAuto(game, id);
                return;
            }
            String problem = GalaxyNames.rename(game, id, name);
            if (problem != null) {
                errors.append("- galaxy `")
                        .append(id)
                        .append("`: ")
                        .append(problem)
                        .append('\n');
            } else if (!inUse.contains(id)) {
                errors.append("- galaxy `")
                        .append(id)
                        .append("`: no systems are on it yet; the name is kept for when it is used.\n");
            }
        });
        GalaxyNames.ensureAssigned(game);
    }

    private static void reportOverlappingManualSectors(Game game, StringBuilder errors) {
        Set<String> placed = game.getTileMap().keySet();
        List<MapSegment> manual = MapSegment.all(game).stream()
                .filter(segment ->
                        segment.kind() == MapSegment.Kind.CIRCLE || segment.kind() == MapSegment.Kind.CLUSTER)
                .toList();
        for (int first = 0; first < manual.size(); first++) {
            for (int second = first + 1; second < manual.size(); second++) {
                Set<String> shared = new HashSet<>(manual.get(first).positions());
                shared.retainAll(manual.get(second).positions());
                shared.retainAll(placed);
                if (!shared.isEmpty()) {
                    errors.append("- sectors: `")
                            .append(manual.get(first).name())
                            .append("` and `")
                            .append(manual.get(second).name())
                            .append("` share ")
                            .append(shared.size())
                            .append(" system(s).\n");
                }
            }
        }
    }

    private static void reportUnusedAutomaticNames(Game game, StringBuilder errors) {
        for (MapSegment.Dormant dormant : MapSegment.dormantNames(game)) {
            errors.append("- sectors: automatic name `").append(dormant.name()).append("` is not used: ");
            if (dormant.mergedInto() == null) {
                errors.append("its systems touch a manual sector or are not on the map.\n");
            } else {
                errors.append("its cluster is already named `")
                        .append(dormant.mergedInto())
                        .append("`.\n");
            }
        }
    }

    private static void sectorError(StringBuilder errors, String position, String reason) {
        errors.append("- sector at ")
                .append(position)
                .append(": ")
                .append(reason)
                .append('\n');
    }

    static void exportSectors(Game game, MapDataIO mapData) {
        mapData.setFormatVersion(FORMAT_VERSION);
        Map<String, TileIO> tilesByPosition = mapData.getMapInfo().stream()
                .collect(Collectors.toMap(TileIO::getPosition, Function.identity(), (first, second) -> first));
        Map<String, String> tags = MapSegment.tags(game);
        tags.forEach((position, name) -> {
            TileIO tile = tilesByPosition.get(position);
            if (tile != null) {
                tile.setSectorName(name);
            }
        });
        List<SectorDefIO> sectors = new ArrayList<>();
        for (MapSegment segment : MapSegment.all(game)) {
            sectors.add(sectorDef(game, segment));
        }
        sectors.addAll(tagsExtendingDefinedSectors(game, tags));
        if (!sectors.isEmpty()) {
            mapData.setSectors(sectors);
        }
        if (MapSegment.isAutoSectors(game)) {
            mapData.setAutoSectors(true);
        }
        if (MapSegment.gap(game) > 0) {
            mapData.setSectorGap(MapSegment.gap(game));
        }
        MapSegment.defaultSegment(game).ifPresent(segment -> mapData.setDefaultSector(segment.name()));
        Map<String, String> manualGalaxies = new LinkedHashMap<>();
        for (String id : GalaxyNames.IDS) {
            if (GalaxyNames.isManual(game, id)) {
                manualGalaxies.put(id, GalaxyNames.name(game, id));
            }
        }
        if (!manualGalaxies.isEmpty()) {
            mapData.setGalaxies(manualGalaxies);
        }
    }

    private static SectorDefIO sectorDef(Game game, MapSegment segment) {
        SectorDefIO sector = new SectorDefIO();
        sector.setName(segment.name());
        sector.setType(typeOf(segment.kind()).jsonName);
        sector.setPositions(sorted(segment.positions()));
        sector.setGalaxy(galaxyOf(segment.positions()));
        sector.setDisplayName(segment.displayName(game));
        if (segment.kind() == MapSegment.Kind.CIRCLE || segment.kind() == MapSegment.Kind.CLUSTER) {
            sector.setCentre(segment.centre());
            sector.setDistance(segment.radius());
        }
        if (segment.kind() == MapSegment.Kind.AUTO) {
            sector.setPinned(MapSegment.isPinned(game, segment.name()));
        }
        return sector;
    }

    private static List<SectorDefIO> tagsExtendingDefinedSectors(Game game, Map<String, String> tags) {
        Set<String> definedNames =
                MapSegment.stored(game).stream().map(MapSegment::name).collect(Collectors.toSet());
        Map<String, Set<String>> positionsByName = new LinkedHashMap<>();
        tags.forEach((position, name) -> {
            if (definedNames.contains(name)) {
                positionsByName.computeIfAbsent(name, key -> new HashSet<>()).add(position);
            }
        });
        return positionsByName.entrySet().stream()
                .map(entry -> {
                    SectorDefIO sector = new SectorDefIO();
                    sector.setName(entry.getKey());
                    sector.setType(SectorType.ASSIGNED.jsonName);
                    sector.setPositions(sorted(entry.getValue()));
                    return sector;
                })
                .toList();
    }

    private static SectorType typeOf(MapSegment.Kind kind) {
        return switch (kind) {
            case CIRCLE -> SectorType.MANUAL_CIRCLE;
            case CLUSTER -> SectorType.MANUAL_AUTO;
            case AUTO -> SectorType.AUTOMATIC;
            case TAGGED -> SectorType.ASSIGNED;
            case BOARD -> SectorType.BOARD;
            case FRACTURE -> SectorType.FRACTURE;
        };
    }

    @Nullable
    private static String galaxyOf(Set<String> positions) {
        Set<Character> boards = positions.stream()
                .filter(position -> !MapSegment.isFracturePosition(position))
                .map(BoardPosition::boardOf)
                .collect(Collectors.toSet());
        return boards.size() == 1 ? GalaxyNames.idOf(boards.iterator().next()) : null;
    }

    private static List<String> sorted(Set<String> positions) {
        return positions.stream().sorted(MapFrame.POSITION_ORDER).toList();
    }
}

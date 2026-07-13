package ti4.service.fow.milty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.experimental.UtilityClass;
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent;
import ti4.discord.interactions.buttons.Buttons;
import ti4.game.Game;
import ti4.game.Planet;
import ti4.game.Player;
import ti4.game.Tile;
import ti4.image.Mapper;
import ti4.image.PositionMapper;
import ti4.message.MessageHelper;
import ti4.model.FactionModel;
import ti4.model.Source.ComponentSource;
import ti4.model.StrategyCardModel;
import ti4.model.StrategyCardSetModel;
import ti4.service.draft.PlayerSetupService;
import ti4.service.draft.PlayerSetupState;
import ti4.service.fow.GMService;
import ti4.service.game.StartPhaseService;
import ti4.service.map.AddTileListService;

/**
 * Orchestration for the Fog-of-War specific milty draft ({@code /fow milty}).
 *
 * <p>The whole feature is gated behind {@link Game#isFowMode()} and driven from the GM room.
 * All draft state lives in a single {@link FowMiltyDraftState} serialized into a game stored
 * value, so no existing save/load code is touched. Existing services are reused by calling
 * their public methods (never modifying them): faction pools are mirrored from the classic
 * milty shuffle, and seating goes through {@link PlayerSetupService}.
 *
 * <p>The first bag is either Strategy Cards (SCs pre-assigned, first strategy phase skipped via
 * {@link StartPhaseService#startActionPhase}) or an obfuscated Draft-Order bag (table re-ordered
 * by closeness to Nx, then {@link StartPhaseService#startStrategyPhase} runs normally).
 */
@UtilityClass
public class FowMiltyService {

    static final String PREFIX = "fowmilty_";

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    /**
     * Entry point from {@code /fow milty}. Initializes state and posts GM setup controls.
     * Returns true if setup was posted, false if a precondition failed (a reason is messaged).
     * A map template is NOT required here — it's only needed for auto region values, which is
     * checked when the GM picks that option.
     */
    public static boolean startSetup(Game game, GenericInteractionCreateEvent event) {
        if (!game.isFowMode()) {
            MessageHelper.sendMessageToChannel(
                    event.getMessageChannel(), "The FoW milty draft is only available in Fog of War games.");
            return false;
        }

        // A drafter is a player with a private channel that isn't a dummy/NPC, in GM-defined table
        // order. They have NOT been set up yet (no faction/color), so getRealPlayers() would exclude
        // them. The GM is NOT excluded: a GM can also be a player (they'll have a private channel),
        // in which case they draft too.
        FowMiltyDraftState state = new FowMiltyDraftState();
        for (Player p : game.getPlayers().values()) {
            if (p == null || p.isDummy() || p.isNpc()) continue;
            String privateChannelId = p.getPrivateChannelID();
            if (privateChannelId == null || privateChannelId.isBlank()) continue;
            state.getTableOrder().add(p.getUserID());
        }
        if (state.getTableOrder().size() < 2) {
            MessageHelper.sendMessageToChannel(
                    event.getMessageChannel(),
                    "Need at least 2 players in the game to start a draft (found "
                            + state.getTableOrder().size() + "). Add players and set the table order first.");
            return false;
        }

        StrategyCardSetModel scSet = game.getStrategyCardSet();
        if (scSet != null) {
            for (StrategyCardModel sc : scSet.getStrategyCardModels()) {
                state.getScInitiatives().add(sc.getInitiative());
            }
        }
        Collections.sort(state.getScInitiatives());

        state.setPhase(FowMiltyDraftState.Phase.SETUP);
        state.save(game);
        repostSetupControls(game, state);
        return true;
    }

    public static void repostSetupControls(Game game, FowMiltyDraftState state) {
        StringBuilder sb = new StringBuilder("## FoW Milty Draft — GM Setup\n");
        sb.append("Players (table order): ")
                .append(state.getTableOrder().size())
                .append("\n");
        sb.append("**First bag:** ");
        if (state.getFirstBag() == FowMiltyDraftState.FirstBag.ORDER) {
            sb.append("Draft Order — range [")
                    .append(state.getOrderNx())
                    .append(", ")
                    .append(state.getOrderNy())
                    .append("], closest to ")
                    .append(state.getOrderNx())
                    .append(" is speaker. (Strategy phase runs normally.)");
        } else {
            sb.append("Strategy Cards ").append(state.getScInitiatives()).append(". (First strategy phase skipped.)");
        }
        sb.append("\n");
        sb.append("**Value bag:** ")
                .append(
                        state.getValues().isEmpty()
                                ? "not configured"
                                : (state.getValueMode() + ", "
                                        + state.getValues().size() + " values"))
                .append("\n");
        sb.append("**Faction bag:** ")
                .append(
                        state.getSubBags().isEmpty()
                                ? "not configured"
                                : (state.getSubBags().size() + " sub-bags"))
                .append("\n");

        List<net.dv8tion.jda.api.components.buttons.Button> buttons = new ArrayList<>();
        buttons.add(Buttons.gray(PREFIX + "setup_firstbag_sc", "First bag: Strategy Cards"));
        buttons.add(Buttons.gray(PREFIX + "setup_firstbag_order~MDL", "First bag: Draft Order"));
        buttons.add(Buttons.blue(PREFIX + "setup_value_auto", "Value: auto region values"));
        buttons.add(Buttons.blue(PREFIX + "setup_value_custom~MDL", "Value: custom list"));
        buttons.add(Buttons.green(PREFIX + "setup_faction_random~MDL", "Factions: random"));
        buttons.add(Buttons.green(PREFIX + "setup_faction_custom~MDL", "Factions: custom list"));
        if (firstBagConfigured(state)
                && !state.getValues().isEmpty()
                && !state.getSubBags().isEmpty()) {
            buttons.add(Buttons.red(PREFIX + "setup_start", "Start Draft"));
        }
        buttons.add(Buttons.red(PREFIX + "abort", "Abort draft"));
        MessageHelper.sendMessageToChannelWithButtons(GMService.getGMChannel(game), sb.toString(), buttons);
    }

    private static boolean firstBagConfigured(FowMiltyDraftState state) {
        if (state.getFirstBag() == FowMiltyDraftState.FirstBag.ORDER) {
            return state.getOrderNy() > state.getOrderNx();
        }
        return !state.getScInitiatives().isEmpty();
    }

    /** Switch the first bag back to Strategy Cards (default). */
    public static void configureStrategyCardBag(Game game, FowMiltyDraftState state) {
        state.setFirstBag(FowMiltyDraftState.FirstBag.STRATEGY_CARD);
        state.getTakenSCs().clear();
        state.getCurrentOrderOptions().clear();
        state.save(game);
    }

    /**
     * Switch the first bag to the obfuscated Draft-Order bag over the integer range [nx, ny].
     * Final turn order is by closeness to {@code nx}; the strategy phase is NOT skipped.
     */
    public static void configureOrderBag(Game game, FowMiltyDraftState state, int nx, int ny) {
        state.setFirstBag(FowMiltyDraftState.FirstBag.ORDER);
        state.setOrderNx(nx);
        state.setOrderNy(ny);
        // Spread picks apart so the final ordering is unambiguous; shrink if the range is tight.
        int players = Math.max(1, state.playerCount());
        int span = ny - nx;
        state.setOrderBuffer(Math.max(1, span / (4 * players)));
        state.getTakenSCs().clear();
        state.getCurrentOrderOptions().clear();
        state.save(game);
    }

    /** GM aborts the draft: discard all persisted state. Nothing is applied to the game. */
    public static void abort(Game game) {
        FowMiltyDraftState.clear(game);
        MessageHelper.sendMessageToChannel(
                GMService.getGMChannel(game), "FoW milty draft aborted. All draft state discarded.");
    }

    /** Home-system placeholder tile IDs that mark a seat/origin on a hand-built map. */
    private static final Set<String> HOME_PLACEHOLDER_IDS = Set.of("0g", "0gray");

    /**
     * Auto-calculate region values (raw resources + influence) directly from the hand-built map.
     * Each seat is a home-placeholder ("0g") tile; its region is the geometrically adjacent tiles.
     * No map template is required.
     */
    public static void configureAutoValues(Game game, FowMiltyDraftState state) {
        List<String> homePositions = new ArrayList<>();
        for (Tile tile : game.getTileMap().values()) {
            if (tile == null || tile.getTileID() == null) continue;
            if (HOME_PLACEHOLDER_IDS.contains(tile.getTileID().toLowerCase())) {
                homePositions.add(tile.getPosition());
            }
        }
        if (homePositions.isEmpty()) {
            MessageHelper.sendMessageToChannel(
                    GMService.getGMChannel(game),
                    "No home-system placeholder (`0g`) tiles found on the map. Place a `0g` tile at each seat's"
                            + " home position, or use **Value: custom list** (with manual seating at the end).");
            return;
        }
        Collections.sort(homePositions);

        state.setValueMode(FowMiltyDraftState.ValueMode.AUTO_REGION);
        state.getValues().clear();
        state.getTakenValues().clear();

        char label = 'A';
        for (String homePos : homePositions) {
            int rawR = 0;
            int rawI = 0;
            for (String adjPos : PositionMapper.getAdjacentTilePositions(homePos)) {
                Tile tile = game.getTileByPosition(adjPos);
                if (tile == null) continue;
                for (Planet planet : tile.getPlanetUnitHolders()) {
                    rawR += planet.getResources();
                    rawI += planet.getInfluence();
                }
            }
            FowMiltyDraftState.ValueEntry entry = new FowMiltyDraftState.ValueEntry();
            entry.setId("region_" + homePos);
            entry.setLabel(label + " (" + rawR + "/" + rawI + ")");
            entry.setOriginPosition(homePos);
            entry.setRawR(rawR);
            entry.setRawI(rawI);
            state.getValues().add(entry);
            label++;
        }
        state.save(game);
    }

    /**
     * The faction sources enabled for this game, from the same "Expansions and Homebrew" picker
     * that normal milty uses ({@code SourceSettings.getFactionSources()}). Falls back to the
     * official set if the draft settings can't be initialized.
     */
    private static List<ComponentSource> enabledFactionSources(Game game) {
        List<ComponentSource> sources = new ArrayList<>(List.of(
                ComponentSource.base,
                ComponentSource.pok,
                ComponentSource.codex1,
                ComponentSource.codex2,
                ComponentSource.codex3,
                ComponentSource.codex4));
        try {
            sources = new ArrayList<>(
                    game.initializeDraftSystemSettings().getSourceSettings().getFactionSources());
        } catch (Exception ignored) {
            // keep the official fallback set
        }
        // Always honor the game's active homebrew modes even if the picker wasn't opened.
        if (game.isDiscordantStarsMode() && !sources.contains(ComponentSource.ds)) sources.add(ComponentSource.ds);
        if (game.isBlueReverieMode() && !sources.contains(ComponentSource.blue_reverie))
            sources.add(ComponentSource.blue_reverie);
        if (game.isTwilightsFallMode() && !sources.contains(ComponentSource.twilights_fall))
            sources.add(ComponentSource.twilights_fall);
        return sources;
    }

    /**
     * Whether a faction belongs in the random pool: its source is enabled for this game (same
     * picker as normal milty) and it isn't neutral/obsidian or a duplicate Keleres flavor.
     */
    private static boolean isEligibleFaction(FactionModel f, List<ComponentSource> sources) {
        if (f == null || f.getAlias() == null || f.getSource() == null) return false;
        String alias = f.getAlias().toLowerCase();
        if (alias.contains("neutral") || alias.contains("obsidian")) return false;
        if (alias.contains("keleres") && !"keleresm".equals(alias)) return false; // one flavor only
        return sources.contains(f.getSource());
    }

    /** Resolve a token (faction alias or name, case-insensitive) to its alias, or null if unknown. */
    private static String resolveFactionAlias(String token) {
        String t = token.trim();
        if (t.isEmpty()) return null;
        FactionModel byAlias = Mapper.getFaction(t.toLowerCase());
        if (byAlias != null) return byAlias.getAlias();
        for (FactionModel f : Mapper.getFactionsValues()) {
            if (t.equalsIgnoreCase(f.getFactionName()) || t.equalsIgnoreCase(f.getAlias())) {
                return f.getAlias();
            }
        }
        return null;
    }

    /** GM custom values from a modal. One entry per comma-separated token; no origin position. */
    public static void configureCustomValues(Game game, FowMiltyDraftState state, String rawList) {
        state.setValueMode(FowMiltyDraftState.ValueMode.CUSTOM);
        state.getValues().clear();
        state.getTakenValues().clear();
        int i = 0;
        for (String token : rawList.split(",")) {
            String name = token.trim();
            if (name.isEmpty()) continue;
            FowMiltyDraftState.ValueEntry entry = new FowMiltyDraftState.ValueEntry();
            entry.setId("custom" + i++);
            entry.setLabel(name);
            entry.setOriginPosition(null);
            state.getValues().add(entry);
        }
        state.save(game);
    }

    /**
     * Build one globally-unique random faction sub-bag per player, drawing only from factions
     * eligible for this game's expansions/homebrew, minus any GM-banned factions.
     */
    public static void configureRandomFactions(
            Game game, FowMiltyDraftState state, int factionsPerSubBag, String bannedRaw) {
        // Resolve the banned list (alias or name); report any tokens we couldn't match.
        Set<String> banned = new java.util.HashSet<>();
        List<String> unknownBans = new ArrayList<>();
        for (String token : bannedRaw == null ? new String[0] : bannedRaw.split(",")) {
            if (token.trim().isEmpty()) continue;
            String alias = resolveFactionAlias(token);
            if (alias == null) unknownBans.add(token.trim());
            else banned.add(alias);
        }
        if (!unknownBans.isEmpty()) {
            MessageHelper.sendMessageToChannel(
                    GMService.getGMChannel(game), "Ignored unknown banned factions: " + unknownBans);
        }

        List<ComponentSource> sources = enabledFactionSources(game);
        List<String> pool = new ArrayList<>();
        for (FactionModel f : Mapper.getFactionsValues()) {
            if (!isEligibleFaction(f, sources)) continue;
            if (banned.contains(f.getAlias())) continue;
            pool.add(f.getAlias());
        }
        Collections.shuffle(pool);

        int players = state.playerCount();
        int perBag = Math.max(1, factionsPerSubBag);
        if (pool.size() < players * perBag) {
            MessageHelper.sendMessageToChannel(
                    GMService.getGMChannel(game),
                    "Only " + pool.size() + " eligible factions for " + players + " sub-bags of " + perBag
                            + ". Sub-bags will be smaller than requested.");
        }
        state.getSubBags().clear();
        int idx = 0;
        for (int b = 0; b < players; b++) {
            List<String> bag = new ArrayList<>();
            while (bag.size() < perBag && idx < pool.size()) {
                bag.add(pool.get(idx++));
            }
            state.getSubBags().add(bag);
        }
        state.save(game);
    }

    /**
     * GM custom faction bags: {@code ;}-separated sub-bags, each {@code ,}-separated factions
     * (alias or name). Unknown tokens are reported to the GM rather than silently dropped.
     */
    public static void configureCustomFactions(Game game, FowMiltyDraftState state, String raw) {
        state.getSubBags().clear();
        Set<String> seen = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String bagStr : raw.split(";")) {
            List<String> bag = new ArrayList<>();
            for (String token : bagStr.split(",")) {
                if (token.trim().isEmpty()) continue;
                String alias = resolveFactionAlias(token);
                if (alias == null) {
                    unknown.add(token.trim());
                    continue;
                }
                if (seen.contains(alias)) continue; // never doubles across the whole set
                seen.add(alias);
                bag.add(alias);
            }
            if (!bag.isEmpty()) state.getSubBags().add(bag);
        }
        if (!unknown.isEmpty()) {
            MessageHelper.sendMessageToChannel(
                    GMService.getGMChannel(game),
                    "These entries didn't match any faction and were skipped: " + unknown
                            + ". Use the faction alias or exact name.");
        }
        state.save(game);
    }

    // ------------------------------------------------------------------
    // Drafting
    // ------------------------------------------------------------------

    public static void startDrafting(Game game, FowMiltyDraftState state) {
        int players = state.playerCount();
        if (state.getValues().size() < players) {
            MessageHelper.sendMessageToChannel(
                    GMService.getGMChannel(game),
                    "Need at least one value per player (" + players + "). Currently "
                            + state.getValues().size() + ".");
            return;
        }
        if (state.getSubBags().size() < players) {
            MessageHelper.sendMessageToChannel(
                    GMService.getGMChannel(game),
                    "Need one faction sub-bag per player (" + players + "). Currently "
                            + state.getSubBags().size() + ".");
            return;
        }
        state.setPhase(FowMiltyDraftState.Phase.DRAFTING);
        state.setRound(1);
        assignSubBagsForRound(state);
        generateOrderOptions(state);
        state.save(game);
        MessageHelper.sendMessageToChannel(
                GMService.getGMChannel(game), "FoW milty draft started. Round 1 prompts sent to players.");
        postRoundPrompts(game, state);
    }

    /**
     * In ORDER mode, draw a fresh shared set of {@code #players} distinct numbers in [Nx, Ny],
     * excluding any number within {@code orderBuffer} of an already-taken number (the "pull").
     * Falls back to exact-only exclusion if the buffered pool is too small to fill the set.
     */
    private static void generateOrderOptions(FowMiltyDraftState state) {
        state.getCurrentOrderOptions().clear();
        if (state.getFirstBag() != FowMiltyDraftState.FirstBag.ORDER) return;

        int nx = state.getOrderNx();
        int ny = state.getOrderNy();
        int need = state.playerCount();
        Set<Integer> taken = state.getTakenSCs();

        List<Integer> picked = drawDistinct(nx, ny, need, taken, state.getOrderBuffer());
        if (picked.size() < need) {
            // Range too tight for the buffer; retry excluding only exact taken numbers.
            picked = drawDistinct(nx, ny, need, taken, 0);
        }
        Collections.sort(picked);
        state.getCurrentOrderOptions().addAll(picked);
    }

    private static List<Integer> drawDistinct(int nx, int ny, int need, Set<Integer> taken, int buffer) {
        List<Integer> chosen = new ArrayList<>();
        java.util.Random rng = new java.util.Random();
        long span = (long) ny - nx + 1;
        int attempts = 0;
        int maxAttempts = (int) Math.min(2_000_000L, span * 20L) + 1000;
        while (chosen.size() < need && attempts++ < maxAttempts) {
            int candidate = nx + (int) (rng.nextDouble() * (ny - nx + 1));
            if (candidate > ny) candidate = ny;
            if (chosen.contains(candidate)) continue;
            boolean tooClose = false;
            for (Integer c : chosen) {
                if (Math.abs((long) c - candidate) <= buffer) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) continue;
            for (Integer t : taken) {
                if (Math.abs((long) t - candidate) <= buffer) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) continue;
            chosen.add(candidate);
        }
        return chosen;
    }

    /** Assign each player who still needs a faction a sub-bag they have not seen and that isn't consumed. */
    private static void assignSubBagsForRound(FowMiltyDraftState state) {
        state.getCurrentSubBag().clear();
        Set<Integer> usedThisRound = new java.util.HashSet<>();
        for (String userId : state.getTableOrder()) {
            if (state.hasChosen(userId, FowMiltyDraftState.BagType.FACTION)) continue;
            List<Integer> seen = state.getSeenSubBags().computeIfAbsent(userId, k -> new ArrayList<>());
            Integer chosen = null;
            // Prefer an unconsumed, unseen, not-yet-used-this-round bag.
            for (int i = 0; i < state.getSubBags().size(); i++) {
                if (state.getConsumedSubBags().contains(i)) continue;
                if (usedThisRound.contains(i)) continue;
                if (seen.contains(i)) continue;
                chosen = i;
                break;
            }
            // Fallback: relax the "unseen" constraint.
            if (chosen == null) {
                for (int i = 0; i < state.getSubBags().size(); i++) {
                    if (state.getConsumedSubBags().contains(i)) continue;
                    if (usedThisRound.contains(i)) continue;
                    chosen = i;
                    break;
                }
            }
            if (chosen != null) {
                state.getCurrentSubBag().put(userId, chosen);
                usedThisRound.add(chosen);
                if (!seen.contains(chosen)) seen.add(chosen);
            }
        }
    }

    private static void postRoundPrompts(Game game, FowMiltyDraftState state) {
        state.getSubmissions().clear();
        for (String userId : state.getTableOrder()) {
            Player player = game.getPlayer(userId);
            if (player == null) continue;
            postBagChoice(game, state, player);
        }
        state.save(game);
    }

    private static void postBagChoice(Game game, FowMiltyDraftState state, Player player) {
        String userId = player.getUserID();
        List<net.dv8tion.jda.api.components.buttons.Button> buttons = new ArrayList<>();

        StringBuilder sb = new StringBuilder("**FoW Milty — Round " + state.getRound() + "**\n");
        sb.append("Choose which bag to draft from this round. Bag contents (not blind):\n");

        if (!state.hasChosen(userId, FowMiltyDraftState.BagType.SC)) {
            boolean order = state.getFirstBag() == FowMiltyDraftState.FirstBag.ORDER;
            buttons.add(Buttons.blue(PREFIX + "pick_SC", order ? "Draft Order bag" : "Strategy Card bag"));
            if (order) {
                sb.append("\n__Draft Order bag__ (rank numbers; closest to ")
                        .append(state.getOrderNx())
                        .append(" ends up earliest): ")
                        .append(describeOrderBag(state))
                        .append("\n");
            } else {
                sb.append("\n__Strategy Card bag__: ")
                        .append(describeScBag(game, state))
                        .append("\n");
            }
        }
        if (!state.hasChosen(userId, FowMiltyDraftState.BagType.VALUE)) {
            buttons.add(Buttons.green(PREFIX + "pick_VALUE", "Value bag"));
            sb.append("\n__Value bag__: ").append(describeValueBag(state)).append("\n");
        }
        if (!state.hasChosen(userId, FowMiltyDraftState.BagType.FACTION)
                && state.getCurrentSubBag().containsKey(userId)) {
            buttons.add(Buttons.gray(PREFIX + "pick_FACTION", "Faction bag"));
            sb.append("\n__Faction bag__ (your sub-bag): ")
                    .append(describeFactionSubBag(state, userId))
                    .append("\n");
        }
        MessageHelper.sendMessageToChannelWithButtons(player.getCorrectChannel(), sb.toString(), buttons);
    }

    /** Available Strategy Cards (initiatives already drafted in prior rounds are hidden). */
    private static String describeScBag(Game game, FowMiltyDraftState state) {
        StrategyCardSetModel scSet = game.getStrategyCardSet();
        List<String> parts = new ArrayList<>();
        for (Integer sc : state.getScInitiatives()) {
            if (state.getTakenSCs().contains(sc)) continue;
            String name = scSet == null ? null : scSet.getSCName(sc);
            parts.add(name == null || name.isEmpty() ? ("SC " + sc) : ("(" + sc + ") " + name));
        }
        return parts.isEmpty() ? "_none left_" : String.join(", ", parts);
    }

    /** This round's offered numbers (a fresh random sample of the range). */
    private static String describeOrderBag(FowMiltyDraftState state) {
        List<String> parts = new ArrayList<>();
        for (Integer n : state.getCurrentOrderOptions()) {
            if (state.getTakenSCs().contains(n)) continue;
            parts.add("#" + n);
        }
        return parts.isEmpty() ? "_none left_" : String.join(", ", parts);
    }

    /** Available values (entries drafted in prior rounds are hidden). */
    private static String describeValueBag(FowMiltyDraftState state) {
        List<String> parts = new ArrayList<>();
        for (FowMiltyDraftState.ValueEntry v : state.getValues()) {
            if (state.getTakenValues().contains(v.getId())) continue;
            parts.add(v.getLabel());
        }
        return parts.isEmpty() ? "_none left_" : String.join(", ", parts);
    }

    /** Factions in the sub-bag currently offered to this player. */
    private static String describeFactionSubBag(FowMiltyDraftState state, String userId) {
        Integer bagIndex = state.getCurrentSubBag().get(userId);
        if (bagIndex == null) return "_none_";
        List<String> parts = new ArrayList<>();
        for (String alias : state.getSubBags().get(bagIndex)) {
            FactionModel fm = Mapper.getFaction(alias);
            parts.add(fm == null ? alias : fm.getFactionName());
        }
        return parts.isEmpty() ? "_none_" : String.join(", ", parts);
    }

    /** A player chose a bag type this round. Begins the appropriate sub-flow. */
    public static void choosePick(Game game, FowMiltyDraftState state, Player player, FowMiltyDraftState.BagType type) {
        String userId = player.getUserID();
        if (state.hasChosen(userId, type)) {
            MessageHelper.sendMessageToChannel(
                    player.getCorrectChannel(), "You already used that bag in a previous round.");
            return;
        }
        FowMiltyDraftState.RoundSubmission sub = new FowMiltyDraftState.RoundSubmission();
        sub.setBagType(type.name());
        state.getSubmissions().put(userId, sub);
        state.save(game);

        if (type == FowMiltyDraftState.BagType.FACTION) {
            postFactionChoice(game, state, player);
        } else {
            postRankPrompt(game, state, player);
        }
    }

    private static List<String> availableOptions(FowMiltyDraftState state, FowMiltyDraftState.BagType type) {
        List<String> options = new ArrayList<>();
        if (type == FowMiltyDraftState.BagType.SC) {
            // First bag: SC initiatives (STRATEGY_CARD) or this round's number set (ORDER).
            List<Integer> pool = state.getFirstBag() == FowMiltyDraftState.FirstBag.ORDER
                    ? state.getCurrentOrderOptions()
                    : state.getScInitiatives();
            for (Integer n : pool) {
                if (!state.getTakenSCs().contains(n)) options.add(String.valueOf(n));
            }
        } else {
            for (FowMiltyDraftState.ValueEntry v : state.getValues()) {
                if (!state.getTakenValues().contains(v.getId())) options.add(v.getId());
            }
        }
        return options;
    }

    private static String firstOrValueBagName(FowMiltyDraftState state, FowMiltyDraftState.BagType type) {
        if (type == FowMiltyDraftState.BagType.VALUE) return "Value";
        return state.getFirstBag() == FowMiltyDraftState.FirstBag.ORDER ? "Draft Order" : "Strategy Card";
    }

    private static String optionLabel(FowMiltyDraftState state, FowMiltyDraftState.BagType type, String optionId) {
        if (type == FowMiltyDraftState.BagType.SC) {
            return state.getFirstBag() == FowMiltyDraftState.FirstBag.ORDER ? ("#" + optionId) : ("SC " + optionId);
        }
        FowMiltyDraftState.ValueEntry v = state.valueById(optionId);
        return v == null ? optionId : v.getLabel();
    }

    private static void postRankPrompt(Game game, FowMiltyDraftState state, Player player) {
        FowMiltyDraftState.RoundSubmission sub = state.getSubmissions().get(player.getUserID());
        FowMiltyDraftState.BagType type = FowMiltyDraftState.BagType.valueOf(sub.getBagType());
        List<String> available = availableOptions(state, type);
        int rankNumber = sub.getRanking().size() + 1;

        List<net.dv8tion.jda.api.components.buttons.Button> buttons = new ArrayList<>();
        for (String optionId : available) {
            if (sub.getRanking().contains(optionId)) continue;
            buttons.add(
                    Buttons.blue(PREFIX + "rank_" + type.name() + "_" + optionId, optionLabel(state, type, optionId)));
        }
        if (!sub.getRanking().isEmpty()) {
            buttons.add(Buttons.red(PREFIX + "ranklock_" + type.name(), "Lock in ranking"));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Rank your choices for the **")
                .append(firstOrValueBagName(state, type))
                .append(" bag**.\n");
        if (!sub.getRanking().isEmpty()) {
            List<String> labels = new ArrayList<>();
            for (String r : sub.getRanking()) labels.add(optionLabel(state, type, r));
            sb.append("So far: ").append(labels).append("\n");
        }
        sb.append("Pick your #").append(rankNumber).append(" choice:");
        MessageHelper.sendMessageToChannelWithButtons(player.getCorrectChannel(), sb.toString(), buttons);
    }

    /** Player clicked a rank option. */
    public static void addRankChoice(
            Game game, FowMiltyDraftState state, Player player, FowMiltyDraftState.BagType type, String optionId) {
        FowMiltyDraftState.RoundSubmission sub = state.getSubmissions().get(player.getUserID());
        if (sub == null || sub.isSubmitted()) return;
        if (!sub.getRanking().contains(optionId)) sub.getRanking().add(optionId);

        List<String> available = availableOptions(state, type);
        int maxDepth = Math.min(available.size(), state.playerCount());
        if (sub.getRanking().size() >= maxDepth) {
            lockRanking(game, state, player);
        } else {
            state.save(game);
            postRankPrompt(game, state, player);
        }
    }

    /** Player locked in an early/complete ranking. */
    public static void lockRanking(Game game, FowMiltyDraftState state, Player player) {
        FowMiltyDraftState.RoundSubmission sub = state.getSubmissions().get(player.getUserID());
        if (sub == null || sub.isSubmitted()) return;
        sub.setSubmitted(true);
        state.save(game);
        MessageHelper.sendMessageToChannel(player.getCorrectChannel(), "Your ranking is locked in for this round.");
        checkRoundComplete(game, state);
    }

    private static void postFactionChoice(Game game, FowMiltyDraftState state, Player player) {
        Integer bagIndex = state.getCurrentSubBag().get(player.getUserID());
        if (bagIndex == null) {
            MessageHelper.sendMessageToChannel(
                    player.getCorrectChannel(), "No faction sub-bag available to you this round.");
            return;
        }
        List<net.dv8tion.jda.api.components.buttons.Button> buttons = new ArrayList<>();
        for (String alias : state.getSubBags().get(bagIndex)) {
            FactionModel fm = Mapper.getFaction(alias);
            String name = fm == null ? alias : fm.getFactionName();
            buttons.add(Buttons.green(PREFIX + "faction_" + alias, name));
        }
        MessageHelper.sendMessageToChannelWithButtons(
                player.getCorrectChannel(), "Pick a faction from your sub-bag:", buttons);
    }

    public static void chooseFaction(Game game, FowMiltyDraftState state, Player player, String alias) {
        FowMiltyDraftState.RoundSubmission sub = state.getSubmissions().get(player.getUserID());
        if (sub == null || sub.isSubmitted()) return;
        Integer bagIndex = state.getCurrentSubBag().get(player.getUserID());
        if (bagIndex == null || !state.getSubBags().get(bagIndex).contains(alias)) return;
        sub.setFaction(alias);
        sub.setSubmitted(true);
        state.save(game);
        FactionModel fm = Mapper.getFaction(alias);
        MessageHelper.sendMessageToChannel(
                player.getCorrectChannel(),
                "You selected **" + (fm == null ? alias : fm.getFactionName()) + "** for this round.");
        checkRoundComplete(game, state);
    }

    private static void checkRoundComplete(Game game, FowMiltyDraftState state) {
        int submitted = 0;
        for (String userId : state.getTableOrder()) {
            FowMiltyDraftState.RoundSubmission sub = state.getSubmissions().get(userId);
            if (sub != null && sub.isSubmitted()) submitted++;
        }
        if (submitted >= state.playerCount()) {
            resolveRound(game, state);
        }
    }

    // ------------------------------------------------------------------
    // Resolution of a round
    // ------------------------------------------------------------------

    private static void resolveRound(Game game, FowMiltyDraftState state) {
        for (String userId : state.resolutionOrder()) {
            FowMiltyDraftState.RoundSubmission sub = state.getSubmissions().get(userId);
            if (sub == null) continue;
            FowMiltyDraftState.BagType type = FowMiltyDraftState.BagType.valueOf(sub.getBagType());
            FowMiltyDraftState.PlayerResult result =
                    state.getResults().computeIfAbsent(userId, k -> new FowMiltyDraftState.PlayerResult());
            Player player = game.getPlayer(userId);

            switch (type) {
                case SC -> {
                    boolean order = state.getFirstBag() == FowMiltyDraftState.FirstBag.ORDER;
                    List<Integer> pool = order ? state.getCurrentOrderOptions() : state.getScInitiatives();
                    Integer picked = null;
                    for (String r : sub.getRanking()) {
                        int n = Integer.parseInt(r);
                        if (!state.getTakenSCs().contains(n)) {
                            picked = n;
                            break;
                        }
                    }
                    if (picked == null) { // fallback: first available in the pool
                        for (Integer n : pool) {
                            if (!state.getTakenSCs().contains(n)) {
                                picked = n;
                                break;
                            }
                        }
                    }
                    if (picked != null) {
                        state.getTakenSCs().add(picked);
                        if (order) {
                            result.setOrderNumber(picked);
                            if (player != null)
                                MessageHelper.sendMessageToChannel(
                                        player.getCorrectChannel(),
                                        "Resolved: you drafted order number **#" + picked + "**.");
                        } else {
                            result.setScInit(picked);
                            if (player != null)
                                MessageHelper.sendMessageToChannel(
                                        player.getCorrectChannel(),
                                        "Resolved: you drafted **Strategy Card " + picked + "**.");
                        }
                    }
                }
                case VALUE -> {
                    String picked = null;
                    for (String r : sub.getRanking()) {
                        if (!state.getTakenValues().contains(r)) {
                            picked = r;
                            break;
                        }
                    }
                    if (picked == null) {
                        for (FowMiltyDraftState.ValueEntry v : state.getValues()) {
                            if (!state.getTakenValues().contains(v.getId())) {
                                picked = v.getId();
                                break;
                            }
                        }
                    }
                    if (picked != null) {
                        state.getTakenValues().add(picked);
                        FowMiltyDraftState.ValueEntry v = state.valueById(picked);
                        result.setValueId(picked);
                        result.setOriginPosition(v == null ? null : v.getOriginPosition());
                        if (player != null)
                            MessageHelper.sendMessageToChannel(
                                    player.getCorrectChannel(),
                                    "Resolved: you drafted value **" + (v == null ? picked : v.getLabel()) + "**.");
                    }
                }
                case FACTION -> {
                    result.setFaction(sub.getFaction());
                    Integer bagIndex = state.getCurrentSubBag().get(userId);
                    if (bagIndex != null) state.getConsumedSubBags().add(bagIndex);
                    if (player != null) {
                        FactionModel fm = Mapper.getFaction(sub.getFaction());
                        MessageHelper.sendMessageToChannel(
                                player.getCorrectChannel(),
                                "Resolved: you drafted **" + (fm == null ? sub.getFaction() : fm.getFactionName())
                                        + "**.");
                    }
                }
            }
            state.markChosen(userId, type);
        }

        if (state.getRound() >= 3) {
            state.setPhase(FowMiltyDraftState.Phase.RESOLVE);
            state.save(game);
            postResolutionToGM(game, state);
        } else {
            state.setRound(state.getRound() + 1);
            assignSubBagsForRound(state);
            generateOrderOptions(state);
            state.save(game);
            MessageHelper.sendMessageToChannel(
                    GMService.getGMChannel(game),
                    "Round " + (state.getRound() - 1) + " resolved. Starting round " + state.getRound() + ".");
            postRoundPrompts(game, state);
        }
    }

    // ------------------------------------------------------------------
    // Final resolution (GM chooses manual vs automatic)
    // ------------------------------------------------------------------

    private static boolean canAutoAssign(FowMiltyDraftState state) {
        if (state.getValueMode() != FowMiltyDraftState.ValueMode.AUTO_REGION) return false;
        return state.getValues().size() == state.playerCount();
    }

    private static void postResolutionToGM(Game game, FowMiltyDraftState state) {
        StringBuilder sb = new StringBuilder("## FoW Milty Draft — Complete\n");
        for (String userId : state.getTableOrder()) {
            Player p = game.getPlayer(userId);
            FowMiltyDraftState.PlayerResult r = state.getResults().get(userId);
            if (r == null) continue;
            FactionModel fm = r.getFaction() == null ? null : Mapper.getFaction(r.getFaction());
            FowMiltyDraftState.ValueEntry v = r.getValueId() == null ? null : state.valueById(r.getValueId());
            sb.append("- ")
                    .append(p == null ? userId : p.getUserName())
                    .append(": faction=")
                    .append(fm == null ? r.getFaction() : fm.getFactionName())
                    .append(", value=")
                    .append(v == null ? r.getValueId() : v.getLabel());
            if (state.getFirstBag() == FowMiltyDraftState.FirstBag.ORDER) {
                sb.append(", order#=").append(r.getOrderNumber());
            } else {
                sb.append(", SC=").append(r.getScInit());
            }
            sb.append("\n");
        }
        if (state.getFirstBag() == FowMiltyDraftState.FirstBag.ORDER) {
            sb.append("\nFinal turn order (closest to ")
                    .append(state.getOrderNx())
                    .append(" first):\n");
            int pos = 1;
            for (String userId : orderedByProximity(state)) {
                Player p = game.getPlayer(userId);
                FowMiltyDraftState.PlayerResult r = state.getResults().get(userId);
                sb.append(pos++)
                        .append(". ")
                        .append(p == null ? userId : p.getUserName())
                        .append(" (#")
                        .append(r == null ? "?" : r.getOrderNumber())
                        .append(")")
                        .append(pos == 2 ? " — speaker" : "")
                        .append("\n");
            }
        }
        List<net.dv8tion.jda.api.components.buttons.Button> buttons = new ArrayList<>();
        if (canAutoAssign(state)) {
            buttons.add(Buttons.green(PREFIX + "resolve_auto", "Automatic assignment"));
        } else {
            sb.append(
                    "\n_Automatic assignment unavailable (custom values or value count ≠ player count). Use manual seating._");
        }
        buttons.add(Buttons.blue(PREFIX + "resolve_manual", "Manual (finish without seating)"));
        MessageHelper.sendMessageToChannelWithButtons(GMService.getGMChannel(game), sb.toString(), buttons);
    }

    /** Seat every player automatically at their drafted region's origin, assign faction/color/SC, skip strategy phase. */
    public static void finalizeAuto(Game game, FowMiltyDraftState state, GenericInteractionCreateEvent event) {
        if (!canAutoAssign(state)) {
            MessageHelper.sendMessageToChannel(
                    GMService.getGMChannel(game),
                    "Automatic assignment is not possible for this draft. Use manual seating.");
            return;
        }
        String speakerId =
                state.getTableOrder().isEmpty() ? null : state.getTableOrder().get(0);
        for (String userId : state.getTableOrder()) {
            Player player = game.getPlayer(userId);
            FowMiltyDraftState.PlayerResult r = state.getResults().get(userId);
            if (player == null || r == null || r.getOriginPosition() == null || r.getFaction() == null) continue;
            boolean speaker = userId.equals(speakerId);
            PlayerSetupState setup = new PlayerSetupState(r.getFaction(), r.getOriginPosition(), speaker);
            PlayerSetupService.setupPlayer(setup, player, game, event);
        }
        finishDraft(game, state, event);
    }

    /** Finish the draft, apply the first bag, but leave seating to the GM. */
    public static void finalizeManual(Game game, FowMiltyDraftState state, GenericInteractionCreateEvent event) {
        String firstBagNote = state.getFirstBag() == FowMiltyDraftState.FirstBag.ORDER
                ? "The drafted turn order will still be applied."
                : "Their drafted Strategy Cards will still be applied.";
        MessageHelper.sendMessageToChannel(
                GMService.getGMChannel(game),
                "Manual mode: assign each player's home position/faction/color yourself. " + firstBagNote);
        finishDraft(game, state, event);
    }

    /** userIds sorted by closeness of their drafted number to Nx (closest first), tie-broken deterministically. */
    private static List<String> orderedByProximity(FowMiltyDraftState state) {
        List<String> ids = new ArrayList<>(state.getTableOrder());
        int nx = state.getOrderNx();
        ids.sort((a, b) -> {
            FowMiltyDraftState.PlayerResult ra = state.getResults().get(a);
            FowMiltyDraftState.PlayerResult rb = state.getResults().get(b);
            long da = ra == null || ra.getOrderNumber() == null
                    ? Long.MAX_VALUE
                    : Math.abs((long) ra.getOrderNumber() - nx);
            long db = rb == null || rb.getOrderNumber() == null
                    ? Long.MAX_VALUE
                    : Math.abs((long) rb.getOrderNumber() - nx);
            if (da != db) return Long.compare(da, db);
            long na = ra == null || ra.getOrderNumber() == null ? Long.MAX_VALUE : ra.getOrderNumber();
            long nb = rb == null || rb.getOrderNumber() == null ? Long.MAX_VALUE : rb.getOrderNumber();
            if (na != nb) return Long.compare(na, nb);
            return a.compareTo(b);
        });
        return ids;
    }

    private static void finishDraft(Game game, FowMiltyDraftState state, GenericInteractionCreateEvent event) {
        boolean orderMode = state.getFirstBag() == FowMiltyDraftState.FirstBag.ORDER;

        if (orderMode) {
            // Re-order the table by closeness to Nx; speaker = closest. (Mirrors SetOrderService.)
            List<String> newOrder = orderedByProximity(state);
            java.util.Map<String, Player> reordered = new java.util.LinkedHashMap<>();
            for (String userId : newOrder) {
                Player p = game.getPlayer(userId);
                if (p != null) reordered.put(userId, p);
            }
            // Preserve any players not in the draft order (defensive).
            for (java.util.Map.Entry<String, Player> e : game.getPlayers().entrySet()) {
                reordered.putIfAbsent(e.getKey(), e.getValue());
            }
            game.setPlayers(reordered);
            Player speaker = newOrder.isEmpty() ? null : game.getPlayer(newOrder.get(0));
            if (speaker != null) game.setSpeaker(speaker);
        } else {
            // Pre-assign drafted Strategy Cards so the first strategy phase can be skipped.
            for (String userId : state.getTableOrder()) {
                Player player = game.getPlayer(userId);
                FowMiltyDraftState.PlayerResult r = state.getResults().get(userId);
                if (player == null || r == null || r.getScInit() == null) continue;
                player.addSC(r.getScInit());
            }
        }

        AddTileListService.finishSetup(game, event);
        // Draft is complete: drop the persisted state entirely so it doesn't ride along in every
        // future save. Any late/stale button click will load null and the handlers no-op.
        FowMiltyDraftState.clear(game);

        if (orderMode) {
            MessageHelper.sendMessageToChannel(
                    GMService.getGMChannel(game),
                    "FoW milty draft finished. Table re-ordered by the draft; starting the strategy phase normally.");
            try {
                StartPhaseService.startStrategyPhase(event, game);
            } catch (Exception e) {
                MessageHelper.sendMessageToChannel(
                        GMService.getGMChannel(game),
                        "Could not auto-start the strategy phase; start it manually. (" + e.getMessage() + ")");
            }
        } else {
            MessageHelper.sendMessageToChannel(
                    GMService.getGMChannel(game),
                    "FoW milty draft finished. Strategy Cards assigned; skipping the first strategy phase and starting the action phase.");
            try {
                StartPhaseService.startActionPhase(event, game);
            } catch (Exception e) {
                MessageHelper.sendMessageToChannel(
                        GMService.getGMChannel(game),
                        "Could not auto-start the action phase; start it manually. (" + e.getMessage() + ")");
            }
        }
    }
}

package ti4.service.fow.milty;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;
import lombok.NoArgsConstructor;
import ti4.game.Game;
import ti4.logging.BotLogger;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializable state for the Fog-of-War specific milty draft ({@code /fow milty}).
 *
 * <p>Everything about this draft is stored inside a single {@link Game} stored value
 * ({@link #STORED_KEY}) as JSON, so it persists with the game and requires no changes to
 * save/load plumbing. The draft is only ever used in FoW games.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FowMiltyDraftState {

    public static final String STORED_KEY = "fowMiltyDraft";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Phase {
        SETUP,
        DRAFTING,
        RESOLVE,
        DONE
    }

    public enum BagType {
        SC,
        VALUE,
        FACTION
    }

    public enum ValueMode {
        AUTO_REGION,
        CUSTOM
    }

    /**
     * What the "first bag" is. In {@link FirstBag#ORDER} mode the {@link BagType#SC} slot is
     * repurposed as an obfuscated draft-order bag (players rank numbers; final turn order is by
     * closeness to {@link #orderNx}). This keeps the 3-bags/3-rounds structure and lets the
     * order bag reuse all of the SC-bag ranking/priority machinery.
     */
    public enum FirstBag {
        STRATEGY_CARD,
        ORDER
    }

    private Phase phase = Phase.SETUP;

    private FirstBag firstBag = FirstBag.STRATEGY_CARD;

    /** 0 while in setup, 1..3 while drafting. */
    private int round = 0;

    /** User IDs in GM-defined table order. */
    private List<String> tableOrder = new ArrayList<>();

    // --- Strategy Card bag (STRATEGY_CARD mode) ---
    private List<Integer> scInitiatives = new ArrayList<>();
    /** Taken first-bag ints: SC initiatives in STRATEGY_CARD mode, drafted numbers in ORDER mode. */
    private Set<Integer> takenSCs = new HashSet<>();

    // --- Draft-Order bag (ORDER mode) ---
    private int orderNx;
    private int orderNy;
    /** Exclusion radius around already-taken numbers when generating a round's number set. */
    private int orderBuffer;
    /** This round's shared set of offered numbers (regenerated each round in ORDER mode). */
    private List<Integer> currentOrderOptions = new ArrayList<>();

    // --- Value bag ---
    private ValueMode valueMode = ValueMode.AUTO_REGION;
    private List<ValueEntry> values = new ArrayList<>();
    private Set<String> takenValues = new HashSet<>();

    // --- Faction bag ---
    private List<List<String>> subBags = new ArrayList<>();
    /** Sub-bag indices that have been consumed (a player picked a faction from them). */
    private Set<Integer> consumedSubBags = new HashSet<>();
    /** userId -> sub-bag index offered to that player in the current round. */
    private Map<String, Integer> currentSubBag = new HashMap<>();
    /** userId -> set of sub-bag indices already offered to them across rounds. */
    private Map<String, List<Integer>> seenSubBags = new HashMap<>();

    // --- Per-player draft progress ---
    /** userId -> bag types already chosen in previous rounds (cannot be re-chosen). */
    private Map<String, List<String>> chosenBagTypes = new HashMap<>();
    /** userId -> this round's submission. */
    private Map<String, RoundSubmission> submissions = new HashMap<>();

    // --- Final resolved picks ---
    private Map<String, PlayerResult> results = new HashMap<>();

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ValueEntry {
        private String id;
        private String label;
        /** Home/origin position for auto-region values; null for GM custom values. */
        private String originPosition;

        private int rawR;
        private int rawI;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RoundSubmission {
        private String bagType; // BagType name
        /** Ranking so far (option ids for SC/VALUE), in preference order. */
        private List<String> ranking = new ArrayList<>();
        /** Chosen faction alias (FACTION bag only). */
        private String faction;

        private boolean submitted;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlayerResult {
        private Integer scInit;
        private Integer orderNumber;
        private String valueId;
        private String originPosition;
        private String faction;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public static FowMiltyDraftState load(Game game) {
        String json = game.getStoredValue(STORED_KEY);
        if (json == null || json.isEmpty()) return null;
        try {
            return MAPPER.readValue(json, FowMiltyDraftState.class);
        } catch (Exception e) {
            BotLogger.error("Failed to load FoW milty draft state for game " + game.getName(), e);
            return null;
        }
    }

    public void save(Game game) {
        try {
            game.setStoredValue(STORED_KEY, MAPPER.writeValueAsString(this));
        } catch (Exception e) {
            BotLogger.error("Failed to save FoW milty draft state for game " + game.getName(), e);
        }
    }

    public static void clear(Game game) {
        game.removeStoredValue(STORED_KEY);
    }

    // ------------------------------------------------------------------
    // Convenience helpers
    // ------------------------------------------------------------------

    public boolean hasChosen(String userId, BagType type) {
        return chosenBagTypes.getOrDefault(userId, List.of()).contains(type.name());
    }

    public void markChosen(String userId, BagType type) {
        chosenBagTypes.computeIfAbsent(userId, k -> new ArrayList<>()).add(type.name());
    }

    public ValueEntry valueById(String id) {
        for (ValueEntry v : values) {
            if (v.getId().equals(id)) return v;
        }
        return null;
    }

    /** Number of seats = number of players in the draft. */
    public int playerCount() {
        return tableOrder.size();
    }

    /** Resolution order for the given round: table order forward on odd rounds, reversed on even. */
    public List<String> resolutionOrder() {
        List<String> order = new ArrayList<>(tableOrder);
        if (round % 2 == 0) {
            java.util.Collections.reverse(order);
        }
        return order;
    }
}

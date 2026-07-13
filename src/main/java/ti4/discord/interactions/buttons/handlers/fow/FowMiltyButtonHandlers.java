package ti4.discord.interactions.buttons.handlers.fow;

import lombok.experimental.UtilityClass;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.modals.Modal;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.function.Consumers;
import ti4.discord.interactions.routing.ButtonHandler;
import ti4.discord.interactions.routing.ModalHandler;
import ti4.game.Game;
import ti4.game.Player;
import ti4.helpers.ButtonHelper;
import ti4.logging.BotLogger;
import ti4.message.MessageHelper;
import ti4.service.fow.milty.FowMiltyDraftState;
import ti4.service.fow.milty.FowMiltyService;

/**
 * Button and modal handlers for the Fog-of-War specific milty draft.
 *
 * <p>Every handler is a no-op outside FoW mode. GM-only actions additionally require the
 * clicking player to hold the game's GM role. Handlers are auto-discovered by the annotation
 * router, so no manual registration is needed.
 */
@UtilityClass
public class FowMiltyButtonHandlers {

    private static final String PREFIX = "fowmilty_";
    private static final String CUSTOM_VALUE_MODAL = "fowmiltyValueCustom";
    private static final String RANDOM_FACTION_MODAL = "fowmiltyFactionRandom";
    private static final String CUSTOM_FACTION_MODAL = "fowmiltyFactionCustom";
    private static final String ORDER_RANGE_MODAL = "fowmiltyOrderRange";
    private static final String FIELD = "input";
    private static final String FIELD_BANNED = "banned";
    private static final String FIELD_NX = "nx";
    private static final String FIELD_NY = "ny";

    private static boolean guard(Game game, ButtonInteractionEvent event) {
        if (!game.isFowMode()) {
            MessageHelper.sendMessageToChannel(
                    event.getMessageChannel(), "FoW milty draft is only available in Fog of War games.");
            return false;
        }
        return true;
    }

    private static boolean gmGuard(Game game, Player player, ButtonInteractionEvent event) {
        if (!guard(game, event)) return false;
        if (!game.getPlayersWithGMRole().contains(player)) {
            MessageHelper.sendMessageToChannel(event.getMessageChannel(), "Only a GM can do that.");
            return false;
        }
        return true;
    }

    private static FowMiltyDraftState state(Game game) {
        return FowMiltyDraftState.load(game);
    }

    // ------------------------------------------------------------------
    // GM setup
    // ------------------------------------------------------------------

    @ButtonHandler(PREFIX + "setup_value_auto")
    public static void setupValueAuto(ButtonInteractionEvent event, Game game, Player player) {
        if (!gmGuard(game, player, event)) return;
        FowMiltyDraftState state = state(game);
        if (state == null) return;
        FowMiltyService.configureAutoValues(game, state);
        if (!state.getValues().isEmpty()) {
            MessageHelper.sendMessageToChannel(
                    event.getMessageChannel(),
                    "Auto region values computed: " + state.getValues().size() + " values.");
        }
        FowMiltyService.repostSetupControls(game, state);
    }

    @ButtonHandler(value = PREFIX + "setup_value_custom~MDL", save = false)
    public static void setupValueCustom(ButtonInteractionEvent event) {
        TextInput input = TextInput.create(FIELD, TextInputStyle.PARAGRAPH)
                .setPlaceholder("Comma-separated value names, e.g. Slice A, Slice B, Slice C")
                .setRequired(true)
                .build();
        Modal modal = Modal.create(CUSTOM_VALUE_MODAL, "Custom Value Bag")
                .addComponents(Label.of("Values (comma-separated)", input))
                .build();
        event.replyModal(modal).queue(Consumers.nop(), BotLogger::catchRestError);
    }

    @ModalHandler(CUSTOM_VALUE_MODAL)
    public static void resolveCustomValues(ModalInteractionEvent event, Game game) {
        FowMiltyDraftState state = state(game);
        if (state == null) return;
        FowMiltyService.configureCustomValues(game, state, event.getValue(FIELD).getAsString());
        MessageHelper.sendMessageToChannel(
                event.getMessageChannel(),
                "Custom values set: " + state.getValues().size() + ".");
        FowMiltyService.repostSetupControls(game, state);
    }

    @ButtonHandler(value = PREFIX + "setup_faction_random~MDL", save = false)
    public static void setupFactionRandom(ButtonInteractionEvent event) {
        TextInput input = TextInput.create(FIELD, TextInputStyle.SHORT)
                .setPlaceholder("Factions per sub-bag (e.g. 2)")
                .setRequired(false)
                .build();
        TextInput banned = TextInput.create(FIELD_BANNED, TextInputStyle.PARAGRAPH)
                .setPlaceholder("Banned factions (alias or name), comma-separated. Optional.")
                .setRequired(false)
                .build();
        Modal modal = Modal.create(RANDOM_FACTION_MODAL, "Random Faction Bags")
                .addComponents(Label.of("Factions per sub-bag", input), Label.of("Banned factions (optional)", banned))
                .build();
        event.replyModal(modal).queue(Consumers.nop(), BotLogger::catchRestError);
    }

    @ModalHandler(RANDOM_FACTION_MODAL)
    public static void resolveRandomFactions(ModalInteractionEvent event, Game game) {
        FowMiltyDraftState state = state(game);
        if (state == null) return;
        int perBag = 2;
        var mapping = event.getValue(FIELD);
        if (mapping != null) {
            try {
                String raw = mapping.getAsString().trim();
                if (!raw.isEmpty()) perBag = Math.max(1, Integer.parseInt(raw));
            } catch (NumberFormatException ignored) {
                // fall back to default
            }
        }
        var bannedMapping = event.getValue(FIELD_BANNED);
        String banned = bannedMapping == null ? "" : bannedMapping.getAsString();
        FowMiltyService.configureRandomFactions(game, state, perBag, banned);
        MessageHelper.sendMessageToChannel(
                event.getMessageChannel(),
                "Random faction bags built: " + state.getSubBags().size() + " sub-bags.");
        FowMiltyService.repostSetupControls(game, state);
    }

    @ButtonHandler(value = PREFIX + "setup_faction_custom~MDL", save = false)
    public static void setupFactionCustom(ButtonInteractionEvent event) {
        TextInput input = TextInput.create(FIELD, TextInputStyle.PARAGRAPH)
                .setPlaceholder("Sub-bags separated by ; and factions by , e.g. sol,hacan;jol,arborec;letnev")
                .setRequired(true)
                .build();
        Modal modal = Modal.create(CUSTOM_FACTION_MODAL, "Custom Faction Bags")
                .addComponents(Label.of("Sub-bags (; between bags, , between factions)", input))
                .build();
        event.replyModal(modal).queue(Consumers.nop(), BotLogger::catchRestError);
    }

    @ModalHandler(CUSTOM_FACTION_MODAL)
    public static void resolveCustomFactions(ModalInteractionEvent event, Game game) {
        FowMiltyDraftState state = state(game);
        if (state == null) return;
        FowMiltyService.configureCustomFactions(
                game, state, event.getValue(FIELD).getAsString());
        MessageHelper.sendMessageToChannel(
                event.getMessageChannel(),
                "Custom faction bags set: " + state.getSubBags().size() + " sub-bags.");
        FowMiltyService.repostSetupControls(game, state);
    }

    @ButtonHandler(PREFIX + "setup_firstbag_sc")
    public static void setupFirstBagSc(ButtonInteractionEvent event, Game game, Player player) {
        if (!gmGuard(game, player, event)) return;
        FowMiltyDraftState state = state(game);
        if (state == null) return;
        FowMiltyService.configureStrategyCardBag(game, state);
        MessageHelper.sendMessageToChannel(event.getMessageChannel(), "First bag set to Strategy Cards.");
        FowMiltyService.repostSetupControls(game, state);
    }

    @ButtonHandler(value = PREFIX + "setup_firstbag_order~MDL", save = false)
    public static void setupFirstBagOrder(ButtonInteractionEvent event) {
        TextInput nx = TextInput.create(FIELD_NX, TextInputStyle.SHORT)
                .setPlaceholder("Nx — closest to this wins position 1 (e.g. 1)")
                .setRequired(true)
                .build();
        TextInput ny = TextInput.create(FIELD_NY, TextInputStyle.SHORT)
                .setPlaceholder("Ny — upper bound, up to 10000 (e.g. 5000)")
                .setRequired(true)
                .build();
        Modal modal = Modal.create(ORDER_RANGE_MODAL, "Draft Order Range")
                .addComponents(Label.of("Nx (target for position 1)", nx), Label.of("Ny (upper bound)", ny))
                .build();
        event.replyModal(modal).queue(Consumers.nop(), BotLogger::catchRestError);
    }

    @ModalHandler(ORDER_RANGE_MODAL)
    public static void resolveOrderRange(ModalInteractionEvent event, Game game) {
        FowMiltyDraftState state = state(game);
        if (state == null) return;
        int nx;
        int ny;
        try {
            nx = Integer.parseInt(event.getValue(FIELD_NX).getAsString().trim());
            ny = Integer.parseInt(event.getValue(FIELD_NY).getAsString().trim());
        } catch (NumberFormatException e) {
            MessageHelper.sendMessageToChannel(event.getMessageChannel(), "Nx and Ny must be whole numbers.");
            return;
        }
        if (ny > 10_000) {
            MessageHelper.sendMessageToChannel(event.getMessageChannel(), "Ny must be at most 10000.");
            return;
        }
        if (ny - nx < state.playerCount()) {
            MessageHelper.sendMessageToChannel(
                    event.getMessageChannel(),
                    "Ny - Nx must be at least the number of players (" + state.playerCount() + ").");
            return;
        }
        FowMiltyService.configureOrderBag(game, state, nx, ny);
        MessageHelper.sendMessageToChannel(
                event.getMessageChannel(), "First bag set to Draft Order over [" + nx + ", " + ny + "].");
        FowMiltyService.repostSetupControls(game, state);
    }

    @ButtonHandler(PREFIX + "setup_start")
    public static void setupStart(ButtonInteractionEvent event, Game game, Player player) {
        if (!gmGuard(game, player, event)) return;
        FowMiltyDraftState state = state(game);
        if (state == null) return;
        FowMiltyService.startDrafting(game, state);
    }

    @ButtonHandler(PREFIX + "abort")
    public static void abort(ButtonInteractionEvent event, Game game, Player player) {
        if (!gmGuard(game, player, event)) return;
        ButtonHelper.deleteMessage(event);
        FowMiltyService.abort(game);
    }

    // ------------------------------------------------------------------
    // Player picks
    // ------------------------------------------------------------------

    @ButtonHandler(PREFIX + "pick_")
    public static void pickBag(ButtonInteractionEvent event, Game game, Player player, String buttonID) {
        if (!guard(game, event)) return;
        FowMiltyDraftState state = state(game);
        if (state == null || state.getPhase() != FowMiltyDraftState.Phase.DRAFTING) return;
        String typeStr = StringUtils.substringAfterLast(buttonID, "_");
        FowMiltyDraftState.BagType type;
        try {
            type = FowMiltyDraftState.BagType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            return;
        }
        ButtonHelper.deleteMessage(event);
        FowMiltyService.choosePick(game, state, player, type);
    }

    @ButtonHandler(PREFIX + "rank_")
    public static void rankChoice(ButtonInteractionEvent event, Game game, Player player, String buttonID) {
        if (!guard(game, event)) return;
        FowMiltyDraftState state = state(game);
        if (state == null || state.getPhase() != FowMiltyDraftState.Phase.DRAFTING) return;
        String rest = StringUtils.substringAfter(buttonID, PREFIX + "rank_"); // e.g. "SC_5" or "VALUE_region1"
        String typeStr = StringUtils.substringBefore(rest, "_");
        String optionId = StringUtils.substringAfter(rest, "_");
        FowMiltyDraftState.BagType type;
        try {
            type = FowMiltyDraftState.BagType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            return;
        }
        ButtonHelper.deleteMessage(event);
        FowMiltyService.addRankChoice(game, state, player, type, optionId);
    }

    @ButtonHandler(PREFIX + "ranklock_")
    public static void rankLock(ButtonInteractionEvent event, Game game, Player player, String buttonID) {
        if (!guard(game, event)) return;
        FowMiltyDraftState state = state(game);
        if (state == null || state.getPhase() != FowMiltyDraftState.Phase.DRAFTING) return;
        ButtonHelper.deleteMessage(event);
        FowMiltyService.lockRanking(game, state, player);
    }

    @ButtonHandler(PREFIX + "faction_")
    public static void factionChoice(ButtonInteractionEvent event, Game game, Player player, String buttonID) {
        if (!guard(game, event)) return;
        FowMiltyDraftState state = state(game);
        if (state == null || state.getPhase() != FowMiltyDraftState.Phase.DRAFTING) return;
        String alias = StringUtils.substringAfter(buttonID, PREFIX + "faction_");
        ButtonHelper.deleteMessage(event);
        FowMiltyService.chooseFaction(game, state, player, alias);
    }

    // ------------------------------------------------------------------
    // GM final resolution
    // ------------------------------------------------------------------

    @ButtonHandler(PREFIX + "resolve_auto")
    public static void resolveAuto(ButtonInteractionEvent event, Game game, Player player) {
        if (!gmGuard(game, player, event)) return;
        FowMiltyDraftState state = state(game);
        if (state == null || state.getPhase() != FowMiltyDraftState.Phase.RESOLVE) return;
        FowMiltyService.finalizeAuto(game, state, event);
    }

    @ButtonHandler(PREFIX + "resolve_manual")
    public static void resolveManual(ButtonInteractionEvent event, Game game, Player player) {
        if (!gmGuard(game, player, event)) return;
        FowMiltyDraftState state = state(game);
        if (state == null || state.getPhase() != FowMiltyDraftState.Phase.RESOLVE) return;
        FowMiltyService.finalizeManual(game, state, event);
    }
}

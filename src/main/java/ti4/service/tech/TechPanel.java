package ti4.service.tech;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import lombok.experimental.UtilityClass;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import ti4.discord.interactions.buttons.handlers.faction.homebrew.beans.migo.MigoPromissoryHandler;
import ti4.game.Game;
import ti4.game.Planet;
import ti4.game.Player;
import ti4.helpers.ButtonHelper;
import ti4.helpers.Helper;
import ti4.message.MessageHelper;
import ti4.message.componentsV2.CleanerUi;
import ti4.message.componentsV2.ComponentPanel;
import ti4.message.componentsV2.PanelEdits;
import ti4.model.Mapper;
import ti4.model.TechnologyModel;
import ti4.model.TechnologyModel.TechnologyType;
import ti4.service.emoji.TechEmojis;
import ti4.service.payment.PaymentPanel;

@UtilityClass
public class TechPanel {

    public static final int RESEARCH = 9006;
    static final String HEADER = "### Research a technology";
    static final String PICK_HINT =
            "-# Shows the technologies you meet the prerequisites for. Use \"Get Other Technology\" for the rest.";
    static final String TYPE_HINT = "-# Choose the type of technology.";
    static final String PAY_DONE_ID = "deleteButtons_technology";
    private static final List<String> SPECIALTY_ORDER = List.of("propulsion", "biotic", "cybernetic", "warfare");

    public static boolean post(MessageChannel channel, Player player, Game game, List<Button> buttons) {
        if (channel == null || !CleanerUi.isOn(player)) {
            return false;
        }
        List<MessageTopLevelComponent> panel = PaymentPanel.compose(
                HEADER,
                info(player, game),
                RESEARCH,
                pickText(player, game),
                PaymentPanel.prepare(channel, buttons),
                false);
        if (panel.isEmpty()) {
            return false;
        }
        MessageHelper.whenThreadIsOpen(channel, () -> ComponentPanel.post(channel, panel));
        return true;
    }

    public static boolean isPanel(Message message) {
        return PanelEdits.isV2(message) && PaymentPanel.hasSlot(message.getComponentTree(), RESEARCH);
    }

    public static boolean isPanel(ButtonInteractionEvent event) {
        return event != null && isPanel(event.getMessage());
    }

    public static boolean showTechs(ButtonInteractionEvent event, Player player, Game game, List<Button> buttons) {
        return showRows(event, buttons, pickText(player, game));
    }

    public static boolean showTypes(ButtonInteractionEvent event, List<Button> buttons) {
        return showRows(event, buttons, TYPE_HINT);
    }

    private static boolean showRows(ButtonInteractionEvent event, List<Button> buttons, String hint) {
        if (!isPanel(event)) {
            return false;
        }
        MessageComponentTree tree = PaymentPanel.mapContainer(
                PanelEdits.tree(event),
                container -> PanelEdits.withRows(
                        withResearch(container, hint),
                        PaymentPanel.rows(PaymentPanel.prepare(event.getChannel(), buttons))));
        if (!ComponentPanel.fits(new ArrayList<>(tree.getComponents()))) {
            return false;
        }
        PanelEdits.edit(event, tree);
        return true;
    }

    public static boolean showPayment(
            ButtonInteractionEvent event, Player player, Game game, TechnologyModel tech, List<Button> buttons) {
        if (!isPanel(event)) {
            return false;
        }
        String info = info(player, game);
        String researching = "**Researching** " + tech.getRepresentation(false);
        return PaymentPanel.showPayment(
                event,
                player,
                game,
                buttons,
                container -> withResearch(PaymentPanel.withSlotText(container, PaymentPanel.INFO, info), researching));
    }

    public static boolean afterResearch(ButtonInteractionEvent event, TechnologyModel tech) {
        if (!isPanel(event)) {
            return false;
        }
        if (!PaymentPanel.showsPayment(PanelEdits.tree(event))) {
            String acquired = "**Acquired** " + tech.getRepresentation(false);
            PaymentPanel.editContainer(event, container -> PaymentPanel.closed(withResearch(container, acquired)));
        }
        return true;
    }

    public static UnaryOperator<Container> restoredView(Player player, Game game, List<Button> savedButtons) {
        String info = info(player, game);
        List<ActionRow> rows = PaymentPanel.rows(savedButtons);
        boolean paying = savedButtons.stream().anyMatch(button -> PAY_DONE_ID.equals(button.getCustomId()));
        if (paying) {
            String spent = Helper.buildSpentThingsMessage(player, game, PaymentPanel.payType(savedButtons));
            return container ->
                    PaymentPanel.payView(PaymentPanel.withSlotText(container, PaymentPanel.INFO, info), spent, rows);
        }
        String hint = pickText(player, game);
        return container -> PaymentPanel.withAccent(
                PanelEdits.withRows(
                        withResearch(
                                PaymentPanel.withoutPayment(
                                        PaymentPanel.withSlotText(container, PaymentPanel.INFO, info)),
                                hint),
                        rows),
                false);
    }

    static String info(Player player, Game game) {
        return ownedText(player) + "\n" + skipsText(player, game);
    }

    static String ownedText(Player player) {
        Map<TechnologyType, List<String>> byType = new LinkedHashMap<>();
        for (String techId : player.getTechs()) {
            TechnologyModel tech = Mapper.getTech(techId);
            if (tech == null) {
                continue;
            }
            String name = player.getExhaustedTechs().contains(techId) ? "~~" + tech.getName() + "~~" : tech.getName();
            byType.computeIfAbsent(tech.getFirstType(), type -> new ArrayList<>())
                    .add(name);
        }
        if (byType.isEmpty()) {
            return "**Technologies:** none yet";
        }
        List<String> lines = new ArrayList<>();
        lines.add("**Technologies**");
        for (TechnologyType type : TechnologyType.mainFive) {
            List<String> names = byType.remove(type);
            if (names != null) {
                lines.add(type.emoji() + " **" + names.size() + "** " + String.join(", ", names));
            }
        }
        List<String> others = byType.values().stream().flatMap(List::stream).toList();
        if (!others.isEmpty()) {
            lines.add("Other **" + others.size() + "** " + String.join(", ", others));
        }
        return String.join("\n", lines);
    }

    static String skipsText(Player player, Game game) {
        boolean exhaustedStillCount = player.hasTech("pa") || player.hasTech("absol_pa");
        Map<String, List<String>> planetsBySpecialty = new LinkedHashMap<>();
        for (String specialty : SPECIALTY_ORDER) {
            planetsBySpecialty.put(specialty, new ArrayList<>());
        }
        for (String planetId : player.getPlanets()) {
            if (player.getExhaustedPlanets().contains(planetId) && !exhaustedStillCount) {
                continue;
            }
            Planet planet = game.getPlanetsInfo().get(planetId);
            if (planet == null || !ButtonHelper.checkForTechSkips(game, planetId)) {
                continue;
            }
            for (String specialty : planet.getTechSpecialities()) {
                planetsBySpecialty
                        .computeIfAbsent(specialty.toLowerCase(), key -> new ArrayList<>())
                        .add(Helper.getPlanetRepresentationNoResInf(planetId, game));
            }
        }
        List<String> groups = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : planetsBySpecialty.entrySet()) {
            List<String> planets = entry.getValue();
            if (!planets.isEmpty()) {
                groups.add(TechEmojis.getBasicTechEmoji(entry.getKey()) + "×" + planets.size() + " ("
                        + String.join(", ", planets) + ")");
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add(groups.isEmpty() ? "-# No tech skips available." : "**Tech skips:** " + String.join(" · ", groups));
        List<String> others = otherSkips(player, game);
        if (!others.isEmpty()) {
            lines.add("-# Also: " + String.join(", ", others));
        }
        return String.join("\n", lines);
    }

    private static List<String> otherSkips(Player player, Game game) {
        List<String> others = new ArrayList<>();
        if (player.hasRelicReady("prophetstears") || player.hasRelicReady("absol_prophetstears")) {
            others.add("Prophet's Tears (any)");
        }
        if (player.hasTechReady("aida") || player.hasTechReady("absol_aida")) {
            others.add("AI Development Algorithm (unit upgrades)");
        }
        addCommander(others, player, game, "yincommander", "Yin commander", TechEmojis.BioticTech);
        addCommander(others, player, game, "kollecccommander", "Kollecc commander", TechEmojis.PropulsionTech);
        addCommander(others, player, game, "dihmohncommander", "Dih-Mohn commander", TechEmojis.WarfareTech);
        addCommander(others, player, game, "augerscommander", "Augers commander", TechEmojis.CyberneticTech);
        return others;
    }

    private static void addCommander(
            List<String> others, Player player, Game game, String commander, String label, TechEmojis skip) {
        if (game.playerHasLeaderUnlockedOrAlliance(player, commander)) {
            others.add(label + " " + skip);
        }
    }

    private static String pickText(Player player, Game game) {
        if (MigoPromissoryHandler.hasExtractionScience(player, game) && !game.isFowMode()) {
            return PICK_HINT + "\n-# " + ListTechService.EXTRACTION_SCIENCE_REMINDER;
        }
        return PICK_HINT;
    }

    private static Container withResearch(Container container, String text) {
        return PaymentPanel.withSlotText(container, RESEARCH, text);
    }
}

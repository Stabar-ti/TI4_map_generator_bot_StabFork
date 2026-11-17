package ti4.service.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import org.apache.commons.lang3.StringUtils;
import ti4.game.Game;
import ti4.game.Player;
import ti4.game.Tile;
import ti4.helpers.ButtonHelper;
import ti4.helpers.Helper;
import ti4.helpers.ProductionLimit;
import ti4.message.MessageHelper;
import ti4.message.componentsV2.CleanerUi;
import ti4.message.componentsV2.ComponentPanel;
import ti4.message.componentsV2.PanelEdits;
import ti4.service.payment.PaymentPanel;

@UtilityClass
public class BuildPanel {

    public static final int PRODUCED = 9002;
    public static final String NOTHING_PRODUCED = "-# Nothing produced yet.";

    public static boolean post(
            MessageChannel channel, Player player, Game game, String header, String info, List<Button> buttons) {
        if (channel == null || !CleanerUi.isOn(player)) {
            return false;
        }
        List<MessageTopLevelComponent> panel = compose(
                header,
                info,
                producedText(player, game),
                PaymentPanel.prepare(channel, buttons),
                isOverLimit(player, game));
        if (panel.isEmpty()) {
            return false;
        }
        MessageHelper.whenThreadIsOpen(channel, () -> ComponentPanel.post(channel, panel));
        return true;
    }

    public static List<MessageTopLevelComponent> compose(
            String header, String info, String produced, List<Button> buttons, boolean warning) {
        return PaymentPanel.compose(header, info, PRODUCED, produced, buttons, warning);
    }

    public static boolean isPanel(Message message) {
        return PanelEdits.isV2(message) && PaymentPanel.hasSlot(message.getComponentTree(), PRODUCED);
    }

    public static void refreshProduced(ButtonInteractionEvent event, Player player, Game game) {
        String produced = producedText(player, game);
        boolean warning = isOverLimit(player, game);
        PaymentPanel.editContainer(
                event, container -> PaymentPanel.withAccent(withProduced(container, produced), warning));
    }

    public static void refreshProducedIfChanged(
            ButtonInteractionEvent event, Player player, Game game, MessageComponentTree tree) {
        if (!producedText(player, game).equals(PaymentPanel.slotText(tree, PRODUCED))) {
            refreshProduced(event, player, game);
        }
    }

    public static boolean showPayment(ButtonInteractionEvent event, Player player, Game game, List<Button> buttons) {
        String produced = producedText(player, game);
        return PaymentPanel.showPayment(event, player, game, buttons, container -> withProduced(container, produced));
    }

    public static Container withProduced(Container container, String produced) {
        return PaymentPanel.withSlotText(container, PRODUCED, produced);
    }

    public static UnaryOperator<Container> restoredView(Player player, Game game, List<ActionRow> rows) {
        String produced = producedText(player, game);
        if (game.getStoredValue("producedUnitCostFor" + player.getFaction()).isEmpty()) {
            boolean warning = isOverLimit(player, game);
            return container -> PaymentPanel.withAccent(
                    PanelEdits.withRows(PaymentPanel.withoutPayment(withProduced(container, produced)), rows), warning);
        }
        String spent = Helper.buildSpentThingsMessage(player, game, "res");
        return container -> PaymentPanel.payView(withProduced(container, produced), spent, rows);
    }

    public static String producedText(Player player, Game game) {
        Map<String, Integer> producedUnits = player.getCurrentProducedUnits();
        List<String> lines = new ArrayList<>();
        if (producedUnits.isEmpty()) {
            lines.add(NOTHING_PRODUCED);
            fleetLine(player, game, game.getTileByPosition(game.getActiveSystem()), false)
                    .ifPresent(lines::add);
            return String.join("\n", lines);
        }
        List<String> places = ProductionLimit.producedPlaces(producedUnits.keySet());
        List<Tile> tiles = places.stream()
                .map(place -> place.split("_")[0])
                .distinct()
                .map(game::getTileByPosition)
                .filter(Objects::nonNull)
                .toList();
        boolean severalSystems = tiles.size() > 1;
        for (String place : places) {
            lines.add(placeLine(player, game, producedUnits, place, severalSystems));
        }
        for (Tile tile : tiles) {
            fleetLine(player, game, tile, severalSystems).ifPresent(lines::add);
        }
        lines.add(totalsLine(player, game));
        for (String note : Helper.productionDiscountNotes(player, game)) {
            lines.add("-# " + note);
        }
        return StringUtils.abbreviate(String.join("\n", lines), Message.MAX_CONTENT_LENGTH);
    }

    private static String placeLine(
            Player player, Game game, Map<String, Integer> producedUnits, String place, boolean severalSystems) {
        String[] parts = place.split("_");
        StringBuilder line = new StringBuilder();
        if (severalSystems) {
            Tile tile = game.getTileByPosition(parts[0]);
            line.append(tile == null ? parts[0] : tile.getRepresentationForButtons(game, player))
                    .append(" · ");
        }
        line.append("**")
                .append("space".equalsIgnoreCase(parts[1]) ? "Space" : Helper.getPlanetRepresentation(parts[1], game))
                .append(":**");
        for (Map.Entry<String, Integer> entry : producedUnits.entrySet()) {
            String[] unit = entry.getKey().split("_");
            if (!place.equalsIgnoreCase(unit[1] + "_" + unit[2])) {
                continue;
            }
            String emoji = Helper.producedUnitModel(player, entry.getKey())
                    .getUnitEmoji()
                    .toString();
            line.append(' ');
            line.append(entry.getValue() < 10 ? emoji.repeat(entry.getValue()) : entry.getValue() + "× " + emoji);
        }
        return line.toString();
    }

    private static Optional<String> fleetLine(Player player, Game game, Tile tile, boolean withSystem) {
        if (tile == null) {
            return Optional.empty();
        }
        String summary = ButtonHelper.fleetAndCapacitySummary(player, game, tile);
        if (summary.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("-# " + (withSystem ? tile.getRepresentationForButtons(game, player) + ": " : "") + summary);
    }

    private static String totalsLine(Player player, Game game) {
        int cost = Helper.calculateCostOfProducedUnits(player, game, true);
        int unitCount = Helper.calculateCostOfProducedUnits(player, game, false);
        Optional<ProductionLimit> limit =
                unitCount <= 1 ? Optional.empty() : ProductionLimit.forProduction(player, game);
        StringBuilder line = new StringBuilder();
        if (limit.isPresent()) {
            line.append("**")
                    .append(unitCount)
                    .append(" / ")
                    .append(limit.get().limit())
                    .append("** ")
                    .append(limit.get().label());
        } else {
            line.append("**").append(unitCount).append("** unit").append(unitCount == 1 ? "" : "s");
        }
        line.append(" · **").append(cost).append("** resource").append(cost == 1 ? "" : "s");
        if (limit.isPresent() && limit.get().isExceededBy(unitCount)) {
            line.append("\n**Over the ")
                    .append(limit.get().label())
                    .append(" limit of ")
                    .append(limit.get().limit())
                    .append(".**");
        }
        return line.toString();
    }

    private static boolean isOverLimit(Player player, Game game) {
        return ProductionLimit.isExceeded(player, game);
    }
}

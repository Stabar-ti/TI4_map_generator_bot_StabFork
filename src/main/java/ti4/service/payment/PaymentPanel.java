package ti4.service.payment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import lombok.experimental.UtilityClass;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.replacer.ComponentReplacer;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.StringUtils;
import ti4.game.Game;
import ti4.game.Player;
import ti4.helpers.ButtonHelper;
import ti4.helpers.Helper;
import ti4.helpers.NewStuffHelper;
import ti4.logging.BotLogger;
import ti4.message.MessageHelper;
import ti4.message.componentsV2.ComponentPanel;
import ti4.message.componentsV2.PanelEdits;
import ti4.message.componentsV2.PanelView;
import ti4.service.build.BuildPanel;
import ti4.service.tech.TechPanel;

@UtilityClass
public class PaymentPanel {

    public static final int INFO = 9001;
    public static final int SPENT = 9003;
    public static final int INFO_DIVIDER = 9004;
    public static final int SPENT_DIVIDER = 9005;
    public static final String PAYMENT = "**Payment**";
    public static final String PAID = "**Paid**";
    private static final String UNDO_MARKER = "panel:";
    private static final String BUILD_KIND = "build";
    private static final String TECH_KIND = "tech";
    private static final int MAX_SPENT_LENGTH = 1500;
    private static final String RESET_SPEND_PREFIX = "resetSpend_";
    private static final String DEFAULT_PAY_TYPE = "res";
    private static final String SPEND_PAGE_PREFIX = "spendPage_";
    private static final int PAYMENT_PAGE_SIZE = 25;

    public static List<MessageTopLevelComponent> compose(
            String header, String info, int slotId, String slotText, List<Button> buttons, boolean warning) {
        List<ContainerChildComponent> body = new ArrayList<>();
        if (StringUtils.isNotBlank(info)) {
            body.add(TextDisplay.of(info).withUniqueId(INFO));
            body.add(Separator.createDivider(Separator.Spacing.SMALL).withUniqueId(INFO_DIVIDER));
        }
        body.add(TextDisplay.of(slotText).withUniqueId(slotId));
        body.addAll(rows(buttons));
        List<MessageTopLevelComponent> components =
                ComponentPanel.compose(header, null, PanelView.of(body, List.of(), warning), List.of());
        return ComponentPanel.fits(components) ? components : List.of();
    }

    public static boolean isPanel(Message message) {
        return BuildPanel.isPanel(message) || TechPanel.isPanel(message);
    }

    public static boolean isPanel(ButtonInteractionEvent event) {
        return event != null && isPanel(event.getMessage());
    }

    public static boolean showPayment(
            ButtonInteractionEvent event,
            Player player,
            Game game,
            List<Button> buttons,
            UnaryOperator<Container> alongside) {
        List<Button> prepared = prepare(event.getChannel(), buttons);
        return showPaymentButtons(event, player, game, prepared, alongside)
                || showPaymentButtons(event, player, game, firstPaymentPage(prepared), alongside);
    }

    private static boolean showPaymentButtons(
            ButtonInteractionEvent event,
            Player player,
            Game game,
            List<Button> buttons,
            UnaryOperator<Container> alongside) {
        if (buttons.isEmpty()) {
            return false;
        }
        String spent = Helper.buildSpentThingsMessage(player, game, payType(buttons));
        List<ActionRow> rows = rows(buttons);
        MessageComponentTree tree =
                mapContainer(PanelEdits.tree(event), container -> payView(alongside.apply(container), spent, rows));
        if (!ComponentPanel.fits(new ArrayList<>(tree.getComponents()))) {
            return false;
        }
        PanelEdits.edit(event, tree);
        return true;
    }

    public static List<Button> firstPaymentPage(List<Button> buttons) {
        List<Button> spend = new ArrayList<>();
        List<Button> pinned = new ArrayList<>();
        for (Button button : buttons) {
            boolean planet =
                    button.getCustomId() != null && button.getCustomId().startsWith("spend_");
            (planet ? spend : pinned).add(button);
        }
        String pagePrefix = SPEND_PAGE_PREFIX + payType(buttons) + "_";
        return NewStuffHelper.paginateWithPinnedButtons(spend, pinned, pagePrefix, PAYMENT_PAGE_SIZE, 0);
    }

    public static void refreshAfterPress(ButtonInteractionEvent event, Player player, Game game) {
        if (!isPanel(event)) {
            return;
        }
        MessageComponentTree tree = PanelEdits.tree(event);
        String shownSpent = slotText(tree, SPENT);
        if (shownSpent == null) {
            if (BuildPanel.isPanel(event.getMessage())) {
                BuildPanel.refreshProducedIfChanged(event, player, game, tree);
            }
            return;
        }
        if (!shownSpent.startsWith(PAYMENT)) {
            return;
        }
        String spent = spentText(tree, player, game);
        if (!paymentText(PAYMENT, spent).equals(shownSpent)) {
            editContainer(event, container -> withPayment(container, PAYMENT, spent));
        }
    }

    public static boolean showsPayment(MessageComponentTree tree) {
        String shownSpent = slotText(tree, SPENT);
        return shownSpent != null && shownSpent.startsWith(PAYMENT);
    }

    public static String spentText(ButtonInteractionEvent event, Player player, Game game) {
        return spentText(PanelEdits.tree(event), player, game);
    }

    private static String spentText(MessageComponentTree tree, Player player, Game game) {
        return Helper.buildSpentThingsMessage(player, game, payType(tree.findAll(Button.class)));
    }

    public static String payType(Collection<Button> buttons) {
        return buttons.stream()
                .map(Button::getCustomId)
                .filter(Objects::nonNull)
                .filter(id -> id.startsWith(RESET_SPEND_PREFIX))
                .map(id -> id.substring(RESET_SPEND_PREFIX.length()))
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(DEFAULT_PAY_TYPE);
    }

    public static void close(ButtonInteractionEvent event) {
        editContainer(event, PaymentPanel::closed);
    }

    public static void showReceipt(ButtonInteractionEvent event, String spentText) {
        editContainer(event, container -> receipt(container, spentText));
    }

    public static Container closed(Container container) {
        return withAccent(PanelEdits.withRows(withoutSlots(container, INFO, INFO_DIVIDER), List.of()), false);
    }

    public static Container receipt(Container container, String spentText) {
        return withSpent(closed(container), paymentText(PAID, spentText));
    }

    public static Container payView(Container container, String spentText, List<ActionRow> rows) {
        return withPayment(PanelEdits.withRows(container, rows), PAYMENT, spentText);
    }

    public static Container withoutPayment(Container container) {
        return withoutSlots(container, SPENT, SPENT_DIVIDER);
    }

    public static Container withPayment(Container container, String heading, String spentText) {
        return withAccent(withSpent(container, paymentText(heading, spentText)), isUnderpaid(spentText));
    }

    public static Container withSlotText(Container container, int slotId, String text) {
        return container.replace(
                ComponentReplacer.byUniqueId(slotId, TextDisplay.of(text).withUniqueId(slotId)));
    }

    public static Container withSpent(Container container, String spent) {
        TextDisplay slot = TextDisplay.of(spent).withUniqueId(SPENT);
        if (hasSlot(container, SPENT)) {
            return container.replace(ComponentReplacer.byUniqueId(SPENT, slot));
        }
        List<ContainerChildComponent> children = new ArrayList<>();
        boolean inserted = false;
        for (ContainerChildComponent child : container.getComponents()) {
            if (!inserted && child instanceof ActionRow) {
                addSpentSlot(children, slot);
                inserted = true;
            }
            children.add(child);
        }
        if (!inserted) {
            addSpentSlot(children, slot);
        }
        return container.withComponents(children);
    }

    public static Container withoutSlots(Container container, int... uniqueIds) {
        List<ContainerChildComponent> children = new ArrayList<>();
        for (ContainerChildComponent child : container.getComponents()) {
            if (!contains(uniqueIds, child.getUniqueId())) {
                children.add(child);
            }
        }
        return container.withComponents(children);
    }

    public static Container withAccent(Container container, boolean warning) {
        return container.withAccentColor(warning ? ComponentPanel.WARNING_ACCENT : ComponentPanel.ACCENT);
    }

    public static String paymentText(String heading, String spentText) {
        String lead = Helper.SPENT_THINGS_LEAD;
        int leadAt = spentText.indexOf(lead);
        String body = leadAt < 0 ? spentText : spentText.substring(leadAt + lead.length());
        return StringUtils.abbreviate(heading + "\n" + body, MAX_SPENT_LENGTH);
    }

    public static boolean isUnderpaid(String spentText) {
        return spentText != null && spentText.contains(Helper.NOT_ENOUGH_SPENT_WARNING);
    }

    public static String undoMarker(Message message) {
        return undoMarker(TechPanel.isPanel(message) ? TECH_KIND : BUILD_KIND, message.getId());
    }

    public static String undoMarker(String kind, String messageId) {
        return UNDO_MARKER + kind + ":" + messageId;
    }

    public static boolean isUndoMarkerFor(String savedMessage, String messageId) {
        return savedMessage != null && savedMessage.startsWith(UNDO_MARKER) && savedMessage.endsWith(":" + messageId);
    }

    public static boolean restoreAfterUndo(Game savedButtonsGame, Game restored) {
        String savedMessage = savedButtonsGame.getSavedMessage();
        if (savedMessage == null || !savedMessage.startsWith(UNDO_MARKER)) {
            return false;
        }
        MessageChannel channel = savedButtonsGame.getSavedChannel();
        if (channel == null || savedButtonsGame.getSavedButtons().isEmpty()) {
            return true;
        }
        String faction = savedButtonsGame.getSavedButtons().getFirst().split(";")[0];
        Player player = restored.getPlayerFromColorOrFaction(faction);
        String[] marker = savedMessage.substring(UNDO_MARKER.length()).split(":", 2);
        if (player == null || marker.length < 2) {
            return true;
        }
        List<Button> saved = prepare(channel, ButtonHelper.getSavedButtons(savedButtonsGame));
        UnaryOperator<Container> restore = TECH_KIND.equals(marker[0])
                ? TechPanel.restoredView(player, restored, saved)
                : BuildPanel.restoredView(player, restored, rows(saved));
        channel.retrieveMessageById(marker[1])
                .queue(
                        message -> {
                            if (isPanel(message)) {
                                PanelEdits.edit(message, mapContainer(message.getComponentTree(), restore));
                            }
                        },
                        BotLogger::catchRestError);
        return true;
    }

    public static List<Button> prepare(MessageChannel channel, List<Button> buttons) {
        return PanelLabels.tidy(MessageHelper.sanitizeButtons(
                MessageHelper.withUndoButtonIfActionsChannel(channel, new ArrayList<>(buttons)), channel));
    }

    public static List<ActionRow> rows(List<Button> buttons) {
        return ListUtils.partition(buttons, 5).stream().map(ActionRow::of).toList();
    }

    public static boolean hasSlot(MessageComponentTree tree, int uniqueId) {
        return slotText(tree, uniqueId) != null;
    }

    public static String slotText(MessageComponentTree tree, int uniqueId) {
        return tree.findAll(TextDisplay.class).stream()
                .filter(text -> text.getUniqueId() == uniqueId)
                .map(TextDisplay::getContent)
                .findFirst()
                .orElse(null);
    }

    public static void editContainer(ButtonInteractionEvent event, UnaryOperator<Container> edit) {
        PanelEdits.edit(event, mapContainer(PanelEdits.tree(event), edit));
    }

    public static MessageComponentTree mapContainer(MessageComponentTree tree, UnaryOperator<Container> edit) {
        return tree.replace(ComponentReplacer.<Container>of(Container.class, c -> true, edit::apply));
    }

    private static boolean hasSlot(Container container, int uniqueId) {
        return container.getComponents().stream().anyMatch(child -> child.getUniqueId() == uniqueId);
    }

    private static void addSpentSlot(List<ContainerChildComponent> children, TextDisplay slot) {
        children.add(Separator.createDivider(Separator.Spacing.SMALL).withUniqueId(SPENT_DIVIDER));
        children.add(slot);
    }

    private static boolean contains(int[] values, int value) {
        for (int candidate : values) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }
}

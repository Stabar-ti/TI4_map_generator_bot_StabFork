package ti4.service.payment;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.actionrow.ActionRowChildComponent;
import net.dv8tion.jda.api.components.buttons.Button;
import org.apache.commons.lang3.StringUtils;
import ti4.service.emoji.MiscEmojis;

@UtilityClass
public class PanelLabels {

    private static final String PRODUCE = "Produce ";
    private static final Pattern SPEND_TRADE_GOODS = Pattern.compile("Spend (\\d+) Trade Goods?");

    public static List<Button> tidy(List<Button> buttons) {
        return buttons.stream().map(PanelLabels::tidy).toList();
    }

    public static List<ActionRow> tidyRows(List<ActionRow> rows) {
        return rows.stream().map(PanelLabels::tidy).toList();
    }

    public static Button tidy(Button button) {
        String label = button.getLabel();
        if (label == null) {
            return button;
        }
        Matcher tradeGoods = SPEND_TRADE_GOODS.matcher(label);
        if (tradeGoods.matches()) {
            Button relabelled = button.withLabel(tradeGoods.group(1) + " TG");
            return button.getEmoji() == null ? relabelled.withEmoji(MiscEmojis.tg.asEmoji()) : relabelled;
        }
        if (label.startsWith(PRODUCE) && label.length() > PRODUCE.length()) {
            return button.withLabel(StringUtils.capitalize(label.substring(PRODUCE.length())));
        }
        return button;
    }

    private static ActionRow tidy(ActionRow row) {
        List<ActionRowChildComponent> children = row.getComponents().stream()
                .map(child -> child instanceof Button button ? tidy(button) : child)
                .map(ActionRowChildComponent.class::cast)
                .toList();
        return ActionRow.of(children);
    }
}

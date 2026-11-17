package ti4.message.componentsV2;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.experimental.UtilityClass;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.apache.commons.lang3.function.Consumers;
import ti4.logging.BotLogger;

@UtilityClass
public class PanelEdits {

    private static final Cache<String, MessageComponentTree> PENDING = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(2))
            .maximumSize(2000)
            .build();

    public static boolean isV2(Message message) {
        return message != null && message.isUsingComponentsV2();
    }

    public static MessageComponentTree tree(ButtonInteractionEvent event) {
        MessageComponentTree pending = PENDING.getIfPresent(event.getId());
        return pending != null ? pending : event.getMessage().getComponentTree();
    }

    public static void edit(ButtonInteractionEvent event, MessageComponentTree tree) {
        PENDING.put(event.getId(), tree);
        edit(event.getMessage(), tree);
    }

    public static void edit(Message message, MessageComponentTree tree) {
        message.editMessageComponents(tree).useComponentsV2().queue(Consumers.nop(), BotLogger::catchRestError);
    }

    public static void replaceRows(ButtonInteractionEvent event, List<ActionRow> rows) {
        edit(event, withRows(tree(event), rows));
    }

    public static MessageComponentTree withRows(MessageComponentTree tree, List<ActionRow> rows) {
        List<MessageTopLevelComponent> components = new ArrayList<>();
        boolean placed = false;
        for (MessageTopLevelComponent component : tree.getComponents()) {
            if (component instanceof Container container && !placed) {
                components.add(withRows(container, rows));
                placed = true;
            } else if (!(component instanceof ActionRow)) {
                components.add(component);
            }
        }
        if (!placed) {
            components.addAll(rows);
        }
        return MessageComponentTree.of(components);
    }

    public static Container withRows(Container container, List<ActionRow> rows) {
        List<ContainerChildComponent> children = new ArrayList<>();
        for (ContainerChildComponent child : container.getComponents()) {
            if (!(child instanceof ActionRow)) {
                children.add(child);
            }
        }
        children.addAll(rows);
        return container.withComponents(children);
    }
}

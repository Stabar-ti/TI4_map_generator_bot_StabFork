package ti4.service.build;

import java.util.List;
import lombok.experimental.UtilityClass;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import org.apache.commons.lang3.function.Consumers;
import ti4.discord.interactions.buttons.Buttons;
import ti4.discord.interactions.routing.ButtonHandler;
import ti4.logging.BotLogger;
import ti4.message.componentsV2.ComponentPanel;
import ti4.message.componentsV2.PanelView;
import ti4.settings.users.UserSettings;
import ti4.settings.users.UserSettingsManager;

@UtilityClass
public class CleanerUiBetaService {

    public static final String TURN_OFF = "betaCleanerUiOff";

    static final String CHECK_TEXT = "Cleaner UI (beta) is on. Building units in a tactical action now uses one panel"
            + " that updates in place; everything else looks the same for now.\n"
            + "You should see a preview card below this message. If it is blank or says the message can't be"
            + " displayed, your Discord app is too old for it: press **Turn it off** (or update Discord).";
    static final String OFF_TEXT = "Cleaner UI (beta) is off. You can try it again with `/beta cleaner_ui`.";

    public static void set(String userId, boolean enabled) {
        UserSettings settings = UserSettingsManager.get(userId);
        settings.setPrefersCleanerUi(enabled);
        UserSettingsManager.save(settings);
    }

    public static void sendCompatibilityCheck(InteractionHook hook) {
        hook.editOriginal(CHECK_TEXT)
                .setComponents(ActionRow.of(Buttons.red(TURN_OFF, "Turn it off")))
                .queue(
                        sent -> hook.sendMessageComponents(preview())
                                .useComponentsV2()
                                .setEphemeral(true)
                                .queue(Consumers.nop(), BotLogger::catchRestError),
                        BotLogger::catchRestError);
    }

    public static void confirmOff(InteractionHook hook) {
        hook.editOriginal(OFF_TEXT).setComponents().queue(Consumers.nop(), BotLogger::catchRestError);
    }

    static List<MessageTopLevelComponent> preview() {
        PanelView view = PanelView.of(
                List.of(TextDisplay.of("If you can read this card, your Discord app supports the cleaner panels.\n"
                        + "-# Your next build will look like this.")),
                List.of());
        return ComponentPanel.compose("### Cleaner UI preview", null, view, List.of());
    }

    @ButtonHandler(value = TURN_OFF, save = false)
    static void turnOff(ButtonInteractionEvent event) {
        set(event.getUser().getId(), false);
        confirmOff(event.getHook());
    }
}

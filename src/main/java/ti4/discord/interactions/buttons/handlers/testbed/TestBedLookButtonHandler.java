package ti4.discord.interactions.buttons.handlers.testbed;

import java.util.List;
import lombok.experimental.UtilityClass;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.function.Consumers;
import ti4.discord.interactions.routing.ButtonHandler;
import ti4.discord.interactions.routing.ModalHandler;
import ti4.logging.BotLogger;
import ti4.message.MessageHelper;
import ti4.service.testbed.TestBedLooks;
import ti4.service.testbed.TestBedLooks.Answer;
import ti4.service.testbed.TestBedLooks.Verdict;
import ti4.service.testbed.TestBedService;

@UtilityClass
class TestBedLookButtonHandler {

    private static final int MAX_SHOWN_NOTE = 300;

    @ButtonHandler(value = TestBedLooks.PREFIX, save = false)
    public static void answer(ButtonInteractionEvent event, String buttonID) {
        if (!isDeveloper(event)) return;
        Verdict verdict = TestBedLooks.verdictOf(buttonID);
        String token = TestBedLooks.tokenOf(buttonID);
        if (verdict == null || !TestBedLooks.isOpen(token)) {
            closeMessage(event.getMessage(), "-# This look is no longer waiting for an answer.");
            if (verdict == Verdict.WRONG) event.deferEdit().queue(Consumers.nop(), BotLogger::catchRestError);
            return;
        }
        if (verdict == Verdict.WRONG) {
            askWhatIsWrong(event, token);
            return;
        }
        record(event, event.getMessage(), token, verdict, "");
    }

    @ModalHandler(TestBedLooks.NOTE_MODAL)
    public static void note(ModalInteractionEvent event, String modalID) {
        if (!isDeveloper(event)) return;
        ModalMapping note = event.getValue(TestBedLooks.NOTE_FIELD);
        String text = note == null ? "" : note.getAsString().trim();
        record(event, event.getMessage(), TestBedLooks.tokenOf(modalID), Verdict.WRONG, text);
    }

    private static void askWhatIsWrong(ButtonInteractionEvent event, String token) {
        TextInput note = TextInput.create(TestBedLooks.NOTE_FIELD, TextInputStyle.PARAGRAPH)
                .setPlaceholder("Optional: what looks wrong? It goes into the run report.")
                .setRequired(false)
                .setMaxLength(1000)
                .build();
        Modal modal = Modal.create(TestBedLooks.NOTE_MODAL + token, "What looks wrong?")
                .addComponents(Label.of("Note for the report", note))
                .build();
        event.replyModal(modal).queue(Consumers.nop(), BotLogger::catchRestError);
    }

    private static void record(
            GenericInteractionCreateEvent event, Message message, String token, Verdict verdict, String note) {
        String by = event.getUser().getName();
        if (!TestBedLooks.answer(token, new Answer(verdict, note, by))) {
            closeMessage(message, "-# This look is no longer waiting for an answer.");
            return;
        }
        String shownNote = note.isEmpty() ? "" : ": " + StringUtils.abbreviate(note.replace('\n', ' '), MAX_SHOWN_NOTE);
        String line = "-# " + verdict.shown() + " by " + by + shownNote;
        closeMessage(message, line);
    }

    private static void closeMessage(Message message, String line) {
        if (message == null) return;
        message.editMessage(message.getContentRaw() + "\n" + line)
                .setComponents(List.of())
                .queue(Consumers.nop(), BotLogger::catchRestError);
    }

    private static boolean isDeveloper(GenericInteractionCreateEvent event) {
        if (TestBedService.isDeveloper(event.getMember())) return true;
        MessageHelper.sendEphemeralMessageToEventChannel(event, "Only developers can answer test bed looks.");
        return false;
    }
}

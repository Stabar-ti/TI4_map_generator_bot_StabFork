package ti4.service.testbed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.dv8tion.jda.api.components.buttons.Button;
import org.junit.jupiter.api.Test;
import ti4.service.testbed.TestBedLooks.Answer;
import ti4.service.testbed.TestBedLooks.Verdict;

class TestBedLooksTest {

    @Test
    void anAnswerReachesTheWaitingRunOnce() {
        String token = TestBedLooks.open();
        assertTrue(TestBedLooks.answer(token, new Answer(Verdict.WRONG, "accent stayed blue", "dev")));
        // A second click (two developers, or a double tap) must not overwrite the first verdict.
        assertFalse(TestBedLooks.answer(token, new Answer(Verdict.RIGHT, "", "dev")));

        Answer answer = TestBedLooks.await(token, 1000).orElseThrow();
        assertEquals(Verdict.WRONG, answer.verdict());
        assertEquals("accent stayed blue", answer.note());
        assertFalse(TestBedLooks.isOpen(token), "a finished look is forgotten");
    }

    @Test
    void unansweredOrCancelledLooksComeBackEmpty() {
        assertTrue(TestBedLooks.await(TestBedLooks.open(), 10).isEmpty());

        String cancelled = TestBedLooks.open();
        TestBedLooks.cancel(cancelled);
        assertTrue(TestBedLooks.await(cancelled, 1000).isEmpty());
    }

    @Test
    void buttonsCarryTheVerdictAndToken() {
        String token = TestBedLooks.open();
        List<Button> buttons = TestBedLooks.buttons(token);

        assertEquals(
                List.of(Verdict.RIGHT, Verdict.WRONG, Verdict.SKIP),
                buttons.stream()
                        .map(button -> TestBedLooks.verdictOf(button.getCustomId()))
                        .toList());
        // "Wrong…" opens a form, so its id carries the modal marker; the token must survive it.
        assertTrue(buttons.get(1).getCustomId().endsWith("~MDL"));
        buttons.forEach(button -> assertEquals(token, TestBedLooks.tokenOf(button.getCustomId())));
        assertEquals(token, TestBedLooks.tokenOf(TestBedLooks.NOTE_MODAL + token));
        TestBedLooks.cancel(token);
    }
}

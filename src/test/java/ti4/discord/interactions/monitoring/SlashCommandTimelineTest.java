package ti4.discord.interactions.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SlashCommandTimelineTest {

    @Test
    void stagesMeasureTheGapSinceThePreviousMark() {
        SlashCommandTimeline timeline = SlashCommandTimeline.received("game undo", 1000, 1080);
        timeline.markStarted(1085);
        timeline.markCompleted(SlashCommandStage.ACCEPT, 1300);
        timeline.markCompleted(SlashCommandStage.PRE_EXECUTE, 1302);
        timeline.markCompleted(SlashCommandStage.EXECUTE, 1500);
        timeline.markCompleted(SlashCommandStage.POST_EXECUTE, 1540);
        timeline.markFinished(1545);

        assertThat(timeline.getStageMillis())
                .containsEntry(SlashCommandStage.GATEWAY, 80L)
                .containsEntry(SlashCommandStage.HANDOFF, 5L)
                .containsEntry(SlashCommandStage.ACCEPT, 215L)
                .containsEntry(SlashCommandStage.PRE_EXECUTE, 2L)
                .containsEntry(SlashCommandStage.EXECUTE, 198L)
                .containsEntry(SlashCommandStage.POST_EXECUTE, 40L);
        assertThat(timeline.getPreprocessingMillis()).isEqualTo(85);
        assertThat(timeline.getProcessingMillis()).isEqualTo(460);
        assertThat(timeline.getResponseMillis()).isEqualTo(545);
        assertThat(timeline.getCommandName()).isEqualTo("game undo");
    }

    @Test
    void aCommandThatThrowsDuringExecuteHasNoExecuteStage() {
        SlashCommandTimeline timeline = SlashCommandTimeline.received("map add_tile", 0, 0);
        timeline.markStarted(0);
        timeline.markCompleted(SlashCommandStage.ACCEPT, 10);
        timeline.markCompleted(SlashCommandStage.PRE_EXECUTE, 12);
        timeline.markFinished(400);

        assertThat(timeline.getStageMillis()).doesNotContainKeys(SlashCommandStage.EXECUTE);
        assertThat(timeline.getProcessingMillis()).isEqualTo(400);
    }

    @Test
    void hostClockBehindDiscordDoesNotProduceNegativeTimes() {
        SlashCommandTimeline timeline = SlashCommandTimeline.received("game undo", 1000, 950);
        timeline.markStarted(960);
        timeline.markFinished(990);

        assertThat(timeline.getStageMillis()).containsEntry(SlashCommandStage.GATEWAY, 0L);
        assertThat(timeline.getPreprocessingMillis()).isZero();
        assertThat(timeline.getResponseMillis()).isZero();
    }

    @Test
    void gameNameIsOptional() {
        SlashCommandTimeline timeline = SlashCommandTimeline.received("help", 0, 0);
        assertThat(timeline.getGameName()).isEmpty();

        timeline.setGameName("pbd123");
        assertThat(timeline.getGameName()).contains("pbd123");
    }
}

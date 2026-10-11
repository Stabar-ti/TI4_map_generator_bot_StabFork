package ti4.discord.interactions.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SlashCommandRuntimeMonitorTest {

    @Test
    void statisticsBreakEachStageOut() {
        SlashCommandRuntimeMonitor monitor = new SlashCommandRuntimeMonitor();
        for (int i = 0; i < 4; i++) {
            monitor.recordQueued();
            monitor.recordStarted();
            monitor.submit(command("game undo", "pbd1", 200, 0));
        }

        String statistics = monitor.formatStatistics("now");

        assertThat(statistics)
                .startsWith("Slash Command Statistics: now")
                .contains("Total commands: 4")
                .contains("0 not accepted (wrong channel, role or game), 0 not run");
        assertThat(rowFor(statistics, "accept")).contains("4", "20.0ms");
        assertThat(rowFor(statistics, "pre-execute")).contains("4", "1.0ms");
        assertThat(rowFor(statistics, "execute")).contains("4", "200.0ms");
        assertThat(rowFor(statistics, "post-execute")).contains("4", "30.0ms");
    }

    @Test
    void rejectedAndNeverRunCommandsAreCountedButNotTimed() {
        SlashCommandRuntimeMonitor monitor = new SlashCommandRuntimeMonitor();
        // 3 commands queued: 1 never started (game busy), 1 not accepted (e.g. wrong channel), 1 processed.
        for (int i = 0; i < 3; i++) monitor.recordQueued();
        for (int i = 0; i < 2; i++) monitor.recordStarted();
        monitor.recordNotAccepted();
        monitor.submit(command("game undo", "pbd1", 50, 0));

        assertThat(monitor.formatStatistics("now"))
                .contains("Total commands: 1")
                .contains("1 not accepted (wrong channel, role or game), 1 not run (game busy or bot paused)");
    }

    @Test
    void commandsAreRankedByTotalExecuteTimeAndShowTheirSlowestCall() {
        SlashCommandRuntimeMonitor monitor = new SlashCommandRuntimeMonitor();
        for (int i = 0; i < 50; i++) monitor.submit(command("help", null, 2, 0));
        monitor.submit(command("game undo", "pbd11", 300, 0));
        // 2026-10-11T09:31:00Z: the slowest undo ran in pbd22 at 09:31 UTC.
        monitor.submit(command("game undo", "pbd22", 900, 1_791_711_060_000L));
        monitor.submit(command("game undo", "pbd33", 100, 0));

        String statistics = monitor.formatStatistics("now");
        List<String> commandOrder = statistics
                .lines()
                .filter(line -> line.startsWith("game undo") || line.startsWith("help "))
                .map(line -> line.startsWith("help") ? "help" : "game undo")
                .toList();

        assertThat(statistics).contains("Most total execute time:");
        assertThat(rowFor(statistics, "command")).endsWith(" max slowest (UTC)");
        assertThat(commandOrder).containsExactly("game undo", "help");
        assertThat(rowFor(statistics, "game undo"))
                .contains(" 3 ", "1.3s", "900ms")
                .endsWith(" pbd22 09:31");
        assertThat(rowFor(statistics, "help")).endsWith(" - 00:00");
    }

    @Test
    void notAcceptedCommandsNeverReachTheCommandTable() {
        SlashCommandRuntimeMonitor monitor = new SlashCommandRuntimeMonitor();
        monitor.recordNotAccepted();

        assertThat(monitor.formatStatistics("now")).doesNotContain("Most total execute time");
    }

    @Test
    void worstCaseCommandNamesStillFitInOneDiscordMessage() {
        // A full command name is at most three 32-character parts: command, subcommand group and subcommand.
        SlashCommandRuntimeMonitor monitor = new SlashCommandRuntimeMonitor();
        for (int i = 0; i < 6; i++) {
            String part = String.valueOf(i).repeat(32);
            String maxLengthName = part + " " + part + " " + part;
            monitor.submit(command(maxLengthName, "pbd123456", 125_000, 0));
        }

        assertThat(monitor.formatStatistics("`2026-10-03 21:00:29.056`")).hasSizeLessThan(1900);
    }

    private static SlashCommandTimeline command(String name, String gameName, long executeMillis, long startMillis) {
        SlashCommandTimeline timeline = SlashCommandTimeline.received(name, startMillis, startMillis);
        timeline.setGameName(gameName);
        timeline.markStarted(startMillis);
        timeline.markCompleted(SlashCommandStage.ACCEPT, startMillis + 20);
        timeline.markCompleted(SlashCommandStage.PRE_EXECUTE, startMillis + 21);
        long executedAt = startMillis + 21 + executeMillis;
        timeline.markCompleted(SlashCommandStage.EXECUTE, executedAt);
        timeline.markCompleted(SlashCommandStage.POST_EXECUTE, executedAt + 30);
        timeline.markFinished(executedAt + 30);
        return timeline;
    }

    private static String rowFor(String statistics, String label) {
        return statistics
                .lines()
                .filter(line -> line.trim().startsWith(label + " "))
                .findFirst()
                .orElseThrow();
    }
}

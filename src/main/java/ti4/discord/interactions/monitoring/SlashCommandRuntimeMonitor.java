package ti4.discord.interactions.monitoring;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

public class SlashCommandRuntimeMonitor {

    private static final SlashCommandRuntimeMonitor INSTANCE = new SlashCommandRuntimeMonitor();
    private static final int TOP_COMMAND_COUNT = 5;
    private static final DateTimeFormatter SLOWEST_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);

    private long queuedCount;
    private long startedCount;
    private long notAcceptedCount;
    private final LatencyHistogram preprocessing = new LatencyHistogram();
    private final LatencyHistogram processing = new LatencyHistogram();
    private final LatencyHistogram total = new LatencyHistogram();
    private final Map<SlashCommandStage, LatencyHistogram> stages = new EnumMap<>(SlashCommandStage.class);
    private final Map<String, LatencyHistogram> executeByCommand = new HashMap<>();
    private final Map<String, SlowestCall> slowestByCommand = new HashMap<>();

    public static SlashCommandRuntimeMonitor get() {
        return INSTANCE;
    }

    public synchronized void recordQueued() {
        queuedCount++;
    }

    public synchronized void recordStarted() {
        startedCount++;
    }

    public synchronized void recordNotAccepted() {
        notAcceptedCount++;
    }

    public synchronized void submit(SlashCommandTimeline timeline) {
        preprocessing.record(timeline.getPreprocessingMillis());
        processing.record(timeline.getProcessingMillis());
        total.record(timeline.getResponseMillis());
        Map<SlashCommandStage, Long> stageMillis = timeline.getStageMillis();
        stageMillis.forEach((stage, millis) ->
                stages.computeIfAbsent(stage, _ -> new LatencyHistogram()).record(millis));

        Long executeMillis = stageMillis.get(SlashCommandStage.EXECUTE);
        if (executeMillis == null) return;
        String commandName = timeline.getCommandName();
        executeByCommand
                .computeIfAbsent(commandName, _ -> new LatencyHistogram())
                .record(executeMillis);
        SlowestCall slowest = slowestByCommand.get(commandName);
        if (slowest == null || executeMillis >= slowest.millis()) {
            slowestByCommand.put(
                    commandName,
                    new SlowestCall(executeMillis, timeline.getGameName().orElse("-"), timeline.getFinishedAtMillis()));
        }
    }

    public synchronized String formatStatistics(String timestamp) {
        long notRunCount = Math.max(0, queuedCount - startedCount);

        StringBuilder table = new StringBuilder();
        LatencyTables.appendStageHeader(table);
        LatencyTables.appendStageRow(table, "preprocessing", preprocessing);
        appendStageRows(table, true);
        LatencyTables.appendStageRow(table, "processing", processing);
        appendStageRows(table, false);
        LatencyTables.appendStageRow(table, "total", total);

        return "Slash Command Statistics: " + timestamp
                + "\n> Total commands: " + processing.count()
                + "\n> Not processed: " + notAcceptedCount + " not accepted (wrong channel, role or game), "
                + notRunCount + " not run (game busy or bot paused)"
                + "\n```\n" + table + "```"
                + LatencyTables.formatTopByTotal(
                        executeByCommand,
                        "Most total execute time",
                        "command",
                        TOP_COMMAND_COUNT,
                        "slowest (UTC)",
                        this::describeSlowest);
    }

    private String describeSlowest(String commandName) {
        SlowestCall slowest = slowestByCommand.get(commandName);
        if (slowest == null) return "-";
        return slowest.gameName() + " " + SLOWEST_TIME_FORMAT.format(Instant.ofEpochMilli(slowest.finishedAtMillis()));
    }

    private void appendStageRows(StringBuilder table, boolean preprocessingStages) {
        for (SlashCommandStage stage : SlashCommandStage.values()) {
            if (stage.isPreprocessing() != preprocessingStages) continue;
            LatencyTables.appendStageRow(table, "  " + stage.getShortName(), stages.get(stage));
        }
    }

    private record SlowestCall(long millis, String gameName, long finishedAtMillis) {}
}
 
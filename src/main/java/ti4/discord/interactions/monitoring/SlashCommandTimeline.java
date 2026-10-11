package ti4.discord.interactions.monitoring;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import ti4.helpers.DateTimeHelper;

public class SlashCommandTimeline {

    private final String commandName;
    private final long discordCreatedAtMillis;
    private final Map<SlashCommandStage, Long> stageMillis = new EnumMap<>(SlashCommandStage.class);
    private long startedAtMillis;
    private long lastMarkMillis;
    private long finishedAtMillis;
    private String gameName;

    private SlashCommandTimeline(String commandName, long discordCreatedAtMillis, long receivedAtMillis) {
        this.commandName = commandName;
        this.discordCreatedAtMillis = discordCreatedAtMillis;
        stageMillis.put(SlashCommandStage.GATEWAY, Math.max(0, receivedAtMillis - discordCreatedAtMillis));
        lastMarkMillis = receivedAtMillis;
    }

    public static SlashCommandTimeline received(SlashCommandInteractionEvent event) {
        return received(
                event.getFullCommandName(),
                DateTimeHelper.getLongDateTimeFromDiscordSnowflake(event.getInteraction()),
                now());
    }

    public static SlashCommandTimeline received(
            String commandName, long discordCreatedAtMillis, long receivedAtMillis) {
        return new SlashCommandTimeline(commandName, discordCreatedAtMillis, receivedAtMillis);
    }

    public void markStarted() {
        markStarted(now());
    }

    public void markStarted(long startedAtMillis) {
        this.startedAtMillis = startedAtMillis;
        markCompleted(SlashCommandStage.HANDOFF, startedAtMillis);
    }

    public void markCompleted(SlashCommandStage stage) {
        markCompleted(stage, now());
    }

    public void markCompleted(SlashCommandStage stage, long completedAtMillis) {
        stageMillis.put(stage, Math.max(0, completedAtMillis - lastMarkMillis));
        lastMarkMillis = completedAtMillis;
    }

    public void markFinished() {
        markFinished(now());
    }

    public void markFinished(long finishedAtMillis) {
        this.finishedAtMillis = finishedAtMillis;
    }

    public void setGameName(String gameName) {
        this.gameName = gameName;
    }

    String getCommandName() {
        return commandName;
    }

    Optional<String> getGameName() {
        return Optional.ofNullable(gameName);
    }

    Map<SlashCommandStage, Long> getStageMillis() {
        return Map.copyOf(stageMillis);
    }

    long getFinishedAtMillis() {
        return finishedAtMillis;
    }

    long getPreprocessingMillis() {
        return Math.max(0, startedAtMillis - discordCreatedAtMillis);
    }

    long getProcessingMillis() {
        return Math.max(0, finishedAtMillis - startedAtMillis);
    }

    long getResponseMillis() {
        return Math.max(0, finishedAtMillis - discordCreatedAtMillis);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}

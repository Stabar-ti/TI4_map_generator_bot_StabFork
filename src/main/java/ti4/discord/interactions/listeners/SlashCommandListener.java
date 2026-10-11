package ti4.discord.interactions.listeners;

import java.util.Arrays;
import java.util.List;
import javax.annotation.Nonnull;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.GenericCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.apache.commons.lang3.function.Consumers;
import ti4.AsyncTI4DiscordBot;
import ti4.contest.replay.core.CombatContestSettings;
import ti4.contest.replay.service.CombatReplayService;
import ti4.discord.interactions.commands.Command;
import ti4.discord.interactions.commands.GameStateContainer;
import ti4.discord.interactions.commands.ParentCommand;
import ti4.discord.interactions.commands.SlashCommandManager;
import ti4.discord.interactions.monitoring.SlashCommandRuntimeMonitor;
import ti4.discord.interactions.monitoring.SlashCommandStage;
import ti4.discord.interactions.monitoring.SlashCommandTimeline;
import ti4.executors.ExecutionLockType;
import ti4.executors.ExecutorServiceManager;
import ti4.helpers.Constants;
import ti4.logging.BotLogger;
import ti4.logging.RollbarManager;
import ti4.service.SusSlashCommandService;
import ti4.service.game.GameNameService;
import ti4.spring.context.SpringContext;
import ti4.spring.service.usage.InteractionCountService;

class SlashCommandListener extends ListenerAdapter implements CommandListener {

    private static final List<String> SLASHCOMMANDS_WITH_MODALS = Arrays.asList(
            Constants.ADD_TILE_LIST,
            Constants.ADD_TILE_LIST_RANDOM,
            Constants.EDIT_TRACK_RECORD,
            Constants.IMPORT_MAP_JSON);

    @Override
    public void onSlashCommandInteraction(@Nonnull SlashCommandInteractionEvent event) {
        if (!canReceiveCommands(event)) return;
        SlashCommandTimeline timeline = SlashCommandTimeline.received(event);
        SlashCommandRuntimeMonitor.get().recordQueued();

        if (!isModalCommand(event)) {
            Command<SlashCommandInteractionEvent> command = getCommand(event);
            event.getInteraction()
                    .deferReply(command.isEphemeral(event))
                    .queue(Consumers.nop(), BotLogger::catchRestError);
        }

        queue(event, timeline);
    }

    private void queue(SlashCommandInteractionEvent event, SlashCommandTimeline timeline) {
        Command<SlashCommandInteractionEvent> command = getCommand(event);
        ExecutionLockType lockType = getLockType(command);
        String eventString = eventToString(event);
        if (lockType == null) {
            ExecutorServiceManager.runAsync(eventString, () -> process(event, timeline));
            return;
        }
        String gameName = GameNameService.getGameName(event);
        ExecutorServiceManager.runAsyncWithLock(
                eventString, gameName, event.getMessageChannel(), () -> process(event, timeline), lockType);
    }

    public String eventToString(GenericCommandInteractionEvent event) {
        String gameName = GameNameService.getGameName(event);
        return "SlashCommandListener task for `" + event.getUser().getEffectiveName() + "`"
                + (gameName == null ? "" : " in `" + gameName + "`")
                + ": `"
                + event.getCommandString() + "`";
    }

    private void process(SlashCommandInteractionEvent event, SlashCommandTimeline timeline) {
        long processStartTime = System.currentTimeMillis();
        timeline.markStarted(processStartTime);
        SlashCommandRuntimeMonitor.get().recordStarted();
        String gameName = GameNameService.getGameName(event);
        timeline.setGameName(gameName);
        RollbarManager.putInteractionMetadata("slash_command", event);
        RollbarManager.put("command_name", event.getCommandString());
        RollbarManager.put("game_name", gameName);

        ParentCommand command = SlashCommandManager.getCommand(event.getName());
        Command<SlashCommandInteractionEvent> resolvedCommand = getCommand(event);
        CombatReplayService combatReplayService =
                CombatContestSettings.isEnabledStatic() ? SpringContext.getBean(CombatReplayService.class) : null;
        boolean accepted = false;
        try {
            accepted = command.accept(event);
            timeline.markCompleted(SlashCommandStage.ACCEPT);
            if (!accepted) {
                SlashCommandRuntimeMonitor.get().recordNotAccepted();
            } else {
                command.preExecute(event);
                if (combatReplayService != null && resolvedCommand instanceof GameStateContainer gameStateContainer) {
                    CombatReplayService.setPreInteractionSnapshot(
                            combatReplayService.capturePreInteractionSnapshot(gameStateContainer.getGame()));
                }
                logSlashCommand(event);
                timeline.markCompleted(SlashCommandStage.PRE_EXECUTE);
                command.execute(event);
                timeline.markCompleted(SlashCommandStage.EXECUTE);
                command.postExecute(event);
                timeline.markCompleted(SlashCommandStage.POST_EXECUTE);
                InteractionCountService.get().incrementSlashCommand(event.getFullCommandName());
                if (!isModalCommand(event) && !resolvedCommand.isEphemeral(event)) {
                    event.getHook().deleteOriginal().queue(Consumers.nop(), BotLogger::catchRestError);
                }
            }
        } catch (Exception e) {
            command.onException(event, e);
        } finally {
            if (combatReplayService != null) {
                CombatReplayService.clearPreInteractionSnapshot();
            }
            RollbarManager.clear();
        }

        warnForLongRunningCommands(event, processStartTime);
        timeline.markFinished();
        if (accepted && !AsyncTI4DiscordBot.isUnstable()) {
            SlashCommandRuntimeMonitor.get().submit(timeline);
        }
    }

    private static boolean isModalCommand(SlashCommandInteractionEvent event) {
        return SLASHCOMMANDS_WITH_MODALS.contains(event.getInteraction().getSubcommandName());
    }

    private static void logSlashCommand(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        if (member == null) return;

        var command = SlashCommandManager.getCommand(event.getInteraction().getName());
        String susPrefix = command.isSuspicious(event) ? "sus" : "notSus";
        String commandText =
                "```" + susPrefix + "\n" + member.getEffectiveName() + " used " + event.getCommandString() + "\n```";
        if (!event.getCommandString().contains("/rules ask")
                && !event.getCommandString().contains("/fow whisper")
                && !event.getCommandString().contains("/bothelper impersonate")) {
            event.getChannel()
                    .sendMessage(commandText)
                    .queue(
                            m -> {
                                BotLogger.logSlashCommand(event, m);
                                SusSlashCommandService.checkIfShouldReportSusSlashCommand(event, m.getJumpUrl());
                            },
                            BotLogger::catchRestError);
        }
    }

    private static ExecutionLockType getLockType(Command<SlashCommandInteractionEvent> command) {
        if (command instanceof GameStateContainer gameStateContainer) {
            return gameStateContainer.isSaveGame() ? ExecutionLockType.WRITE : ExecutionLockType.READ;
        }
        return null;
    }

    private static Command<SlashCommandInteractionEvent> getCommand(SlashCommandInteractionEvent event) {
        ParentCommand command = SlashCommandManager.getCommand(event.getName());
        Command<SlashCommandInteractionEvent> subcommand = command.getSubcommand(event);
        return subcommand == null ? command : subcommand;
    }
}

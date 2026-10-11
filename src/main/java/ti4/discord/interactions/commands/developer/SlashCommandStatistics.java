package ti4.discord.interactions.commands.developer;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import ti4.discord.interactions.commands.Subcommand;
import ti4.discord.interactions.monitoring.SlashCommandRuntimeMonitor;
import ti4.helpers.DateTimeHelper;
import ti4.message.MessageHelper;

class SlashCommandStatistics extends Subcommand {

    SlashCommandStatistics() {
        super("slash_command_statistics", "Get stats related to slash command processing.");
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        String statistics = SlashCommandRuntimeMonitor.get().formatStatistics(DateTimeHelper.getCurrentTimestamp());
        MessageHelper.sendMessageToChannel(event.getChannel(), statistics);
    }
}

package ti4.discord.interactions.commands.beta;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import ti4.discord.interactions.commands.Subcommand;
import ti4.service.build.CleanerUiBetaService;

class CleanerUiBeta extends Subcommand {

    private static final String ENABLED = "enabled";

    CleanerUiBeta() {
        super("cleaner_ui", "Single-panel layouts that update in place, starting with building units");
        addOptions(new OptionData(OptionType.BOOLEAN, ENABLED, "True to try it, false to go back").setRequired(true));
    }

    @Override
    public boolean isEphemeral(SlashCommandInteractionEvent event) {
        return true;
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        boolean enabled = event.getOption(ENABLED, false, OptionMapping::getAsBoolean);
        CleanerUiBetaService.set(event.getUser().getId(), enabled);
        if (enabled) {
            CleanerUiBetaService.sendCompatibilityCheck(event.getHook());
        } else {
            CleanerUiBetaService.confirmOff(event.getHook());
        }
    }
}

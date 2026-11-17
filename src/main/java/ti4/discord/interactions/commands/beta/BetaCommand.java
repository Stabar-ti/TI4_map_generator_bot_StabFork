package ti4.discord.interactions.commands.beta;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import ti4.discord.interactions.commands.ParentCommand;
import ti4.discord.interactions.commands.Subcommand;

public class BetaCommand implements ParentCommand {

    private final Map<String, Subcommand> subcommands =
            Stream.of(new CleanerUiBeta()).collect(Collectors.toMap(Subcommand::getName, subcommand -> subcommand));

    @Override
    public String getName() {
        return "beta";
    }

    @Override
    public String getDescription() {
        return "Opt in to features that are still being tried out";
    }

    @Override
    public Map<String, Subcommand> getSubcommands() {
        return subcommands;
    }
}

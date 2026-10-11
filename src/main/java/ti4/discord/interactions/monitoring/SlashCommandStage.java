package ti4.discord.interactions.monitoring;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SlashCommandStage {
    GATEWAY("gateway", true),
    HANDOFF("handoff", true),
    ACCEPT("accept", false),
    PRE_EXECUTE("pre-execute", false),
    EXECUTE("execute", false),
    POST_EXECUTE("post-execute", false);

    private final String shortName;
    private final boolean preprocessing;
}

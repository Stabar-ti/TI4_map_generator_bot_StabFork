package ti4.model;

/**
 * Declares whether the bot actually does anything with a token, or whether it is purely map decoration.
 *
 * <p>Token behaviour is implemented as scattered filename string-matching (see {@code Tile.hasAnyToken},
 * {@code FoWHelper.getTileWHs}), so it is not otherwise discoverable which tokens are wired up. This enum
 * records the answer as data.
 *
 * <p>Values are lowercase to match the JSON spelling: {@code JsonMapperManager} disables
 * {@code READ_ENUMS_USING_TO_STRING}, so Jackson matches against {@code name()}.
 */
public enum TokenAutomation {
    /** Art only - the bot has no behaviour attached to this token. */
    cosmetic,
    /** The bot acts on this token. See {@code needsUserInput} for whether it also prompts a player. */
    automated,
    /** Not yet triaged. */
    unknown
}

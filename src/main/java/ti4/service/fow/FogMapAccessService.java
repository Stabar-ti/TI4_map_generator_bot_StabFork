package ti4.service.fow;

import java.util.Arrays;
import java.util.List;
import lombok.experimental.UtilityClass;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent;
import org.apache.commons.lang3.StringUtils;
import ti4.discord.interactions.buttons.Buttons;
import ti4.game.Game;
import ti4.game.Player;
import ti4.helpers.DisplayType;
import ti4.helpers.FoWHelper;
import ti4.image.MapRenderPipeline;
import ti4.message.MessageHelper;
import ti4.service.ShowGameService;

@UtilityClass
public class FogMapAccessService {

    public static final String ENDED_CHOICE_PREFIX = "showEndedFogMap_";
    private static final String OWN_VISION = "own";
    private static final String FULL_MAP = "full";

    public enum Access {
        FULL,
        OWN_VISION,
        ENDED_CHOICE,
        DENIED
    }

    public static Access access(Game game, String userId) {
        if (FoWHelper.isGameMaster(userId, game)) {
            return Access.FULL;
        }
        Player player = game.getPlayer(userId);
        if (player == null || !game.getRealPlayers().contains(player)) {
            return game.isHasEnded() ? Access.FULL : Access.DENIED;
        }
        return game.isHasEnded() ? Access.ENDED_CHOICE : Access.OWN_VISION;
    }

    public static List<DisplayType> displayTypes(String requested) {
        if (DisplayType.split.getValue().equals(requested)) {
            return List.of(DisplayType.map, DisplayType.stats);
        }
        DisplayType type = Arrays.stream(DisplayType.values())
                .filter(t -> t.getValue().equals(requested))
                .findFirst()
                .orElse(DisplayType.all);
        return List.of(type);
    }

    public static void showGame(Game game, GenericInteractionCreateEvent event, List<DisplayType> types) {
        switch (access(game, event.getUser().getId())) {
            case FULL -> types.forEach(type -> ShowGameService.simpleShowGame(game, event, type));
            case OWN_VISION -> showOwnVision(game, event, types);
            case ENDED_CHOICE -> offerEndedChoice(event, types);
            case DENIED -> replyDenied(event);
        }
    }

    public static void showFeature(Game game, GenericInteractionCreateEvent event, DisplayType feature) {
        switch (access(game, event.getUser().getId())) {
            case FULL -> renderToChannel(game, event, feature, event.getMessageChannel());
            case ENDED_CHOICE -> renderToChannel(game, null, feature, event.getMessageChannel());
            case OWN_VISION -> showOwnFeature(game, event, feature);
            case DENIED -> replyDenied(event);
        }
    }

    public static void resolveEndedChoice(Game game, GenericInteractionCreateEvent event, String buttonID) {
        if (!game.isFowMode() || !game.isHasEnded()) {
            MessageHelper.sendEphemeralMessageToEventChannel(
                    event, "This choice is only available once the game has ended.");
            return;
        }
        String choice = StringUtils.substringBetween(buttonID, ENDED_CHOICE_PREFIX, "_");
        List<DisplayType> types =
                displayTypes(StringUtils.substringAfter(buttonID, ENDED_CHOICE_PREFIX + choice + "_"));
        if (OWN_VISION.equals(choice)) {
            showOwnVision(game, event, types);
        } else {
            types.forEach(type -> renderToChannel(game, null, type, event.getMessageChannel()));
        }
    }

    private static void showOwnVision(Game game, GenericInteractionCreateEvent event, List<DisplayType> types) {
        if (privateChannelOf(game, event) == null) {
            replyNoPrivateChannel(event);
            return;
        }
        types.forEach(type -> ShowGameService.simpleShowGame(game, event, type));
    }

    private static void showOwnFeature(Game game, GenericInteractionCreateEvent event, DisplayType feature) {
        MessageChannel privateChannel = privateChannelOf(game, event);
        GenericInteractionCreateEvent playerView = ShowGameService.asPlayerView(game, event);
        if (privateChannel == null || playerView == null) {
            replyNoPrivateChannel(event);
            return;
        }
        MessageChannel target = FoWHelper.isPrivateGame(game, event) ? event.getMessageChannel() : privateChannel;
        renderToChannel(game, playerView, feature, target);
    }

    private static void renderToChannel(
            Game game, GenericInteractionCreateEvent renderEvent, DisplayType type, MessageChannel channel) {
        MapRenderPipeline.queue(
                game, renderEvent, type, fileUpload -> MessageHelper.sendFileUploadToChannel(channel, fileUpload));
    }

    private static void offerEndedChoice(GenericInteractionCreateEvent event, List<DisplayType> types) {
        String requestKey = types.size() > 1
                ? DisplayType.split.getValue()
                : types.getFirst().getValue();
        MessageHelper.sendMessageToChannelWithButtons(
                event.getMessageChannel(),
                "This Fog of War game has ended. Which map do you want to see?",
                List.of(
                        Buttons.gray(ENDED_CHOICE_PREFIX + OWN_VISION + "_" + requestKey, "My Vision"),
                        Buttons.blue(ENDED_CHOICE_PREFIX + FULL_MAP + "_" + requestKey, "Full Map")));
    }

    private static MessageChannel privateChannelOf(Game game, GenericInteractionCreateEvent event) {
        Player player = game.getPlayer(event.getUser().getId());
        return player == null ? null : player.getPrivateChannel();
    }

    private static void replyDenied(GenericInteractionCreateEvent event) {
        MessageHelper.sendEphemeralMessageToEventChannel(
                event,
                "Only the GM and the players of this Fog of War game can view its map until the game has ended.");
    }

    private static void replyNoPrivateChannel(GenericInteractionCreateEvent event) {
        MessageHelper.sendEphemeralMessageToEventChannel(
                event, "Your private channel is not set up, so your Fog of War map cannot be shown.");
    }
}

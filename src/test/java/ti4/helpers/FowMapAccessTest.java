package ti4.helpers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.unions.IThreadContainerUnion;
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ti4.discord.JdaService;
import ti4.game.Game;
import ti4.game.Player;
import ti4.testUtils.BaseTi4Test;

/**
 * Guards who may see the whole unfogged map of an active Fog of War game: only that game's GM, and
 * only from that game's own GM room. Regression for a leak where a player of one fog game ran
 * /show_game with game_name set to that game from ANOTHER game's GM room (where they are GM) and
 * got the full map, because the renderer only fogged channels whose name ends in "-private".
 */
class FowMapAccessTest extends BaseTi4Test {

    private static final String GAME = "fow100";
    private static final String GM_ID = "gm-user";
    private static final String PLAYER_ID = "player-user";

    private Game game;

    @BeforeEach
    void setUp() {
        JdaService.testingMode = true;
        JdaService.jda = mock(JDA.class);

        game = spy(new Game());
        game.setName(GAME);
        game.setFowMode(true);
        Player gm = game.addPlayer(GM_ID, "gm");
        game.addPlayer(PLAYER_ID, "player");
        // getPlayersWithGMRole reads the guild's "<game> GM" role; stub it so no guild is needed.
        doReturn(List.of(gm)).when(game).getPlayersWithGMRole();
    }

    @Test
    void gmInOwnGmRoom_seesWholeMap() {
        GenericInteractionCreateEvent event = event(GM_ID, textChannel(GAME + "-gm-room"));
        assertThat(FoWHelper.canSeeWholeMap(game, event)).isTrue();
        assertThat(FoWHelper.rendersFogged(game, event)).isFalse();
    }

    @Test
    void gmInThreadOfOwnGmRoom_seesWholeMap() {
        GenericInteractionCreateEvent event = event(GM_ID, thread(GAME + "-gm-room"));
        assertThat(FoWHelper.rendersFogged(game, event)).isFalse();
    }

    @Test
    void playerInAnotherGamesGmRoom_isFogged() {
        // The reported leak: this user is GM over in fow99, but only a player in fow100.
        GenericInteractionCreateEvent event = event(PLAYER_ID, textChannel("fow99-gm-room"));
        assertThat(FoWHelper.canSeeWholeMap(game, event)).isFalse();
        assertThat(FoWHelper.rendersFogged(game, event)).isTrue();
    }

    @Test
    void gmInAnotherGamesGmRoom_isFogged() {
        // Being GM of this game is not enough - the whole map must stay inside this game's GM room.
        GenericInteractionCreateEvent event = event(GM_ID, textChannel("fow99-gm-room"));
        assertThat(FoWHelper.rendersFogged(game, event)).isTrue();
    }

    @Test
    void playerInOrdinaryChannel_isFogged() {
        GenericInteractionCreateEvent event = event(PLAYER_ID, textChannel("general"));
        assertThat(FoWHelper.rendersFogged(game, event)).isTrue();
    }

    @Test
    void playerInOwnGamesGmRoom_isFogged() {
        GenericInteractionCreateEvent event = event(PLAYER_ID, textChannel(GAME + "-gm-room"));
        assertThat(FoWHelper.rendersFogged(game, event)).isTrue();
    }

    @Test
    void endedGame_isNotFoggedOutsidePrivateChannels() {
        game.setHasEnded(true);
        GenericInteractionCreateEvent event = event(PLAYER_ID, textChannel("general"));
        assertThat(FoWHelper.canSeeWholeMap(game, event)).isTrue();
        assertThat(FoWHelper.rendersFogged(game, event)).isFalse();
    }

    @Test
    void nonFogGame_isNeverFogged() {
        game.setFowMode(false);
        GenericInteractionCreateEvent event = event(PLAYER_ID, textChannel("general"));
        assertThat(FoWHelper.rendersFogged(game, event)).isFalse();
    }

    @Test
    void nullEvent_isNotFogged() {
        // Bot-internal renders (GM activity thread, website) pass no event and stay unfogged.
        assertThat(FoWHelper.rendersFogged(game, null)).isFalse();
    }

    private static GenericInteractionCreateEvent event(String userId, Channel channel) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        GenericInteractionCreateEvent event = mock(GenericInteractionCreateEvent.class);
        when(event.getUser()).thenReturn(user);
        when(event.getChannel()).thenReturn(channel);
        return event;
    }

    private static TextChannel textChannel(String name) {
        TextChannel channel = mock(TextChannel.class);
        when(channel.getName()).thenReturn(name);
        return channel;
    }

    private static ThreadChannel thread(String parentName) {
        IThreadContainerUnion parent = mock(IThreadContainerUnion.class);
        when(parent.getName()).thenReturn(parentName);
        ThreadChannel thread = mock(ThreadChannel.class);
        when(thread.getParentChannel()).thenReturn(parent);
        when(thread.getName()).thenReturn("some-thread");
        return thread;
    }
}

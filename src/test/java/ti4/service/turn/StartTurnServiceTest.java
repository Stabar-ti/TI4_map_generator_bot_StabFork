package ti4.service.turn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import java.util.Optional;
import java.util.Set;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import ti4.game.Game;
import ti4.game.Player;
import ti4.message.MessageHelper;
import ti4.service.strategycard.StrategyCardMessageService;
import ti4.testUtils.BaseTi4Test;

class StartTurnServiceTest extends BaseTi4Test {

    @Test
    void GetMissedSCFollowsText_SkipsZeroStrategyPoolReminder_WhenLeadershipIsOnlyPendingFollow() {
        Game game = createGameWithPlayedStrategyCard(1);
        Player player = game.getPlayer("p2");
        player.setStrategicCC(0);

        String message = StartTurnService.getMissedSCFollowsText(game, player);

        assertTrue(message.contains("Please resolve these before doing anything else:"));
        assertFalse(message.contains("You currently have 0 command tokens in your strategy pool."));
    }

    @Test
    void GetMissedSCFollowsText_KeepsZeroStrategyPoolReminder_WhenNonLeadershipIsPendingFollow() {
        Game game = createGameWithPlayedStrategyCard(2);
        Player player = game.getPlayer("p2");
        player.setStrategicCC(0);

        String message = StartTurnService.getMissedSCFollowsText(game, player);

        assertTrue(message.contains("You currently have 0 command tokens in your strategy pool."));
    }

    // The reminder text used to be built three times per turn start (null check, empty check, send). Each build
    // looks up the strategy card message in the database, so it must now happen exactly once per unfollowed card.
    @Test
    void SendMissedSCFollowsReminder_BuildsTextOnceAndSendsItOnce() {
        Game game = createGameWithPlayedStrategyCard(2);
        Player player = game.getPlayer("p2");
        MessageChannel channel = mock(MessageChannel.class);

        try (MockedStatic<StrategyCardMessageService> scMessages = mockStatic(StrategyCardMessageService.class);
                MockedStatic<MessageHelper> messages = mockStatic(MessageHelper.class)) {
            scMessages
                    .when(() -> StrategyCardMessageService.getStrategyCardMessage(anyString(), anyInt(), anyInt()))
                    .thenReturn(Optional.empty());

            StartTurnService.sendMissedSCFollowsReminder(channel, game, player);

            scMessages.verify(
                    () -> StrategyCardMessageService.getStrategyCardMessage(anyString(), anyInt(), anyInt()), times(1));
            messages.verify(
                    () -> MessageHelper.sendMessageToChannel(
                            eq(channel), contains("Please resolve these before doing anything else:")),
                    times(1));
        }
    }

    @Test
    void SendMissedSCFollowsReminder_SendsNothing_WhenEverythingIsFollowed() {
        Game game = createGameWithPlayedStrategyCard(2);
        Player player = game.getPlayer("p2");
        player.addFollowedSC(2);
        MessageChannel channel = mock(MessageChannel.class);

        try (MockedStatic<MessageHelper> messages = mockStatic(MessageHelper.class)) {
            StartTurnService.sendMissedSCFollowsReminder(channel, game, player);

            messages.verifyNoInteractions();
        }
    }

    private static Game createGameWithPlayedStrategyCard(int playedSc) {
        Game game = new Game();
        game.setName("testGame");

        Player scHolder = game.addPlayer("p1", "Player 1");
        scHolder.setFaction("sol");
        scHolder.setColor("blue");
        scHolder.setSCs(Set.of(playedSc));

        Player player = game.addPlayer("p2", "Player 2");
        player.setFaction("hacan");
        player.setColor("yellow");
        player.setSCs(Set.of(8));

        game.setSCPlayed(playedSc, true);
        return game;
    }
}

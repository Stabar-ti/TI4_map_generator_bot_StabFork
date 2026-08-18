package ti4.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import ti4.image.Mapper;
import ti4.testUtils.BaseTi4Test;

class TokenModelTest extends BaseTi4Test {
    private static String error(TokenModel token, String descr) {
        return "Error on token [" + token.getAlias() + "]: " + descr;
    }

    @Test
    void testTokens() {
        List<TokenModel> tokens = new ArrayList<>(Mapper.getTokensValues());
        assertFalse(tokens.isEmpty(), "Did not import any tokens");

        Map<String, Predicate<TokenModel>> validators = new LinkedHashMap<>();
        validators.put("E1", TokenModel::isValid);
        validators.put("E2", TokenModelTest::tokenExistsElsewhere);
        validators.put("E3", TokenModelTest::tokenComplete);
        validators.put("E4 - missing 'automation'", TokenModelTest::declaresAutomation);
        validators.put(
                "E5 - 'needsUserInput' requires automation=automated", TokenModelTest::userInputImpliesAutomated);

        for (TokenModel token : tokens)
            for (Entry<String, Predicate<TokenModel>> e : validators.entrySet())
                assertTrue(e.getValue().test(token), error(token, e.getKey()));
        for (String token : Mapper.getTokensFromProperties()) {
            // assertTrue(tokenIsTokenModel(token), "Error: " + token + " is not represented in TokenModel.");
        }
    }

    /**
     * Every token must say whether the bot does anything with it. {@code unknown} is a legal answer, so this never
     * blocks adding a token - it only forbids leaving the question silently unanswered.
     */
    private static boolean declaresAutomation(TokenModel token) {
        return token.getAutomation() != null;
    }

    private static boolean userInputImpliesAutomated(TokenModel token) {
        return !Boolean.TRUE.equals(token.getNeedsUserInput()) || token.getAutomation() == TokenAutomation.automated;
    }

    private static boolean tokenExistsElsewhere(TokenModel token) {
        return Mapper.getTokensFromProperties().contains(token.getAlias());
    }

    private static boolean tokenComplete(TokenModel token) {
        return Mapper.getTokensFromProperties().contains(token.getAlias());
    }

    private static boolean tokenIsTokenModel(String token) {
        return Mapper.getTokensValues().stream().anyMatch(tok -> tok.getAlias().equalsIgnoreCase(token));
    }
}

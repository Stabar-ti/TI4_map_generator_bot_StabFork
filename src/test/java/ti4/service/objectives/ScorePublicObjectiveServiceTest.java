package ti4.service.objectives;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ti4.testUtils.BaseTi4Test;

class ScorePublicObjectiveServiceTest extends BaseTi4Test {

    @Test
    void paymentPanelNamesTheObjectiveAndWhatItCosts() {
        assertThat(ScorePublicObjectiveService.objectiveCost("Sway the Council", "sway_council"))
                .isEqualTo("**Sway the Council**\n-# Spend 8 influence.");
        // Custom objectives have no model; the name alone still says what is being paid for.
        assertThat(ScorePublicObjectiveService.objectiveCost("Custom", "custom_po"))
                .isEqualTo("**Custom**");
    }

    @Test
    void influenceObjectivesPayWithInfluenceSoInfluencePlanetsComeFirst() {
        assertThat(ScorePublicObjectiveService.objectivePayType("Sway the Council"))
                .isEqualTo("inf");
        assertThat(ScorePublicObjectiveService.objectivePayType("Manipulate Galactic Law"))
                .isEqualTo("inf");
        // Resource and mixed objectives keep "both", which already sorts resource planets first.
        assertThat(ScorePublicObjectiveService.objectivePayType("Erect a Monument"))
                .isEqualTo("both");
        assertThat(ScorePublicObjectiveService.objectivePayType("Amass Wealth")).isEqualTo("both");
    }
}

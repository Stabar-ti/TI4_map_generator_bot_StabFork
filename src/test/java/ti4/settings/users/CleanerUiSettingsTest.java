package ti4.settings.users;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ti4.json.JsonMapperManager;
import tools.jackson.databind.json.JsonMapper;

class CleanerUiSettingsTest {

    private static final JsonMapper MAPPER = JsonMapperManager.basic();

    @Test
    void cleanerUiIsOffByDefaultAndForSettingsSavedBeforeTheFlagExisted() {
        assertThat(new UserSettings().isPrefersCleanerUi()).isFalse();

        // Rows written before the beta have no such key; they must read as "off", not fail.
        UserSettings legacy = MAPPER.readValue("{\"userId\":\"123\",\"prefersPillageMsg\":false}", UserSettings.class);
        assertThat(legacy.isPrefersCleanerUi()).isFalse();
    }

    @Test
    void cleanerUiSurvivesAJsonRoundTrip() {
        UserSettings settings = new UserSettings("123");
        settings.setPrefersCleanerUi(true);

        UserSettings read = MAPPER.readValue(MAPPER.writeValueAsString(settings), UserSettings.class);

        assertThat(read.isPrefersCleanerUi()).isTrue();
    }
}

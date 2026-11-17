package ti4.service.testbed;

import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;
import ti4.game.Player;
import ti4.service.build.CleanerUiBetaService;
import ti4.settings.users.UserSettings;

@UtilityClass
class TestBedBetas {

    private record Beta(BiConsumer<String, Boolean> set, Predicate<UserSettings> isOn) {}

    private static final Map<String, Beta> BETAS =
            Map.of("cleaner_ui", new Beta(CleanerUiBetaService::set, UserSettings::isPrefersCleanerUi));

    static Set<String> names() {
        return BETAS.keySet();
    }

    @Nullable
    static String problem(@Nullable String name, @Nullable String value) {
        if (name != null && !BETAS.containsKey(name)) {
            return "unknown beta `" + name + "`; use " + names() + ".";
        }
        if (value != null && !"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            return "`beta` value must be `true` or `false`.";
        }
        return null;
    }

    @Nullable
    static String set(Player seat, String name, String value) {
        if (!TestBedService.isVirtualSeat(seat)) {
            return "`" + seat.getFaction() + "` is not a virtual seat; use `/beta` for your own account";
        }
        BETAS.get(name).set().accept(seat.getUserID(), Boolean.parseBoolean(value));
        return null;
    }

    static String enabled(Player seat) {
        UserSettings settings = seat.getUserSettings();
        return BETAS.entrySet().stream()
                .filter(beta -> beta.getValue().isOn().test(settings))
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.joining(","));
    }
}

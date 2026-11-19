package ti4.service.testbed;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import net.dv8tion.jda.api.components.buttons.Button;
import ti4.discord.interactions.buttons.Buttons;

public final class TestBedLooks {

    public static final String PREFIX = "testbedLook_";
    public static final String NOTE_MODAL = PREFIX + "note_";
    public static final String NOTE_FIELD = "note";
    static final String MODAL_SUFFIX = "~MDL";

    public enum Verdict {
        RIGHT("✅ Looks right"),
        WRONG("❌ Wrong"),
        SKIP("⏭️ Skipped");

        private final String shown;

        Verdict(String shown) {
            this.shown = shown;
        }

        public String shown() {
            return shown;
        }
    }

    public record Answer(Verdict verdict, String note, String by) {}

    private static final Map<String, CompletableFuture<Answer>> PENDING = new ConcurrentHashMap<>();

    private TestBedLooks() {}

    static String open() {
        String token = UUID.randomUUID().toString().substring(0, 8);
        PENDING.put(token, new CompletableFuture<>());
        return token;
    }

    static Optional<Answer> await(String token, long timeoutMillis) {
        CompletableFuture<Answer> pending = PENDING.get(token);
        if (pending == null) return Optional.empty();
        try {
            return Optional.of(pending.get(timeoutMillis, TimeUnit.MILLISECONDS));
        } catch (TimeoutException | ExecutionException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            PENDING.remove(token);
        }
    }

    public static boolean answer(String token, Answer answer) {
        CompletableFuture<Answer> pending = PENDING.get(token);
        return pending != null && pending.complete(answer);
    }

    static void cancel(String token) {
        CompletableFuture<Answer> pending = PENDING.remove(token);
        if (pending != null) pending.completeExceptionally(new IllegalStateException("look cancelled"));
    }

    public static boolean isOpen(String token) {
        return PENDING.containsKey(token);
    }

    static List<Button> buttons(String token) {
        return List.of(
                Buttons.green(PREFIX + "right_" + token, "Looks Right"),
                Buttons.red(PREFIX + "wrong_" + token + MODAL_SUFFIX, "Wrong…"),
                Buttons.gray(PREFIX + "skip_" + token, "Skip"));
    }

    @Nullable
    public static Verdict verdictOf(String buttonId) {
        String rest = buttonId.substring(PREFIX.length());
        if (rest.startsWith("right_")) return Verdict.RIGHT;
        if (rest.startsWith("wrong_")) return Verdict.WRONG;
        if (rest.startsWith("skip_")) return Verdict.SKIP;
        return null;
    }

    public static String tokenOf(String componentId) {
        String rest = componentId.replace(MODAL_SUFFIX, "");
        return rest.substring(rest.lastIndexOf('_') + 1);
    }
}

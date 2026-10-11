package ti4.discord.interactions.monitoring;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import lombok.experimental.UtilityClass;

@UtilityClass
public class LatencyTables {

    private static final String STAGE_ROW_FORMAT = "%-15s %7s %8s %7s %7s%n";

    public static void appendStageHeader(StringBuilder table) {
        table.append(String.format(Locale.ROOT, STAGE_ROW_FORMAT, "stage", "count", "mean", "p50", "p95"));
    }

    public static void appendStageRow(StringBuilder table, String label, LatencyHistogram histogram) {
        if (histogram == null || histogram.count() == 0) {
            table.append(String.format(Locale.ROOT, STAGE_ROW_FORMAT, label, 0, "-", "-", "-"));
            return;
        }
        table.append(String.format(
                Locale.ROOT,
                STAGE_ROW_FORMAT,
                label,
                histogram.count(),
                String.format(Locale.ROOT, "%.1fms", histogram.meanMillis()),
                histogram.percentileMillis(0.5) + "ms",
                histogram.percentileMillis(0.95) + "ms"));
    }

    public static String formatTopByTotal(
            Map<String, LatencyHistogram> histogramsByName, String title, String nameHeader, int limit) {
        return formatTopByTotal(histogramsByName, title, nameHeader, limit, null, null);
    }

    public static String formatTopByTotal(
            Map<String, LatencyHistogram> histogramsByName,
            String title,
            String nameHeader,
            int limit,
            String extraHeader,
            Function<String, String> extraColumn) {
        List<Map.Entry<String, LatencyHistogram>> topEntries = histogramsByName.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, LatencyHistogram> entry) ->
                                entry.getValue().totalMillis())
                        .reversed())
                .limit(limit)
                .toList();
        if (topEntries.isEmpty()) {
            return "";
        }

        int nameWidth = topEntries.stream()
                .mapToInt(entry -> entry.getKey().length())
                .max()
                .orElse(0);
        nameWidth = Math.max(nameWidth, nameHeader.length());
        String rowFormat = "%-" + nameWidth + "s %6s %7s %8s %7s %8s";

        StringBuilder table = new StringBuilder();
        table.append(String.format(Locale.ROOT, rowFormat, nameHeader, "count", "total", "mean", "p95", "max"));
        appendExtraColumn(table, extraHeader);
        for (Map.Entry<String, LatencyHistogram> entry : topEntries) {
            LatencyHistogram histogram = entry.getValue();
            table.append(String.format(
                    Locale.ROOT,
                    rowFormat,
                    entry.getKey(),
                    histogram.count(),
                    formatTotalMillis(histogram.totalMillis()),
                    String.format(Locale.ROOT, "%.1fms", histogram.meanMillis()),
                    histogram.percentileMillis(0.95) + "ms",
                    histogram.maxMillis() + "ms"));
            appendExtraColumn(table, extraColumn == null ? null : extraColumn.apply(entry.getKey()));
        }
        return "\n" + title + ":\n```\n" + table + "```";
    }

    private static void appendExtraColumn(StringBuilder table, String value) {
        if (value != null) {
            table.append(' ').append(value);
        }
        table.append(String.format(Locale.ROOT, "%n"));
    }

    private static String formatTotalMillis(long totalMillis) {
        if (totalMillis < 1000) {
            return totalMillis + "ms";
        }
        return String.format(Locale.ROOT, "%.1fs", totalMillis / 1000.0);
    }
}

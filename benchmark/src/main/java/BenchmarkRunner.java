import com.sun.management.ThreadMXBean;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class BenchmarkRunner {

  private static final int DEFAULT_BENCH_RUNS = 1;
  private static final int BENCH_RUNS = resolveBenchRuns();

  private record JsonSample(String path, String content, int length) {}

  private record GcSnapshot(String name, long count, long timeMs) {}

  private record RunResult(JsonSample sample, long[] medianElapsed, long[] medianAllocatedBytes, int gcHitCount) {}

  private record LengthMedianAggregate(long[] totalNsByTarget, int uniqueLengthCount) {}

  private static final class AllocationTracker {
    private final ThreadMXBean threadMxBean;
    private final long threadId;
    private final boolean enabled;

    private AllocationTracker(ThreadMXBean threadMxBean, long threadId, boolean enabled) {
      this.threadMxBean = threadMxBean;
      this.threadId = threadId;
      this.enabled = enabled;
    }

    static AllocationTracker create() {
      java.lang.management.ThreadMXBean baseMxBean = ManagementFactory.getThreadMXBean();
      if (!(baseMxBean instanceof ThreadMXBean mxBean) || !mxBean.isThreadAllocatedMemorySupported()) {
        return new AllocationTracker(null, Thread.currentThread().getId(), false);
      }

      if (!mxBean.isThreadAllocatedMemoryEnabled()) {
        try {
          mxBean.setThreadAllocatedMemoryEnabled(true);
        } catch (SecurityException | UnsupportedOperationException ignored) {
          return new AllocationTracker(null, Thread.currentThread().getId(), false);
        }
      }

      if (!mxBean.isThreadAllocatedMemoryEnabled()) {
        return new AllocationTracker(null, Thread.currentThread().getId(), false);
      }

      return new AllocationTracker(mxBean, Thread.currentThread().getId(), true);
    }

    boolean isEnabled() {
      return enabled;
    }

    long snapshotAllocatedBytes() {
      if (!enabled) {
        return -1L;
      }
      long allocated = threadMxBean.getThreadAllocatedBytes(threadId);
      return allocated >= 0 ? allocated : -1L;
    }

    long deltaAllocatedBytes(long before) {
      if (!enabled || before < 0) {
        return -1L;
      }
      long after = threadMxBean.getThreadAllocatedBytes(threadId);
      if (after < before) {
        return -1L;
      }
      return after - before;
    }
  }

  private static final Pattern FILE_NAME_PATTERN = Pattern.compile("valid-json-(\\d+)-(\\d+)\\.json");

  private BenchmarkRunner() {}

  private static int resolveBenchRuns() {
    String prop = System.getProperty("jsonvalidator.benchRuns");
    if (prop == null || prop.isBlank()) {
      return DEFAULT_BENCH_RUNS;
    }
    try {
      int parsed = Integer.parseInt(prop.trim());
      return Math.max(1, parsed);
    } catch (NumberFormatException ignored) {
      return DEFAULT_BENCH_RUNS;
    }
  }

  public static void main(String[] args) {
    long benchmarkStartNs = System.nanoTime();

    List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
    if (!jvmArgs.isEmpty()) {
      System.out.println("JVM options: " + String.join(" ", jvmArgs));
    } else {
      System.out.println("JVM options: (none)");
    }
    System.out.println();

    List<BenchTargets.BenchTarget> targets = BenchTargets.ACTIVE_TARGETS;

    List<String> targetDirs = (args.length > 0 ? Arrays.asList(args) : resolveDefaultTargetDirs()).stream()
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()
            .collect(Collectors.toList());

    System.out.println("Loading JSON samples...");
    List<JsonSample> samples = loadJsonSamples(targetDirs);
    System.out.println("Loading done. (" + samples.size() + " files)");

    System.out.println("Sorting samples by length...");
    List<JsonSample> sortedSamples = new ArrayList<>(samples);
    sortedSamples.sort(Comparator.comparingInt(JsonSample::length));
    System.out.println("Sorting done.");

    System.out.println();
    System.out.println("=".repeat(100));
    System.out.println("Valid JSON Benchmark (Median of " + BENCH_RUNS + " Runs per File)");
    System.out.println("=".repeat(100));
    System.out.println("Source directories: " + String.join(", ", targetDirs));
    System.out.println("Validation BenchTargets  : " + targets.stream().map(BenchTargets.BenchTarget::name).collect(Collectors.joining(", ")));
    System.out.println("Files loaded      : " + samples.size());
    System.out.println("Bench runs        : " + BENCH_RUNS);
    System.out.println("=".repeat(100));
    System.out.println();

    for (BenchTargets.BenchTarget target : targets) {
      System.out.println("[Warmup] " + target.name() + ": starting...");
      int warmupCalls = runIntegratedWarmup(sortedSamples, target.func());
      System.out.println("[Warmup] " + target.name() + ": " + warmupCalls + " calls done");
    }
    System.out.println();

    List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    AllocationTracker allocationTracker = AllocationTracker.create();
    System.out.println("Thread allocation tracking: " + (allocationTracker.isEnabled() ? "enabled" : "unsupported"));
    System.out.println();

    String timeCols = targets.stream()
            .map(t -> String.format("%12s", t.name()))
            .collect(Collectors.joining(" | "));
    System.out.printf(" %8s | %s | FILE%n", "Length", timeCols);
    System.out.println("-".repeat(100));

    long[] totalNs = new long[targets.size()];
    long[] totalAllocatedBytes = new long[targets.size()];
    List<RunResult> results = new ArrayList<>(samples.size());

    for (JsonSample sample : samples) {
      long[][] allElapsed = new long[BENCH_RUNS][targets.size()];
      long[][] allAllocatedBytes = new long[BENCH_RUNS][targets.size()];
      int gcHitCount = 0;

      for (int run = 0; run < BENCH_RUNS; run++) {
        List<GcSnapshot> gcBefore = snapshotGc(gcBeans);
        for (int i = 0; i < targets.size(); i++) {
          long allocatedBefore = allocationTracker.snapshotAllocatedBytes();
          long start = System.nanoTime();
          targets.get(i).func().apply(sample.content());
          allElapsed[run][i] = System.nanoTime() - start;
          allAllocatedBytes[run][i] = allocationTracker.deltaAllocatedBytes(allocatedBefore);
        }
        List<GcSnapshot> gcAfter = snapshotGc(gcBeans);
        if (!gcDiff(gcBefore, gcAfter).isEmpty()) {
          gcHitCount++;
        }
      }

      long[] medianElapsed = new long[targets.size()];
      for (int i = 0; i < targets.size(); i++) {
        long[] times = new long[BENCH_RUNS];
        for (int run = 0; run < BENCH_RUNS; run++) {
          times[run] = allElapsed[run][i];
        }
        Arrays.sort(times);
        medianElapsed[i] = times[BENCH_RUNS / 2];
        totalNs[i] += medianElapsed[i];
      }

      long[] medianAllocatedBytes = new long[targets.size()];
      for (int i = 0; i < targets.size(); i++) {
        medianAllocatedBytes[i] = medianNonNegative(allAllocatedBytes, i);
        if (medianAllocatedBytes[i] >= 0) {
          totalAllocatedBytes[i] += medianAllocatedBytes[i];
        }
      }

      results.add(new RunResult(sample, medianElapsed, medianAllocatedBytes, gcHitCount));
    }

    for (RunResult result : results) {
      String cols = Arrays.stream(result.medianElapsed())
              .mapToObj(BenchmarkRunner::formatTime)
              .collect(Collectors.joining(" | "));
      String gcLabel = result.gcHitCount() > 0 ? " [GC: " + result.gcHitCount() + "/" + BENCH_RUNS + " runs]" : "";
      System.out.printf(" %8d | %s | %s%s%n",
              result.sample().length(),
              cols,
              result.sample().path(),
              gcLabel);
    }

    System.out.println("-".repeat(100));
    System.out.println();

    if (allocationTracker.isEnabled()) {
      String allocCols = targets.stream()
              .map(t -> String.format("%12s", t.name()))
              .collect(Collectors.joining(" | "));
      System.out.println("Median allocated bytes per validation call:");
      System.out.printf(" %8s | %s | FILE%n", "Length", allocCols);
      System.out.println("-".repeat(100));

      for (RunResult result : results) {
        String cols = Arrays.stream(result.medianAllocatedBytes())
                .mapToObj(BenchmarkRunner::formatBytesColumn)
                .collect(Collectors.joining(" | "));
        System.out.printf(" %8d | %s | %s%n",
                result.sample().length(),
                cols,
                result.sample().path());
      }

      System.out.println("-".repeat(100));
      System.out.println();
    }

    long gcHits = results.stream().filter(r -> r.gcHitCount() > 0).count();
    System.out.println("Total files: " + results.size());
    System.out.println("Samples with GC: " + gcHits + " / " + results.size());
    System.out.println();

    if (!results.isEmpty()) {
      for (int i = 0; i < targets.size(); i++) {
        BenchTargets.BenchTarget target = targets.get(i);
        final int idx = i;
        long[] times = results.stream().mapToLong(r -> r.medianElapsed()[idx]).sorted().toArray();
        long median = times[times.length / 2];
        long threshold = median > Long.MAX_VALUE / 10 ? Long.MAX_VALUE : median * 10;
        List<RunResult> spikes = results.stream()
                .filter(r -> r.medianElapsed()[idx] > threshold)
                .collect(Collectors.toList());

        if (!spikes.isEmpty()) {
          System.out.println("[Spike] " + target.name() + " (median " + formatTime(median) + ", threshold >10x):");
          for (RunResult r : spikes) {
            String gcLabel = r.gcHitCount() > 0 ? " [GC: " + r.gcHitCount() + "/" + BENCH_RUNS + " runs]" : "";
            System.out.printf("  %8d chars | %s | %s%s%n",
                    r.sample().length(),
                    formatTime(r.medianElapsed()[idx]),
                    r.sample().path(),
                    gcLabel);
          }
          System.out.println();
        }
      }
    }

    for (int i = 0; i < targets.size(); i++) {
      System.out.println("Total  [" + targets.get(i).name() + "]: " + formatTime(totalNs[i]));
    }
    System.out.println();

    for (int i = 0; i < targets.size(); i++) {
      if (results.isEmpty()) {
        System.out.println("Average[" + targets.get(i).name() + "]: n/a");
      } else {
        System.out.println("Average[" + targets.get(i).name() + "]: " + formatTime(totalNs[i] / results.size()));
      }
    }

    System.out.println();
    LengthMedianAggregate lengthMedianAggregate = aggregateByLengthMedianElapsed(results, targets.size());
    System.out.println("Length-grouped median aggregation:");
    System.out.println("Unique lengths: " + lengthMedianAggregate.uniqueLengthCount());
    for (int i = 0; i < targets.size(); i++) {
      if (lengthMedianAggregate.uniqueLengthCount() == 0) {
        System.out.println("Total  [" + targets.get(i).name() + " len-median]: n/a");
      } else {
        System.out.println("Total  [" + targets.get(i).name() + " len-median]: " +
            formatTime(lengthMedianAggregate.totalNsByTarget()[i]));
      }
    }
    System.out.println();
    for (int i = 0; i < targets.size(); i++) {
      if (lengthMedianAggregate.uniqueLengthCount() == 0) {
        System.out.println("Average[" + targets.get(i).name() + " len-median]: n/a");
      } else {
        System.out.println("Average[" + targets.get(i).name() + " len-median]: " +
            formatTime(lengthMedianAggregate.totalNsByTarget()[i] / lengthMedianAggregate.uniqueLengthCount()));
      }
    }

    if (allocationTracker.isEnabled()) {
      System.out.println();
      for (int i = 0; i < targets.size(); i++) {
        if (results.isEmpty()) {
          System.out.println("Total  [" + targets.get(i).name() + " alloc]: n/a");
        } else {
          System.out.println("Total  [" + targets.get(i).name() + " alloc]: " + formatBytesHuman(totalAllocatedBytes[i]));
        }
      }
      System.out.println();

      for (int i = 0; i < targets.size(); i++) {
        if (results.isEmpty()) {
          System.out.println("Average[" + targets.get(i).name() + " alloc]: n/a");
        } else {
          System.out.println("Average[" + targets.get(i).name() + " alloc]: " + formatBytesHuman(totalAllocatedBytes[i] / results.size()));
        }
      }
    }

    System.out.println();
    System.out.println("=".repeat(100));
    long benchmarkElapsedNs = System.nanoTime() - benchmarkStartNs;
    System.out.println("Total benchmark elapsed: " + formatDuration(benchmarkElapsedNs));
    System.out.println();
  }

  private static int runIntegratedWarmup(List<JsonSample> samples, java.util.function.Function<String, Boolean> validate) {
    if (samples.isEmpty()) {
      return 0;
    }

    int targetCalls = 60000;
    int minCalls = 10000;

    List<JsonSample> sorted = samples;
    int pickN = Math.min(targetCalls, sorted.size());

    String[] payloads;
    if (pickN == sorted.size()) {
      payloads = sorted.stream().map(JsonSample::content).toArray(String[]::new);
    } else {
      payloads = new String[pickN];
      for (int i = 0; i < pickN; i++) {
        int idx = (int) ((long) i * (sorted.size() - 1) / (pickN - 1));
        payloads[i] = sorted.get(idx).content();
      }
    }

    int totalCalls = payloads.length >= minCalls ? payloads.length : minCalls;
    for (int i = 0; i < totalCalls; i++) {
      validate.apply(payloads[i % payloads.length]);
    }
    return totalCalls;
  }

  private static List<String> resolveDefaultTargetDirs() {
    Path root = Paths.get("src/main/resources/benchmarks");
    if (!Files.exists(root) || !Files.isDirectory(root)) {
      return List.of();
    }

    List<Path> subDirs;
    try (Stream<Path> stream = Files.list(root)) {
      subDirs = stream
              .filter(Files::isDirectory)
              .sorted(Comparator.comparing(p -> p.getFileName().toString()))
              .collect(Collectors.toList());
    } catch (IOException e) {
      throw new RuntimeException("Failed to read benchmark directories", e);
    }

    if (subDirs.isEmpty()) {
      return List.of();
    }
    if (subDirs.size() == 1) {
      return List.of(subDirs.get(0).toString());
    }

    System.out.println("Available benchmark directories:");
    for (int i = 0; i < subDirs.size(); i++) {
      System.out.println("  " + (i + 1) + ". " + subDirs.get(i).getFileName());
    }
    System.out.print("Select directory (1-" + subDirs.size() + "): ");

    String line;
    try {
      BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      line = reader.readLine();
    } catch (IOException e) {
      line = "";
    }

    int choice;
    try {
      choice = Integer.parseInt(line.trim());
    } catch (Exception e) {
      choice = 0;
    }

    if (choice < 1 || choice > subDirs.size()) {
      System.out.println("Invalid selection. Exiting.");
      System.exit(1);
    }

    return List.of(subDirs.get(choice - 1).toString());
  }

  private static List<JsonSample> loadJsonSamples(List<String> dirs) {
    List<JsonSample> loaded = new ArrayList<>();
    for (String dir : dirs) {
      Path root = Paths.get(dir);
      if (!Files.exists(root) || !Files.isDirectory(root)) {
        continue;
      }

      Path jsonlFile = root.resolve("samples.jsonl");
      if (Files.exists(jsonlFile)) {
        loaded.addAll(loadFromJsonl(jsonlFile));
      } else {
        loaded.addAll(loadFromJsonFiles(root));
      }
    }

    loaded.sort((a, b) -> {
      int[] oa = extractOrder(fileNameOnly(a.path()));
      int[] ob = extractOrder(fileNameOnly(b.path()));
      int cmp = Integer.compare(oa[0], ob[0]);
      if (cmp != 0) {
        return cmp;
      }
      return Integer.compare(oa[1], ob[1]);
    });

    return loaded;
  }

  private static List<JsonSample> loadFromJsonl(Path jsonlFile) {
    List<String> lines;
    try {
      lines = Files.readAllLines(jsonlFile, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read jsonl file: " + jsonlFile, e);
    }

    List<JsonSample> samples = new ArrayList<>();
    int idx = 0;
    for (String line : lines) {
      String text = line.trim();
      if (text.isEmpty()) {
        continue;
      }
      samples.add(new JsonSample("[jsonl] entry-" + idx, text, text.length()));
      idx++;
    }
    return samples;
  }

  private static List<JsonSample> loadFromJsonFiles(Path root) {
    List<Path> files;
    try (Stream<Path> stream = Files.walk(root)) {
      files = stream
              .filter(Files::isRegularFile)
              .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))
              .sorted(Comparator.comparing(Path::toString))
              .collect(Collectors.toList());
    } catch (IOException e) {
      throw new RuntimeException("Failed to walk directory: " + root, e);
    }

    List<JsonSample> samples = new ArrayList<>(files.size());
    for (Path path : files) {
      String text = readUtf8Lenient(path);
      samples.add(new JsonSample(root.relativize(path).toString(), text, text.length()));
    }
    return samples;
  }

  private static List<GcSnapshot> snapshotGc(List<GarbageCollectorMXBean> beans) {
    List<GcSnapshot> snapshots = new ArrayList<>(beans.size());
    for (GarbageCollectorMXBean bean : beans) {
      snapshots.add(new GcSnapshot(bean.getName(), bean.getCollectionCount(), bean.getCollectionTime()));
    }
    return snapshots;
  }

  private static String gcDiff(List<GcSnapshot> before, List<GcSnapshot> after) {
    Map<String, GcSnapshot> beforeMap = new LinkedHashMap<>();
    for (GcSnapshot b : before) {
      beforeMap.put(b.name(), b);
    }

    List<String> diffs = new ArrayList<>();
    for (GcSnapshot a : after) {
      GcSnapshot b = beforeMap.get(a.name());
      if (b == null) {
        continue;
      }
      if (a.count() >= 0 && b.count() >= 0 && a.count() > b.count()) {
        long countDiff = a.count() - b.count();
        long timeDiff = a.timeMs() - b.timeMs();
        diffs.add(a.name() + ": " + countDiff + "x " + timeDiff + "ms");
      }
    }

    if (diffs.isEmpty()) {
      return "";
    }
    return " [GC: " + String.join(", ", diffs) + "]";
  }

  private static String formatTime(long nanos) {
    double micros = nanos / 1000.0;
    if (micros < 1000.0) {
      return String.format("%10.2f us", micros);
    }
    return String.format("%10.3f ms", micros / 1000.0);
  }

  private static long medianNonNegative(long[][] values, int column) {
    long[] filtered = new long[values.length];
    int size = 0;
    for (long[] row : values) {
      long value = row[column];
      if (value >= 0) {
        filtered[size++] = value;
      }
    }
    if (size == 0) {
      return -1L;
    }
    Arrays.sort(filtered, 0, size);
    return filtered[size / 2];
  }

  private static LengthMedianAggregate aggregateByLengthMedianElapsed(List<RunResult> results, int targetCount) {
    if (results.isEmpty()) {
      return new LengthMedianAggregate(new long[targetCount], 0);
    }

    Map<Integer, List<RunResult>> byLength = new TreeMap<>();
    for (RunResult r : results) {
      byLength.computeIfAbsent(r.sample().length(), k -> new ArrayList<>()).add(r);
    }

    long[] totalNs = new long[targetCount];
    for (int targetIdx = 0; targetIdx < targetCount; targetIdx++) {
      for (List<RunResult> group : byLength.values()) {
        long[] values = new long[group.size()];
        for (int j = 0; j < group.size(); j++) {
          values[j] = group.get(j).medianElapsed()[targetIdx];
        }
        Arrays.sort(values);
        totalNs[targetIdx] += values[values.length / 2];
      }
    }

    return new LengthMedianAggregate(totalNs, byLength.size());
  }

  private static String formatBytesColumn(long bytes) {
    if (bytes < 0) {
      return String.format("%12s", "n/a");
    }
    if (bytes < 1024) {
      return String.format("%10d B", bytes);
    }
    if (bytes < 1024L * 1024L) {
      return String.format("%9.2f KB", bytes / 1024.0);
    }
    if (bytes < 1024L * 1024L * 1024L) {
      return String.format("%9.2f MB", bytes / (1024.0 * 1024.0));
    }
    return String.format("%9.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
  }

  private static String formatBytesHuman(long bytes) {
    if (bytes < 0) {
      return "n/a";
    }
    if (bytes < 1024) {
      return bytes + " B";
    }
    if (bytes < 1024L * 1024L) {
      return String.format("%.2f KB", bytes / 1024.0);
    }
    if (bytes < 1024L * 1024L * 1024L) {
      return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }
    return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
  }

  private static String formatDuration(long nanos) {
    long totalMs = nanos / 1_000_000L;
    long hours = totalMs / 3_600_000L;
    long minutes = (totalMs % 3_600_000L) / 60_000L;
    long seconds = (totalMs % 60_000L) / 1000L;
    long millis = totalMs % 1000L;
    if (hours > 0) {
      return String.format("%dh %dm %ds %dms", hours, minutes, seconds, millis);
    }
    if (minutes > 0) {
      return String.format("%dm %ds %dms", minutes, seconds, millis);
    }
    return String.format("%ds %dms", seconds, millis);
  }

  private static String fileNameOnly(String path) {
    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return slash >= 0 ? path.substring(slash + 1) : path;
  }

  private static int[] extractOrder(String fileName) {
    Matcher matcher = FILE_NAME_PATTERN.matcher(fileName);
    if (matcher.matches()) {
      return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }
    return new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE};
  }

  private static String readUtf8Lenient(Path path) {
    try {
      return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read file: " + path, e);
    }
  }
}

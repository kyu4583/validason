import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class JsonSuiteChecker {

  private enum Expected {
    VALID,
    INVALID,
    INDETERMINATE
  }

  private record FileResult(String name, Expected expected, boolean[] results) {}

  private interface ResultRenderer {
    String render(boolean result, FileResult row);
  }

  private static final String SUITE_DIR = "src/main/resources/json-parsing-suite";
  private static final String SEP = "=".repeat(100);
  private static final String SUB_SEP = "-".repeat(100);

  private JsonSuiteChecker() {}

  public static void main(String[] args) {
    Path suiteDir = Paths.get(SUITE_DIR);
    if (!Files.exists(suiteDir) || !Files.isDirectory(suiteDir)) {
      System.out.println("Directory not found: " + SUITE_DIR);
      System.exit(1);
    }

    List<BenchTargets.BenchTarget> targets = BenchTargets.ACTIVE_TARGETS;

    List<Path> files;
    try (Stream<Path> stream = Files.walk(suiteDir)) {
      files = stream
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))
          .sorted(Comparator.comparing(p -> p.getFileName().toString()))
          .collect(Collectors.toList());
    } catch (IOException e) {
      throw new RuntimeException("Failed to read suite files", e);
    }

    System.out.println("json-parsing-suite: " + files.size() + " files found");
    System.out.println("Running validation...");
    System.out.println();

    List<FileResult> allResults = new ArrayList<>(files.size());
    for (Path path : files) {
      String name = path.getFileName().toString();
      String content = readUtf8Lenient(path);

      Expected expected;
      if (name.startsWith("y_")) {
        expected = Expected.VALID;
      } else if (name.startsWith("n_")) {
        expected = Expected.INVALID;
      } else {
        expected = Expected.INDETERMINATE;
      }

      boolean[] funcResults = new boolean[targets.size()];
      for (int i = 0; i < targets.size(); i++) {
        funcResults[i] = targets.get(i).func().apply(content);
      }

      allResults.add(new FileResult(name, expected, funcResults));
    }

    List<FileResult> yFiles = allResults.stream().filter(r -> r.expected() == Expected.VALID).collect(Collectors.toList());
    List<FileResult> nFiles = allResults.stream().filter(r -> r.expected() == Expected.INVALID).collect(Collectors.toList());
    List<FileResult> iFiles = allResults.stream().filter(r -> r.expected() == Expected.INDETERMINATE).collect(Collectors.toList());

    System.out.println(SEP);
    System.out.println("JSON Parsing Suite - Validation Result Agreement Check");
    System.out.println(SEP);
    System.out.println("Total files: " + allResults.size() + "  (y_: " + yFiles.size() + ", n_: " + nFiles.size() + ", i_: " + iFiles.size() + ")");
    System.out.println("Target functions: " + targets.stream().map(BenchTargets.BenchTarget::name).collect(Collectors.joining(", ")));
    System.out.println(SEP);
    System.out.println();

    System.out.println("[ Accuracy (based on y_/n_ files) ]");
    System.out.println();

    int nameW = Math.max(8, targets.stream().map(BenchTargets.BenchTarget::name).mapToInt(String::length).max().orElse(8));
    System.out.println(
        left("Function", nameW) + "  " +
            right("y_ Correct", 12) + "  " +
            right("n_ Correct", 12) + "  " +
            right("Total Correct", 14) + "  " +
            right("Accuracy", 7)
    );
    System.out.println(SUB_SEP.substring(0, Math.min(SUB_SEP.length(), nameW + 55)));

    for (int i = 0; i < targets.size(); i++) {
      int yOk = countMatches(yFiles, i, true);
      int nOk = countMatches(nFiles, i, false);
      int total = yOk + nOk;
      int denom = yFiles.size() + nFiles.size();
      double pct = denom == 0 ? 0.0 : total * 100.0 / denom;
      System.out.println(
          left(targets.get(i).name(), nameW) + "  " +
              right(yOk + "/" + yFiles.size(), 12) + "  " +
              right(nOk + "/" + nFiles.size(), 12) + "  " +
              right(total + "/" + denom, 14) + "  " +
              String.format("%6.2f%%", pct)
      );
    }
    System.out.println();

    if (targets.size() > 1) {
      System.out.println("[ Agreement Rate Between Targets (all files) ]");
      System.out.println();
      for (int i = 0; i < targets.size(); i++) {
        for (int j = i + 1; j < targets.size(); j++) {
          int agree = 0;
          for (FileResult r : allResults) {
            if (r.results()[i] == r.results()[j]) {
              agree++;
            }
          }
          int disagree = allResults.size() - agree;
          double pct = allResults.isEmpty() ? 0.0 : agree * 100.0 / allResults.size();
          System.out.println(
              "  " + targets.get(i).name() + " vs " + targets.get(j).name() + ": " +
                  String.format("agree %d/%d (%.1f%%) / disagree %d", agree, allResults.size(), pct, disagree)
          );
        }
      }
      System.out.println();
    }

    List<FileResult> interDisagreements = allResults.stream()
        .filter(JsonSuiteChecker::hasDisagreement)
        .collect(Collectors.toList());
    if (!interDisagreements.isEmpty()) {
      printFileTable(
          "[ Files with Target Disagreement (" + interDisagreements.size() + ") ]",
          interDisagreements,
          targets.stream().map(BenchTargets.BenchTarget::name).collect(Collectors.toList()),
          true,
          (res, row) -> res ? "true" : "false"
      );
    }

    List<FileResult> wrongFiles = allResults.stream()
        .filter(r -> r.expected() != Expected.INDETERMINATE)
        .filter(r -> {
          boolean expect = r.expected() == Expected.VALID;
          for (boolean result : r.results()) {
            if (result != expect) {
              return true;
            }
          }
          return false;
        })
        .collect(Collectors.toList());

    if (!wrongFiles.isEmpty()) {
      printFileTable(
          "[ Files with Unexpected Results (" + wrongFiles.size() + ") ]",
          wrongFiles,
          targets.stream().map(BenchTargets.BenchTarget::name).collect(Collectors.toList()),
          true,
          (res, row) -> {
            boolean expect = row.expected() == Expected.VALID;
            return res == expect ? "OK" : "FAIL";
          }
      );
    } else {
      System.out.println("[ Files with Unexpected Results ]");
      System.out.println();
      System.out.println("  None (all y_/n_ files match expected values across all functions)");
      System.out.println();
    }

    if (!iFiles.isEmpty()) {
      printFileTable(
          "[ Implementation-Dependent (i_) File Results (" + iFiles.size() + ") ]",
          iFiles,
          targets.stream().map(BenchTargets.BenchTarget::name).collect(Collectors.toList()),
          false,
          (res, row) -> res ? "true" : "false"
      );
    }

    System.out.println(SEP);
  }

  private static int countMatches(List<FileResult> rows, int targetIndex, boolean expected) {
    int count = 0;
    for (FileResult row : rows) {
      if (row.results()[targetIndex] == expected) {
        count++;
      }
    }
    return count;
  }

  private static boolean hasDisagreement(FileResult row) {
    if (row.results().length <= 1) {
      return false;
    }
    boolean first = row.results()[0];
    for (int i = 1; i < row.results().length; i++) {
      if (row.results()[i] != first) {
        return true;
      }
    }
    return false;
  }

  private static void printFileTable(
      String title,
      List<FileResult> rows,
      List<String> targetNames,
      boolean showExpected,
      ResultRenderer resultRenderer
  ) {
    System.out.println(title);
    System.out.println();

    int colW = Math.max(5, targetNames.stream().mapToInt(String::length).max().orElse(5));
    int fileW = 55;

    String expPart = showExpected ? "  Tag" : "";
    String header = left("Filename", fileW) + expPart + "  " +
        targetNames.stream().map(n -> right(n, colW)).collect(Collectors.joining("  "));
    System.out.println(header);
    System.out.println(SUB_SEP.substring(0, Math.min(SUB_SEP.length(), header.length())));

    for (FileResult row : rows) {
      String tag;
      if (row.expected() == Expected.VALID) {
        tag = "y";
      } else if (row.expected() == Expected.INVALID) {
        tag = "n";
      } else {
        tag = "i";
      }

      String expPart2 = showExpected ? "  " + tag + "  " : "  ";

      StringBuilder cols = new StringBuilder();
      for (int i = 0; i < row.results().length; i++) {
        if (i > 0) {
          cols.append("  ");
        }
        cols.append(right(resultRenderer.render(row.results()[i], row), colW));
      }

      System.out.println(left(row.name(), fileW) + expPart2 + cols);
    }

    System.out.println();
  }

  private static String left(String s, int width) {
    return String.format("%-" + width + "s", s);
  }

  private static String right(String s, int width) {
    return String.format("%" + width + "s", s);
  }

  private static String readUtf8Lenient(Path path) {
    try {
      return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read file: " + path, e);
    }
  }
}

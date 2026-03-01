[English](README.md) | [한국어](README.ko.md)

# Validason Benchmark

This folder is a project for reproducing benchmarks.

## Included

- Java benchmark sources: `src/main/java`
- Resource Generator: `resources/RandomValidJsonGenerator.py`
- Plot scripts: `scripts/*.py`
- IntelliJ run configs: `run/*.run.xml`

## 1) Generate benchmark data

Example:

```powershell
python src/main/resources/RandomValidJsonGenerator.py `
  --max-length 10000 `
  --count-per-length 10 `
  --length-step 1 `
```

Output example:

- `src/main/resources/benchmarks/random-valid-json-10000x10x1/samples.jsonl`

## 2) Configure benchmark targets

Benchmark targets are configured via `ACTIVE_TARGETS` in `BenchTargets.java`.

```java
public static final List<BenchTarget> ACTIVE_TARGETS = List.of(
    new BenchTarget("Jackson", BenchTargets::jackson),
    new BenchTarget("Gson", BenchTargets::gsonValidate),
    new BenchTarget("Validason", Validason::isValid)
);
```

Select the validation functions defined in the same file as needed.

## 3) Run benchmark

```powershell
.\gradlew.bat run --no-daemon --console=plain
```

To change heap size or iteration count:

```powershell
.\gradlew.bat run --no-daemon --console=plain -PrunHeap=8g -PrunBenchRuns=3
```

To specify a particular data directory:

```powershell
.\gradlew.bat run --no-daemon --console=plain --args="src/main/resources/benchmarks/random-valid-json-10000x10x1"
```

## 4) Run suite checker

```powershell
.\gradlew.bat runSuiteChecker --no-daemon --console=plain
```

## 5) Generate plots

```powershell
python scripts/plot_bench.py full --non-interactive --input=bench_output.txt
```

The following script is also available if needed:

- `scripts/plot_memory_alloc_from_bench_output.py`

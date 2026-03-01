[English](README.md) | [한국어](README.ko.md)

# Validason Benchmark

이 폴더는 벤치마크 재현용 프로젝트입니다.

## Included

- Java benchmark sources: `src/main/java`
- Resource Generator: `resources/RandomValidJsonGenerator.py`
- Plot scripts: `scripts/*.py`
- IntelliJ run configs: `run/*.run.xml`

## 1) Generate benchmark data

예시:

```powershell
python src/main/resources/RandomValidJsonGenerator.py `
  --max-length 10000 `
  --count-per-length 10 `
  --length-step 1 `
```

생성 결과 예:

- `src/main/resources/benchmarks/random-valid-json-10000x10x1/samples.jsonl`

## 2) Configure benchmark targets

벤치마크 대상은 `BenchTargets.java`의 `ACTIVE_TARGETS`에서 조정합니다.

```java
public static final List<BenchTarget> ACTIVE_TARGETS = List.of(
    new BenchTarget("Jackson", BenchTargets::jackson),
    new BenchTarget("Gson", BenchTargets::gsonValidate),
    new BenchTarget("Validason", Validason::isValid)
);
```

같은 파일에 정의된 검증 함수 중 필요한 것만 골라 넣으면 됩니다.

## 3) Run benchmark

```powershell
.\gradlew.bat run --no-daemon --console=plain
```

힙 크기나 반복 횟수를 바꾸려면:

```powershell
.\gradlew.bat run --no-daemon --console=plain -PrunHeap=8g -PrunBenchRuns=3
```

특정 데이터 디렉토리를 지정하려면:

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

필요 시 아래 스크립트도 사용 가능합니다.

- `scripts/plot_memory_alloc_from_bench_output.py`

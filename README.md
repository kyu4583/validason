[English](README.md) | [한국어](README.ko.md)

# Validason

Validason is a high-performance Java library focused solely on **JSON string validation**.
Rather than providing broad parsing/mapping/tree manipulation like general-purpose JSON libraries,
it is optimized for a single purpose: quickly and reliably answering "Is this string valid JSON?"

## Quick Start

**Gradle:**
```kotlin
implementation("io.github.kyu4583:validason:1.0.1")
```

**Maven:**

```xml
<dependency>
    <groupId>io.github.kyu4583</groupId>
    <artifactId>validason</artifactId>
    <version>1.0.1</version>
</dependency>
```

## How to Use

```java
String json = "{\"name\":\"validason\",\"ok\":true}";
boolean ok = Validason.isValid(json);
```

## Why Validason

1. Fast processing speed, specialized for a single purpose (JSON string validation)
2. Extremely low memory allocation
3. Simple API with virtually no room for misconfiguration: `Validason.isValid(String)`

## Benchmark Evidence

All figures and graphs below are based on stored benchmark artifacts.
Conditions are specified beneath each graph.

### Notation (Dataset / Runtime)

- `BENCH_RUNS=N`: each sample is executed `N` times; the run median is used
- `Xms=Xmx=Ng`: JVM initial and max heap both fixed at `N GB`

### Are comparisons beyond Jackson sufficient?

Six JVM libraries commonly mentioned for JSON string validation were compared under identical conditions.

- Targets: `Jackson`, `Gson`, `OrgJson`, `Jsonp`, `Moshi`, `Networknt`
- Conditions: `length=7–1,000`, 500 JSONs per length, `BENCH_RUNS=11`, `Xms=Xmx=4g`
- Metric: average processing time based on per-length run medians
- All validation functions can be found in [`BenchTargets.java`](benchmark/src/main/java/BenchTargets.java)

![Validator library comparison (all targets)](/graphs/bench_1000_validator_compare_all_validator_core6_reimpl_20260227_024811.png)

Results:

- `Jackson`: `2.03 us`
- `Jsonp`: `2.70 us`
- `Networknt`: `4.14 us`
- `Gson`: `4.51 us`
- `Moshi`: `4.62 us`
- `OrgJson`: `11.52 us`

In this comparison group, Jackson is the fastest. The rest of this document presents evidence relative to Jackson.
The Jackson function used for comparison is the optimal configuration for JSON string validation, described in [2) Usability / Configuration](#2-usability--configuration).

## 1) Speed

> **What is `AlwaysEscape`?**
> A naive baseline that simply iterates over every character and escapes it (see `BenchTargets.alwaysEscape` in `benchmark/`).
> It performs no actual validation — it represents the theoretical lower bound of scanning an entire string once.

### 1-1. Main speed case

![Speed benchmark (7-10,000)](/graphs/bench_10000_readme_refresh_final_speed_10000x100x1_b21_h8g.png)

- Conditions: `length=7–10,000`, 100 JSONs per length, `BENCH_RUNS=21`, `Xms=Xmx=8g`
- Metric: average processing time based on per-length run medians

Results:

- `Jackson`: `19.26 us`
- `AlwaysEscape`: `16.85 us`
- `Validason`: `14.10 us`

Relative comparison:

- `Validason` vs `AlwaysEscape`: **+16.32% faster**
- `Validason` vs `Jackson`: **+26.79% faster**

### 1-2. Shorter-length dense case

![Speed benchmark (7-2,000)](/graphs/bench_2000_readme_refresh_final_speed_2000x1500x1_b11_h8g.png)

- Conditions: `length=7–2,000`, 1,500 JSONs per length, `BENCH_RUNS=11`, `Xms=Xmx=8g`
- Metric: average processing time based on per-length run medians

Results:

- `Validason`: `2.17 us`
- `AlwaysEscape`: `2.18 us`
- `Jackson`: `3.17 us`

Relative comparison:

- `Validason` vs `AlwaysEscape`: **+0.46% faster**
- `Validason` vs `Jackson`: **+31.55% faster**

## 2) Usability / Configuration

All Jackson variants below (`readTree`, `defaultCanonOn`, `canonOff`) are available in [`BenchTargets.java`](benchmark/src/main/java/BenchTargets.java).

### Jackson: simple approach (`readTree`)

```java
static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

boolean jacksonReadTree(String json) {
  try {
    OBJECT_MAPPER.readTree(json);
    return true;
  } catch (Exception e) {
    return false;
  }
}

String json = "{\"name\":\"validason\",\"ok\":true}";
boolean ok = jacksonReadTree(json);
```

This is a commonly used pattern for JSON string validation with Jackson.

### Jackson: streaming validation + default canonicalize on

```java
static final JsonFactory FACTORY_DEFAULT = JsonFactory.builder().build();

boolean jacksonDefaultCanonOn(String json) {
  try (JsonParser parser = FACTORY_DEFAULT.createParser(json)) {
    while (parser.nextToken() != null) {
      // consume
    }
    return true;
  } catch (Exception e) {
    return false;
  }
}

String json = "{\"name\":\"validason\",\"ok\":true}";
boolean ok = jacksonDefaultCanonOn(json);
```

Generally faster than `readTree`, but suffers severe performance degradation under high call frequency.

### Jackson: streaming validation + canonicalize off (optimal)

```java
static final JsonFactory FACTORY_CANON_OFF = JsonFactory.builder()
    .configure(JsonFactory.Feature.CANONICALIZE_FIELD_NAMES, false)
    .build();

boolean jacksonCanonOff(String json) {
  try (JsonParser parser = FACTORY_CANON_OFF.createParser(json)) {
    while (parser.nextToken() != null) {
      // consume
    }
    return true;
  } catch (Exception e) {
    return false;
  }
}

String json = "{\"name\":\"validason\",\"ok\":true}";
boolean ok = jacksonCanonOff(json);
```

The optimal pattern with no performance degradation even under high call frequency.

### Validason

```java
String json = "{\"name\":\"validason\",\"ok\":true}";
boolean ok = Validason.isValid(json);
```

Validason's single usage pattern.

Full comparison:

![Usability benchmark](/graphs/bench_1000_usability_1000x500x1_b1_h4g_readme_graph_refresh_20260226_r1.png)

- Conditions: `length=7–1,000`, 500 JSONs per length, `BENCH_RUNS=1`, `Xms=Xmx=4g`
- Results:
  - `Validason`: `2.70 us`
  - `JacksonCanonOff`: `3.35 us`
  - `JacksonReadTree`: `11.53 us`
  - `JacksonDefaultCanonOn`: `18.34 us`

Even for the same "validation" task, Jackson's performance varies significantly depending on implementation and configuration,
while Validason delivers consistent performance with a single `isValid` call.

## 3) Accuracy

Accuracy was verified against [JSONTestSuite](https://github.com/nst/JSONTestSuite) (`y_` valid, `n_` invalid), running `JacksonCanonOff` and `JacksonReadTree` as independent targets.

Accuracy summary:

- `Validason`: `283/283 (100.00%)`
- `JacksonCanonOff`: `279/283 (98.59%)`
- `JacksonReadTree`: `267/283 (94.35%)`

All misclassifications are false positives where Jackson variants passed `n_` (invalid) files as `true`.

### Shared misclassification cases (CanonOff + ReadTree)

The 4 misclassifications from `JacksonCanonOff` also appear in `JacksonReadTree`.

```text
n_single_space.json
<single space>
```

```text
n_structure_double_array.json
[][]
```

```text
n_structure_no_data.json
[][]
```

```text
n_structure_object_with_trailing_garbage.json
{"a": true} "x"
```

### ReadTree-only additional misclassification cases (12 cases)

```text
n_array_comma_after_close.json
[""],
```

```text
n_array_extra_close.json
["x"]]
```

```text
n_object_trailing_comment.json
{"a":"b"}/**/
```

```text
n_object_trailing_comment_open.json
{"a":"b"}/**//
```

```text
n_object_trailing_comment_slash_open.json
{"a":"b"}//
```

```text
n_object_trailing_comment_slash_open_incomplete.json
{"a":"b"}/
```

```text
n_object_with_trailing_garbage.json
{"a":"b"}#
```

```text
n_string_with_trailing_garbage.json
""x
```

```text
n_structure_array_trailing_garbage.json
[1]x
```

```text
n_structure_array_with_extra_array_close.json
[1]]
```

```text
n_structure_object_followed_by_closing_object.json
{}}
```

```text
n_structure_trailing_#.json
{"a":"b"}#{}
```

### Misclassification type summary

- Whitespace-only input (`single space`)
- Multiple top-level structures or no data (`[][]` variants)
- Trailing garbage/comments/tokens after complete JSON (`"x"`, `#`, `//`, `/**/`, extra `]`, extra `}`)

In contrast, Validason passed all cases from [JSONTestSuite](https://github.com/nst/JSONTestSuite) with 100% accuracy.

## 4) Memory

![Memory allocation benchmark](/graphs/memory_alloc_readme_refresh_final_speed_10000x100x1_b21_h8g.png)

- Conditions: `length=7–10,000`, 100 JSONs per length, `BENCH_RUNS=21`, `Xms=Xmx=8g`
- Metric: average allocation based on per-length median allocations

Results:

- `Jackson`: `11.32 KB`
- `AlwaysEscape`: `5.72 KB`
- `Validason`: `72 B`

Validason reduces memory pressure by eliminating unnecessary object creation for string validation.

## 5) GC-focused Runs

![GC Jackson Max](/graphs/gc_max_100000x5x1_b1_h32g_readme_refresh_jackson.png)

![GC Validason Max](/graphs/gc_max_100000x5x1_b1_h32g_readme_refresh_validason.png)

GC-focused summary:

- Conditions: `length=7–100,000`, 5 JSONs per length, `BENCH_RUNS=1`, `Xms=Xmx=32g`, single-target runs
- `Samples with GC` (bench output): Jackson `15 / 499,970`, Validason `0 / 499,970`
- `gc.log Pause event count`: Jackson `54`, Validason `23`
- `gc.log Pause total`: Jackson `5425.546 ms`, Validason `2867.065 ms`
- `max-by-length latency point`: Jackson `24075.0 us`, Validason `1285.0 us`

## Benchmark Reproducibility

Reproduction steps are documented in `benchmark/README.md`.

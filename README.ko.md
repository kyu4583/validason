[English](README.md) | [한국어](README.ko.md)

# Validason

Validason은 **JSON 문자열 검증(validate)** 하나에만 집중한 고성능 Java 라이브러리입니다.
일반 목적 JSON 라이브러리처럼 파싱/매핑/트리 조작 기능을 폭넓게 제공하기보다는,
"이 문자열이 유효한 JSON인가?"를 빠르고 안정적으로 판별하는 단일 목적에 최적화했습니다.

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

1. 단일 목적(JSON String validate) 특화로 빠른 처리 속도
2. 매우 낮은 메모리 할당
3. 설정 실수 여지가 거의 없는 단순 API: `Validason.isValid(String)`

## Benchmark Evidence

아래 수치와 그래프는 모두 저장된 벤치 아티팩트 기준으로 작성했습니다.
조건은 각 그래프 아래에 명시합니다.

### 표기 규칙 (Dataset / Runtime)

- `BENCH_RUNS=N`: 각 샘플을 `N`회 실행하고 run median 사용
- `Xms=Xmx=Ng`: JVM 초기/최대 힙을 모두 `N GB`로 고정

### Jackson 말고도 충분한 비교가 있는가?

JSON String Validator 용도로 자주 언급되는 JVM 라이브러리 6종을 같은 조건에서 비교했습니다.

- 대상: `Jackson`, `Gson`, `OrgJson`, `Jsonp`, `Moshi`, `Networknt`
- 조건: `length=7-1,000`, length당 500개 JSON, `BENCH_RUNS=11`, `Xms=Xmx=4g`
- 지표: length별 run median 기반 평균 처리 시간
- 모든 검증 함수는 [`BenchTargets.java`](benchmark/src/main/java/BenchTargets.java)에서 확인할 수 있습니다

![Validator library comparison (all targets)](/graphs/bench_1000_validator_compare_all_validator_core6_reimpl_20260227_024811.png)

결과:

- `Jackson`: `2.03 us`
- `Jsonp`: `2.70 us`
- `Networknt`: `4.14 us`
- `Gson`: `4.51 us`
- `Moshi`: `4.62 us`
- `OrgJson`: `11.52 us`

즉, 본 비교군에서는 Jackson이 가장 빠르고, 이후 문서는 그 Jackson 대비 근거를 제시합니다.

## 1) Speed

> **`AlwaysEscape`란?**
> 모든 문자를 순회하며 escape만 수행하는 단순 베이스라인입니다 (`benchmark/` 내 `BenchTargets.alwaysEscape` 참고).
> 실제 검증은 하지 않으며, 문자열 전체를 한 번 훑는 데 드는 이론적 하한선을 나타냅니다.

### 1-1. Main speed case

![Speed benchmark (7-10,000)](/graphs/bench_10000_readme_refresh_final_speed_10000x100x1_b21_h8g.png)

- 조건: `length=7-10,000`, length당 100개 JSON, `BENCH_RUNS=21`, `Xms=Xmx=8g`
- 지표: length별 run median 기반 평균 처리 시간

결과:

- `Jackson`: `19.26 us`
- `AlwaysEscape`: `16.85 us`
- `Validason`: `14.10 us`

상대 비교:

- `Validason` vs `AlwaysEscape`: **+16.32% faster**
- `Validason` vs `Jackson`: **+26.79% faster**

### 1-2. Shorter-length dense case

![Speed benchmark (7-2,000)](/graphs/bench_2000_readme_refresh_final_speed_2000x1500x1_b11_h8g.png)

- 조건: `length=7-2,000`, length당 1,500개 JSON, `BENCH_RUNS=11`, `Xms=Xmx=8g`
- 지표: length별 run median 기반 평균 처리 시간

결과:

- `Validason`: `2.17 us`
- `AlwaysEscape`: `2.18 us`
- `Jackson`: `3.17 us`

상대 비교:

- `Validason` vs `AlwaysEscape`: **+0.46% faster**
- `Validason` vs `Jackson`: **+31.55% faster**

## 2) Usability / Configuration

아래 Jackson 변형(`readTree`, `defaultCanonOn`, `canonOff`)은 모두 [`BenchTargets.java`](benchmark/src/main/java/BenchTargets.java)에서 확인할 수 있습니다.

### Jackson: 단순 사용 방식(`readTree`)

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

Jackson을 통한 JSON 문자열 검증 시 흔하게 사용되는 패턴이다.

### Jackson: 스트리밍 검증 + default canonicalize on

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

일반적으로 readTree보다 빠르지만 호출 빈도가 높을 때 급격하게 성능 저하가 발생하는 패턴이다.

### Jackson: 스트리밍 검증 + canonicalize off (최적)

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

높은 빈도의 호출에도 성능 저하가 없는 최적 패턴이다.

### Validason

```java
String json = "{\"name\":\"validason\",\"ok\":true}";
boolean ok = Validason.isValid(json);
```

Validason의 단일 사용 패턴이다.

전체 비교:

![Usability benchmark](/graphs/bench_1000_usability_1000x500x1_b1_h4g_readme_graph_refresh_20260226_r1.png)

- 조건: `length=7-1,000`, length당 500개 JSON, `BENCH_RUNS=1`, `Xms=Xmx=4g`
- 결과:
  - `Validason`: `2.70 us`
  - `JacksonCanonOff`: `3.35 us`
  - `JacksonReadTree`: `11.53 us`
  - `JacksonDefaultCanonOn`: `18.34 us`

동일한 "검증" 작업이라도 Jackson은 구현/설정에 따라 성능 차이가 크고,
Validason은 `isValid` 단일 호출로 같은 목적의 일관된 성능을 제공합니다.

## 3) Accuracy

[JSONTestSuite](https://github.com/nst/JSONTestSuite)(`y_` valid, `n_` invalid) 기준으로 `JacksonCanonOff`와 `JacksonReadTree`를 각각 독립 타깃으로 실행해 정확도를 확인했습니다.

정확도 요약:

- `Validason`: `283/283 (100.00%)`
- `JacksonCanonOff`: `279/283 (98.59%)`
- `JacksonReadTree`: `267/283 (94.35%)`

오판은 모두 Jackson 변형이 `n_`(invalid) 파일을 `true`로 통과시킨 false positive 형태입니다.

### 공통 오판 케이스 (CanonOff + ReadTree)

`JacksonCanonOff`의 4건 오판은 `JacksonReadTree`에도 공통으로 존재합니다.

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

### ReadTree 전용 추가 오판 케이스 (12건)

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

### 오판 타입 정리

- 공백-only 입력 (`single space`)
- 다중 top-level 구조 또는 데이터 없음 (`[][]` 류)
- 완료된 JSON 뒤 trailing garbage/comment/토큰 추가 (`"x"`, `#`, `//`, `/**/`, extra `]`, extra `}`)

반면 Validason은 [JSONTestSuite](https://github.com/nst/JSONTestSuite)에서 제시하는 모든 케이스를 100% 정확도로 통과했습니다.

## 4) Memory

![Memory allocation benchmark](/graphs/memory_alloc_readme_refresh_final_speed_10000x100x1_b21_h8g.png)

- 조건: `length=7-10,000`, length당 100개 JSON, `BENCH_RUNS=21`, `Xms=Xmx=8g`
- 지표: length별 median 할당량 기반 평균 할당량

결과:

- `Jackson`: `11.32 KB`
- `AlwaysEscape`: `5.72 KB`
- `Validason`: `72 B`

Validason은 문자열 검증 목적에서 불필요한 객체 생성을 줄여 메모리 압박을 낮춥니다.

## 5) GC-focused Runs

![GC Jackson Max](/graphs/gc_max_100000x5x1_b1_h32g_readme_refresh_jackson.png)

![GC Validason Max](/graphs/gc_max_100000x5x1_b1_h32g_readme_refresh_validason.png)

GC-focused summary:

- 조건: `length=7-100,000`, length당 5개 JSON, `BENCH_RUNS=1`, `Xms=Xmx=32g`, single-target runs
- `Samples with GC` (bench output): Jackson `15 / 499,970`, Validason `0 / 499,970`
- `gc.log Pause event count`: Jackson `54`, Validason `23`
- `gc.log Pause total`: Jackson `5425.546 ms`, Validason `2867.065 ms`
- `max-by-length latency point`: Jackson `24075.0 us`, Validason `1285.0 us`

## Benchmark Reproducibility

재현 절차는 `benchmark/README.md`에 정리되어 있습니다.

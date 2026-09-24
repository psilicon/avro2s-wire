# Benchmark report

Measured coverage: **171 result rows**, **6 benchmark classes**, **18 workload/parameter groups**. Only explicitly supplied measurements are included.

Timing is JMH average time in **ns/op ± JMH confidence error** (`scoreError`, normally a 99.9% confidence interval half-width). Allocation is `gc.alloc.rate.norm` in **B/op**. Lower means less time or allocation for the measured operation.

**N/A** means an engine or metric was unsupported or unmeasured in the supplied results, or JMH could not estimate a confidence error. It does not mean zero. No missing results, confidence intervals or speedup claims are inferred.

## Result sources

| Source | JMH result file |
| --- | --- |
| 1 | benchmarks/reference/2026-09-17/raw/after-Comparison.json |
| 2 | benchmarks/reference/2026-09-17/raw/after-NestedComparison.json |
| 3 | benchmarks/reference/2026-09-17/raw/after-LogicalComparison.json |
| 4 | benchmarks/reference/2026-09-17/raw/after-DecimalComparison.json |
| 5 | benchmarks/reference/2026-09-17/raw/after-evolution-Evolution.json |
| 6 | benchmarks/reference/2026-09-17/raw/decoded-strings-DecodedString.json |

Source identifies the input file for each measured row. Shared JMH settings do not imply that different source files belong to the same campaign or commit.

## Source metadata

### Metadata 1

| Field | Recorded value |
| --- | --- |
| metadataFile | benchmarks/reference/2026-09-17/raw/after.environment.json |
| startedAt | 2026-09-17T15:20:33.273700+00:00 |
| status | complete |
| profile | comparison |
| sourceRoot | /private/tmp/avro2s-speed-255_nufn/work |
| gitRevision | 5e3f95f85ea397c6f3f6a478279600caa815f6b2 |
| java | /Users/sam/Library/Java/JavaVirtualMachines/corretto-21/Contents/Home/bin/java |
| javaVersion | openjdk version "21" 2023-09-19 LTS<br>OpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)<br>OpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)<br> |
| platform | macOS-15.4-arm64-arm-64bit |
| machine | arm64 |
| cpuCount | 10 |
| pythonVersion | 3.9.6 (default, Apr 30 2025, 02:07:17) <br>[Clang 17.0.0 (clang-1700.0.13.5)] |
| sbtVersion | sbt.version=1.11.0 |
| timing | {"forks":2,"measurementIterations":5,"measurementTime":"500ms","warmupIterations":3,"warmupTime":"500ms"} |
| sourceSha256 | 156 source/build hashes recorded in the metadata file |
| finishedAt | 2026-09-17T15:43:56.145753+00:00 |
| sourceSha256After | 156 source/build hashes recorded in the metadata file |
| sourcesUnchanged | True |

Recorded run coverage:

| Run | Result | Expected cases | Status |
| --- | --- | ---: | --- |
| Comparison | /private/tmp/avro2s-speed-255_nufn/results/after-Comparison.json | 120 | N/A |
| NestedComparison | /private/tmp/avro2s-speed-255_nufn/results/after-NestedComparison.json | 10 | N/A |
| LogicalComparison | /private/tmp/avro2s-speed-255_nufn/results/after-LogicalComparison.json | 10 | N/A |
| DecimalComparison | /private/tmp/avro2s-speed-255_nufn/results/after-DecimalComparison.json | 8 | N/A |

### Metadata 2

| Field | Recorded value |
| --- | --- |
| metadataFile | benchmarks/reference/2026-09-17/raw/after-evolution.environment.json |
| startedAt | 2026-09-17T15:54:35.971569+00:00 |
| status | complete |
| profile | evolution |
| sourceRoot | /private/tmp/avro2s-speed-255_nufn/work |
| gitRevision | 5e3f95f85ea397c6f3f6a478279600caa815f6b2 |
| java | /Users/sam/Library/Java/JavaVirtualMachines/corretto-21/Contents/Home/bin/java |
| javaVersion | openjdk version "21" 2023-09-19 LTS<br>OpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)<br>OpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)<br> |
| platform | macOS-15.4-arm64-arm-64bit |
| machine | arm64 |
| cpuCount | 10 |
| pythonVersion | 3.9.6 (default, Apr 30 2025, 02:07:17) <br>[Clang 17.0.0 (clang-1700.0.13.5)] |
| sbtVersion | sbt.version=1.11.0 |
| timing | {"forks":2,"measurementIterations":5,"measurementTime":"500ms","warmupIterations":3,"warmupTime":"500ms"} |
| sourceSha256 | 156 source/build hashes recorded in the metadata file |
| finishedAt | 2026-09-17T15:56:04.223975+00:00 |
| sourceSha256After | 156 source/build hashes recorded in the metadata file |
| sourcesUnchanged | True |

Recorded run coverage:

| Run | Result | Expected cases | Status |
| --- | --- | ---: | --- |
| Evolution | /private/tmp/avro2s-speed-255_nufn/results/after-evolution-Evolution.json | 8 | N/A |

### Metadata 3

| Field | Recorded value |
| --- | --- |
| metadataFile | benchmarks/reference/2026-09-17/raw/decoded-strings.environment.json |
| startedAt | 2026-09-17T15:05:37.769015+00:00 |
| status | complete |
| profile | decoded-strings |
| sourceRoot | /private/tmp/avro2s-speed-255_nufn/work |
| gitRevision | 6e6c0effdec43d61cd978ec1a970a645459487f2 |
| java | /Users/sam/Library/Java/JavaVirtualMachines/corretto-21/Contents/Home/bin/java |
| javaVersion | openjdk version "21" 2023-09-19 LTS<br>OpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)<br>OpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)<br> |
| platform | macOS-15.4-arm64-arm-64bit |
| machine | arm64 |
| cpuCount | 10 |
| pythonVersion | 3.9.6 (default, Apr 30 2025, 02:07:17) <br>[Clang 17.0.0 (clang-1700.0.13.5)] |
| sbtVersion | sbt.version=1.11.0 |
| timing | {"forks":2,"measurementIterations":5,"measurementTime":"500ms","warmupIterations":3,"warmupTime":"500ms"} |
| sourceSha256 | 155 source/build hashes recorded in the metadata file |
| finishedAt | 2026-09-17T15:08:10.812245+00:00 |
| sourceSha256After | 155 source/build hashes recorded in the metadata file |
| sourcesUnchanged | True |

Recorded run coverage:

| Run | Result | Expected cases | Status |
| --- | --- | ---: | --- |
| DecodedString | /private/tmp/avro2s-speed-255_nufn/results/decoded-strings-DecodedString.json | 15 | N/A |

Metadata is reproduced as provenance, not reverified against the current checkout. Separate manifests may describe different commits or campaigns; their presence does not establish identical environments.

## Measured JMH setups

| Setup | Rows | JVM | JMH settings |
| --- | ---: | --- | --- |
| 1 | 171 | jvm=/Users/sam/Library/Java/JavaVirtualMachines/corretto-21/Contents/Home/bin/java; jdkVersion=21; vmName=OpenJDK 64-Bit Server VM; vmVersion=21+35-LTS; jvmArgs=["-Xms512m","-Xmx512m"] | jmhVersion=1.37; threads=1; forks=2; warmupIterations=3; warmupTime=500 ms; warmupBatchSize=1; measurementIterations=5; measurementTime=500 ms; measurementBatchSize=1 |

Setup numbers identify settings recorded in JMH JSON, not machine identity. Missing settings are N/A. Different setups or campaigns should not be treated as controlled comparisons. Results describe their recorded sources and environment; do not assume they apply to another checkout or machine.

## Measurements

Each table keeps one full benchmark class, exact parameter set and operation. Wire Java primitive backend means Wire codecs over official Java Avro primitive I/O; official Java specific, custom and generic datum implementations remain distinct.

### ComparisonBenchmark — profile=bytes

Full benchmark class: `avro2s.wire.benchmarks.ComparisonBenchmark`.

Wire native and its Java primitive backend use the same generated Wire model. avro2s and official Java readers use their own generated or generic models; string and collection representations can differ. Java default-model readers may return Utf8. Custom coders are a separate baseline.

#### Read

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeRead` | 163.88 ± 13.36 | 4,192.00 | 1 | 1 |
| Wire Java primitive backend | `javaPrimitivesRead` | 327.17 ± 3.49 | 8,400.00 | 1 | 1 |
| avro2s generated Scala | `avro2sRead` | 369.20 ± 20.86 | 8,428.01 | 1 | 1 |
| Official Java specific (default model) | `javaSpecificRead` | 251.74 ± 1.03 | 4,328.00 | 1 | 1 |
| Official Java custom coders (default model) | `javaCustomRead` | 270.33 ± 7.71 | 4,352.00 | 1 | 1 |
| Official Java generic (default model) | `javaGenericRead` | 259.62 ± 10.59 | 4,360.00 | 1 | 1 |

#### Write

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeWrite` | 90.02 ± 13.52 | 0.00 | 1 | 1 |
| Wire Java primitive backend | `javaPrimitivesWrite` | 123.52 ± 4.58 | 0.00 | 1 | 1 |
| avro2s generated Scala | `avro2sWrite` | 339.32 ± 5.08 | 4,224.00 | 1 | 1 |
| Official Java specific (default model) | `javaSpecificWrite` | 333.06 ± 11.19 | 4,140.00 | 1 | 1 |
| Official Java custom coders (default model) | `javaCustomWrite` | 321.20 ± 11.83 | 4,112.00 | 1 | 1 |
| Official Java generic (default model) | `javaGenericWrite` | 342.73 ± 11.37 | 4,168.00 | 1 | 1 |

### ComparisonBenchmark — profile=collections-empty

Full benchmark class: `avro2s.wire.benchmarks.ComparisonBenchmark`.

Wire native and its Java primitive backend use the same generated Wire model. avro2s and official Java readers use their own generated or generic models; string and collection representations can differ. Java default-model readers may return Utf8. Custom coders are a separate baseline.

#### Read

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeRead` | 6.69 ± 0.11 | 72.00 | 1 | 1 |
| Wire Java primitive backend | `javaPrimitivesRead` | 30.23 ± 1.92 | 256.00 | 1 | 1 |
| avro2s generated Scala | `avro2sRead` | 65.46 ± 0.90 | 424.00 | 1 | 1 |
| Official Java specific (default model) | `javaSpecificRead` | 42.55 ± 0.25 | 320.00 | 1 | 1 |
| Official Java custom coders (default model) | `javaCustomRead` | 82.60 ± 6.34 | 344.00 | 1 | 1 |
| Official Java generic (default model) | `javaGenericRead` | 66.30 ± 0.81 | 344.00 | 1 | 1 |

#### Write

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeWrite` | 4.10 ± 0.03 | 0.00 | 1 | 1 |
| Wire Java primitive backend | `javaPrimitivesWrite` | 28.84 ± 0.36 | 0.00 | 1 | 1 |
| avro2s generated Scala | `avro2sWrite` | 48.17 ± 0.90 | 152.00 | 1 | 1 |
| Official Java specific (default model) | `javaSpecificWrite` | 31.45 ± 0.18 | 0.00 | 1 | 1 |
| Official Java custom coders (default model) | `javaCustomWrite` | 28.90 ± 0.45 | 0.00 | 1 | 1 |
| Official Java generic (default model) | `javaGenericWrite` | 31.79 ± 0.76 | 0.00 | 1 | 1 |

### ComparisonBenchmark — profile=collections-full

Full benchmark class: `avro2s.wire.benchmarks.ComparisonBenchmark`.

Wire native and its Java primitive backend use the same generated Wire model. avro2s and official Java readers use their own generated or generic models; string and collection representations can differ. Java default-model readers may return Utf8. Custom coders are a separate baseline.

#### Read

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeRead` | 8,302.19 ± 40.77 | 23,512.11 | 1 | 1 |
| Wire Java primitive backend | `javaPrimitivesRead` | 10,032.41 ± 101.55 | 23,688.14 | 1 | 1 |
| avro2s generated Scala | `avro2sRead` | 16,282.23 ± 234.42 | 51,800.23 | 1 | 1 |
| Official Java specific (default model) | `javaSpecificRead` | 3,053.07 ± 59.16 | 12,336.04 | 1 | 1 |
| Official Java custom coders (default model) | `javaCustomRead` | 4,619.53 ± 205.78 | 11,864.06 | 1 | 1 |
| Official Java generic (default model) | `javaGenericRead` | 3,072.30 ± 103.20 | 12,360.04 | 1 | 1 |

#### Write

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeWrite` | 1,711.50 ± 18.06 | 96.02 | 1 | 1 |
| Wire Java primitive backend | `javaPrimitivesWrite` | 2,506.61 ± 85.60 | 5,216.03 | 1 | 1 |
| avro2s generated Scala | `avro2sWrite` | 4,185.82 ± 141.29 | 11,480.06 | 1 | 1 |
| Official Java specific (default model) | `javaSpecificWrite` | 2,588.64 ± 19.11 | 5,120.04 | 1 | 1 |
| Official Java custom coders (default model) | `javaCustomWrite` | 2,245.93 ± 44.08 | 5,120.03 | 1 | 1 |
| Official Java generic (default model) | `javaGenericWrite` | 2,601.49 ± 35.34 | 5,136.04 | 1 | 1 |

### ComparisonBenchmark — profile=enum-fixed

Full benchmark class: `avro2s.wire.benchmarks.ComparisonBenchmark`.

Wire native and its Java primitive backend use the same generated Wire model. avro2s and official Java readers use their own generated or generic models; string and collection representations can differ. Java default-model readers may return Utf8. Custom coders are a separate baseline.

#### Read

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeRead` | 369.36 ± 2.68 | 2,688.01 | 1 | 1 |
| Wire Java primitive backend | `javaPrimitivesRead` | 424.54 ± 49.25 | 2,776.01 | 1 | 1 |
| avro2s generated Scala | `avro2sRead` | 1,828.59 ± 20.63 | 4,864.03 | 1 | 1 |
| Official Java specific (default model) | `javaSpecificRead` | 1,076.37 ± 10.52 | 2,944.01 | 1 | 1 |
| Official Java custom coders (default model) | `javaCustomRead` | 1,712.73 ± 146.68 | 3,232.02 | 1 | 1 |
| Official Java generic (default model) | `javaGenericRead` | 822.57 ± 3.29 | 2,440.01 | 1 | 1 |

#### Write

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeWrite` | 139.24 ± 1.41 | 0.00 | 1 | 1 |
| Wire Java primitive backend | `javaPrimitivesWrite` | 182.90 ± 2.72 | 0.00 | 1 | 1 |
| avro2s generated Scala | `avro2sWrite` | 1,026.73 ± 102.78 | 2,184.01 | 1 | 1 |
| Official Java specific (default model) | `javaSpecificWrite` | 432.71 ± 7.97 | 32.01 | 1 | 1 |
| Official Java custom coders (default model) | `javaCustomWrite` | 181.96 ± 6.79 | 0.00 | 1 | 1 |
| Official Java generic (default model) | `javaGenericWrite` | 522.19 ± 16.01 | 0.01 | 1 | 1 |

### ComparisonBenchmark — profile=ints-small

Full benchmark class: `avro2s.wire.benchmarks.ComparisonBenchmark`.

Wire native and its Java primitive backend use the same generated Wire model. avro2s and official Java readers use their own generated or generic models; string and collection representations can differ. Java default-model readers may return Utf8. Custom coders are a separate baseline.

#### Read

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeRead` | 3,452.75 ± 74.05 | 5,072.05 | 1 | 1 |
| Wire Java primitive backend | `javaPrimitivesRead` | 3,542.88 ± 44.16 | 5,176.05 | 1 | 1 |
| avro2s generated Scala | `avro2sRead` | 11,617.71 ± 97.15 | 33,160.16 | 1 | 1 |
| Official Java specific (default model) | `javaSpecificRead` | 3,859.11 ± 53.15 | 4,344.05 | 1 | 1 |
| Official Java custom coders (default model) | `javaCustomRead` | 14,097.62 ± 313.92 | 4,368.19 | 1 | 1 |
| Official Java generic (default model) | `javaGenericRead` | 3,863.09 ± 33.52 | 4,376.05 | 1 | 1 |

#### Write

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeWrite` | 1,622.67 ± 23.00 | 80.02 | 1 | 1 |
| Wire Java primitive backend | `javaPrimitivesWrite` | 2,727.70 ± 240.97 | 80.04 | 1 | 1 |
| avro2s generated Scala | `avro2sWrite` | 15,079.17 ± 1,443.78 | 32,968.21 | 1 | 1 |
| Official Java specific (default model) | `javaSpecificWrite` | 4,477.43 ± 118.51 | 16.06 | 1 | 1 |
| Official Java custom coders (default model) | `javaCustomWrite` | 1,074.04 ± 25.75 | 0.01 | 1 | 1 |
| Official Java generic (default model) | `javaGenericWrite` | 4,438.08 ± 90.64 | 0.06 | 1 | 1 |

### ComparisonBenchmark — profile=ints-wide

Full benchmark class: `avro2s.wire.benchmarks.ComparisonBenchmark`.

Wire native and its Java primitive backend use the same generated Wire model. avro2s and official Java readers use their own generated or generic models; string and collection representations can differ. Java default-model readers may return Utf8. Custom coders are a separate baseline.

#### Read

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeRead` | 4,565.04 ± 10.59 | 21,456.06 | 1 | 1 |
| Wire Java primitive backend | `javaPrimitivesRead` | 4,568.86 ± 32.05 | 21,560.06 | 1 | 1 |
| avro2s generated Scala | `avro2sRead` | 12,926.09 ± 271.52 | 65,928.18 | 1 | 1 |
| Official Java specific (default model) | `javaSpecificRead` | 5,088.25 ± 188.28 | 20,728.07 | 1 | 1 |
| Official Java custom coders (default model) | `javaCustomRead` | 16,096.19 ± 595.73 | 20,752.22 | 1 | 1 |
| Official Java generic (default model) | `javaGenericRead` | 5,077.71 ± 35.63 | 20,760.07 | 1 | 1 |

#### Write

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeWrite` | 2,582.82 ± 46.70 | 80.03 | 1 | 1 |
| Wire Java primitive backend | `javaPrimitivesWrite` | 4,970.32 ± 2,001.69 | 80.07 | 1 | 1 |
| avro2s generated Scala | `avro2sWrite` | 15,497.76 ± 94.88 | 49,352.21 | 1 | 1 |
| Official Java specific (default model) | `javaSpecificWrite` | 5,871.47 ± 83.73 | 32.08 | 1 | 1 |
| Official Java custom coders (default model) | `javaCustomWrite` | 3,335.48 ± 16.43 | 0.05 | 1 | 1 |
| Official Java generic (default model) | `javaGenericWrite` | 5,843.95 ± 117.90 | 0.08 | 1 | 1 |

### ComparisonBenchmark — profile=longs-mixed

Full benchmark class: `avro2s.wire.benchmarks.ComparisonBenchmark`.

Wire native and its Java primitive backend use the same generated Wire model. avro2s and official Java readers use their own generated or generic models; string and collection representations can differ. Java default-model readers may return Utf8. Custom coders are a separate baseline.

#### Read

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeRead` | 5,821.92 ± 53.07 | 27,488.08 | 1 | 1 |
| Wire Java primitive backend | `javaPrimitivesRead` | 5,250.32 ± 77.12 | 27,592.07 | 1 | 1 |
| avro2s generated Scala | `avro2sRead` | 14,604.19 ± 792.35 | 77,992.20 | 1 | 1 |
| Official Java specific (default model) | `javaSpecificRead` | 6,077.44 ± 35.94 | 26,760.08 | 1 | 1 |
| Official Java custom coders (default model) | `javaCustomRead` | 20,541.29 ± 3,845.32 | 26,784.28 | 1 | 1 |
| Official Java generic (default model) | `javaGenericRead` | 6,106.83 ± 68.33 | 26,792.08 | 1 | 1 |

#### Write

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeWrite` | 3,388.89 ± 74.81 | 80.05 | 1 | 1 |
| Wire Java primitive backend | `javaPrimitivesWrite` | 4,777.79 ± 82.83 | 80.07 | 1 | 1 |
| avro2s generated Scala | `avro2sWrite` | 17,137.54 ± 275.92 | 55,384.24 | 1 | 1 |
| Official Java specific (default model) | `javaSpecificWrite` | 6,504.14 ± 97.83 | 32.09 | 1 | 1 |
| Official Java custom coders (default model) | `javaCustomWrite` | 3,944.39 ± 101.05 | 0.05 | 1 | 1 |
| Official Java generic (default model) | `javaGenericWrite` | 6,505.30 ± 136.44 | 32.09 | 1 | 1 |

### ComparisonBenchmark — profile=numerics

Full benchmark class: `avro2s.wire.benchmarks.ComparisonBenchmark`.

Wire native and its Java primitive backend use the same generated Wire model. avro2s and official Java readers use their own generated or generic models; string and collection representations can differ. Java default-model readers may return Utf8. Custom coders are a separate baseline.

#### Read

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeRead` | 1,169.32 ± 9.90 | 7,800.02 | 1 | 1 |
| Wire Java primitive backend | `javaPrimitivesRead` | 1,657.79 ± 57.54 | 7,856.02 | 1 | 1 |
| avro2s generated Scala | `avro2sRead` | 6,626.53 ± 31.37 | 23,224.09 | 1 | 1 |
| Official Java specific (default model) | `javaSpecificRead` | 2,476.75 ± 17.31 | 7,064.03 | 1 | 1 |
| Official Java custom coders (default model) | `javaCustomRead` | 6,674.35 ± 874.18 | 7,048.09 | 1 | 1 |
| Official Java generic (default model) | `javaGenericRead` | 2,460.86 ± 13.39 | 7,088.03 | 1 | 1 |

#### Write

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeWrite` | 326.79 ± 5.62 | 0.00 | 1 | 1 |
| Wire Java primitive backend | `javaPrimitivesWrite` | 555.47 ± 7.23 | 0.01 | 1 | 1 |
| avro2s generated Scala | `avro2sWrite` | 5,696.59 ± 23.77 | 17,640.08 | 1 | 1 |
| Official Java specific (default model) | `javaSpecificWrite` | 1,895.63 ± 15.27 | 40.03 | 1 | 1 |
| Official Java custom coders (default model) | `javaCustomWrite` | 594.07 ± 5.00 | 0.01 | 1 | 1 |
| Official Java generic (default model) | `javaGenericWrite` | 1,898.74 ± 19.66 | 0.03 | 1 | 1 |

### ComparisonBenchmark — profile=string-ascii

Full benchmark class: `avro2s.wire.benchmarks.ComparisonBenchmark`.

Wire native and its Java primitive backend use the same generated Wire model. avro2s and official Java readers use their own generated or generic models; string and collection representations can differ. Java default-model readers may return Utf8. Custom coders are a separate baseline.

#### Read

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeRead` | 54.13 ± 0.22 | 1,128.00 | 1 | 1 |
| Wire Java primitive backend | `javaPrimitivesRead` | 106.33 ± 1.20 | 2,264.00 | 1 | 1 |
| avro2s generated Scala | `avro2sRead` | 116.68 ± 1.02 | 2,264.00 | 1 | 1 |
| Official Java specific (default model) | `javaSpecificRead` | 69.31 ± 4.21 | 1,232.00 | 1 | 1 |
| Official Java custom coders (default model) | `javaCustomRead` | 95.31 ± 1.14 | 1,256.00 | 1 | 1 |
| Official Java generic (default model) | `javaGenericRead` | 67.64 ± 0.33 | 1,264.00 | 1 | 1 |

#### Write

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeWrite` | 110.85 ± 0.26 | 1,040.00 | 1 | 1 |
| Wire Java primitive backend | `javaPrimitivesWrite` | 92.59 ± 0.26 | 1,040.00 | 1 | 1 |
| avro2s generated Scala | `avro2sWrite` | 104.70 ± 9.66 | 1,040.00 | 1 | 1 |
| Official Java specific (default model) | `javaSpecificWrite` | 97.96 ± 0.42 | 1,040.00 | 1 | 1 |
| Official Java custom coders (default model) | `javaCustomWrite` | 95.02 ± 2.73 | 1,040.00 | 1 | 1 |
| Official Java generic (default model) | `javaGenericWrite` | 98.07 ± 1.54 | 1,040.00 | 1 | 1 |

### ComparisonBenchmark — profile=string-unicode

Full benchmark class: `avro2s.wire.benchmarks.ComparisonBenchmark`.

Wire native and its Java primitive backend use the same generated Wire model. avro2s and official Java readers use their own generated or generic models; string and collection representations can differ. Java default-model readers may return Utf8. Custom coders are a separate baseline.

#### Read

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeRead` | 2,444.73 ± 40.17 | 11,144.03 | 1 | 1 |
| Wire Java primitive backend | `javaPrimitivesRead` | 2,269.00 ± 5.60 | 14,072.03 | 1 | 1 |
| avro2s generated Scala | `avro2sRead` | 2,379.68 ± 34.51 | 14,072.03 | 1 | 1 |
| Official Java specific (default model) | `javaSpecificRead` | 148.98 ± 0.64 | 3,024.00 | 1 | 1 |
| Official Java custom coders (default model) | `javaCustomRead` | 174.23 ± 3.17 | 3,048.00 | 1 | 1 |
| Official Java generic (default model) | `javaGenericRead` | 151.01 ± 0.95 | 3,056.00 | 1 | 1 |

#### Write

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeWrite` | 2,003.33 ± 21.82 | 6,688.03 | 1 | 1 |
| Wire Java primitive backend | `javaPrimitivesWrite` | 1,889.94 ± 10.13 | 6,688.03 | 1 | 1 |
| avro2s generated Scala | `avro2sWrite` | 1,964.42 ± 33.35 | 6,688.03 | 1 | 1 |
| Official Java specific (default model) | `javaSpecificWrite` | 1,894.01 ± 12.03 | 6,688.03 | 1 | 1 |
| Official Java custom coders (default model) | `javaCustomWrite` | 1,920.87 ± 38.77 | 6,688.03 | 1 | 1 |
| Official Java generic (default model) | `javaGenericWrite` | 1,894.11 ± 33.67 | 6,688.03 | 1 | 1 |

### DecimalComparisonBenchmark — no parameters

Full benchmark class: `avro2s.wire.benchmarks.DecimalComparisonBenchmark`.

Wire native and its Java primitive backend use the same generated Wire model. avro2s and official Java readers use their own generated or generic models; string and collection representations can differ. Java default-model readers may return Utf8. Custom coders are a separate baseline.

#### Read

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeRead` | 86.59 ± 0.51 | 544.00 | 1 | 4 |
| Wire Java primitive backend | `javaPrimitivesRead` | 92.72 ± 0.69 | 680.00 | 1 | 4 |
| avro2s generated Scala | N/A | N/A | N/A | N/A | N/A |
| Official Java specific (default model) | `javaSpecificRead` | 155.92 ± 0.82 | 688.00 | 1 | 4 |
| Official Java custom coders (default model) | N/A | N/A | N/A | N/A | N/A |
| Official Java generic (default model) | `javaGenericRead` | 128.51 ± 2.77 | 696.00 | 1 | 4 |

#### Write

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeWrite` | 53.24 ± 3.46 | 128.00 | 1 | 4 |
| Wire Java primitive backend | `javaPrimitivesWrite` | 90.10 ± 87.71 | 128.00 | 1 | 4 |
| avro2s generated Scala | N/A | N/A | N/A | N/A | N/A |
| Official Java specific (default model) | `javaSpecificWrite` | 185.83 ± 8.68 | 304.00 | 1 | 4 |
| Official Java custom coders (default model) | N/A | N/A | N/A | N/A | N/A |
| Official Java generic (default model) | `javaGenericWrite` | 180.54 ± 13.61 | 248.00 | 1 | 4 |

### DecodedStringBenchmark — profile=collections-full

Full benchmark class: `avro2s.wire.benchmarks.DecodedStringBenchmark`.

The measured read variants materialize java.lang.String for text fields and map keys. Java String-result variants are explicit rows, separate from default-model readers. Record and collection representations still differ.

#### Read

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeRead` | 8,264.29 ± 64.54 | 23,512.11 | 1 | 6 |
| Wire Java primitive backend | `javaPrimitivesRead` | 10,146.50 ± 181.16 | 23,688.14 | 1 | 6 |
| avro2s generated Scala | `avro2sRead` | 15,702.52 ± 267.26 | 51,800.21 | 1 | 6 |
| Official Java specific (default model) | N/A | N/A | N/A | N/A | N/A |
| Official Java custom coders (default model) | N/A | N/A | N/A | N/A | N/A |
| Official Java generic (default model) | N/A | N/A | N/A | N/A | N/A |
| Official Java specific (String results) | `javaSpecificStringRead` | 7,126.05 ± 39.93 | 16,520.10 | 1 | 6 |
| Official Java generic (String results) | `javaGenericStringRead` | 7,204.78 ± 128.38 | 16,544.10 | 1 | 6 |

### DecodedStringBenchmark — profile=string-ascii

Full benchmark class: `avro2s.wire.benchmarks.DecodedStringBenchmark`.

The measured read variants materialize java.lang.String for text fields and map keys. Java String-result variants are explicit rows, separate from default-model readers. Record and collection representations still differ.

#### Read

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeRead` | 54.47 ± 0.56 | 1,128.00 | 1 | 6 |
| Wire Java primitive backend | `javaPrimitivesRead` | 108.19 ± 3.29 | 2,264.00 | 1 | 6 |
| avro2s generated Scala | `avro2sRead` | 114.66 ± 1.16 | 2,264.00 | 1 | 6 |
| Official Java specific (default model) | N/A | N/A | N/A | N/A | N/A |
| Official Java custom coders (default model) | N/A | N/A | N/A | N/A | N/A |
| Official Java generic (default model) | N/A | N/A | N/A | N/A | N/A |
| Official Java specific (String results) | `javaSpecificStringRead` | 117.10 ± 0.80 | 2,264.00 | 1 | 6 |
| Official Java generic (String results) | `javaGenericStringRead` | 115.35 ± 2.18 | 2,296.00 | 1 | 6 |

### DecodedStringBenchmark — profile=string-unicode

Full benchmark class: `avro2s.wire.benchmarks.DecodedStringBenchmark`.

The measured read variants materialize java.lang.String for text fields and map keys. Java String-result variants are explicit rows, separate from default-model readers. Record and collection representations still differ.

#### Read

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeRead` | 2,444.07 ± 26.25 | 11,144.03 | 1 | 6 |
| Wire Java primitive backend | `javaPrimitivesRead` | 2,292.58 ± 12.80 | 14,072.03 | 1 | 6 |
| avro2s generated Scala | `avro2sRead` | 2,299.71 ± 17.03 | 14,072.03 | 1 | 6 |
| Official Java specific (default model) | N/A | N/A | N/A | N/A | N/A |
| Official Java custom coders (default model) | N/A | N/A | N/A | N/A | N/A |
| Official Java generic (default model) | N/A | N/A | N/A | N/A | N/A |
| Official Java specific (String results) | `javaSpecificStringRead` | 2,357.38 ± 56.82 | 14,072.03 | 1 | 6 |
| Official Java generic (String results) | `javaGenericStringRead` | 2,324.51 ± 14.74 | 14,104.03 | 1 | 6 |

### EvolutionBenchmark — discardedBytes=0

Full benchmark class: `avro2s.wire.benchmarks.EvolutionBenchmark`.

Only the two warm resolution methods perform writer-to-reader schema resolution. Same-schema decoding returns the old model and does different work; cold plan construction does not read a datum. The native resolving reader returns a generated immutable model; Java returns GenericRecord/Utf8.

#### Warm schema resolution

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeResolved` | 316.40 ± 9.53 | 640.00 | 1 | 5 |
| Official Java generic resolving reader (GenericRecord/Utf8) | `javaResolved` | 366.05 ± 14.80 | 1,840.00 | 1 | 5 |

#### Same-schema read baseline (different work)

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeSameSchema` | 119.78 ± 18.16 | 432.00 | 1 | 5 |

#### Cold resolution-plan construction (different work)

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `compileResolution` | 23,203.58 ± 607.94 | 129,816.52 | 1 | 5 |

### EvolutionBenchmark — discardedBytes=4096

Full benchmark class: `avro2s.wire.benchmarks.EvolutionBenchmark`.

Only the two warm resolution methods perform writer-to-reader schema resolution. Same-schema decoding returns the old model and does different work; cold plan construction does not read a datum. The native resolving reader returns a generated immutable model; Java returns GenericRecord/Utf8.

#### Warm schema resolution

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeResolved` | 310.81 ± 5.20 | 640.00 | 1 | 5 |
| Official Java generic resolving reader (GenericRecord/Utf8) | `javaResolved` | 365.39 ± 14.04 | 1,840.00 | 1 | 5 |

#### Same-schema read baseline (different work)

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeSameSchema` | 207.84 ± 10.12 | 4,544.00 | 1 | 5 |

#### Cold resolution-plan construction (different work)

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `compileResolution` | 22,928.20 ± 321.04 | 130,300.52 | 1 | 5 |

### LogicalComparisonBenchmark — no parameters

Full benchmark class: `avro2s.wire.benchmarks.LogicalComparisonBenchmark`.

Wire native and its Java primitive backend use the same generated Wire model. avro2s and official Java readers use their own generated or generic models; string and collection representations can differ. Java default-model readers may return Utf8. Custom coders are a separate baseline.

#### Read

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeRead` | 127.34 ± 1.29 | 472.00 | 1 | 3 |
| Wire Java primitive backend | `javaPrimitivesRead` | 151.55 ± 1.41 | 640.00 | 1 | 3 |
| avro2s generated Scala | `avro2sRead` | 227.59 ± 1.24 | 1,008.00 | 1 | 3 |
| Official Java specific (default model) | `javaSpecificRead` | 177.97 ± 0.80 | 808.00 | 1 | 3 |
| Official Java custom coders (default model) | N/A | N/A | N/A | N/A | N/A |
| Official Java generic (default model) | `javaGenericRead` | 174.82 ± 1.68 | 832.00 | 1 | 3 |

#### Write

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeWrite` | 72.88 ± 2.53 | 112.00 | 1 | 3 |
| Wire Java primitive backend | `javaPrimitivesWrite` | 92.62 ± 1.15 | 56.00 | 1 | 3 |
| avro2s generated Scala | `avro2sWrite` | 190.68 ± 2.94 | 232.00 | 1 | 3 |
| Official Java specific (default model) | `javaSpecificWrite` | 191.09 ± 1.50 | 232.00 | 1 | 3 |
| Official Java custom coders (default model) | N/A | N/A | N/A | N/A | N/A |
| Official Java generic (default model) | `javaGenericWrite` | 289.12 ± 2.41 | 232.00 | 1 | 3 |

### NestedComparisonBenchmark — no parameters

Full benchmark class: `avro2s.wire.benchmarks.NestedComparisonBenchmark`.

Wire native and its Java primitive backend use the same generated Wire model. avro2s and official Java readers use their own generated or generic models; string and collection representations can differ. Java default-model readers may return Utf8. Custom coders are a separate baseline.

#### Read

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeRead` | 1,172.48 ± 18.72 | 9,920.02 | 1 | 2 |
| Wire Java primitive backend | `javaPrimitivesRead` | 1,356.23 ± 40.58 | 9,432.02 | 1 | 2 |
| avro2s generated Scala | `avro2sRead` | 3,429.83 ± 38.56 | 13,176.05 | 1 | 2 |
| Official Java specific (default model) | `javaSpecificRead` | 2,186.04 ± 103.52 | 4,192.03 | 1 | 2 |
| Official Java custom coders (default model) | N/A | N/A | N/A | N/A | N/A |
| Official Java generic (default model) | `javaGenericRead` | 2,949.85 ± 175.70 | 4,912.04 | 1 | 2 |

#### Write

| Engine / result model | Method | ns/op ± JMH error | B/op | Setup | Source |
| --- | --- | ---: | ---: | ---: | ---: |
| Wire native | `nativeWrite` | 651.31 ± 21.05 | 160.01 | 1 | 2 |
| Wire Java primitive backend | `javaPrimitivesWrite` | 1,218.66 ± 218.77 | 1,288.02 | 1 | 2 |
| avro2s generated Scala | `avro2sWrite` | 3,592.74 ± 60.13 | 7,056.05 | 1 | 2 |
| Official Java specific (default model) | `javaSpecificWrite` | 2,053.35 ± 95.25 | 1,488.03 | 1 | 2 |
| Official Java custom coders (default model) | N/A | N/A | N/A | N/A | N/A |
| Official Java generic (default model) | `javaGenericWrite` | 2,008.27 ± 63.43 | 1,128.03 | 1 | 2 |

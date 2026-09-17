# Native performance comparison — measured report inputs

This generated report keeps the original target set fixed, uses the fresh native before run for change estimates, and ranks **current** Java datum implementations only when the final comparison is supplied. Historical Java measurements are context, not a concurrent control.

Coverage: historical 148/148; fresh native before 26/26; final comparison 148/148; final extra controls 83; separate before confirmations 2; separate after confirmations 2; supplementary-control pilot 10 recorded (4 new cases).

Times are ns/op (lower is better); allocations are B/op. `±` is the error reported by JMH (its default 99.9% confidence interval). Ratios are ratios of means, without ratio confidence intervals. Native/Java greater than 1 means native is slower. Selecting the lowest Java mean does not prove that Java implementation is statistically faster than its alternatives. Separate/overlapping CIs are descriptive; they are not a paired significance test.

## Input and interpretation cautions

- extra controls: 26 paired cases have different JMH/JVM protocols; changed fields: forks, measurementIterations, measurementTime, warmupTime. Treat changes as descriptive, not independently confirmed improvements.

## Original 13 deficits (historical context only)

Frozen from the original broad comparison. The best Java datum implementation is selected from generic, specific and generated custom coders where supported; JavaPrimitives is a separate same-generated-model control.

| Workload | Op | Old native | Old fastest Java | Old Java | N/J | Old JavaPrimitives | N/JP | B/op native / Java / JP |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| ints-small | write | 2,340.1 ± 15.5 | javaCustom | 1,820.5 ± 18.1 | 1.285 | 2,627.4 ± 32.3 | 0.891 | 80.0 / 0.0 / 80.0 |
| ints-wide | read | 7,798.3 ± 346.5 | javaGeneric | 5,120.7 ± 44.6 | 1.523 | 4,810.6 ± 170.9 | 1.621 | 21,456.1 / 20,760.1 / 21,560.1 |
| ints-wide | write | 4,498.6 ± 17.1 | javaCustom | 3,316.9 ± 14.6 | 1.356 | 4,183.9 ± 36.3 | 1.075 | 80.1 / 0.0 / 80.1 |
| longs-mixed | read | 7,949.8 ± 68.3 | javaGeneric | 6,167.1 ± 45.5 | 1.289 | 5,425.7 ± 280.4 | 1.465 | 27,488.1 / 26,792.1 / 27,592.1 |
| longs-mixed | write | 5,271.9 ± 10.2 | javaCustom | 3,891.0 ± 16.9 | 1.355 | 4,853.2 ± 75.9 | 1.086 | 80.1 / 0.1 / 80.1 |
| string-ascii | read | 234.9 ± 0.6 | javaGeneric | 67.6 ± 0.6 | 3.474 | 106.7 ± 0.3 | 2.201 | 1,128.0 / 1,264.0 / 2,264.0 |
| string-ascii | write | 391.8 ± 1.4 | javaCustom | 95.5 ± 0.3 | 4.104 | 94.2 ± 0.7 | 4.161 | 0.0 / 1,040.0 / 1,040.0 |
| string-unicode | read | 4,211.3 ± 18.7 | javaSpecific | 151.9 ± 7.4 | 27.717 | 2,335.7 ± 8.0 | 1.803 | 11,144.1 / 3,024.0 / 14,072.0 |
| string-unicode | write | 3,706.2 ± 50.1 | javaSpecific | 1,925.5 ± 9.3 | 1.925 | 1,920.0 ± 7.1 | 1.930 | 0.1 / 6,688.0 / 6,688.0 |
| collections-full | read | 9,705.8 ± 105.5 | javaSpecific | 3,116.5 ± 50.1 | 3.114 | 10,733.8 ± 69.6 | 0.904 | 23,668.1 / 12,336.0 / 23,832.1 |
| collections-full | write | 2,595.5 ± 17.4 | javaCustom | 2,228.5 ± 7.6 | 1.165 | 3,037.1 ± 180.2 | 0.855 | 240.0 / 5,120.0 / 5,360.0 |
| numerics | write | 1,414.0 ± 47.8 | javaCustom | 595.0 ± 5.5 | 2.377 | 1,200.9 ± 12.2 | 1.177 | 240.0 / 0.0 / 240.0 |
| logical | read | 203.1 ± 14.8 | javaGeneric | 172.2 ± 2.9 | 1.180 | 210.5 ± 2.5 | 0.965 | 472.0 / 832.0 / 640.0 |

## All 26 native before/after cases and current Java controls

The before column uses the fresh before run, not the older historical run. This table includes previously winning cases so regressions cannot disappear from a targeted report.

| Workload | Op | Fresh before | Final native | Time Δ | After vs before CI | Native B/op before → after | Current fastest Java | Current Java | N/J | Native vs Java CI | Current JavaPrimitives | N/JP |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| ints-small | read | 3,647.3 ± 15.9 | 3,452.7 ± 74.1 | -5.3% | lower, separate CIs | 5,072.1 → 5,072.0 | javaSpecific | 3,859.1 ± 53.2 | 0.895 | lower, separate CIs | 3,542.9 ± 44.2 | 0.975 |
| ints-small | write | 2,340.8 ± 21.1 | 1,622.7 ± 23.0 | -30.7% | lower, separate CIs | 80.0 → 80.0 | javaCustom | 1,074.0 ± 25.8 | 1.511 | higher, separate CIs | 2,727.7 ± 241.0 | 0.595 |
| ints-wide | read | 7,830.5 ± 107.2 | 4,565.0 ± 10.6 | -41.7% | lower, separate CIs | 21,456.1 → 21,456.1 | javaGeneric | 5,077.7 ± 35.6 | 0.899 | lower, separate CIs | 4,568.9 ± 32.0 | 0.999 |
| ints-wide | write | 4,525.1 ± 21.8 | 2,582.8 ± 46.7 | -42.9% | lower, separate CIs | 80.1 → 80.0 | javaCustom | 3,335.5 ± 16.4 | 0.774 | lower, separate CIs | 4,970.3 ± 2,001.7 | 0.520 |
| longs-mixed | read | 8,090.9 ± 49.7 | 5,821.9 ± 53.1 | -28.0% | lower, separate CIs | 27,488.1 → 27,488.1 | javaSpecific | 6,077.4 ± 35.9 | 0.958 | lower, separate CIs | 5,250.3 ± 77.1 | 1.109 |
| longs-mixed | write | 5,310.2 ± 38.4 | 3,388.9 ± 74.8 | -36.2% | lower, separate CIs | 80.1 → 80.0 | javaCustom | 3,944.4 ± 101.1 | 0.859 | lower, separate CIs | 4,777.8 ± 82.8 | 0.709 |
| string-ascii | read | 238.1 ± 3.1 | 54.1 ± 0.2 | -77.3% | lower, separate CIs | 1,128.0 → 1,128.0 | javaGeneric | 67.6 ± 0.3 | 0.800 | lower, separate CIs | 106.3 ± 1.2 | 0.509 |
| string-ascii | write | 393.8 ± 3.7 | 110.9 ± 0.3 | -71.8% | lower, separate CIs | 0.0 → 1,040.0 | javaCustom | 95.0 ± 2.7 | 1.167 | higher, separate CIs | 92.6 ± 0.3 | 1.197 |
| string-unicode | read | 4,262.3 ± 39.1 | 2,444.7 ± 40.2 | -42.6% | lower, separate CIs | 11,144.1 → 11,144.0 | javaSpecific | 149.0 ± 0.6 | 16.409 | higher, separate CIs | 2,269.0 ± 5.6 | 1.077 |
| string-unicode | write | 3,720.8 ± 59.8 | 2,003.3 ± 21.8 | -46.2% | lower, separate CIs | 0.1 → 6,688.0 | javaSpecific | 1,894.0 ± 12.0 | 1.058 | higher, separate CIs | 1,889.9 ± 10.1 | 1.060 |
| bytes | read | 174.2 ± 4.2 | 163.9 ± 13.4 | -5.9% | overlapping CIs | 4,192.0 → 4,192.0 | javaSpecific | 251.7 ± 1.0 | 0.651 | lower, separate CIs | 327.2 ± 3.5 | 0.501 |
| bytes | write | 79.5 ± 1.1 | 90.0 ± 13.5 | +13.2% | overlapping CIs | 0.0 → 0.0 | javaCustom | 321.2 ± 11.8 | 0.280 | lower, separate CIs | 123.5 ± 4.6 | 0.729 |
| collections-empty | read | 7.1 ± 0.0 | 6.7 ± 0.1 | -5.9% | lower, separate CIs | 72.0 → 72.0 | javaSpecific | 42.6 ± 0.3 | 0.157 | lower, separate CIs | 30.2 ± 1.9 | 0.221 |
| collections-empty | write | 4.2 ± 0.0 | 4.1 ± 0.0 | -2.2% | lower, separate CIs | 0.0 → 0.0 | javaCustom | 28.9 ± 0.4 | 0.142 | lower, separate CIs | 28.8 ± 0.4 | 0.142 |
| collections-full | read | 9,909.4 ± 422.2 | 8,302.2 ± 40.8 | -16.2% | lower, separate CIs | 23,656.1 → 23,512.1 | javaSpecific | 3,053.1 ± 59.2 | 2.719 | higher, separate CIs | 10,032.4 ± 101.5 | 0.828 |
| collections-full | write | 2,596.2 ± 19.0 | 1,711.5 ± 18.1 | -34.1% | lower, separate CIs | 240.0 → 96.0 | javaCustom | 2,245.9 ± 44.1 | 0.762 | lower, separate CIs | 2,506.6 ± 85.6 | 0.683 |
| enum-fixed | read | 401.2 ± 5.0 | 369.4 ± 2.7 | -7.9% | lower, separate CIs | 2,688.0 → 2,688.0 | javaGeneric | 822.6 ± 3.3 | 0.449 | lower, separate CIs | 424.5 ± 49.2 | 0.870 |
| enum-fixed | write | 161.1 ± 1.6 | 139.2 ± 1.4 | -13.6% | lower, separate CIs | 0.0 → 0.0 | javaCustom | 182.0 ± 6.8 | 0.765 | lower, separate CIs | 182.9 ± 2.7 | 0.761 |
| numerics | read | 1,483.9 ± 21.2 | 1,169.3 ± 9.9 | -21.2% | lower, separate CIs | 7,800.0 → 7,800.0 | javaGeneric | 2,460.9 ± 13.4 | 0.475 | lower, separate CIs | 1,657.8 ± 57.5 | 0.705 |
| numerics | write | 1,390.6 ± 51.6 | 326.8 ± 5.6 | -76.5% | lower, separate CIs | 240.0 → 0.0 | javaCustom | 594.1 ± 5.0 | 0.550 | lower, separate CIs | 555.5 ± 7.2 | 0.588 |
| nested | read | 1,305.9 ± 9.7 | 1,172.5 ± 18.7 | -10.2% | lower, separate CIs | 9,920.0 → 9,920.0 | javaSpecific | 2,186.0 ± 103.5 | 0.536 | lower, separate CIs | 1,356.2 ± 40.6 | 0.865 |
| nested | write | 671.5 ± 44.8 | 651.3 ± 21.1 | -3.0% | overlapping CIs | 1,760.0 → 160.0 | javaGeneric | 2,008.3 ± 63.4 | 0.324 | lower, separate CIs | 1,218.7 ± 218.8 | 0.534 |
| logical | read | 199.8 ± 2.8 | 127.3 ± 1.3 | -36.3% | lower, separate CIs | 472.0 → 472.0 | javaGeneric | 174.8 ± 1.7 | 0.728 | lower, separate CIs | 151.5 ± 1.4 | 0.840 |
| logical | write | 79.3 ± 0.2 | 72.9 ± 2.5 | -8.0% | lower, separate CIs | 80.0 → 112.0 | javaSpecific | 191.1 ± 1.5 | 0.381 | lower, separate CIs | 92.6 ± 1.2 | 0.787 |
| decimal | read | 89.0 ± 0.3 | 86.6 ± 0.5 | -2.7% | lower, separate CIs | 544.0 → 544.0 | javaGeneric | 128.5 ± 2.8 | 0.674 | lower, separate CIs | 92.7 ± 0.7 | 0.934 |
| decimal | write | 54.9 ± 3.0 | 53.2 ± 3.5 | -3.0% | overlapping CIs | 128.0 → 128.0 | javaGeneric | 180.5 ± 13.6 | 0.295 | lower, separate CIs | 90.1 ± 87.7 | 0.591 |

## Original deficit status using current Java

| Workload | Op | Mean comparison | Current Java | N/J | CI comparison | B/op native / Java |
| --- | --- | --- | --- | --- | --- | --- |
| ints-small | write | native higher mean | javaCustom | 1.511 | higher, separate CIs | 80.0 / 0.0 |
| ints-wide | read | native lower mean | javaGeneric | 0.899 | lower, separate CIs | 21,456.1 / 20,760.1 |
| ints-wide | write | native lower mean | javaCustom | 0.774 | lower, separate CIs | 80.0 / 0.0 |
| longs-mixed | read | native lower mean | javaSpecific | 0.958 | lower, separate CIs | 27,488.1 / 26,760.1 |
| longs-mixed | write | native lower mean | javaCustom | 0.859 | lower, separate CIs | 80.0 / 0.1 |
| string-ascii | read | native lower mean | javaGeneric | 0.800 | lower, separate CIs | 1,128.0 / 1,264.0 |
| string-ascii | write | native higher mean | javaCustom | 1.167 | higher, separate CIs | 1,040.0 / 1,040.0 |
| string-unicode | read | native higher mean | javaSpecific | 16.409 | higher, separate CIs | 11,144.0 / 3,024.0 |
| string-unicode | write | native higher mean | javaSpecific | 1.058 | higher, separate CIs | 6,688.0 / 6,688.0 |
| collections-full | read | native higher mean | javaSpecific | 2.719 | higher, separate CIs | 23,512.1 / 12,336.0 |
| collections-full | write | native lower mean | javaCustom | 0.762 | lower, separate CIs | 96.0 / 5,120.0 |
| numerics | write | native lower mean | javaCustom | 0.550 | lower, separate CIs | 0.0 / 0.0 |
| logical | read | native lower mean | javaGeneric | 0.728 | lower, separate CIs | 472.0 / 832.0 |

Available original targets: 8 native lower/equal means; 5 native higher means; 0 have overlapping native/selected-Java confidence intervals. 0 remain pending. These counts are descriptive, not a claim that every mean difference is repeatable.

### Previously winning cases with a new higher native mean

None among the available final cases.

## Equivalently materialized String controls

Default Java datum reads can retain UTF-8 bytes in Utf8; native/JavaPrimitives/avro2s produce decoded java.lang.String. The very large default Unicode read difference therefore **is not evidence that Java decoded the same String that much faster**. These additional readers set avro.java.string=String recursively, including map keys, and correctness setup asserts that returned strings are materialized. They still differ in mutable Java versus immutable Scala records/collections and in input-validation policy.

| Workload | Native | Control | Control time | N/control | CI comparison | B/op native / control |
| --- | --- | --- | --- | --- | --- | --- |
| string-ascii | 54.5 ± 0.6 | javaGenericStringRead | 115.3 ± 2.2 | 0.472 | lower, separate CIs | 1,128.0 / 2,296.0 |
| string-ascii | 54.5 ± 0.6 | javaSpecificStringRead | 117.1 ± 0.8 | 0.465 | lower, separate CIs | 1,128.0 / 2,264.0 |
| string-ascii | 54.5 ± 0.6 | javaPrimitivesRead | 108.2 ± 3.3 | 0.503 | lower, separate CIs | 1,128.0 / 2,264.0 |
| string-ascii | 54.5 ± 0.6 | avro2sRead | 114.7 ± 1.2 | 0.475 | lower, separate CIs | 1,128.0 / 2,264.0 |
| string-unicode | 2,444.1 ± 26.3 | javaGenericStringRead | 2,324.5 ± 14.7 | 1.051 | higher, separate CIs | 11,144.0 / 14,104.0 |
| string-unicode | 2,444.1 ± 26.3 | javaSpecificStringRead | 2,357.4 ± 56.8 | 1.037 | higher, separate CIs | 11,144.0 / 14,072.0 |
| string-unicode | 2,444.1 ± 26.3 | javaPrimitivesRead | 2,292.6 ± 12.8 | 1.066 | higher, separate CIs | 11,144.0 / 14,072.0 |
| string-unicode | 2,444.1 ± 26.3 | avro2sRead | 2,299.7 ± 17.0 | 1.063 | higher, separate CIs | 11,144.0 / 14,072.0 |
| collections-full | 8,264.3 ± 64.5 | javaGenericStringRead | 7,204.8 ± 128.4 | 1.147 | higher, separate CIs | 23,512.1 / 16,544.1 |
| collections-full | 8,264.3 ± 64.5 | javaSpecificStringRead | 7,126.1 ± 39.9 | 1.160 | higher, separate CIs | 23,512.1 / 16,520.1 |
| collections-full | 8,264.3 ± 64.5 | javaPrimitivesRead | 10,146.5 ± 181.2 | 0.814 | lower, separate CIs | 23,512.1 / 23,688.1 |
| collections-full | 8,264.3 ± 64.5 | avro2sRead | 15,702.5 ± 267.3 | 0.526 | lower, separate CIs | 23,512.1 / 51,800.2 |

## Trade same-generated-model controls

This final Trade run measures native and JavaPrimitives only. It does not remeasure the Java datum or avro2s implementations, so historical results from those methods must not be presented as current concurrent controls.

| Size | Operation | Native | JavaPrimitives | N/JP | CI comparison | B/op native / JP |
| --- | --- | --- | --- | --- | --- | --- |
| 0 | read | 45.8 ± 2.7 | 59.8 ± 2.3 | 0.766 | lower, separate CIs | 200.0 / 376.0 |
| 0 | write | 17.2 ± 0.2 | 45.6 ± 15.1 | 0.376 | lower, separate CIs | 0.0 / 64.0 |
| 32 | read | 204.4 ± 5.6 | 230.5 ± 1.4 | 0.887 | lower, separate CIs | 960.0 / 1,104.0 |
| 32 | write | 78.5 ± 1.0 | 113.0 ± 21.8 | 0.695 | lower, separate CIs | 0.0 / 64.0 |
| 1024 | read | 4,051.3 ± 94.0 | 4,440.1 ± 24.7 | 0.912 | lower, separate CIs | 21,592.1 / 21,736.1 |
| 1024 | write | 2,206.7 ± 26.8 | 3,806.2 ± 44.3 | 0.580 | lower, separate CIs | 80.0 / 144.1 |

## Matched public API before/after controls

These encode/decode methods include the public convenience-API costs. Encode allocates an output buffer and produces an owned result array; decode checks trailing input. Compare the same method before and after, not these times against direct read/write.

| Suite | Parameters | Method | Before | After | Time Δ | After vs before CI | B/op before → after |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BytesBenchmark | byteCount=4096 | nativeDecode | 161.1 ± 0.7 | 162.5 ± 2.0 | +0.9% | overlapping CIs | 4,192.0 → 4,192.0 |
| BytesBenchmark | byteCount=4096 | nativeEncode | 301.3 ± 1.3 | 309.4 ± 2.8 | +2.7% | higher, separate CIs | 8,512.0 → 8,536.0 |
| CollectionsBenchmark | collectionSize=0 | nativeDecode | 7.3 ± 0.0 | 7.0 ± 0.1 | -3.8% | lower, separate CIs | 72.0 → 72.0 |
| CollectionsBenchmark | collectionSize=0 | nativeEncode | 10.2 ± 0.1 | 10.4 ± 0.2 | +1.4% | overlapping CIs | 296.0 → 296.0 |
| CollectionsBenchmark | collectionSize=128 | nativeDecode | 20,226.9 ± 367.1 | 17,342.8 ± 257.0 | -14.3% | lower, separate CIs | 43,024.3 → 42,880.2 |
| CollectionsBenchmark | collectionSize=128 | nativeEncode | 5,263.2 ± 31.8 | 3,861.6 ± 126.8 | -26.6% | lower, separate CIs | 11,160.1 → 11,016.1 |
| IntegerBenchmark | distribution=mixed, kind=int | nativeDecode | 6,609.0 ± 56.6 | 4,061.8 ± 37.3 | -38.5% | lower, separate CIs | 18,304.1 → 18,304.1 |
| IntegerBenchmark | distribution=mixed, kind=int | nativeEncode | 4,772.8 ± 32.9 | 2,932.2 ± 20.8 | -38.6% | lower, separate CIs | 11,808.1 → 11,808.0 |
| NestedUnionBenchmark | depth=1 | nativeDecode | 255.2 ± 1.1 | 236.7 ± 7.6 | -7.3% | lower, separate CIs | 1,904.0 → 1,904.0 |
| NestedUnionBenchmark | depth=1 | nativeEncode | 141.0 ± 1.3 | 138.3 ± 2.1 | -1.9% | overlapping CIs | 712.0 → 424.0 |
| StringBenchmark | ascii-short | nativeDecode | 23.3 ± 0.9 | 16.5 ± 0.1 | -29.0% | lower, separate CIs | 136.0 → 136.0 |
| StringBenchmark | ascii-short | nativeEncode | 35.0 ± 0.3 | 26.1 ± 0.1 | -25.4% | lower, separate CIs | 352.0 → 376.0 |
| StringBenchmark | emoji-long | nativeDecode | 16,682.8 ± 141.0 | 10,062.8 ± 81.6 | -39.7% | lower, separate CIs | 65,672.2 → 65,672.1 |
| StringBenchmark | emoji-long | nativeEncode | 9,835.9 ± 53.8 | 10,303.8 ± 117.8 | +4.8% | higher, separate CIs | 33,112.1 → 74,104.1 |

## Schema-evolution controls

Warm resolving reads reuse the compiled resolution plan and create fresh values. Plan construction is timed separately. Same-schema reads produce the old model including its discarded byte field, while resolved reads skip that field and produce the new model; those two operations are not equivalent results.

| Discarded-byte parameter | Method | ns/op ± CI | B/op |
| --- | --- | --- | --- |
| discardedBytes=0 | compileResolution | 23,203.6 ± 607.9 | 129,816.5 |
| discardedBytes=0 | javaResolved | 366.1 ± 14.8 | 1,840.0 |
| discardedBytes=0 | nativeResolved | 316.4 ± 9.5 | 640.0 |
| discardedBytes=0 | nativeSameSchema | 119.8 ± 18.2 | 432.0 |
| discardedBytes=4096 | compileResolution | 22,928.2 ± 321.0 | 130,300.5 |
| discardedBytes=4096 | javaResolved | 365.4 ± 14.0 | 1,840.0 |
| discardedBytes=4096 | nativeResolved | 310.8 ± 5.2 | 640.0 |
| discardedBytes=4096 | nativeSameSchema | 207.8 ± 10.1 | 4,544.0 |

discardedBytes=0: native resolved/Java resolved 0.864×; lower, separate CIs. Java returns GenericRecord/Utf8 and native returns the immutable generated model.

discardedBytes=4096: native resolved/Java resolved 0.851×; lower, separate CIs. Java returns GenericRecord/Utf8 and native returns the immutable generated model.

## Separate final-implementation confirmations

Longer confirmations are reported alongside the broad run. They never replace its rows, alter the original target set or mix samples from different runs.

| Workload | Method | Broad final run | Longer confirmation | B/op broad / confirmation |
| --- | --- | --- | --- | --- |
| ints-small | javaCustomWrite | 1,074.0 ± 25.8 | 1,805.9 ± 45.4 | 0.0 / 0.0 |
| ints-small | nativeWrite | 1,622.7 ± 23.0 | 1,629.6 ± 34.6 | 80.0 / 80.0 |

Confirmation ints-small write: native/javaCustom 0.902×; lower, separate CIs.

**Ranking changes across runs for ints-small write.** The broad-run native/javaCustom ratio is 1.511×, while the separate confirmation ratio is 0.902×. The broad-run target counts remain descriptive of that run; this workload's cross-run ranking is unresolved. Retain both observations rather than selecting the favorable run. Narrow within-run confidence intervals do not account for this cross-run variation.

## Additional final controls

String matrix, Trade, public convenience APIs and evolution are distinct experiments. Never compare encode/decode (new output buffers, final owned copy or trailing-data check) with reused-buffer write/direct read as though they measured the same operation. Before-extra changes are descriptive when protocol differs, particularly the one-fork 300 ms before-string pilot.

| Suite | Parameters | Method | Earlier control | Final | Time Δ | Final B/op |
| --- | --- | --- | --- | --- | --- | --- |
| BytesBenchmark | byteCount=4096 | nativeDecode | 161.1 ± 0.7 | 162.5 ± 2.0 | +0.9% | 4,192.0 |
| BytesBenchmark | byteCount=4096 | nativeEncode | 301.3 ± 1.3 | 309.4 ± 2.8 | +2.7% | 8,536.0 |
| CollectionsBenchmark | collectionSize=0 | nativeDecode | 7.3 ± 0.0 | 7.0 ± 0.1 | -3.8% | 72.0 |
| CollectionsBenchmark | collectionSize=0 | nativeEncode | 10.2 ± 0.1 | 10.4 ± 0.2 | +1.4% | 296.0 |
| CollectionsBenchmark | collectionSize=128 | nativeDecode | 20,226.9 ± 367.1 | 17,342.8 ± 257.0 | -14.3% | 42,880.2 |
| CollectionsBenchmark | collectionSize=128 | nativeEncode | 5,263.2 ± 31.8 | 3,861.6 ± 126.8 | -26.6% | 11,016.1 |
| EvolutionBenchmark | discardedBytes=0 | compileResolution | not measured | 23,203.6 ± 607.9 | — | 129,816.5 |
| EvolutionBenchmark | discardedBytes=0 | javaResolved | not measured | 366.1 ± 14.8 | — | 1,840.0 |
| EvolutionBenchmark | discardedBytes=0 | nativeResolved | not measured | 316.4 ± 9.5 | — | 640.0 |
| EvolutionBenchmark | discardedBytes=0 | nativeSameSchema | not measured | 119.8 ± 18.2 | — | 432.0 |
| EvolutionBenchmark | discardedBytes=4096 | compileResolution | not measured | 22,928.2 ± 321.0 | — | 130,300.5 |
| EvolutionBenchmark | discardedBytes=4096 | javaResolved | not measured | 365.4 ± 14.0 | — | 1,840.0 |
| EvolutionBenchmark | discardedBytes=4096 | nativeResolved | not measured | 310.8 ± 5.2 | — | 640.0 |
| EvolutionBenchmark | discardedBytes=4096 | nativeSameSchema | not measured | 207.8 ± 10.1 | — | 4,544.0 |
| IntegerBenchmark | distribution=mixed, kind=int | nativeDecode | 6,609.0 ± 56.6 | 4,061.8 ± 37.3 | -38.5% | 18,304.1 |
| IntegerBenchmark | distribution=mixed, kind=int | nativeEncode | 4,772.8 ± 32.9 | 2,932.2 ± 20.8 | -38.6% | 11,808.0 |
| NestedUnionBenchmark | depth=1 | nativeDecode | 255.2 ± 1.1 | 236.7 ± 7.6 | -7.3% | 1,904.0 |
| NestedUnionBenchmark | depth=1 | nativeEncode | 141.0 ± 1.3 | 138.3 ± 2.1 | -1.9% | 424.0 |
| StringBenchmark | ascii-long | nativeRead | 884.8 ± 63.7 | 205.3 ± 6.9 | -76.8% | 4,200.0 |
| StringBenchmark | ascii-long | nativeWrite | 1,467.1 ± 6.6 | 435.4 ± 8.2 | -70.3% | 4,112.0 |
| StringBenchmark | ascii-short | nativeDecode | 23.3 ± 0.9 | 16.5 ± 0.1 | -29.0% | 136.0 |
| StringBenchmark | ascii-short | nativeEncode | 35.0 ± 0.3 | 26.1 ± 0.1 | -25.4% | 376.0 |
| StringBenchmark | ascii-short | nativeRead | 22.7 ± 3.2 | 16.7 ± 0.2 | -26.7% | 136.0 |
| StringBenchmark | ascii-short | nativeWrite | 27.4 ± 0.2 | 20.5 ± 1.6 | -24.9% | 48.0 |
| StringBenchmark | emoji-long | nativeDecode | 16,682.8 ± 141.0 | 10,062.8 ± 81.6 | -39.7% | 65,672.1 |
| StringBenchmark | emoji-long | nativeEncode | 9,835.9 ± 53.8 | 10,303.8 ± 117.8 | +4.8% | 74,104.1 |
| StringBenchmark | emoji-long | nativeRead | 17,081.0 ± 1,816.1 | 10,207.0 ± 50.4 | -40.2% | 65,672.1 |
| StringBenchmark | emoji-long | nativeWrite | 8,817.6 ± 38.6 | 9,478.1 ± 99.2 | +7.5% | 40,992.1 |
| StringBenchmark | emoji-short | nativeRead | 149.2 ± 7.3 | 95.1 ± 0.8 | -36.3% | 648.0 |
| StringBenchmark | emoji-short | nativeWrite | 83.2 ± 7.0 | 85.5 ± 0.4 | +2.8% | 352.0 |
| StringBenchmark | empty | nativeRead | 10.6 ± 0.1 | 5.8 ± 0.0 | -45.0% | 64.0 |
| StringBenchmark | empty | nativeWrite | 4.5 ± 0.1 | 3.2 ± 0.0 | -30.3% | 0.0 |
| StringBenchmark | latin1-long | nativeRead | 5,352.7 ± 394.6 | 3,233.1 ± 12.7 | -39.6% | 12,408.0 |
| StringBenchmark | latin1-long | nativeWrite | 4,190.5 ± 11.9 | 2,443.4 ± 150.7 | -41.7% | 8,208.0 |
| StringBenchmark | latin1-short | nativeRead | 81.1 ± 9.5 | 46.3 ± 0.5 | -42.8% | 216.0 |
| StringBenchmark | latin1-short | nativeWrite | 44.5 ± 1.4 | 35.6 ± 0.5 | -20.0% | 80.0 |
| StringBenchmark | multilingual-long | nativeRead | 14,882.3 ± 162.6 | 8,583.5 ± 770.4 | -42.3% | 39,048.1 |
| StringBenchmark | multilingual-long | nativeWrite | 9,524.7 ± 74.9 | 6,081.6 ± 44.6 | -36.1% | 22,560.1 |
| StringBenchmark | multilingual-short | nativeRead | 141.1 ± 3.3 | 84.7 ± 3.9 | -40.0% | 440.0 |
| StringBenchmark | multilingual-short | nativeWrite | 80.4 ± 1.4 | 61.6 ± 0.5 | -23.3% | 208.0 |
| StringBenchmark | question-long | nativeRead | 874.8 ± 15.8 | 205.8 ± 0.7 | -76.5% | 4,200.0 |
| StringBenchmark | question-long | nativeWrite | 1,464.9 ± 7.2 | 234.9 ± 1.4 | -84.0% | 4,112.0 |
| StringBenchmark | question-short | nativeRead | 22.5 ± 1.0 | 16.7 ± 0.1 | -25.5% | 136.0 |
| StringBenchmark | question-short | nativeWrite | 27.3 ± 0.1 | 15.7 ± 0.8 | -42.6% | 48.0 |
| StringBenchmark | replacement-long | nativeRead | 15,733.2 ± 491.7 | 15,751.8 ± 114.3 | +0.1% | 41,096.2 |
| StringBenchmark | replacement-long | nativeWrite | 16,898.6 ± 268.8 | 8,384.7 ± 26.9 | -50.4% | 25,632.1 |
| StringBenchmark | replacement-short | nativeRead | 148.4 ± 5.8 | 150.1 ± 0.5 | +1.1% | 456.0 |
| StringBenchmark | replacement-short | nativeWrite | 115.0 ± 0.9 | 78.9 ± 0.6 | -31.4% | 232.0 |
| StringBenchmark | supplementary-mixed-long | nativeRead | not measured | 8,917.6 ± 19.6 | — | 43,840.1 |
| StringBenchmark | supplementary-mixed-long | nativeWrite | not measured | 7,764.9 ± 170.1 | — | 27,352.1 |
| StringBenchmark | supplementary-mixed-short | nativeRead | not measured | 101.8 ± 1.4 | — | 488.0 |
| StringBenchmark | supplementary-mixed-short | nativeWrite | not measured | 75.6 ± 1.7 | — | 256.0 |
| StringBenchmark | supplementary-prefix-ascii-long | nativeRead | not measured | 1,814.9 ± 12.2 | — | 20,640.0 |
| StringBenchmark | supplementary-prefix-ascii-long | nativeWrite | not measured | 4,917.6 ± 62.6 | — | 16,432.1 |
| StringBenchmark | supplementary-prefix-bmp-long | nativeRead | not measured | 8,786.0 ± 36.6 | — | 45,216.1 |
| StringBenchmark | supplementary-prefix-bmp-long | nativeWrite | not measured | 7,704.4 ± 233.0 | — | 24,624.1 |
| TradeBenchmark | collectionSize=0 | javaPrimitivesRead | not measured | 59.8 ± 2.3 | — | 376.0 |
| TradeBenchmark | collectionSize=0 | javaPrimitivesWrite | not measured | 45.6 ± 15.1 | — | 64.0 |
| TradeBenchmark | collectionSize=0 | nativeRead | not measured | 45.8 ± 2.7 | — | 200.0 |
| TradeBenchmark | collectionSize=0 | nativeWrite | not measured | 17.2 ± 0.2 | — | 0.0 |
| TradeBenchmark | collectionSize=1024 | javaPrimitivesRead | not measured | 4,440.1 ± 24.7 | — | 21,736.1 |
| TradeBenchmark | collectionSize=1024 | javaPrimitivesWrite | not measured | 3,806.2 ± 44.3 | — | 144.1 |
| TradeBenchmark | collectionSize=1024 | nativeRead | not measured | 4,051.3 ± 94.0 | — | 21,592.1 |
| TradeBenchmark | collectionSize=1024 | nativeWrite | not measured | 2,206.7 ± 26.8 | — | 80.0 |
| TradeBenchmark | collectionSize=32 | javaPrimitivesRead | not measured | 230.5 ± 1.4 | — | 1,104.0 |
| TradeBenchmark | collectionSize=32 | javaPrimitivesWrite | not measured | 113.0 ± 21.8 | — | 64.0 |
| TradeBenchmark | collectionSize=32 | nativeRead | not measured | 204.4 ± 5.6 | — | 960.0 |
| TradeBenchmark | collectionSize=32 | nativeWrite | not measured | 78.5 ± 1.0 | — | 0.0 |

### Matched-protocol before confirmations

These are separate measurements of the earlier implementation. They do not replace the pilot above. Comparing a confirmed before run with the final after run supports a more credible assessment of the pure-emoji write tradeoff.

| Workload | Method | Old pilot | Confirmed old | Final | Confirmed time Δ | Final vs confirmed old CI | B/op old / final |
| --- | --- | --- | --- | --- | --- | --- | --- |
| emoji-long | nativeWrite | 8,817.6 ± 38.6 | 8,724.2 ± 130.5 | 9,478.1 ± 99.2 | +8.6% | higher, separate CIs | 0.1 / 40,992.1 |
| emoji-short | nativeWrite | 83.2 ± 7.0 | 81.4 ± 0.4 | 85.5 ± 0.4 | +5.0% | higher, separate CIs | 0.0 / 352.0 |

### New supplementary-string exploratory controls

Only the four newly introduced supplementary-* workloads are shown here. The control run also repeated existing string workloads, but those measurements are not merged with or substituted for the final string run. These are one-fork exploratory means; there is no original implementation baseline for the new workloads. The attempted direct supplementary encoder was rejected after it slowed mixed-string workloads; the retained implementation continues using the JDK path.

| Workload | Method | Pilot ns/op ± CI | Final control ns/op ± CI | Pilot B/op | Final control B/op | Pilot forks | Pilot iterations |
| --- | --- | --- | --- | --- | --- | --- | --- |
| supplementary-mixed-long | nativeWrite | 7,723.0 ± 174.1 | 7,764.9 ± 170.1 | 27,352.1 | 27,352.1 | 1 | 4 |
| supplementary-mixed-short | nativeWrite | 75.6 ± 8.2 | 75.6 ± 1.7 | 256.0 | 256.0 | 1 | 4 |
| supplementary-prefix-ascii-long | nativeWrite | 4,950.8 ± 97.6 | 4,917.6 ± 62.6 | 16,432.1 | 16,432.1 | 1 | 4 |
| supplementary-prefix-bmp-long | nativeWrite | 7,595.2 ± 192.2 | 7,704.4 ± 233.0 | 24,624.1 | 24,624.1 | 1 | 4 |

## All final comparison means, uncertainty and allocations

| Workload | Method | ns/op ± CI | Time CI bounds | B/op ± CI |
| --- | --- | --- | --- | --- |
| ints-small | avro2sRead | 11,617.7 ± 97.2 | [11,520.6, 11,714.9] | 33,160.2 ± 0.0 |
| ints-small | avro2sWrite | 15,079.2 ± 1,443.8 | [13,635.4, 16,523.0] | 32,968.2 ± 0.0 |
| ints-small | javaCustomRead | 14,097.6 ± 313.9 | [13,783.7, 14,411.5] | 4,368.2 ± 0.0 |
| ints-small | javaCustomWrite | 1,074.0 ± 25.8 | [1,048.3, 1,099.8] | 0.0 ± 0.0 |
| ints-small | javaGenericRead | 3,863.1 ± 33.5 | [3,829.6, 3,896.6] | 4,376.1 ± 0.0 |
| ints-small | javaGenericWrite | 4,438.1 ± 90.6 | [4,347.4, 4,528.7] | 0.1 ± 0.0 |
| ints-small | javaPrimitivesRead | 3,542.9 ± 44.2 | [3,498.7, 3,587.0] | 5,176.0 ± 0.0 |
| ints-small | javaPrimitivesWrite | 2,727.7 ± 241.0 | [2,486.7, 2,968.7] | 80.0 ± 0.0 |
| ints-small | javaSpecificRead | 3,859.1 ± 53.2 | [3,806.0, 3,912.3] | 4,344.1 ± 0.0 |
| ints-small | javaSpecificWrite | 4,477.4 ± 118.5 | [4,358.9, 4,595.9] | 16.1 ± 25.5 |
| ints-small | nativeRead | 3,452.7 ± 74.1 | [3,378.7, 3,526.8] | 5,072.0 ± 0.0 |
| ints-small | nativeWrite | 1,622.7 ± 23.0 | [1,599.7, 1,645.7] | 80.0 ± 0.0 |
| ints-wide | avro2sRead | 12,926.1 ± 271.5 | [12,654.6, 13,197.6] | 65,928.2 ± 0.0 |
| ints-wide | avro2sWrite | 15,497.8 ± 94.9 | [15,402.9, 15,592.6] | 49,352.2 ± 0.0 |
| ints-wide | javaCustomRead | 16,096.2 ± 595.7 | [15,500.5, 16,691.9] | 20,752.2 ± 0.0 |
| ints-wide | javaCustomWrite | 3,335.5 ± 16.4 | [3,319.0, 3,351.9] | 0.0 ± 0.0 |
| ints-wide | javaGenericRead | 5,077.7 ± 35.6 | [5,042.1, 5,113.3] | 20,760.1 ± 0.0 |
| ints-wide | javaGenericWrite | 5,844.0 ± 117.9 | [5,726.1, 5,961.9] | 0.1 ± 0.0 |
| ints-wide | javaPrimitivesRead | 4,568.9 ± 32.0 | [4,536.8, 4,600.9] | 21,560.1 ± 0.0 |
| ints-wide | javaPrimitivesWrite | 4,970.3 ± 2,001.7 | [2,968.6, 6,972.0] | 80.1 ± 0.0 |
| ints-wide | javaSpecificRead | 5,088.2 ± 188.3 | [4,900.0, 5,276.5] | 20,728.1 ± 0.0 |
| ints-wide | javaSpecificWrite | 5,871.5 ± 83.7 | [5,787.7, 5,955.2] | 32.1 ± 0.0 |
| ints-wide | nativeRead | 4,565.0 ± 10.6 | [4,554.5, 4,575.6] | 21,456.1 ± 0.0 |
| ints-wide | nativeWrite | 2,582.8 ± 46.7 | [2,536.1, 2,629.5] | 80.0 ± 0.0 |
| longs-mixed | avro2sRead | 14,604.2 ± 792.3 | [13,811.8, 15,396.5] | 77,992.2 ± 0.0 |
| longs-mixed | avro2sWrite | 17,137.5 ± 275.9 | [16,861.6, 17,413.5] | 55,384.2 ± 0.0 |
| longs-mixed | javaCustomRead | 20,541.3 ± 3,845.3 | [16,696.0, 24,386.6] | 26,784.3 ± 0.1 |
| longs-mixed | javaCustomWrite | 3,944.4 ± 101.1 | [3,843.3, 4,045.4] | 0.1 ± 0.0 |
| longs-mixed | javaGenericRead | 6,106.8 ± 68.3 | [6,038.5, 6,175.2] | 26,792.1 ± 0.0 |
| longs-mixed | javaGenericWrite | 6,505.3 ± 136.4 | [6,368.9, 6,641.7] | 32.1 ± 0.0 |
| longs-mixed | javaPrimitivesRead | 5,250.3 ± 77.1 | [5,173.2, 5,327.4] | 27,592.1 ± 0.0 |
| longs-mixed | javaPrimitivesWrite | 4,777.8 ± 82.8 | [4,695.0, 4,860.6] | 80.1 ± 0.0 |
| longs-mixed | javaSpecificRead | 6,077.4 ± 35.9 | [6,041.5, 6,113.4] | 26,760.1 ± 0.0 |
| longs-mixed | javaSpecificWrite | 6,504.1 ± 97.8 | [6,406.3, 6,602.0] | 32.1 ± 0.0 |
| longs-mixed | nativeRead | 5,821.9 ± 53.1 | [5,768.9, 5,875.0] | 27,488.1 ± 0.0 |
| longs-mixed | nativeWrite | 3,388.9 ± 74.8 | [3,314.1, 3,463.7] | 80.0 ± 0.0 |
| string-ascii | avro2sRead | 116.7 ± 1.0 | [115.7, 117.7] | 2,264.0 ± 0.0 |
| string-ascii | avro2sWrite | 104.7 ± 9.7 | [95.0, 114.4] | 1,040.0 ± 0.0 |
| string-ascii | javaCustomRead | 95.3 ± 1.1 | [94.2, 96.4] | 1,256.0 ± 0.0 |
| string-ascii | javaCustomWrite | 95.0 ± 2.7 | [92.3, 97.7] | 1,040.0 ± 0.0 |
| string-ascii | javaGenericRead | 67.6 ± 0.3 | [67.3, 68.0] | 1,264.0 ± 0.0 |
| string-ascii | javaGenericWrite | 98.1 ± 1.5 | [96.5, 99.6] | 1,040.0 ± 0.0 |
| string-ascii | javaPrimitivesRead | 106.3 ± 1.2 | [105.1, 107.5] | 2,264.0 ± 0.0 |
| string-ascii | javaPrimitivesWrite | 92.6 ± 0.3 | [92.3, 92.9] | 1,040.0 ± 0.0 |
| string-ascii | javaSpecificRead | 69.3 ± 4.2 | [65.1, 73.5] | 1,232.0 ± 0.0 |
| string-ascii | javaSpecificWrite | 98.0 ± 0.4 | [97.5, 98.4] | 1,040.0 ± 0.0 |
| string-ascii | nativeRead | 54.1 ± 0.2 | [53.9, 54.3] | 1,128.0 ± 0.0 |
| string-ascii | nativeWrite | 110.9 ± 0.3 | [110.6, 111.1] | 1,040.0 ± 0.0 |
| string-unicode | avro2sRead | 2,379.7 ± 34.5 | [2,345.2, 2,414.2] | 14,072.0 ± 0.0 |
| string-unicode | avro2sWrite | 1,964.4 ± 33.3 | [1,931.1, 1,997.8] | 6,688.0 ± 0.0 |
| string-unicode | javaCustomRead | 174.2 ± 3.2 | [171.1, 177.4] | 3,048.0 ± 0.0 |
| string-unicode | javaCustomWrite | 1,920.9 ± 38.8 | [1,882.1, 1,959.6] | 6,688.0 ± 0.0 |
| string-unicode | javaGenericRead | 151.0 ± 0.9 | [150.1, 152.0] | 3,056.0 ± 0.0 |
| string-unicode | javaGenericWrite | 1,894.1 ± 33.7 | [1,860.4, 1,927.8] | 6,688.0 ± 0.0 |
| string-unicode | javaPrimitivesRead | 2,269.0 ± 5.6 | [2,263.4, 2,274.6] | 14,072.0 ± 0.0 |
| string-unicode | javaPrimitivesWrite | 1,889.9 ± 10.1 | [1,879.8, 1,900.1] | 6,688.0 ± 0.0 |
| string-unicode | javaSpecificRead | 149.0 ± 0.6 | [148.3, 149.6] | 3,024.0 ± 0.0 |
| string-unicode | javaSpecificWrite | 1,894.0 ± 12.0 | [1,882.0, 1,906.0] | 6,688.0 ± 0.0 |
| string-unicode | nativeRead | 2,444.7 ± 40.2 | [2,404.6, 2,484.9] | 11,144.0 ± 0.0 |
| string-unicode | nativeWrite | 2,003.3 ± 21.8 | [1,981.5, 2,025.2] | 6,688.0 ± 0.0 |
| bytes | avro2sRead | 369.2 ± 20.9 | [348.3, 390.1] | 8,428.0 ± 44.6 |
| bytes | avro2sWrite | 339.3 ± 5.1 | [334.2, 344.4] | 4,224.0 ± 0.0 |
| bytes | javaCustomRead | 270.3 ± 7.7 | [262.6, 278.0] | 4,352.0 ± 0.0 |
| bytes | javaCustomWrite | 321.2 ± 11.8 | [309.4, 333.0] | 4,112.0 ± 0.0 |
| bytes | javaGenericRead | 259.6 ± 10.6 | [249.0, 270.2] | 4,360.0 ± 0.0 |
| bytes | javaGenericWrite | 342.7 ± 11.4 | [331.4, 354.1] | 4,168.0 ± 0.0 |
| bytes | javaPrimitivesRead | 327.2 ± 3.5 | [323.7, 330.7] | 8,400.0 ± 0.0 |
| bytes | javaPrimitivesWrite | 123.5 ± 4.6 | [118.9, 128.1] | 0.0 ± 0.0 |
| bytes | javaSpecificRead | 251.7 ± 1.0 | [250.7, 252.8] | 4,328.0 ± 0.0 |
| bytes | javaSpecificWrite | 333.1 ± 11.2 | [321.9, 344.3] | 4,140.0 ± 44.6 |
| bytes | nativeRead | 163.9 ± 13.4 | [150.5, 177.2] | 4,192.0 ± 0.0 |
| bytes | nativeWrite | 90.0 ± 13.5 | [76.5, 103.5] | 0.0 ± 0.0 |
| collections-empty | avro2sRead | 65.5 ± 0.9 | [64.6, 66.4] | 424.0 ± 0.0 |
| collections-empty | avro2sWrite | 48.2 ± 0.9 | [47.3, 49.1] | 152.0 ± 0.0 |
| collections-empty | javaCustomRead | 82.6 ± 6.3 | [76.3, 88.9] | 344.0 ± 0.0 |
| collections-empty | javaCustomWrite | 28.9 ± 0.4 | [28.5, 29.3] | 0.0 ± 0.0 |
| collections-empty | javaGenericRead | 66.3 ± 0.8 | [65.5, 67.1] | 344.0 ± 0.0 |
| collections-empty | javaGenericWrite | 31.8 ± 0.8 | [31.0, 32.5] | 0.0 ± 0.0 |
| collections-empty | javaPrimitivesRead | 30.2 ± 1.9 | [28.3, 32.1] | 256.0 ± 0.0 |
| collections-empty | javaPrimitivesWrite | 28.8 ± 0.4 | [28.5, 29.2] | 0.0 ± 0.0 |
| collections-empty | javaSpecificRead | 42.6 ± 0.3 | [42.3, 42.8] | 320.0 ± 0.0 |
| collections-empty | javaSpecificWrite | 31.4 ± 0.2 | [31.3, 31.6] | 0.0 ± 0.0 |
| collections-empty | nativeRead | 6.7 ± 0.1 | [6.6, 6.8] | 72.0 ± 0.0 |
| collections-empty | nativeWrite | 4.1 ± 0.0 | [4.1, 4.1] | 0.0 ± 0.0 |
| collections-full | avro2sRead | 16,282.2 ± 234.4 | [16,047.8, 16,516.6] | 51,800.2 ± 0.0 |
| collections-full | avro2sWrite | 4,185.8 ± 141.3 | [4,044.5, 4,327.1] | 11,480.1 ± 25.5 |
| collections-full | javaCustomRead | 4,619.5 ± 205.8 | [4,413.7, 4,825.3] | 11,864.1 ± 0.0 |
| collections-full | javaCustomWrite | 2,245.9 ± 44.1 | [2,201.8, 2,290.0] | 5,120.0 ± 0.0 |
| collections-full | javaGenericRead | 3,072.3 ± 103.2 | [2,969.1, 3,175.5] | 12,360.0 ± 0.0 |
| collections-full | javaGenericWrite | 2,601.5 ± 35.3 | [2,566.1, 2,636.8] | 5,136.0 ± 25.5 |
| collections-full | javaPrimitivesRead | 10,032.4 ± 101.5 | [9,930.9, 10,134.0] | 23,688.1 ± 0.0 |
| collections-full | javaPrimitivesWrite | 2,506.6 ± 85.6 | [2,421.0, 2,592.2] | 5,216.0 ± 0.0 |
| collections-full | javaSpecificRead | 3,053.1 ± 59.2 | [2,993.9, 3,112.2] | 12,336.0 ± 0.0 |
| collections-full | javaSpecificWrite | 2,588.6 ± 19.1 | [2,569.5, 2,607.7] | 5,120.0 ± 0.0 |
| collections-full | nativeRead | 8,302.2 ± 40.8 | [8,261.4, 8,343.0] | 23,512.1 ± 0.0 |
| collections-full | nativeWrite | 1,711.5 ± 18.1 | [1,693.4, 1,729.6] | 96.0 ± 0.0 |
| enum-fixed | avro2sRead | 1,828.6 ± 20.6 | [1,808.0, 1,849.2] | 4,864.0 ± 0.0 |
| enum-fixed | avro2sWrite | 1,026.7 ± 102.8 | [923.9, 1,129.5] | 2,184.0 ± 114.7 |
| enum-fixed | javaCustomRead | 1,712.7 ± 146.7 | [1,566.1, 1,859.4] | 3,232.0 ± 0.0 |
| enum-fixed | javaCustomWrite | 182.0 ± 6.8 | [175.2, 188.7] | 0.0 ± 0.0 |
| enum-fixed | javaGenericRead | 822.6 ± 3.3 | [819.3, 825.9] | 2,440.0 ± 0.0 |
| enum-fixed | javaGenericWrite | 522.2 ± 16.0 | [506.2, 538.2] | 0.0 ± 0.0 |
| enum-fixed | javaPrimitivesRead | 424.5 ± 49.2 | [375.3, 473.8] | 2,776.0 ± 0.0 |
| enum-fixed | javaPrimitivesWrite | 182.9 ± 2.7 | [180.2, 185.6] | 0.0 ± 0.0 |
| enum-fixed | javaSpecificRead | 1,076.4 ± 10.5 | [1,065.8, 1,086.9] | 2,944.0 ± 0.0 |
| enum-fixed | javaSpecificWrite | 432.7 ± 8.0 | [424.7, 440.7] | 32.0 ± 51.0 |
| enum-fixed | nativeRead | 369.4 ± 2.7 | [366.7, 372.0] | 2,688.0 ± 0.0 |
| enum-fixed | nativeWrite | 139.2 ± 1.4 | [137.8, 140.7] | 0.0 ± 0.0 |
| numerics | avro2sRead | 6,626.5 ± 31.4 | [6,595.2, 6,657.9] | 23,224.1 ± 0.0 |
| numerics | avro2sWrite | 5,696.6 ± 23.8 | [5,672.8, 5,720.4] | 17,640.1 ± 0.0 |
| numerics | javaCustomRead | 6,674.4 ± 874.2 | [5,800.2, 7,548.5] | 7,048.1 ± 0.0 |
| numerics | javaCustomWrite | 594.1 ± 5.0 | [589.1, 599.1] | 0.0 ± 0.0 |
| numerics | javaGenericRead | 2,460.9 ± 13.4 | [2,447.5, 2,474.3] | 7,088.0 ± 0.0 |
| numerics | javaGenericWrite | 1,898.7 ± 19.7 | [1,879.1, 1,918.4] | 0.0 ± 0.0 |
| numerics | javaPrimitivesRead | 1,657.8 ± 57.5 | [1,600.3, 1,715.3] | 7,856.0 ± 0.0 |
| numerics | javaPrimitivesWrite | 555.5 ± 7.2 | [548.2, 562.7] | 0.0 ± 0.0 |
| numerics | javaSpecificRead | 2,476.7 ± 17.3 | [2,459.4, 2,494.1] | 7,064.0 ± 0.0 |
| numerics | javaSpecificWrite | 1,895.6 ± 15.3 | [1,880.4, 1,910.9] | 40.0 ± 0.0 |
| numerics | nativeRead | 1,169.3 ± 9.9 | [1,159.4, 1,179.2] | 7,800.0 ± 0.0 |
| numerics | nativeWrite | 326.8 ± 5.6 | [321.2, 332.4] | 0.0 ± 0.0 |
| nested | avro2sRead | 3,429.8 ± 38.6 | [3,391.3, 3,468.4] | 13,176.0 ± 0.0 |
| nested | avro2sWrite | 3,592.7 ± 60.1 | [3,532.6, 3,652.9] | 7,056.0 ± 0.0 |
| nested | javaGenericRead | 2,949.8 ± 175.7 | [2,774.2, 3,125.5] | 4,912.0 ± 0.0 |
| nested | javaGenericWrite | 2,008.3 ± 63.4 | [1,944.8, 2,071.7] | 1,128.0 ± 0.0 |
| nested | javaPrimitivesRead | 1,356.2 ± 40.6 | [1,315.7, 1,396.8] | 9,432.0 ± 0.0 |
| nested | javaPrimitivesWrite | 1,218.7 ± 218.8 | [999.9, 1,437.4] | 1,288.0 ± 0.0 |
| nested | javaSpecificRead | 2,186.0 ± 103.5 | [2,082.5, 2,289.6] | 4,192.0 ± 0.0 |
| nested | javaSpecificWrite | 2,053.4 ± 95.3 | [1,958.1, 2,148.6] | 1,488.0 ± 0.0 |
| nested | nativeRead | 1,172.5 ± 18.7 | [1,153.8, 1,191.2] | 9,920.0 ± 0.0 |
| nested | nativeWrite | 651.3 ± 21.1 | [630.3, 672.4] | 160.0 ± 0.0 |
| logical | avro2sRead | 227.6 ± 1.2 | [226.4, 228.8] | 1,008.0 ± 0.0 |
| logical | avro2sWrite | 190.7 ± 2.9 | [187.7, 193.6] | 232.0 ± 0.0 |
| logical | javaGenericRead | 174.8 ± 1.7 | [173.1, 176.5] | 832.0 ± 0.0 |
| logical | javaGenericWrite | 289.1 ± 2.4 | [286.7, 291.5] | 232.0 ± 0.0 |
| logical | javaPrimitivesRead | 151.5 ± 1.4 | [150.1, 153.0] | 640.0 ± 0.0 |
| logical | javaPrimitivesWrite | 92.6 ± 1.2 | [91.5, 93.8] | 56.0 ± 0.0 |
| logical | javaSpecificRead | 178.0 ± 0.8 | [177.2, 178.8] | 808.0 ± 0.0 |
| logical | javaSpecificWrite | 191.1 ± 1.5 | [189.6, 192.6] | 232.0 ± 0.0 |
| logical | nativeRead | 127.3 ± 1.3 | [126.1, 128.6] | 472.0 ± 0.0 |
| logical | nativeWrite | 72.9 ± 2.5 | [70.4, 75.4] | 112.0 ± 0.0 |
| decimal | javaGenericRead | 128.5 ± 2.8 | [125.7, 131.3] | 696.0 ± 0.0 |
| decimal | javaGenericWrite | 180.5 ± 13.6 | [166.9, 194.1] | 248.0 ± 0.0 |
| decimal | javaPrimitivesRead | 92.7 ± 0.7 | [92.0, 93.4] | 680.0 ± 0.0 |
| decimal | javaPrimitivesWrite | 90.1 ± 87.7 | [2.4, 177.8] | 128.0 ± 0.0 |
| decimal | javaSpecificRead | 155.9 ± 0.8 | [155.1, 156.7] | 688.0 ± 0.0 |
| decimal | javaSpecificWrite | 185.8 ± 8.7 | [177.1, 194.5] | 304.0 ± 0.0 |
| decimal | nativeRead | 86.6 ± 0.5 | [86.1, 87.1] | 544.0 ± 0.0 |
| decimal | nativeWrite | 53.2 ± 3.5 | [49.8, 56.7] | 128.0 ± 0.0 |

## Model and measurement caveats

- Read results and decoders are fresh across timed paths. No Java record-reuse baseline is conflated with an immutable native read. Writers reuse buffers/encoders across paths; Java flush is timed and all write methods return size without a final array copy.
- JavaPrimitives uses the same generated immutable Scala models and codec with Java primitive I/O. It controls model construction, but not identical UTF-8 validation, limits or intermediate byte-copy policy.
- Generic/specific/custom Java use mutable Java records and collections; native uses immutable Scala records, Vector, Map and owned Bytes. avro2s uses the genuine generated Scala SpecificRecord representation, including List conversions. Allocation differences therefore include representation choices as well as I/O.
- Java String fields and map keys are prepared as java.lang.String for all writers outside timing. The default Java read representation is Utf8; decoded-String controls are reported separately.
- Standard Java, generic Java and avro2s enable the default fast reader. Generated custom coders explicitly enable custom coders and disable the fast reader so customDecode is actually called; untimed probes check dispatch. Not every schema supports custom coders.
- Native rejects malformed UTF-8/varints and enforces resource limits. The timed workloads are valid data. Faster results do not justify weakening those checks.
- ints-small deliberately uses boxed-Integer cache values; ints-wide and Trade values are outside that cache. Interpret allocation numbers per workload rather than assuming all integer collections allocate alike.
- Logical baselines construct equivalent semantic values; decimals retain exact precision/scale. Native Scala BigDecimal and Java BigDecimal still have representation differences. Very wide CIs (including a noisy historical decimal adapter result) warrant caution rather than a headline ratio.
- JMH intervals describe these forks and iterations on one host. They do not establish performance across CPUs, JVM versions, schema shapes or concurrent applications.

## Input provenance and actual protocols

### historical: comparison-Comparison.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/comparison-2026-09-17/comparison-Comparison.json`; SHA-256 `bfd0d036427fac9dbbc01be2e8e4588fd887216f870f0db760224bf634602861`; 120 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/comparison-2026-09-17/comparison.environment.json",
  "sha256": "d95926fca4b810575f8fecebf7c949ca4adc4dc60cf3c4f9a7418503f748d3f0",
  "status": "complete",
  "gitRevision": "553f32f2b0e834c0a58715c7f199be7e76c40296",
  "sourceRoot": "/Users/sam/AI/avro2s-wire",
  "startedAt": "2026-09-17T13:02:47.633835+00:00",
  "finishedAt": "2026-09-17T13:26:17.468005+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 12,
  "implementationSha256": "98cbd9fc19f0f82b66e433aa395bc9726b707ca061d92c0e3ed3d5ec362fe52c"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 120
}
```

### historical: comparison-NestedComparison.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/comparison-2026-09-17/comparison-NestedComparison.json`; SHA-256 `9051f1772e291e753449c26a7e657911de23fc30285e22445f7d58e02ae18d2f`; 10 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/comparison-2026-09-17/comparison.environment.json",
  "sha256": "d95926fca4b810575f8fecebf7c949ca4adc4dc60cf3c4f9a7418503f748d3f0",
  "status": "complete",
  "gitRevision": "553f32f2b0e834c0a58715c7f199be7e76c40296",
  "sourceRoot": "/Users/sam/AI/avro2s-wire",
  "startedAt": "2026-09-17T13:02:47.633835+00:00",
  "finishedAt": "2026-09-17T13:26:17.468005+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 12,
  "implementationSha256": "98cbd9fc19f0f82b66e433aa395bc9726b707ca061d92c0e3ed3d5ec362fe52c"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 10
}
```

### historical: comparison-LogicalComparison.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/comparison-2026-09-17/comparison-LogicalComparison.json`; SHA-256 `6121e90657966009bb6ad0d2ef0662691df088d68a0ab4921a389e206bc6a230`; 10 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/comparison-2026-09-17/comparison.environment.json",
  "sha256": "d95926fca4b810575f8fecebf7c949ca4adc4dc60cf3c4f9a7418503f748d3f0",
  "status": "complete",
  "gitRevision": "553f32f2b0e834c0a58715c7f199be7e76c40296",
  "sourceRoot": "/Users/sam/AI/avro2s-wire",
  "startedAt": "2026-09-17T13:02:47.633835+00:00",
  "finishedAt": "2026-09-17T13:26:17.468005+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 12,
  "implementationSha256": "98cbd9fc19f0f82b66e433aa395bc9726b707ca061d92c0e3ed3d5ec362fe52c"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 10
}
```

### historical: comparison-DecimalComparison.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/comparison-2026-09-17/comparison-DecimalComparison.json`; SHA-256 `f90a5be2f901d2674fc2ff24ed99d495b451bea341311ad78c45767960387924`; 8 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/comparison-2026-09-17/comparison.environment.json",
  "sha256": "d95926fca4b810575f8fecebf7c949ca4adc4dc60cf3c4f9a7418503f748d3f0",
  "status": "complete",
  "gitRevision": "553f32f2b0e834c0a58715c7f199be7e76c40296",
  "sourceRoot": "/Users/sam/AI/avro2s-wire",
  "startedAt": "2026-09-17T13:02:47.633835+00:00",
  "finishedAt": "2026-09-17T13:26:17.468005+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 12,
  "implementationSha256": "98cbd9fc19f0f82b66e433aa395bc9726b707ca061d92c0e3ed3d5ec362fe52c"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 8
}
```

### fresh-before: before-Comparison.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before-Comparison.json`; SHA-256 `f81454fb8bf0a878136f96847960f7563708a10a4fed6077325819abd6f22289`; 20 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before.environment.json",
  "sha256": "c178ac81d2adf94092b1b9d9d89acca0ae2b69c2b8b458d067aecc51ec865951",
  "status": "complete",
  "gitRevision": "59d35ee06fe8bcb624ce448470018accaa9fcb9e",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T14:10:17.813540+00:00",
  "finishedAt": "2026-09-17T14:15:22.634542+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 12,
  "implementationSha256": "98cbd9fc19f0f82b66e433aa395bc9726b707ca061d92c0e3ed3d5ec362fe52c"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 20
}
```

### fresh-before: before-NestedComparison.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before-NestedComparison.json`; SHA-256 `60c0f8fa098ce405fa66600e51c99d4c0f6dea40a2f618a8b3cc71ee8cd5063e`; 2 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before.environment.json",
  "sha256": "c178ac81d2adf94092b1b9d9d89acca0ae2b69c2b8b458d067aecc51ec865951",
  "status": "complete",
  "gitRevision": "59d35ee06fe8bcb624ce448470018accaa9fcb9e",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T14:10:17.813540+00:00",
  "finishedAt": "2026-09-17T14:15:22.634542+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 12,
  "implementationSha256": "98cbd9fc19f0f82b66e433aa395bc9726b707ca061d92c0e3ed3d5ec362fe52c"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 2
}
```

### fresh-before: before-LogicalComparison.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before-LogicalComparison.json`; SHA-256 `dd67923a010139b9beedc89eed7cfee6ded1547d7b12613678e5c3060e9846ef`; 2 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before.environment.json",
  "sha256": "c178ac81d2adf94092b1b9d9d89acca0ae2b69c2b8b458d067aecc51ec865951",
  "status": "complete",
  "gitRevision": "59d35ee06fe8bcb624ce448470018accaa9fcb9e",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T14:10:17.813540+00:00",
  "finishedAt": "2026-09-17T14:15:22.634542+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 12,
  "implementationSha256": "98cbd9fc19f0f82b66e433aa395bc9726b707ca061d92c0e3ed3d5ec362fe52c"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 2
}
```

### fresh-before: before-DecimalComparison.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before-DecimalComparison.json`; SHA-256 `9111711b814c29c5168e1b78d6504e85e33fe625ad8700faefdc567e33c53dbc`; 2 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before.environment.json",
  "sha256": "c178ac81d2adf94092b1b9d9d89acca0ae2b69c2b8b458d067aecc51ec865951",
  "status": "complete",
  "gitRevision": "59d35ee06fe8bcb624ce448470018accaa9fcb9e",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T14:10:17.813540+00:00",
  "finishedAt": "2026-09-17T14:15:22.634542+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 12,
  "implementationSha256": "98cbd9fc19f0f82b66e433aa395bc9726b707ca061d92c0e3ed3d5ec362fe52c"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 2
}
```

### final-after: after-Comparison.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-Comparison.json`; SHA-256 `47a68441f8141eb8650bc0dd61180c7bd8c331d9dae766d1911fadf79bbd8468`; 120 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after.environment.json",
  "sha256": "562144720d5b9bb5a18b5bc2021171106f59efc97396569639486215561332a1",
  "status": "complete",
  "gitRevision": "5e3f95f85ea397c6f3f6a478279600caa815f6b2",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T15:20:33.273700+00:00",
  "finishedAt": "2026-09-17T15:43:56.145753+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 14,
  "implementationSha256": "3f8f9221596cd90c5f7cba4fbe783b608dc00885fdd4815d4632e9d521c65887"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 120
}
```

### final-after: after-NestedComparison.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-NestedComparison.json`; SHA-256 `d3ce1651837f4aca258906b5fa67e8500bab897c8f31bc42a8207f1d83aac5de`; 10 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after.environment.json",
  "sha256": "562144720d5b9bb5a18b5bc2021171106f59efc97396569639486215561332a1",
  "status": "complete",
  "gitRevision": "5e3f95f85ea397c6f3f6a478279600caa815f6b2",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T15:20:33.273700+00:00",
  "finishedAt": "2026-09-17T15:43:56.145753+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 14,
  "implementationSha256": "3f8f9221596cd90c5f7cba4fbe783b608dc00885fdd4815d4632e9d521c65887"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 10
}
```

### final-after: after-LogicalComparison.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-LogicalComparison.json`; SHA-256 `1e099c38e6012a5479eedd59f672d45c3ebc0b36b059f19d0774a3c19f2a6ebe`; 10 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after.environment.json",
  "sha256": "562144720d5b9bb5a18b5bc2021171106f59efc97396569639486215561332a1",
  "status": "complete",
  "gitRevision": "5e3f95f85ea397c6f3f6a478279600caa815f6b2",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T15:20:33.273700+00:00",
  "finishedAt": "2026-09-17T15:43:56.145753+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 14,
  "implementationSha256": "3f8f9221596cd90c5f7cba4fbe783b608dc00885fdd4815d4632e9d521c65887"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 10
}
```

### final-after: after-DecimalComparison.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-DecimalComparison.json`; SHA-256 `c2403882e40dbf1f0697654a15999bc3a08803e9bd3eedbef7fe460eaa2d2759`; 8 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after.environment.json",
  "sha256": "562144720d5b9bb5a18b5bc2021171106f59efc97396569639486215561332a1",
  "status": "complete",
  "gitRevision": "5e3f95f85ea397c6f3f6a478279600caa815f6b2",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T15:20:33.273700+00:00",
  "finishedAt": "2026-09-17T15:43:56.145753+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 14,
  "implementationSha256": "3f8f9221596cd90c5f7cba4fbe783b608dc00885fdd4815d4632e9d521c65887"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 8
}
```

### final-extra: after-strings-String.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-strings-String.json`; SHA-256 `d3bb95854cff1cde1e8227b134a28a011193265c50be8af369cbc68517c26cce`; 26 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-strings.environment.json",
  "sha256": "97682c0fa8b1e4bbf0dad55695671cf1db442bf9b768085a887d25eb620f8150",
  "status": "complete",
  "gitRevision": "6e6c0effdec43d61cd978ec1a970a645459487f2",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T14:59:11.046529+00:00",
  "finishedAt": "2026-09-17T15:04:03.541612+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 14,
  "implementationSha256": "3f8f9221596cd90c5f7cba4fbe783b608dc00885fdd4815d4632e9d521c65887"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 26
}
```

### final-extra: decoded-strings-DecodedString.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/decoded-strings-DecodedString.json`; SHA-256 `82d54fa0d4d15eab84a4532764db66a610330d065725585fa75631c006015cd7`; 15 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/decoded-strings.environment.json",
  "sha256": "12fa9bea527a68d9d53997f668a23ce5bfb72ca9f0e0710e001896b9c6494e63",
  "status": "complete",
  "gitRevision": "6e6c0effdec43d61cd978ec1a970a645459487f2",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T15:05:37.769015+00:00",
  "finishedAt": "2026-09-17T15:08:10.812245+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 14,
  "implementationSha256": "3f8f9221596cd90c5f7cba4fbe783b608dc00885fdd4815d4632e9d521c65887"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 15
}
```

### final-extra: after-trade-Trade.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-trade-Trade.json`; SHA-256 `9469d7c038e5506f5343e718f1e7ca8ffdd3a5215c76922853e3864e63002b4f`; 12 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-trade.environment.json",
  "sha256": "006b93b21c2930d941ed3e7a2e8bdec7587892ec9c6455132c30e79f4be2c683",
  "status": "complete",
  "gitRevision": "5e3f95f85ea397c6f3f6a478279600caa815f6b2",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T15:45:41.356418+00:00",
  "finishedAt": "2026-09-17T15:47:45.621935+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 14,
  "implementationSha256": "3f8f9221596cd90c5f7cba4fbe783b608dc00885fdd4815d4632e9d521c65887"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 12
}
```

### final-extra: after-api-Integer.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-api-Integer.json`; SHA-256 `d342427b8fd555dc5559fbb4b7efd8cac20b5a2edb11089028c518fb80a0c399`; 2 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-api.environment.json",
  "sha256": "8849517dd72d466ed3de28a0427bb85f344d923dc35ba18fbd8d939ee6cf054c",
  "status": "complete",
  "gitRevision": "5e3f95f85ea397c6f3f6a478279600caa815f6b2",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T15:51:11.554375+00:00",
  "finishedAt": "2026-09-17T15:54:35.882396+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 14,
  "implementationSha256": "3f8f9221596cd90c5f7cba4fbe783b608dc00885fdd4815d4632e9d521c65887"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 2
}
```

### final-extra: after-api-String.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-api-String.json`; SHA-256 `a3c76b66ef770b6a11285f32d3ee6768cb69ca07cc2d9b3342546dd7730735ef`; 4 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-api.environment.json",
  "sha256": "8849517dd72d466ed3de28a0427bb85f344d923dc35ba18fbd8d939ee6cf054c",
  "status": "complete",
  "gitRevision": "5e3f95f85ea397c6f3f6a478279600caa815f6b2",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T15:51:11.554375+00:00",
  "finishedAt": "2026-09-17T15:54:35.882396+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 14,
  "implementationSha256": "3f8f9221596cd90c5f7cba4fbe783b608dc00885fdd4815d4632e9d521c65887"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 4
}
```

### final-extra: after-api-Collections.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-api-Collections.json`; SHA-256 `d1cb896910b01a3f3b6d3586073aba948d749eceefa214b9c725f8cf3b5f5cf5`; 4 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-api.environment.json",
  "sha256": "8849517dd72d466ed3de28a0427bb85f344d923dc35ba18fbd8d939ee6cf054c",
  "status": "complete",
  "gitRevision": "5e3f95f85ea397c6f3f6a478279600caa815f6b2",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T15:51:11.554375+00:00",
  "finishedAt": "2026-09-17T15:54:35.882396+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 14,
  "implementationSha256": "3f8f9221596cd90c5f7cba4fbe783b608dc00885fdd4815d4632e9d521c65887"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 4
}
```

### final-extra: after-api-Bytes.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-api-Bytes.json`; SHA-256 `cbfc17d82d86ad95b4a5fb2d68bf57b8c11f49d9e3e4ca480fed1ec37d01b413`; 2 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-api.environment.json",
  "sha256": "8849517dd72d466ed3de28a0427bb85f344d923dc35ba18fbd8d939ee6cf054c",
  "status": "complete",
  "gitRevision": "5e3f95f85ea397c6f3f6a478279600caa815f6b2",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T15:51:11.554375+00:00",
  "finishedAt": "2026-09-17T15:54:35.882396+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 14,
  "implementationSha256": "3f8f9221596cd90c5f7cba4fbe783b608dc00885fdd4815d4632e9d521c65887"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 2
}
```

### final-extra: after-api-NestedUnion.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-api-NestedUnion.json`; SHA-256 `63cd3d97fb813fe566ae405ced5d34479cf32f3de1e77751d538d80380a2a392`; 2 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-api.environment.json",
  "sha256": "8849517dd72d466ed3de28a0427bb85f344d923dc35ba18fbd8d939ee6cf054c",
  "status": "complete",
  "gitRevision": "5e3f95f85ea397c6f3f6a478279600caa815f6b2",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T15:51:11.554375+00:00",
  "finishedAt": "2026-09-17T15:54:35.882396+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 14,
  "implementationSha256": "3f8f9221596cd90c5f7cba4fbe783b608dc00885fdd4815d4632e9d521c65887"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 2
}
```

### final-extra: after-evolution-Evolution.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-evolution-Evolution.json`; SHA-256 `d32a12cba255bfa50196b31d3d2feaa17ad8d706b190475c32fd123582fba20d`; 8 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-evolution.environment.json",
  "sha256": "c42965df40bc708f231c05c50505c10d7baf40951b9bdbbefd22f773a54f7a2c",
  "status": "complete",
  "gitRevision": "5e3f95f85ea397c6f3f6a478279600caa815f6b2",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T15:54:35.971569+00:00",
  "finishedAt": "2026-09-17T15:56:04.223975+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 14,
  "implementationSha256": "3f8f9221596cd90c5f7cba4fbe783b608dc00885fdd4815d4632e9d521c65887"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 8
}
```

### final-extra: after-supplementary-String.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-supplementary-String.json`; SHA-256 `107444b845f5308b1f51e1460f862ff39202fff64d39d95a7785641f5720c6e8`; 8 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/after-supplementary.environment.json",
  "sha256": "0d91067b7297431cc630ef218adab11a71f4b540fb8f960f318cbad6caa03264",
  "status": "complete",
  "gitRevision": "5e3f95f85ea397c6f3f6a478279600caa815f6b2",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T15:56:04.316270+00:00",
  "finishedAt": "2026-09-17T15:57:32.028791+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 14,
  "implementationSha256": "3f8f9221596cd90c5f7cba4fbe783b608dc00885fdd4815d4632e9d521c65887"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 8
}
```

### before-extra: before-strings-String.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before-strings-String.json`; SHA-256 `938877ff37d74a244b8492e22432614432fb8900e8307641b6977242e44ed2a0`; 26 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before-strings.environment.json",
  "sha256": "91d48b77e6813b4c6eca263fbb3fc630e3d724b812f83a4cde468be2a0bd98c4",
  "status": "complete",
  "gitRevision": "59d35ee06fe8bcb624ce448470018accaa9fcb9e",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T14:16:06.034237+00:00",
  "finishedAt": "2026-09-17T14:17:52.313427+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 12,
  "implementationSha256": "98cbd9fc19f0f82b66e433aa395bc9726b707ca061d92c0e3ed3d5ec362fe52c"
}
```

```json
{
  "forks": 1,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 4,
  "measurementTime": "300 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "300 ms",
  "cases": 26
}
```

### before-extra: before-api-Integer.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before-api-Integer.json`; SHA-256 `3c0034c63cebf92faa504cfbce510f2ba4e7100c825ff5d87b63f9b1a456a9bf`; 2 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before-api.environment.json",
  "sha256": "ad85572f9d75a51896dedb904e26451532f814aaeb3f28bea33af324a79d0f36",
  "status": "complete",
  "gitRevision": "59d35ee06fe8bcb624ce448470018accaa9fcb9e",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/baseline-confirm",
  "startedAt": "2026-09-17T15:47:45.725113+00:00",
  "finishedAt": "2026-09-17T15:51:11.429295+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 12,
  "implementationSha256": "98cbd9fc19f0f82b66e433aa395bc9726b707ca061d92c0e3ed3d5ec362fe52c"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 2
}
```

### before-extra: before-api-String.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before-api-String.json`; SHA-256 `e1f988e0b17d42422323f707f653703c0b657d926bebb8b41732ca8e7e721afe`; 4 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before-api.environment.json",
  "sha256": "ad85572f9d75a51896dedb904e26451532f814aaeb3f28bea33af324a79d0f36",
  "status": "complete",
  "gitRevision": "59d35ee06fe8bcb624ce448470018accaa9fcb9e",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/baseline-confirm",
  "startedAt": "2026-09-17T15:47:45.725113+00:00",
  "finishedAt": "2026-09-17T15:51:11.429295+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 12,
  "implementationSha256": "98cbd9fc19f0f82b66e433aa395bc9726b707ca061d92c0e3ed3d5ec362fe52c"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 4
}
```

### before-extra: before-api-Collections.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before-api-Collections.json`; SHA-256 `fdb8ed6e94bdc0ab2cf70cfdf4a6a4c35524bbbf57a392e1463ed2209b69ea9a`; 4 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before-api.environment.json",
  "sha256": "ad85572f9d75a51896dedb904e26451532f814aaeb3f28bea33af324a79d0f36",
  "status": "complete",
  "gitRevision": "59d35ee06fe8bcb624ce448470018accaa9fcb9e",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/baseline-confirm",
  "startedAt": "2026-09-17T15:47:45.725113+00:00",
  "finishedAt": "2026-09-17T15:51:11.429295+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 12,
  "implementationSha256": "98cbd9fc19f0f82b66e433aa395bc9726b707ca061d92c0e3ed3d5ec362fe52c"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 4
}
```

### before-extra: before-api-Bytes.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before-api-Bytes.json`; SHA-256 `8cfea2195f35c473c351b3976c93fd0ebe6fe8fae3fc2bbd63fe8bb66a628e0f`; 2 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before-api.environment.json",
  "sha256": "ad85572f9d75a51896dedb904e26451532f814aaeb3f28bea33af324a79d0f36",
  "status": "complete",
  "gitRevision": "59d35ee06fe8bcb624ce448470018accaa9fcb9e",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/baseline-confirm",
  "startedAt": "2026-09-17T15:47:45.725113+00:00",
  "finishedAt": "2026-09-17T15:51:11.429295+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 12,
  "implementationSha256": "98cbd9fc19f0f82b66e433aa395bc9726b707ca061d92c0e3ed3d5ec362fe52c"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 2
}
```

### before-extra: before-api-NestedUnion.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before-api-NestedUnion.json`; SHA-256 `273a39424ff84af9bcad3a21ecc0e60d97ceac9f47b8e0c6f2b7ab1654f8b6b2`; 2 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before-api.environment.json",
  "sha256": "ad85572f9d75a51896dedb904e26451532f814aaeb3f28bea33af324a79d0f36",
  "status": "complete",
  "gitRevision": "59d35ee06fe8bcb624ce448470018accaa9fcb9e",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/baseline-confirm",
  "startedAt": "2026-09-17T15:47:45.725113+00:00",
  "finishedAt": "2026-09-17T15:51:11.429295+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 12,
  "implementationSha256": "98cbd9fc19f0f82b66e433aa395bc9726b707ca061d92c0e3ed3d5ec362fe52c"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 2
}
```

### confirmed-before: before-emoji-confirm-String.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before-emoji-confirm-String.json`; SHA-256 `8ef17fff1756a578eb4b1d4bf4cb2d0bf434770e7adb957e48c313cba2bce678`; 2 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/before-emoji-confirm.environment.json",
  "sha256": "7ae0d7e7471a94848250fe3f6d637d274bdf9077f6e530c08e334ceff334e973",
  "status": "complete",
  "gitRevision": "59d35ee06fe8bcb624ce448470018accaa9fcb9e",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/baseline-confirm",
  "startedAt": "2026-09-17T15:08:44.390489+00:00",
  "finishedAt": "2026-09-17T15:10:11.166125+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 12,
  "implementationSha256": "98cbd9fc19f0f82b66e433aa395bc9726b707ca061d92c0e3ed3d5ec362fe52c"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 2
}
```

### confirmed-after: confirm-small-ints-Comparison.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/confirm-small-ints-Comparison.json`; SHA-256 `64c4869f7390d96b1a91fb88a04627b7ba3909d3e4b47af4954819ba889c1a19`; 2 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/confirm-small-ints.environment.json",
  "sha256": "ecb16b63384286d7eabec257ff6275242fe1706de41e26aec96879c5bdb6555b",
  "status": "complete",
  "gitRevision": "5e3f95f85ea397c6f3f6a478279600caa815f6b2",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T15:57:32.119211+00:00",
  "finishedAt": "2026-09-17T15:58:30.020567+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 14,
  "implementationSha256": "3f8f9221596cd90c5f7cba4fbe783b608dc00885fdd4815d4632e9d521c65887"
}
```

```json
{
  "forks": 2,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 5,
  "measurementTime": "1 s",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 5,
  "warmupTime": "1 s",
  "cases": 2
}
```

### supplementary-control-pilot: supplementary-control-String.json

Source: `/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/supplementary-control-String.json`; SHA-256 `e349fec5e6983514e279bb933cc12c0c8ffca8b3f5cc96bf8026c591e232c3da`; 10 cases.

Environment manifest:

```json
{
  "path": "/private/tmp/avro2s-speed-255_nufn/work/docs/benchmarks/speed-2026-09-17/raw/supplementary-control.environment.json",
  "sha256": "4bad28ac195c8b7950b4e724be0280820a36cada226bd841b53d4dc3bc6fb42d",
  "status": "complete",
  "gitRevision": "6e6c0effdec43d61cd978ec1a970a645459487f2",
  "sourceRoot": "/private/tmp/avro2s-speed-255_nufn/work",
  "startedAt": "2026-09-17T15:12:47.066870+00:00",
  "finishedAt": "2026-09-17T15:13:47.625974+00:00",
  "sourcesUnchanged": true,
  "platform": "macOS-15.4-arm64-arm-64bit",
  "machine": "arm64",
  "javaVersion": "openjdk version \"21\" 2023-09-19 LTS\nOpenJDK Runtime Environment Corretto-21.0.0.35.1 (build 21+35-LTS)\nOpenJDK 64-Bit Server VM Corretto-21.0.0.35.1 (build 21+35-LTS, mixed mode, sharing)\n",
  "implementationFiles": 14,
  "implementationSha256": "3f8f9221596cd90c5f7cba4fbe783b608dc00885fdd4815d4632e9d521c65887"
}
```

```json
{
  "forks": 1,
  "jdkVersion": "21",
  "jmhVersion": "1.37",
  "jvmArgs": [
    "-Xms512m",
    "-Xmx512m"
  ],
  "measurementIterations": 4,
  "measurementTime": "500 ms",
  "mode": "avgt",
  "threads": 1,
  "vmName": "OpenJDK 64-Bit Server VM",
  "vmVersion": "21+35-LTS",
  "warmupIterations": 3,
  "warmupTime": "500 ms",
  "cases": 10
}
```

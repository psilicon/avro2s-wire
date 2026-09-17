# Full benchmark results — 17 September 2026

All observations are retained. Broad and longer confirmation runs are summarized separately.

## Broad matching-schema run

Raw JSON: [comparison-Comparison.json](comparison-Comparison.json), [comparison-NestedComparison.json](comparison-NestedComparison.json), [comparison-LogicalComparison.json](comparison-LogicalComparison.json), [comparison-DecimalComparison.json](comparison-DecimalComparison.json).

### ComparisonBenchmark: profile=bytes

| Method | ns/op | ±99.9% CI | B/op |
| --- | ---: | ---: | ---: |
| avro2sRead | 349.99 | 4.63 | 8,400.00 |
| avro2sWrite | 355.05 | 20.04 | 4,224.00 |
| javaCustomRead | 267.97 | 3.64 | 4,352.00 |
| javaCustomWrite | 309.97 | 12.39 | 4,112.00 |
| javaGenericRead | 266.94 | 9.74 | 4,360.00 |
| javaGenericWrite | 320.64 | 9.86 | 4,140.00 |
| javaPrimitivesRead | 342.84 | 14.18 | 8,400.00 |
| javaPrimitivesWrite | 118.51 | 3.48 | 0.00 |
| javaSpecificRead | 262.45 | 5.72 | 4,328.00 |
| javaSpecificWrite | 335.19 | 2.56 | 4,168.00 |
| nativeRead | 169.85 | 1.37 | 4,192.00 |
| nativeWrite | 85.10 | 6.81 | 0.00 |

Ratios are comparison time / native time; above 1 means native took less time. They are ratios of means, not confidence intervals for speedups.

| Comparison | Read ratio | Write ratio | Read CIs overlap? | Write CIs overlap? |
| --- | ---: | ---: | :---: | :---: |
| avro2s | 2.06× | 4.17× | no | no |
| javaCustom | 1.58× | 3.64× | no | no |
| javaGeneric | 1.57× | 3.77× | no | no |
| javaPrimitives | 2.02× | 1.39× | no | no |
| javaSpecific | 1.55× | 3.94× | no | no |

### ComparisonBenchmark: profile=collections-empty

| Method | ns/op | ±99.9% CI | B/op |
| --- | ---: | ---: | ---: |
| avro2sRead | 61.84 | 4.41 | 432.00 |
| avro2sWrite | 48.98 | 1.65 | 152.00 |
| javaCustomRead | 83.71 | 10.71 | 344.00 |
| javaCustomWrite | 29.30 | 0.13 | 0.00 |
| javaGenericRead | 72.33 | 10.20 | 344.00 |
| javaGenericWrite | 32.44 | 0.20 | 0.00 |
| javaPrimitivesRead | 29.98 | 1.25 | 256.00 |
| javaPrimitivesWrite | 29.21 | 0.11 | 0.00 |
| javaSpecificRead | 47.83 | 6.91 | 320.00 |
| javaSpecificWrite | 32.07 | 0.10 | 0.00 |
| nativeRead | 7.05 | 0.03 | 72.00 |
| nativeWrite | 4.18 | 0.01 | 0.00 |

Ratios are comparison time / native time; above 1 means native took less time. They are ratios of means, not confidence intervals for speedups.

| Comparison | Read ratio | Write ratio | Read CIs overlap? | Write CIs overlap? |
| --- | ---: | ---: | :---: | :---: |
| avro2s | 8.77× | 11.72× | no | no |
| javaCustom | 11.87× | 7.01× | no | no |
| javaGeneric | 10.26× | 7.76× | no | no |
| javaPrimitives | 4.25× | 6.99× | no | no |
| javaSpecific | 6.79× | 7.68× | no | no |

### ComparisonBenchmark: profile=collections-full

| Method | ns/op | ±99.9% CI | B/op |
| --- | ---: | ---: | ---: |
| avro2sRead | 15,884.49 | 110.04 | 51,768.22 |
| avro2sWrite | 4,050.40 | 52.38 | 11,384.06 |
| javaCustomRead | 4,647.55 | 107.83 | 11,864.06 |
| javaCustomWrite | 2,228.52 | 7.58 | 5,120.03 |
| javaGenericRead | 3,117.14 | 43.36 | 12,360.04 |
| javaGenericWrite | 2,654.29 | 31.89 | 5,136.04 |
| javaPrimitivesRead | 10,733.78 | 69.59 | 23,832.15 |
| javaPrimitivesWrite | 3,037.05 | 180.22 | 5,360.04 |
| javaSpecificRead | 3,116.51 | 50.13 | 12,336.04 |
| javaSpecificWrite | 2,611.61 | 13.03 | 5,120.04 |
| nativeRead | 9,705.78 | 105.51 | 23,668.13 |
| nativeWrite | 2,595.49 | 17.44 | 240.04 |

Ratios are comparison time / native time; above 1 means native took less time. They are ratios of means, not confidence intervals for speedups.

| Comparison | Read ratio | Write ratio | Read CIs overlap? | Write CIs overlap? |
| --- | ---: | ---: | :---: | :---: |
| avro2s | 1.64× | 1.56× | no | no |
| javaCustom | 0.48× | 0.86× | no | no |
| javaGeneric | 0.32× | 1.02× | no | no |
| javaPrimitives | 1.11× | 1.17× | no | no |
| javaSpecific | 0.32× | 1.01× | no | yes |

### ComparisonBenchmark: profile=enum-fixed

| Method | ns/op | ±99.9% CI | B/op |
| --- | ---: | ---: | ---: |
| avro2sRead | 1,780.75 | 225.02 | 4,864.02 |
| avro2sWrite | 1,122.52 | 73.21 | 2,200.02 |
| javaCustomRead | 1,717.17 | 151.63 | 3,232.02 |
| javaCustomWrite | 180.26 | 2.16 | 0.00 |
| javaGenericRead | 813.25 | 28.73 | 2,440.01 |
| javaGenericWrite | 523.38 | 2.78 | 0.01 |
| javaPrimitivesRead | 398.67 | 2.87 | 2,776.01 |
| javaPrimitivesWrite | 204.62 | 9.40 | 0.00 |
| javaSpecificRead | 1,106.32 | 12.82 | 2,944.02 |
| javaSpecificWrite | 439.69 | 2.97 | 0.01 |
| nativeRead | 397.51 | 1.52 | 2,688.01 |
| nativeWrite | 160.89 | 0.52 | 0.00 |

Ratios are comparison time / native time; above 1 means native took less time. They are ratios of means, not confidence intervals for speedups.

| Comparison | Read ratio | Write ratio | Read CIs overlap? | Write CIs overlap? |
| --- | ---: | ---: | :---: | :---: |
| avro2s | 4.48× | 6.98× | no | no |
| javaCustom | 4.32× | 1.12× | no | no |
| javaGeneric | 2.05× | 3.25× | no | no |
| javaPrimitives | 1.00× | 1.27× | yes | no |
| javaSpecific | 2.78× | 2.73× | no | no |

### ComparisonBenchmark: profile=ints-small

| Method | ns/op | ±99.9% CI | B/op |
| --- | ---: | ---: | ---: |
| avro2sRead | 11,400.53 | 126.21 | 33,160.16 |
| avro2sWrite | 14,292.40 | 212.32 | 32,968.20 |
| javaCustomRead | 14,457.06 | 384.22 | 4,368.20 |
| javaCustomWrite | 1,820.54 | 18.09 | 0.02 |
| javaGenericRead | 3,911.37 | 42.09 | 4,376.05 |
| javaGenericWrite | 4,548.51 | 77.44 | 0.06 |
| javaPrimitivesRead | 3,610.69 | 14.68 | 5,176.05 |
| javaPrimitivesWrite | 2,627.44 | 32.32 | 80.04 |
| javaSpecificRead | 3,926.75 | 46.98 | 4,344.05 |
| javaSpecificWrite | 4,562.91 | 98.37 | 16.06 |
| nativeRead | 3,613.86 | 11.25 | 5,072.05 |
| nativeWrite | 2,340.13 | 15.46 | 80.03 |

Ratios are comparison time / native time; above 1 means native took less time. They are ratios of means, not confidence intervals for speedups.

| Comparison | Read ratio | Write ratio | Read CIs overlap? | Write CIs overlap? |
| --- | ---: | ---: | :---: | :---: |
| avro2s | 3.15× | 6.11× | no | no |
| javaCustom | 4.00× | 0.78× | no | no |
| javaGeneric | 1.08× | 1.94× | no | no |
| javaPrimitives | 1.00× | 1.12× | yes | no |
| javaSpecific | 1.09× | 1.95× | no | no |

### ComparisonBenchmark: profile=ints-wide

| Method | ns/op | ±99.9% CI | B/op |
| --- | ---: | ---: | ---: |
| avro2sRead | 12,848.77 | 113.22 | 65,928.18 |
| avro2sWrite | 15,541.44 | 148.54 | 49,352.21 |
| javaCustomRead | 16,068.76 | 431.32 | 20,752.22 |
| javaCustomWrite | 3,316.94 | 14.57 | 0.05 |
| javaGenericRead | 5,120.69 | 44.59 | 20,760.07 |
| javaGenericWrite | 6,046.86 | 424.74 | 0.08 |
| javaPrimitivesRead | 4,810.64 | 170.95 | 21,560.07 |
| javaPrimitivesWrite | 4,183.86 | 36.26 | 80.06 |
| javaSpecificRead | 5,145.51 | 41.84 | 20,728.07 |
| javaSpecificWrite | 5,911.22 | 75.96 | 0.08 |
| nativeRead | 7,798.33 | 346.46 | 21,456.11 |
| nativeWrite | 4,498.62 | 17.07 | 80.06 |

Ratios are comparison time / native time; above 1 means native took less time. They are ratios of means, not confidence intervals for speedups.

| Comparison | Read ratio | Write ratio | Read CIs overlap? | Write CIs overlap? |
| --- | ---: | ---: | :---: | :---: |
| avro2s | 1.65× | 3.45× | no | no |
| javaCustom | 2.06× | 0.74× | no | no |
| javaGeneric | 0.66× | 1.34× | no | no |
| javaPrimitives | 0.62× | 0.93× | no | no |
| javaSpecific | 0.66× | 1.31× | no | no |

### ComparisonBenchmark: profile=longs-mixed

| Method | ns/op | ±99.9% CI | B/op |
| --- | ---: | ---: | ---: |
| avro2sRead | 14,089.88 | 165.46 | 77,992.19 |
| avro2sWrite | 17,268.14 | 1,026.93 | 55,384.24 |
| javaCustomRead | 20,340.94 | 3,928.33 | 26,784.28 |
| javaCustomWrite | 3,890.99 | 16.87 | 0.05 |
| javaGenericRead | 6,167.12 | 45.53 | 26,792.08 |
| javaGenericWrite | 6,685.41 | 203.36 | 32.09 |
| javaPrimitivesRead | 5,425.67 | 280.44 | 27,592.07 |
| javaPrimitivesWrite | 4,853.19 | 75.86 | 80.07 |
| javaSpecificRead | 6,184.93 | 46.76 | 26,760.09 |
| javaSpecificWrite | 6,755.51 | 157.68 | 32.09 |
| nativeRead | 7,949.78 | 68.25 | 27,488.11 |
| nativeWrite | 5,271.91 | 10.16 | 80.07 |

Ratios are comparison time / native time; above 1 means native took less time. They are ratios of means, not confidence intervals for speedups.

| Comparison | Read ratio | Write ratio | Read CIs overlap? | Write CIs overlap? |
| --- | ---: | ---: | :---: | :---: |
| avro2s | 1.77× | 3.28× | no | no |
| javaCustom | 2.56× | 0.74× | no | no |
| javaGeneric | 0.78× | 1.27× | no | no |
| javaPrimitives | 0.68× | 0.92× | no | no |
| javaSpecific | 0.78× | 1.28× | no | no |

### ComparisonBenchmark: profile=numerics

| Method | ns/op | ±99.9% CI | B/op |
| --- | ---: | ---: | ---: |
| avro2sRead | 6,617.39 | 47.40 | 23,224.09 |
| avro2sWrite | 5,746.39 | 11.67 | 17,640.08 |
| javaCustomRead | 6,275.14 | 14.13 | 7,048.09 |
| javaCustomWrite | 594.99 | 5.49 | 0.01 |
| javaGenericRead | 2,565.91 | 107.41 | 7,088.04 |
| javaGenericWrite | 1,965.86 | 12.74 | 0.03 |
| javaPrimitivesRead | 1,645.28 | 14.76 | 7,856.02 |
| javaPrimitivesWrite | 1,200.92 | 12.23 | 240.02 |
| javaSpecificRead | 2,533.58 | 9.03 | 7,064.03 |
| javaSpecificWrite | 1,953.64 | 26.25 | 40.03 |
| nativeRead | 1,480.34 | 45.93 | 7,800.02 |
| nativeWrite | 1,414.01 | 47.80 | 240.02 |

Ratios are comparison time / native time; above 1 means native took less time. They are ratios of means, not confidence intervals for speedups.

| Comparison | Read ratio | Write ratio | Read CIs overlap? | Write CIs overlap? |
| --- | ---: | ---: | :---: | :---: |
| avro2s | 4.47× | 4.06× | no | no |
| javaCustom | 4.24× | 0.42× | no | no |
| javaGeneric | 1.73× | 1.39× | no | no |
| javaPrimitives | 1.11× | 0.85× | no | no |
| javaSpecific | 1.71× | 1.38× | no | no |

### ComparisonBenchmark: profile=string-ascii

| Method | ns/op | ±99.9% CI | B/op |
| --- | ---: | ---: | ---: |
| avro2sRead | 117.80 | 5.16 | 2,264.00 |
| avro2sWrite | 100.02 | 1.50 | 1,040.00 |
| javaCustomRead | 95.38 | 4.64 | 1,256.00 |
| javaCustomWrite | 95.47 | 0.25 | 1,040.00 |
| javaGenericRead | 67.61 | 0.56 | 1,264.00 |
| javaGenericWrite | 99.83 | 2.05 | 1,040.00 |
| javaPrimitivesRead | 106.72 | 0.35 | 2,264.00 |
| javaPrimitivesWrite | 94.16 | 0.67 | 1,040.00 |
| javaSpecificRead | 68.77 | 0.62 | 1,232.00 |
| javaSpecificWrite | 104.75 | 8.32 | 1,040.00 |
| nativeRead | 234.90 | 0.60 | 1,128.00 |
| nativeWrite | 391.83 | 1.40 | 0.01 |

Ratios are comparison time / native time; above 1 means native took less time. They are ratios of means, not confidence intervals for speedups.

| Comparison | Read ratio | Write ratio | Read CIs overlap? | Write CIs overlap? |
| --- | ---: | ---: | :---: | :---: |
| avro2s | 0.50× | 0.26× | no | no |
| javaCustom | 0.41× | 0.24× | no | no |
| javaGeneric | 0.29× | 0.25× | no | no |
| javaPrimitives | 0.45× | 0.24× | no | no |
| javaSpecific | 0.29× | 0.27× | no | no |

### ComparisonBenchmark: profile=string-unicode

| Method | ns/op | ±99.9% CI | B/op |
| --- | ---: | ---: | ---: |
| avro2sRead | 2,425.34 | 94.40 | 14,072.03 |
| avro2sWrite | 1,971.86 | 18.84 | 6,688.03 |
| javaCustomRead | 173.45 | 2.23 | 3,048.00 |
| javaCustomWrite | 1,929.40 | 18.14 | 6,688.03 |
| javaGenericRead | 153.19 | 6.81 | 3,056.00 |
| javaGenericWrite | 1,975.11 | 59.67 | 6,688.03 |
| javaPrimitivesRead | 2,335.70 | 7.98 | 14,072.03 |
| javaPrimitivesWrite | 1,920.03 | 7.08 | 6,688.03 |
| javaSpecificRead | 151.94 | 7.42 | 3,024.00 |
| javaSpecificWrite | 1,925.47 | 9.26 | 6,688.03 |
| nativeRead | 4,211.31 | 18.73 | 11,144.06 |
| nativeWrite | 3,706.17 | 50.05 | 0.05 |

Ratios are comparison time / native time; above 1 means native took less time. They are ratios of means, not confidence intervals for speedups.

| Comparison | Read ratio | Write ratio | Read CIs overlap? | Write CIs overlap? |
| --- | ---: | ---: | :---: | :---: |
| avro2s | 0.58× | 0.53× | no | no |
| javaCustom | 0.04× | 0.52× | no | no |
| javaGeneric | 0.04× | 0.53× | no | no |
| javaPrimitives | 0.55× | 0.52× | no | no |
| javaSpecific | 0.04× | 0.52× | no | no |

### DecimalComparisonBenchmark: single workload

| Method | ns/op | ±99.9% CI | B/op |
| --- | ---: | ---: | ---: |
| javaGenericRead | 119.13 | 1.58 | 696.00 |
| javaGenericWrite | 183.78 | 7.88 | 248.00 |
| javaPrimitivesRead | 92.43 | 0.38 | 680.00 |
| javaPrimitivesWrite | 97.44 | 110.49 | 128.00 |
| javaSpecificRead | 157.71 | 1.14 | 688.00 |
| javaSpecificWrite | 173.14 | 8.04 | 248.00 |
| nativeRead | 86.85 | 1.09 | 544.00 |
| nativeWrite | 52.60 | 2.72 | 128.00 |

Ratios are comparison time / native time; above 1 means native took less time. They are ratios of means, not confidence intervals for speedups.

| Comparison | Read ratio | Write ratio | Read CIs overlap? | Write CIs overlap? |
| --- | ---: | ---: | :---: | :---: |
| javaGeneric | 1.37× | 3.49× | no | no |
| javaPrimitives | 1.06× | 1.85× | no | yes |
| javaSpecific | 1.82× | 3.29× | no | no |

### LogicalComparisonBenchmark: single workload

| Method | ns/op | ±99.9% CI | B/op |
| --- | ---: | ---: | ---: |
| avro2sRead | 226.13 | 7.45 | 1,008.00 |
| avro2sWrite | 191.15 | 3.53 | 232.00 |
| javaGenericRead | 172.16 | 2.92 | 832.00 |
| javaGenericWrite | 286.63 | 2.90 | 232.00 |
| javaPrimitivesRead | 210.50 | 2.52 | 640.00 |
| javaPrimitivesWrite | 93.50 | 2.18 | 56.00 |
| javaSpecificRead | 178.19 | 1.63 | 808.00 |
| javaSpecificWrite | 190.81 | 9.78 | 232.00 |
| nativeRead | 203.07 | 14.81 | 472.00 |
| nativeWrite | 77.47 | 0.63 | 80.00 |

Ratios are comparison time / native time; above 1 means native took less time. They are ratios of means, not confidence intervals for speedups.

| Comparison | Read ratio | Write ratio | Read CIs overlap? | Write CIs overlap? |
| --- | ---: | ---: | :---: | :---: |
| avro2s | 1.11× | 2.47× | no | no |
| javaGeneric | 0.85× | 3.70× | no | no |
| javaPrimitives | 1.04× | 1.21× | yes | no |
| javaSpecific | 0.88× | 2.46× | no | no |

### NestedComparisonBenchmark: single workload

| Method | ns/op | ±99.9% CI | B/op |
| --- | ---: | ---: | ---: |
| avro2sRead | 3,594.23 | 95.14 | 13,176.05 |
| avro2sWrite | 3,562.33 | 271.28 | 7,056.05 |
| javaGenericRead | 2,715.84 | 64.75 | 4,912.04 |
| javaGenericWrite | 1,898.14 | 42.27 | 1,128.03 |
| javaPrimitivesRead | 1,362.73 | 8.18 | 9,432.02 |
| javaPrimitivesWrite | 1,118.13 | 19.49 | 2,888.02 |
| javaSpecificRead | 1,940.83 | 15.67 | 4,192.03 |
| javaSpecificWrite | 2,035.08 | 38.38 | 1,488.03 |
| nativeRead | 1,294.55 | 24.41 | 9,920.02 |
| nativeWrite | 655.71 | 17.23 | 1,760.01 |

Ratios are comparison time / native time; above 1 means native took less time. They are ratios of means, not confidence intervals for speedups.

| Comparison | Read ratio | Write ratio | Read CIs overlap? | Write CIs overlap? |
| --- | ---: | ---: | :---: | :---: |
| avro2s | 2.78× | 5.43× | no | no |
| javaGeneric | 2.10× | 2.89× | no | no |
| javaPrimitives | 1.05× | 1.71× | no | no |
| javaSpecific | 1.50× | 3.10× | no | no |

## Schema evolution

Raw JSON: [evolution-Evolution.json](evolution-Evolution.json).

### EvolutionBenchmark: discardedBytes=0

| Method | ns/op | ±99.9% CI | B/op |
| --- | ---: | ---: | ---: |
| compileResolution | 23,252.66 | 516.78 | 130,193.56 |
| javaResolved | 364.50 | 2.40 | 1,840.01 |
| nativeResolved | 331.77 | 11.26 | 640.00 |
| nativeSameSchema | 142.10 | 33.12 | 432.00 |

### EvolutionBenchmark: discardedBytes=4096

| Method | ns/op | ±99.9% CI | B/op |
| --- | ---: | ---: | ---: |
| compileResolution | 23,409.59 | 329.85 | 130,300.53 |
| javaResolved | 369.35 | 21.19 | 1,840.01 |
| nativeResolved | 333.66 | 12.49 | 640.00 |
| nativeSameSchema | 215.58 | 5.01 | 4,544.00 |

## Longer string confirmation

Raw JSON: [confirm-strings-Comparison.json](confirm-strings-Comparison.json).

### ComparisonBenchmark: profile=string-ascii

| Method | ns/op | ±99.9% CI | B/op |
| --- | ---: | ---: | ---: |
| avro2sRead | 117.70 | 3.17 | 2,264.00 |
| avro2sWrite | 106.51 | 9.96 | 1,040.00 |
| nativeRead | 234.34 | 2.97 | 1,128.00 |
| nativeWrite | 393.07 | 3.33 | 0.00 |

Ratios are comparison time / native time; above 1 means native took less time. They are ratios of means, not confidence intervals for speedups.

| Comparison | Read ratio | Write ratio | Read CIs overlap? | Write CIs overlap? |
| --- | ---: | ---: | :---: | :---: |
| avro2s | 0.50× | 0.27× | no | no |

### ComparisonBenchmark: profile=string-unicode

| Method | ns/op | ±99.9% CI | B/op |
| --- | ---: | ---: | ---: |
| avro2sRead | 2,354.04 | 45.08 | 14,072.02 |
| avro2sWrite | 1,931.00 | 67.23 | 6,688.01 |
| nativeRead | 4,300.90 | 235.51 | 11,144.03 |
| nativeWrite | 3,691.94 | 102.13 | 0.03 |

Ratios are comparison time / native time; above 1 means native took less time. They are ratios of means, not confidence intervals for speedups.

| Comparison | Read ratio | Write ratio | Read CIs overlap? | Write CIs overlap? |
| --- | ---: | ---: | :---: | :---: |
| avro2s | 0.55× | 0.52× | no | no |

## Longer integer/map confirmation

Raw JSON: [confirm-reads-Comparison.json](confirm-reads-Comparison.json).

### ComparisonBenchmark: profile=collections-full

| Method | ns/op | ±99.9% CI | B/op |
| --- | ---: | ---: | ---: |
| javaSpecificRead | 3,251.97 | 47.98 | 12,336.02 |
| nativeRead | 9,888.13 | 136.66 | 23,656.07 |

Ratios are comparison time / native time; above 1 means native took less time. They are ratios of means, not confidence intervals for speedups.

| Comparison | Read ratio | Write ratio | Read CIs overlap? | Write CIs overlap? |
| --- | ---: | ---: | :---: | :---: |
| javaSpecific | 0.33× | — | no | — |

### ComparisonBenchmark: profile=ints-wide

| Method | ns/op | ±99.9% CI | B/op |
| --- | ---: | ---: | ---: |
| javaSpecificRead | 5,356.22 | 482.12 | 20,728.04 |
| nativeRead | 7,801.89 | 218.17 | 21,456.05 |

Ratios are comparison time / native time; above 1 means native took less time. They are ratios of means, not confidence intervals for speedups.

| Comparison | Read ratio | Write ratio | Read CIs overlap? | Write CIs overlap? |
| --- | ---: | ---: | :---: | :---: |
| javaSpecific | 0.69× | — | no | — |

## Longer decimal confirmation

Raw JSON: [confirm-decimal-DecimalComparison.json](confirm-decimal-DecimalComparison.json).

### DecimalComparisonBenchmark: single workload

| Method | ns/op | ±99.9% CI | B/op |
| --- | ---: | ---: | ---: |
| javaPrimitivesWrite | 92.02 | 64.52 | 128.00 |
| nativeWrite | 54.40 | 2.44 | 128.00 |

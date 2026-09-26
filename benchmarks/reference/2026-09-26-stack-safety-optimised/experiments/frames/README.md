# Intermediate frame implementation

Complete 30-case campaign `20260926T152616.037374Z-stack-safety`, before the
small leaf entry and in-place frame-resumption optimization in `Step.run`.
All cases passed, including the correctness gate and unchanged source hashes.
Raw results and the complete measured-source snapshot are preserved here.

This intermediate version substantially reduced allocation, but recursive
matching-schema decode still took 926 ns for 32 records versus 161 ns direct.
That result motivated the subsequent driver experiment. These are intermediate
measurements, not the final implementation's results. All forks are retained.

The final report describes the complete experiment sequence and qualifications.

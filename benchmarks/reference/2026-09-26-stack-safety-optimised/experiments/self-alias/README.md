# Self-reference diagnostic

Complete two-case campaign `20260926T154419.400502Z-stack-safety`: stack-safe
32-record encode/decode, one fork, five 500 ms warmups and three 500 ms measurements.
Both generated reads and writes used a codec self-alias in this experiment.
Correctness and unchanged source hashes passed. All observations/sources retained.

Compared with the preceding driver diagnostic, decode averaged 444 rather than
506 ns with unchanged allocation (2,872 B). Encode averaged 306 rather than
289 ns, but the earlier timing interval was wide; allocation rose from 1,480 to
1,736 B. The final candidate retains the read alias and restores the previous
writer lookup to avoid the extra writer-frame reference. These short campaigns
are exploratory, not paired statistical evidence of a general speedup.

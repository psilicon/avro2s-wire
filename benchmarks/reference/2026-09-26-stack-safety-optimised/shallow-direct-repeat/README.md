# Current direct shallow-decode repetition

Complete campaign `20260926T155858.209134Z-stack-safety`, using the final sources:
four forks, five 1-second warmups and five 1-second measurement iterations per
fork, matching the original shallow-decode repetition. All cases passed;
source hashes stayed unchanged. Results, metadata and the source snapshot are
exact copies. Every fork is retained.

Mean: 52.213 ± 2.637 ns/op (JMH 99.9% confidence error), 208 B/op.
Fork means: 49.692, 49.142, 56.190 and 53.829 ns/op.

The final two-fork campaign measured 56.336 ± 1.379 ns/op. Both are reported;
the repeat is used for the before/after direct comparison because it matches
the original four-fork protocol, not because its mean is lower. It is not
substituted into the final direct-versus-stack-safe table.

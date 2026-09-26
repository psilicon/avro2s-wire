# Driver diagnostic

Complete six-case campaign `20260926T153842.517842Z-stack-safety`: stack-safe
shallow/32-record inputs only, one fork, five 500 ms warmups and three 500 ms
measurements. This diagnostic follows the small leaf entry and in-place frame
resumption in `Step.run`, plus Direct resolution-plan factory specialization.
Correctness and unchanged source hashes passed. All raw observations and sources
are retained. These short measurements guide optimization; the final comparison
uses more forks and longer iterations.

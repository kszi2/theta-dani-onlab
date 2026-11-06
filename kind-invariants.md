# KIND/BMC proofs as DSS invariants

This branch is `dss-svcomp-fixes` plus one change: the safety proofs of the bounded checkers
(`BMC`, `KIND`, `IMC`, `KINDIMC`, `BOUNDED`) become sound over-approximations of the reachable
states, expressed over the program variables. DSS can then use them as block postconditions and as
witness invariants. The change was developed on `dss-bench-precfix` (`45794c128`) in the worktree
`C:\Users\danie\theta-kind-invariants` (branch `kind-invariants`, uncommitted there) and moved here.

## The problems

1. **No invariants from KIND.** A safe bounded run returned its proof as an existential path
   formula over fresh copies of every variable. DSS postconditions built from it were not over
   program variables, and the witness writer drops such conjuncts. So KIND blocks contributed no
   location invariants.
2. **The old proof under-approximated the reachable states.** It described the states at the end of
   k-step paths. A state reached only by a path shorter than k that cannot be extended (for example,
   a block's exit) was missing, so as a DSS postcondition it was unsound. `BoundedProofTest`
   reproduces this: the old `BoundedChecker` fails its coverage tests, the new one passes.

## What changed

- `BoundedChecker` (`common/analysis`) builds a new proof:
  - Loop-free BMC/IMC success at depth k: no simple path has k steps, so the states reached within
    k-1 steps are exactly the reachable states.
  - KIND success at depth k: the states reached in fewer than k steps, plus the ends of k-step paths
    whose first k states satisfy the property. This set is inductive and lies inside the property
    (BMC covers the first part, the induction step covers the second), and it contains every
    reachable state.
  - With `interpolateProof`, that set is projected onto the current state by interpolating it
    against the states that violate the property within one step. The interpolant is kept only if
    it mentions current-state variables only and is implied by the set, so it stays a sound
    over-approximation. It need not be inductive. If interpolation fails, or the option is off, the
    proof is the set itself, with the other path states as fresh variables (prefix `__boundedN_`,
    unique per proof), i.e. implicitly existential.
  - States are compared by representation for floats (`FpAssign`), so NaN equals itself.
- `XcfaSingleThreadToMonolithicAdapter`: an invariant over the encoding's `__edge_` variable is split
  per location into one disjunct per value that `__edge_` can take there: the indices of the
  location's incoming edges, plus -1 at `proc.initLoc`. The init location is found via
  `proc.initLoc`, not the `initial` flag, because DSS block programs start at a synthetic `__pre`
  location that is not flagged initial. The same fix applies to the transition-invariant split.
- `PredicateBlockBehavior`: a block's entry states are left out of its witness invariants unless the
  block is the root or re-enters its entry. Those states are its preconditions, which the
  predecessors' exits already cover, and an interpolant is often `true` there, which would erase
  the per-location union.
- Options:
  - `--interpolate-proof` (`BoundedConfig`, default `false`) for plain bounded runs.
  - `--dss-interpolate-bounded-proofs` (`DssConfig`, default `true`), applied by
    `ConfigToDssChecker` to every bounded block checker, including `emergent27`'s `KIND,CEGAR`.
  - Without interpolation, proofs still use the corrected (sound) proof.
- Docs: a DSS `README.md` option row and witness note, and a DSS `AGENTS.md` invariant.
- Tests:
  - `BoundedProofTest`: 4 tests.
  - `XcfaMonolithicTest`: the init location without the `initial` flag.
  - `XcfaCliDssTest`: a KIND-only witness that Theta validates, plus option rows `KIND`,
    `KIND --dss-interpolate-bounded-proofs false`, and `BMC`.

## Verification so far

On the original base (`45794c128`):

- Bounded tests: 13/13 pass. `XcfaMonolithicTest`: 10/10.
- `XcfaCliDssTest`: 72 cases ran, including every KIND/BMC row and the KIND witness test, all passing.
  - The 2 failures don't come from this change. `DssMeetingActorTest` "outdated input is not
    certified" touches no changed code. `XcfaCliDssTest` BACKWARD/LINEAR on `loop` also times out on
    clean HEAD.
  - The run was stopped early: its test JVM grew about 1 GB of native memory every 20 s.
- Hand-written programs, KIND blocks only: HEAD times out on `loop.c` and `short_path_5.c`; the new
  build solves both and writes loop invariants for `loop.c`.
- Partial local benchmark: 35 of 60 sv-benchmarks loop tasks, `--svcomp --architecture ILP32`,
  `--dss-checker-backends KIND`, 60 s timeout, a loaded Windows machine.
  - HEAD solved 8 tasks, the new build 5. Neither gave a wrong verdict.
  - Lost tasks:
    - `array_1-2` (timeout vs 21.7 s; possibly load).
    - `array_3-1` (fails loudly with DSS's "path violation condition ties nondeterministic values"
      guard).
    - `phases_2-2` (the JVM could not get memory: an environment problem).
  - Gained: `nested_1-1` now has 1 witness invariant (0 before).
  - This is not conclusive. It needs a benchcloud run.

On this branch (after the merge onto `dss-svcomp-fixes`): it compiles, and the focused tests pass:
`BoundedProofTest` (4/4), the new `XcfaMonolithicTest` case, and the KIND witness test in
`XcfaCliDssTest`. The full DSS suite and the option matrix have not been rerun here.

## Open points

- The interpolation horizon is one step. A k-step horizon gave interpolants of thousands of lines on
  `loop.c`, and inductive strengthening rounds made it time out, so both were dropped.
- A block whose property is trivially true (no error location) gets `true` from interpolation, as
  there is nothing to separate from.
- Interpolated proofs are not inductive. Consumers that need inductive invariants should leave
  interpolation off (`--dss-interpolate-bounded-proofs false` in DSS).
- Investigate the `array_3-1` regression above.
- Suspected and pre-existing, not changed here: the loop-free BMC check compares float variables
  with IEEE `Eq`, so states differing only in the sign of a zero count as equal, and a path through
  both is excluded.

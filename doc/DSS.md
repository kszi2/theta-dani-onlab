# Distributed Summary Synthesis (DSS) in Theta

Theta's DSS (`--backend DSS`) is a port of the distributed summary synthesis of

> D. Beyer, M. Kettl, T. Lemberger: *Decomposing Software Verification using Distributed Summary
> Synthesis*. Proc. ACM Softw. Eng. 1 (FSE), 2024.

and follows the reference implementation in CPAchecker
(`org.sosy_lab.cpachecker.core.algorithm.distributed_summaries`, configuration `config/dss.properties`)
as closely as Theta's architecture allows. Where CPAchecker and the paper disagree, Theta follows
CPAchecker. This document lists how the pieces map onto each other and every place where Theta
deliberately differs, and why.

## Modules

| Theta module | Contents | CPAchecker counterpart |
|---|---|---|
| `xcfa-dss-decomposition` | `Block`, `BlockGraph`, `LinearBlockDecomposition`, `MergeBlockDecomposition` (+ `BlockGraphMerging.kt`), `SingleBlockDecomposition`, `BlockGraphInstrumentation`, `BlockGraphDot` | `decomposition.*`, `decomposition.graph.*` |
| `xcfa-dss-analysis` | `extractBlockXcfa`, `computeViolationCondition` / `DssAuxVars`, `DssPredicateOperators`, `combinePredicatePrecisions`, `packPostcondition`, `runWorkerConfig`, `DssCheckerRoster`, `globalAssumePredicates` | `cpa.block.*` (`BlockCPA`), `distributed_cpa.predicate.*` operators |
| `xcfa-dss-actor` | messages, `newDssMessageQueue`, `DssMessageBroadcaster`, `DssBlockActor`, `PredicateBlockBehavior`, `DssObserverActor`, `DssVisualizationActor`, `DssThreadMonitor`, `runDssActors`, `runDssActorsSequentially` | `communication.*`, `worker.*`, `block_analysis.DssBlockAnalysis`, `executors.*` |
| `xcfa-cli` (`ConfigToDssChecker.kt`) | `--backend DSS` and the `--dss-*` options | `DistributedSummarySynthesis`, `DssDecompositionOptions`, `DssAnalysisOptions` |

## What is the same as in CPAchecker

### Decomposition

* `LINEAR` (`LinearBlockNodeDecomposition` with `dss.properties`' block operator): a block ends at
  every join point, branch point and dead end. Blocks are named `L0`, `L1`, ... in DFS order.
* `MERGE` (`MergeBlockNodesDecomposition`, **the default, as in CPAchecker**): alternates one
  horizontal pass and one vertical pass until the target block count is reached or a round makes
  no progress. Target defaults to `2`, CPAchecker's hardcoded value (`--dss-target-block-count`).
  * Horizontal pass: groups blocks with the same `(predecessors, successors, final location)`, in
    id order, merges each group into `MH<n>`, stops as soon as the target is reached; a group is
    skipped if more than one of its blocks has more locations than `largestHorizontalMerge`
    (`--dss-largest-horizontal-merge`, `-1` = no limit).
  * Vertical pass: a single walk over the id-sorted blocks that merges a block with its unique
    successor if that successor has a unique predecessor, into `MV<n>`; every block takes part in at
    most one merge per pass, and the pass stops as soon as the target is reached.
  * `allowSingleBlockDecompositionWhenMerging` (`--dss-allow-single-block-decomposition`).
* `NONE` (`SingleBlockDecomposition`): one block `SB1` from the entry to the last dead end found by
  a breadth-first traversal.
* `BlockGraph` wiring (`fromBlockNodesWithoutGraphInformation`): `Y` succeeds `X` iff `Y` starts
  where `X` ends. The block without predecessors is the root.
* `BlockGraphInstrumentation` mirrors `BlockGraphModification.instrumentCFA` (ghost edge to a fresh
  violation-condition location at every block end that has successors; no change for a single
  block) - but see below for why the CLI does not need it.

### Block analysis (`PredicateBlockBehavior` = `DssBlockAnalysis` + `DssAnalysisWorker.processMessage`)

* **Initial analysis** (`runInitialAnalysis`): analyze the block from the most general entry state
  (top). No target reachable: broadcast the postcondition (nothing if the block end is
  unreachable). Target reachable: mark the block as containing a violation, broadcast a top
  postcondition and a violation condition.
* **Postcondition received** (`storePrecondition` + `analyzePrecondition`): preconditions are a
  per-sender list of states (CPAchecker's multimap, *not* the paper's least upper bound). A new state
  removes the sender's states it implies, and is irrelevant if one of the sender's states implies
  it (`PredicateStateCoverageOperator`, an SMT implication check). If some predecessor has not
  reported yet or reported top, top is analyzed too (`appendTopToRelevantIfNecessary`). The block is
  only re-analyzed if it contains a violation or holds violation conditions - otherwise there is
  nothing a precondition could refute (CPAchecker propagates postconditions lazily, unlike
  Algorithm 3 of the paper).
* **Violation condition received** (`storeViolationCondition` + `analyzeViolationCondition`):
  unsatisfiable conditions are dropped (`ProceedPredicateStateOperator.processBackward`), as are
  repeated ones. The block is analyzed from every stored precondition separately, with the received
  condition as an additional target at the violation-condition location. A refuted condition makes
  the analysis refine (CEGAR) and yields more precise postconditions, which are broadcast; a
  reachable one yields a new violation condition for the predecessors - the weakest precondition of
  the block's paths to the target, conjoined with the received condition.
* **SCC handling**: as in CPAchecker, once every predecessor provided a non-trivial state, top
  states that were themselves computed from non-trivial preconditions are skipped (the
  `hasNonTrivialSummaryForEachPredecessor` flag travels with the postcondition). This is
  CPAchecker's realization of the paper's "only join postconditions of the same SCC that are unequal
  to the initial state", it does not compute SCCs with Tarjan's algorithm. Theta extends this rule,
  see "Deviations in the block analysis itself" below.
* **Precision**: postconditions carry the sender's predicates; non-top start states are analyzed
  with the combination of the received precisions (`CombinePredicatePrecisionOperator`: restricted
  to predicates over variables that occur in every precision, if there is one), top start states
  with the start precision; `--dss-reset-precision` = `resetPrecisionForEveryRun`.
* **Deduplication** of the reported postconditions (`deduplicateStates`): a state covered by an
  earlier one is dropped; all states of one analysis travel in one message.
* **Root block**: only reports satisfiable violation conditions (`hasRootAsPredecessor`), and a
  violation condition of the root means UNSAFE.
* Worker failures (including `Error`s) become `EXCEPTION` messages instead of silently killing the
  worker thread.

### Actors and termination

* Five message types (`POST_CONDITION`, `VIOLATION_CONDITION`, `EXCEPTION`, `RESULT`, `STATISTIC`).
* Routing (`DssAnalysisWorker.broadcast`): postconditions go to the observer and the successors,
  violation conditions to the observer and the predecessors, a violation condition of the root
  becomes `RESULT(UNSAFE)` to everyone, and `EXCEPTION`/`RESULT`/`STATISTIC` go to everyone.
* Inboxes prioritize `RESULT`/`EXCEPTION`/`STATISTIC` over analysis messages (`DssDefaultQueue`).
* SAFE is detected by quiescence (`DssThreadMonitor`): every block thread waiting, every queue
  empty, nobody processing.
* The observer waits for the result and one `STATISTIC` per block (`DssObserverWorker`).
* `CONCURRENT` executor = `MultithreadingDssExecutor` (one platform thread per block);
  `SEQUENTIAL` = `SequentialDssExecutor` (round-robin, one message per actor per round).

## Differences caused by Theta's architecture

1. **Blocks are analyzed as standalone programs.** CPAchecker restricts one shared CFA to a block
   with `BlockCPA` and places the only abstraction of the predicate analysis at the block end
   (`cpa.predicate.blk.alwaysAtGivenNodes`). Theta's checkers have no such switch (predicate CEGAR
   abstracts after every edge, large blocks are a static property of the XCFA), so
   `extractBlockXcfa` clones the block into its own XCFA and the block is checked by an ordinary
   checker (`runWorkerConfig`). Preconditions become an `assume` edge before the block entry, a
   violation condition becomes an edge from the block exit to an error location
   (`havoc` its auxiliary variables, then `assume` it). Abstracting inside a block is sound but can
   make the postcondition coarser than CPAchecker's for the same predicates.
2. **No ghost edges needed.** CPAchecker inserts a ghost edge at each block end so that the
   abstraction/target location of a block is not shared with the entry of the next block, and its
   `BlockCPA` distinguishes `INITIAL` from `FINAL` states for loop bodies whose entry is their exit.
   In an extracted block every location is private already; a block whose entry is its exit gets two
   separate clones of that location instead. `BlockGraphInstrumentation` still exists (mirroring
   CPAchecker), but the CLI does not run it - it would modify the input XCFA in place, while
   CPAchecker instruments a copy. Consequently CPAchecker's "no abstraction possible" special case
   (blocks ending in a function call) does not arise.
3. **Checkers stop at the first counterexample and do not expose an ARG.** CPAchecker's block
   analysis keeps exploring after a target and reads everything off the reached set. In Theta:
   * Violation conditions are computed from the block's own paths
     (`computeViolationCondition`): the disjunction of the weakest existential preconditions of all
     entry-to-target paths. Inside a block CPAchecker does not abstract either, so these are the same
     paths as in its ARG, except that the ARG may already omit paths that are infeasible together
     with the start state; since violation conditions never contain the precondition, Theta's
     condition can only carry extra, harmless disjuncts. Because the checker does not tell which
     target it reached, the condition covers the block's own targets (if it contains a violation)
     and the received conditions together.
   * When a target is reachable, the block's postcondition is computed by a second run without
     targets (CPAchecker gets it from the same run).
   * In the initial analysis, "the block end was reached" (CPAchecker sends a top postcondition
     then) is approximated by "the block has successors".
4. **Violation conditions are expressions, not SSA path formulas.** CPAchecker's conditions are
   backward path formulas whose intermediate SSA variables are implicitly existential. Theta's must
   fit on an `assume` edge of the predecessor block, so a `havoc` is encoded with an auxiliary
   variable (`DssAuxVars`) that the receiving block havocs before assuming the condition.
   Auxiliary variables are interned and renumbered canonically, so repeated conditions are
   recognized by structural equality (CPAchecker compares the `ViolationWitness`, i.e. the path
   edges). Labels other than assumptions, assignments, havocs, skips, sequences and nondeterministic
   choices (e.g. function calls, memory operations) are not supported yet and make the analysis fail
   with an exception message.
5. **Precision transport is approximated.** Theta's checkers do not return their final precision,
   so a block transmits the atoms of all states it reached (plus the precision it started with)
   instead of its `PredicatePrecision`. Precisions are global sets of predicates (Theta's default
   precision granularity), not location-specific ones, and one precision is sent per message
   instead of one per state. Likewise, the SCC flag is one per message.
6. **Global predicate pool (Theta-only, optional).** `--dss-global-predicate-pool` (default on)
   seeds the start precision with every assume condition of the whole program. CPAchecker starts
   with an empty precision. The pool does not change the algorithm, it saves rounds of violation
   conditions; turn it off for CPAchecker's behavior.
7. **Checker roster (Theta-only).** Every block analysis takes its checker from a shared roster
   (`--dss-checker-backends`, `--dss-checker-selection`), which may mix predicate CEGAR domains and
   bounded model checkers. CPAchecker always uses its configured predicate analysis. Bounded
   checkers ignore the precision.
8. **In-memory messages.** Messages are objects with Theta expressions, not serialized string maps;
   there is no serialization/deserialization operator pair and no cross-process transport.
9. **One procedure.** Theta's DSS works on a single XCFA procedure (the CLI rejects anything else); CPAchecker's
   `INLINING_DECOMPOSITION`, `CallstackCPA`/`FunctionPointerCPA` distribution and the
   `mergeFunctionCalls` restriction have no counterpart, and `MERGE` uses a fixed target instead of
   the documented "number of functions".
10. **Solvers.** Each analysis run builds a fresh checker with fresh solvers (construction and
    closing are serialized, see `runWorkerConfig`, because concurrent native Z3 context creation
    crashed). The run happens in a `SolverManager.withSolverScope`, which closes those solvers when
    the run is done - Theta's solver managers otherwise keep every solver until
    `SolverManager.closeAll()`, which exhausted the heap after a few thousand block analyses. The
    coverage and satisfiability checks of a block use one solver of its own (default `Z3`).
    CPAchecker keeps one `PredicateCPA` (and solver) per worker for the whole run.
11. **Executors.** No `SINGLE_WORKER` executor. The sequential executor still waits for the
    `STATISTIC` messages through the observer instead of returning at the first `RESULT`; actors
    that have shut down discard their remaining messages. Inbox priority uses a creation sequence
    number instead of CPAchecker's buffered deques; the optional
    `DssPrioritizeViolationConditionQueue` is not ported. The visualization actor reads its inbox
    in plain arrival order, so that the final `RESULT`/`STATISTIC` messages cannot end its run
    before it has logged everything.
12. **Not ported**: `AlgorithmStatus` (soundness/precision status) tracking, per-block statistics
    contents, block graph JSON export/import (`ImportDecomposition`, Theta has DOT export instead),
    witnesses (DSS reports `EmptyCex`/`EmptyProof`; CPAchecker reports no witness either), the
    `standardVcs=false` witness-traversing mode of `BlockTransferRelation`, the `trackHistory`
    option.

## Deviations in the block analysis itself

These change the behavior of `PredicateBlockBehavior` relative to `DssBlockAnalysis`. Each one is
needed because of a representation difference above, or closes a gap that the CPAchecker code
leaves to an assumption.

1. **Valid postconditions become `true`.** CPAchecker recognizes the most general entry state
   syntactically (`isTrue` on the abstraction formula), which works because its abstraction
   formulas are canonical (BDD-based). Theta's packed postconditions are disjunctions of predicate
   cubes, so a valid one (e.g. `(flag == 0) || (flag != 0)`) is turned into `true` with a validity
   check. Without this, the SCC rule never fires for such a block.
2. **A start state's own predicates are part of its precision.** A CPAchecker state is an
   abstraction over the predicates of the precision it travels with. Theta's transmitted precision
   is approximated (see above, and `CombinePredicatePrecisionOperator` can filter predicates out),
   so the atoms of the start state are added to the precision of the run that starts from it.
3. **Start state order.** CPAchecker iterates the stored preconditions in hash order. Theta
   analyzes non-top states first: deduplication keeps the first of two states that cover each
   other, and a top summary first would swallow every other summary of the same analysis.
4. **States derived from top (extension of the SCC rule).** CPAchecker skips top start states that
   were computed from non-trivial preconditions once every predecessor provided a non-trivial
   state. Theta additionally remembers, for every postcondition, whether it was computed
   (transitively) from a top start state of a non-root block, and skips such states once every
   predecessor provided a state that is neither top nor derived from top. With CPAchecker's empty
   initial precision these summaries are typically literally top; Theta's Cartesian abstraction
   (and the global predicate pool) produce coarse non-top states instead. Example: a loop entry
   analyzed from top forgets `x == z`, and the coarse state then sustains itself around the loop's
   self-edge, re-deriving ever longer violation conditions. The root's top start state is the real
   program entry, so its results are never marked.
5. **"The same violation condition must have been sent already."** In `analyzePrecondition`,
   CPAchecker does not report a violation condition reached from a top start state when other
   start states are analyzed too, assuming it was reported before. That assumption fails when the
   violation condition arrived while the block had no precondition at all (then
   `analyzeViolationCondition` returns without analyzing anything), and the violation would be
   lost - a wrong SAFE verdict, observed on two sequential loops. Theta records which received
   conditions were already reported from a top start state and only suppresses those.
6. **Factored violation conditions.** `(c1 && φ) || (c2 && φ)` is built as `(c1 || c2) && φ`
   while computing weakest preconditions. Two branches with the same continuation would otherwise
   double the condition at every loop iteration it is pushed through (CPAchecker's formulas are
   DAG-shared inside the SMT solver's formula manager, so this does not hurt there).

### Termination

As in CPAchecker, DSS with predicate abstraction is not guaranteed to terminate. A loop block can
keep receiving and refuting ever longer violation conditions while interpolation keeps
discovering new predicates (e.g. `y >= -1`, `y >= -2`, ...); whether a run converges can depend on
the order in which messages are processed. Unsafe programs whose shortest counterexample unrolls
a loop `n` times need about `n` rounds of violation conditions through the loop block. When the
branches of a loop body end up in separate blocks (e.g. `LINEAR` decomposition of a body that
branches on a nondeterministic value), every combination of branch choices along an unrolling
yields its own violation condition (CPAchecker also emits one per path), which can be exponential
in the number of iterations; the default `MERGE` decomposition folds such branches into one
block.

## Differences between CPAchecker and the paper (Theta follows CPAchecker)

* Preconditions are lists of states per sender with coverage checks, not one least upper bound.
* Postconditions are only recomputed when there is something to refute (a violation inside the
  block or a received violation condition).
* The violation condition is checked from every stored precondition separately.
* SCCs are handled with the "non-trivial for each predecessor" flag rather than Tarjan's algorithm.
* There are five message types, not two, and messages are routed to predecessors/successors and the
  observer instead of being broadcast to every block.

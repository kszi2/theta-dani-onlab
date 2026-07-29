/*
 *  Copyright 2026 Budapest University of Technology and Economics
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package hu.bme.mit.theta.xcfa.dss.analysis

import hu.bme.mit.theta.analysis.Trace
import hu.bme.mit.theta.analysis.algorithm.SafetyChecker
import hu.bme.mit.theta.analysis.algorithm.SafetyResult
import hu.bme.mit.theta.analysis.ptr.PtrState
import hu.bme.mit.theta.xcfa.analysis.XcfaAction
import hu.bme.mit.theta.xcfa.analysis.XcfaPrec
import hu.bme.mit.theta.xcfa.analysis.XcfaState
import hu.bme.mit.theta.xcfa.analysis.proof.LocationInvariants
import hu.bme.mit.theta.xcfa.model.XCFA

/**
 * The exact shape `xcfa-cli`'s `getCegarChecker` returns - but nothing in this module needs to know
 * that `getCegarChecker`, or `xcfa-cli`, exist at all:
 * [SafetyChecker]/[LocationInvariants]/[XcfaPrec] already live in lower modules
 * (`theta-analysis`/`xcfa-analysis`), so this alias is all [runWorkerConfig] needs to depend on.
 * Any [SafetyChecker] implementation fits here, not just a CEGAR one - a caller could substitute a
 * BMC-based checker, once one exists, with zero changes to DSS itself.
 */
typealias XcfaChecker =
  SafetyChecker<LocationInvariants, Trace<XcfaState<PtrState<*>>, XcfaAction>, XcfaPrec<*>>

private val workerConfigLock = Any()

/**
 * Runs [xcfa] through a checker [checkerFactory] builds for it, exactly as a standalone `xcfa-cli`
 * invocation with the same underlying config would - this *is* the "thin orchestration layer" piece
 * of the DSS plan (§0/§3): DSS does not reimplement predicate-abstraction CEGAR, a worker simply
 * forwards to an ordinary, already-existing analysis. For the `NO_DECOMPOSITION` case (build-order
 * step 3: the whole program is one block), this is the entire worker - there is no block-local
 * initial/target-state substitution to do, since there is only one block and no neighbours to
 * exchange postconditions/violation conditions with.
 *
 * [checkerFactory] is supplied by the caller rather than built in here (which is what this module
 * did through build-order step 8, calling `xcfa-cli`'s `getCegarChecker` directly with a fixed
 * predicate-CEGAR [hu.bme.mit.theta.xcfa.cli.params.XcfaConfig]) for two reasons: it decouples this
 * module from any *specific* checker or config type - the caller decides what "the ordinary,
 * already-existing analysis" actually is, CEGAR or otherwise - and it removes this module's
 * compile-time dependency on `xcfa-cli` entirely, which is what let `xcfa-cli` start depending on
 * DSS (build-order step 8's CLI integration) without creating a circular project dependency (this
 * module already needed `getCegarChecker` from `xcfa-cli`; `xcfa-cli` needing DSS back would have
 * closed the cycle). Callers that do want `xcfa-cli`'s real CEGAR checker still get it exactly the
 * same way - `getCegarChecker(xcfa, mcm, parseContext, config, logger)` - just supplied as the
 * factory from wherever `xcfa-cli` *is* on the classpath (production code behind `--algorithm DSS`,
 * or a test's own `testImplementation(project(":theta-xcfa-cli"))`), never required here.
 *
 * The caller must ensure solver names inside whatever config the factory captures (e.g. `"Z3"`) are
 * already resolvable through Theta's process-wide `SolverManager` registry before calling this -
 * exactly as a real `xcfa-cli` invocation's own startup would have done
 * (`ensureDefaultSolversRegistered`/`registerAllSolverManagers`, both in `xcfa-cli`).
 *
 * [initialPrecision], if given, is passed straight to the checker instead of letting it derive one
 * on its own. This matters once real block decomposition is involved (build-order step 5): a
 * config's own initial-precision derivation looks only at whatever XCFA is *checked* - for one
 * block extracted in isolation ([extractBlockXcfa]), that is only the block's own edges, which for
 * many blocks contain no assume/branch conditions of their own at all. Predicate-abstraction CEGAR
 * only refines precision in response to a counterexample it needs to refute; a block with nothing
 * to refute against never refines past whatever initial precision it started with, so with an empty
 * precision and no local branches, [packPostcondition] ends up packing the trivial, useless
 * `True()` for every such block - confirmed empirically while building this.
 * [globalAssumePredicatePrecision] (seeded from the *whole* original program's assume conditions,
 * not just one block's) is the validated fix: passed as [initialPrecision], it gives even a
 * branch-free block enough predicates to compute a precise summary at its exit.
 *
 * Serialized across a single process-wide lock: running this concurrently from multiple threads (as
 * the actor runtime's one-thread-per-block model does, build-order step 4/5) crashed the JVM
 * outright (`EXCEPTION_ACCESS_VIOLATION` inside Z3's native library) the first time this was
 * exercised with real, concurrent per-block checks - confirmed reproducible, not a one-off. This
 * resolves the plan's own open question on solver/thread affinity, just not the way CPAchecker
 * resolves it: CPAchecker pins each block's analysis to a single thread for its whole lifetime,
 * allowing genuine parallelism across blocks (different blocks on different threads, each solver
 * only ever touched by the one thread that created it). This is cruder - one global lock means no
 * two [runWorkerConfig] calls ever run concurrently at all, anywhere, sacrificing parallelism
 * entirely for now - but it is what could be verified safe under time pressure, and correctness
 * (build-order step 5's actual goal) does not depend on real parallelism; performance does, and
 * that is explicitly build-order step 6's concern (`SINGLE_WORKER` then `DSS` executors). Revisit
 * once it is clear whether Z3's binding here is safe under CPAchecker's finer-grained
 * one-thread-per-solver discipline, which would let this lock be narrowed or removed.
 */
fun runWorkerConfig(
  xcfa: XCFA,
  checkerFactory: (XCFA) -> XcfaChecker,
  initialPrecision: XcfaPrec<*>? = null,
): SafetyResult<LocationInvariants, Trace<XcfaState<PtrState<*>>, XcfaAction>> {
  synchronized(workerConfigLock) {
    val checker = checkerFactory(xcfa)
    return if (initialPrecision != null) checker.check(initialPrecision) else checker.check()
  }
}

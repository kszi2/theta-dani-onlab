# Possible issues

Suspected bugs and smells noticed while working on something else. Delete an entry when it is fixed.

- **C statements never get their function name.** `FunctionVisitor.propagateFunctionName` only
  sets a name when `getFunctionName()` is null, but `CStatement.functionName` defaults to `""`, and
  it only descends into `CCompound`s. So `CMetaData.functionName` is null for ordinary statements,
  and `YamlWitnessWriter`'s violation-witness assumptions, which keep a local only if its function
  matches, drop every local variable. `DssWitnessContent` infers the function from the locals a
  statement uses instead.
- **DSS compares k-induction postconditions as if they were quantifier-free.** A bounded checker's
  proof (`BoundedChecker.extractModel`) is a path formula over fresh variables named after the
  indexed constants, i.e. implicitly existential, and the names repeat across analyses.
  `DssPredicateOperators.isLeq` checks `A && !B` with those variables free, which is not `A`
  implying `exists F. B`; `statesEqual` may then never see a repeated postcondition, so a block
  cycle proven by KIND could keep re-analyzing. Unverified; it matters more with KIND first.

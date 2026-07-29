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
package hu.bme.mit.theta.xcfa.cli

import hu.bme.mit.theta.xcfa.cli.XcfaCli.Companion.main
import java.util.stream.Stream
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * End-to-end tests for `--backend DSS` (build-order step 8's CLI integration,
 * [hu.bme.mit.theta.xcfa.cli.checkers.getDssChecker]) - the actual new surface here is the CLI
 * wiring itself (flag parsing, backend dispatch, result/witness reporting), not DSS's own
 * decomposition/analysis/actor logic, which already has 87 tests across the `xcfa-dss-*` modules.
 */
class XcfaCliDssTest {

  companion object {

    @JvmStatic
    fun decompositionAndExecutor(): Stream<Arguments> =
      Stream.of(
        Arguments.of("LINEAR", "CONCURRENT"),
        Arguments.of("LINEAR", "SEQUENTIAL"),
        Arguments.of("MERGE", "CONCURRENT"),
        Arguments.of("MERGE", "SEQUENTIAL"),
        Arguments.of("NONE", "CONCURRENT"),
        Arguments.of("NONE", "SEQUENTIAL"),
      )
  }

  @Test
  fun `--backend DSS runs to completion on a SAFE program`() {
    main(
      arrayOf(
        "--backend",
        "DSS",
        "--input-type",
        "C",
        "--input",
        javaClass.getResource("/c/dss/safe.c")!!.path,
        "--stacktrace",
        "--debug",
      )
    )
  }

  @Test
  fun `--backend DSS runs to completion on an UNSAFE program`() {
    main(
      arrayOf(
        "--backend",
        "DSS",
        "--input-type",
        "C",
        "--input",
        javaClass.getResource("/c/dss/unsafe.c")!!.path,
        "--stacktrace",
        "--debug",
      )
    )
  }

  @ParameterizedTest
  @MethodSource("decompositionAndExecutor")
  fun `every --dss-decomposition and --dss-executor combination parses and runs on a SAFE program`(
    decomposition: String,
    executor: String,
  ) {
    main(
      arrayOf(
        "--backend",
        "DSS",
        "--dss-decomposition",
        decomposition,
        "--dss-executor",
        executor,
        "--input-type",
        "C",
        "--input",
        javaClass.getResource("/c/dss/safe.c")!!.path,
        "--stacktrace",
        "--debug",
      )
    )
  }

  @ParameterizedTest
  @MethodSource("decompositionAndExecutor")
  fun `every --dss-decomposition and --dss-executor combination parses and runs on an UNSAFE program`(
    decomposition: String,
    executor: String,
  ) {
    main(
      arrayOf(
        "--backend",
        "DSS",
        "--dss-decomposition",
        decomposition,
        "--dss-executor",
        executor,
        "--input-type",
        "C",
        "--input",
        javaClass.getResource("/c/dss/unsafe.c")!!.path,
        "--stacktrace",
        "--debug",
      )
    )
  }

  @Test
  fun `--backend DSS with --output ALL does not crash on witness writing for an UNSAFE (no-trace) verdict`() {
    // DSS's UNSAFE verdict carries no concrete counterexample trace (doc/DSS.md's Known
    // Limitations - packVcond/unpackVcond aren't implemented) - this is exactly the case that
    // exposed a latent unchecked-cast crash in VerificationLogging.kt's trace-writing code, fixed
    // alongside this backend. --output ALL is the default WitnessLevel, so this also doubles as a
    // regression test for that fix without needing to force any extra flags.
    //
    // Reaching the assertion below at all is the actual regression guard: before the fix, this
    // threw a ClassCastException (EmptyCex is not a Trace) from inside the witness-writing block,
    // caught only by the generic "Could not output files" catch-all. There genuinely is no
    // witness.graphml/witness.yml afterward either way - GraphmlWitnessWriter/YamlWitnessWriter
    // both decline to write anything meaningful without a concrete trace, which is correct, not a
    // bug (see the same Known Limitation - DSS cannot yet produce violation witnesses).
    val temp = createTempDirectory()
    main(
      arrayOf(
        "--output",
        "ALL",
        "--output-directory",
        temp.absolutePathString(),
        "--backend",
        "DSS",
        "--input-type",
        "C",
        "--input",
        javaClass.getResource("/c/dss/unsafe.c")!!.path,
        "--stacktrace",
        "--debug",
      )
    )
    assertTrue(temp.exists())
    temp.toFile().deleteRecursively()
  }
}

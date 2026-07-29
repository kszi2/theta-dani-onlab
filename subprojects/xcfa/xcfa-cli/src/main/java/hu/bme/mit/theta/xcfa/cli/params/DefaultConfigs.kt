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
package hu.bme.mit.theta.xcfa.cli.params

/**
 * Builds a plain predicate-abstraction CEGAR [XcfaConfig] with everything besides the
 * backend/domain choice left at its own defaults - a convenience for any caller that wants a
 * ready-to-use predicate CEGAR config without loading one from a file (`XcfaCli`'s own JSON loader,
 * `getGson()` in `utils/GsonUtils.kt`, is `internal` and not usable outside this module).
 * Originally written for `xcfa-dss-analysis`'s tests and `PredicateBlockBehavior`, moved here since
 * it isn't DSS-specific - any caller building an [XcfaConfig] by hand can use it, and having it
 * live next to [hu.bme.mit.theta.xcfa.cli.checkers.getCegarChecker] avoids
 * `xcfa-dss-analysis`/`xcfa-dss-actor` needing a compile-time dependency on this module just to
 * construct one (which would otherwise create a circular project dependency once this module
 * depends on DSS for `--algorithm DSS`).
 */
fun defaultPredicateCegarConfig(): XcfaConfig<SpecFrontendConfig, SpecBackendConfig> {
  val config = XcfaConfig<SpecFrontendConfig, SpecBackendConfig>()
  config.backendConfig.createSpecConfig()
  val cegarConfig = config.backendConfig.specConfig as CegarConfig
  cegarConfig.abstractorConfig.domain = Domain.PRED_CART
  return config
}

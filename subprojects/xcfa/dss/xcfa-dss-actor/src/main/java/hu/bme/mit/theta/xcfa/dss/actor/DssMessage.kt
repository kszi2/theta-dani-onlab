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
package hu.bme.mit.theta.xcfa.dss.actor

import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolType

/**
 * Mirrors CPAchecker's confirmed `DssMessage.DssMessageType` - five types, not the paper's three.
 */
enum class DssMessageType {
  POST_CONDITION,
  VIOLATION_CONDITION,
  EXCEPTION,
  RESULT,
  STATISTIC,
}

enum class DssResult {
  SAFE,
  UNSAFE,
}

/**
 * A message exchanged between [DssActor]s. Mirrors CPAchecker's `DssMessage` hierarchy, confirmed
 * to have five subtypes (plan §2), not the paper's three.
 *
 * `POST_CONDITION`/`VIOLATION_CONDITION` carry an optional [Expr] payload - the packed boolean
 * formula from `xcfa-dss-analysis`'s `packPostcondition` (plan §3's `packPost`). Optional (not
 * required) so the stub workers this module was originally validated against (`EchoBlockAnalysis`,
 * plan step 4) keep compiling unchanged; a real [DssBlockBehavior] (e.g. `PredicateBlockBehavior`,
 * build-order step 5) always sets it.
 */
sealed class DssMessage {

  abstract val senderId: String

  val type: DssMessageType
    get() =
      when (this) {
        is DssPostConditionMessage -> DssMessageType.POST_CONDITION
        is DssViolationConditionMessage -> DssMessageType.VIOLATION_CONDITION
        is DssExceptionMessage -> DssMessageType.EXCEPTION
        is DssResultMessage -> DssMessageType.RESULT
        is DssStatisticsMessage -> DssMessageType.STATISTIC
      }
}

data class DssPostConditionMessage(
  override val senderId: String,
  val postcondition: Expr<BoolType>? = null,
) : DssMessage()

data class DssViolationConditionMessage(
  override val senderId: String,
  val violationCondition: Expr<BoolType>? = null,
) : DssMessage()

data class DssExceptionMessage(override val senderId: String, val error: Throwable) : DssMessage()

data class DssResultMessage(override val senderId: String, val result: DssResult) : DssMessage()

data class DssStatisticsMessage(
  override val senderId: String,
  val stats: Map<String, String> = emptyMap(),
) : DssMessage()

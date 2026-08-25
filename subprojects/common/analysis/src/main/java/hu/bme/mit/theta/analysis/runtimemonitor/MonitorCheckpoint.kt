/*
 *  Copyright 2025 Budapest University of Technology and Economics
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
package hu.bme.mit.theta.analysis.runtimemonitor

/**
 * This class handles the creation, registering and execution of monitor checkpoints. If you would
 * like to add a new checkpoint do the following: 1, Add its name below to checkpointNames in the
 * companion object (from then on it will be automatically created) 2, Wherever you would like to
 * execute it, add a MonitorCheckpoint.execute(<name>) call 3, Register your monitors to it so they
 * are executed when the checkpoint is executed: (MonitorCheckpoint.register)
 */
class MonitorCheckpoint internal constructor(private val name: String) {

  // Scoped per calling thread, not a single shared set: `getCegarChecker` calls reset()+register()
  // unconditionally on every checker construction, and execute() fires during that same checker's
  // own check() call later - always on the one thread that built it, but (once more than one
  // checker
  // can be alive on more than one thread at a time, as DSS's concurrent block actors do) never
  // reliably the *only* checker alive process-wide anymore. A plain shared set - even a thread-safe
  // one - is wrong here, not just unsafe: block A's still-running check() would have its monitor
  // silently swapped out for block B's the moment block B's checker got constructed, so the
  // checkpoint firing mid-A's-refinement would wrongly execute B's monitor against A's
  // counterexample
  // history (confirmed empirically - this is exactly what produced spurious
  // `NotSolvableException`s in DSS's own UNSAFE-verdict tests once checker construction and
  // solving were allowed to interleave across blocks). A `ThreadLocal` restores the single-runner
  // assumption the rest of this class's design already relies on, per thread instead of
  // process-wide -
  // exactly matching how it's actually used: one thread registers a monitor and is the only thread
  // that ever triggers its execution, for the lifetime of one checker.
  private val registeredMonitors = ThreadLocal.withInitial { mutableSetOf<Monitor>() }

  fun registerMonitor(m: Monitor) {
    registeredMonitors.get().add(m)
  }

  fun executeCheckpoint() {
    registeredMonitors.get().forEach { monitor: Monitor -> monitor.execute(name) }
  }

  companion object Checkpoints {

    // Add any new checkpoints here
    private val checkpointNames = setOf("CegarChecker.unsafeARG")

    private val registeredCheckpoints: HashMap<String, MonitorCheckpoint> = HashMap()

    init {
      checkpointNames.forEach { registeredCheckpoints.put(it, MonitorCheckpoint(it)) }
    }

    fun register(m: Monitor, checkpointName: String) {
      assert(registeredCheckpoints.contains(checkpointName)) {
        "Checkpoint name $checkpointName was not registered (add it in MonitorCheckpoint.kt)"
      } // see checkpointNames above
      registeredCheckpoints[checkpointName]?.registerMonitor(m)
        ?: error("Checkpoint with name $checkpointName not found.")
    }

    fun execute(name: String) {
      assert(registeredCheckpoints.contains(name)) {
        "Checkpoint name $name was not registered (add it in MonitorCheckpoint.kt)"
      } // see checkpointNames above
      registeredCheckpoints[name]?.executeCheckpoint()
        ?: error("Checkpoint with name $name not found.")
    }

    fun reset() {
      registeredCheckpoints.values.forEach { it.reset() }
    }
  }

  private fun reset() {
    registeredMonitors.get().clear()
  }
}

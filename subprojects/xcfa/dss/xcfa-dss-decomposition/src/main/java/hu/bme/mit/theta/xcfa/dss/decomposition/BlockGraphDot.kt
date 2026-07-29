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
package hu.bme.mit.theta.xcfa.dss.decomposition

/**
 * Renders this [BlockGraph] as Graphviz DOT source, for visual debugging (plan §5 - CPAchecker's
 * `DssVisualizationWorker` does the same, via its own block-graph renderer). Purely structural:
 * reads only [Block] ids, endpoint location names, and topology - no analysis, no actor runtime
 * involved, so this can be called on a [BlockGraph] straight out of a [DssBlockDecomposition],
 * before any worker ever runs.
 *
 * The root block ([Block.isRoot]) gets a double border - it's where DSS's "no predecessor found a
 * violation ⇒ report UNSAFE immediately" short-circuit rule applies. A block with a dedicated
 * abstraction point ([Block.isAbstractionPossible]) gets a dashed border, since that is the
 * location a worker for this block actually places its postcondition/violation-condition check,
 * distinct from where the block topologically ends.
 */
fun BlockGraph.toDot(): String {
  val sb = StringBuilder()
  sb.append("digraph BlockGraph {\n")
  sb.append("  rankdir=LR;\n")
  sb.append("  node [shape=box, fontname=\"monospace\"];\n")
  for (block in blocks.sortedBy { it.id }) {
    sb.append("  \"${dotEscape(block.id)}\" [label=\"${nodeLabel(block)}\"")
    val style = nodeStyle(block)
    if (style.isNotEmpty()) {
      sb.append(", ").append(style)
    }
    sb.append("];\n")
  }
  for (block in blocks.sortedBy { it.id }) {
    for (successorId in block.successorIds.sorted()) {
      sb.append("  \"${dotEscape(block.id)}\" -> \"${dotEscape(successorId)}\";\n")
    }
  }
  sb.append("}\n")
  return sb.toString()
}

private fun nodeLabel(block: Block): String {
  val lines = buildList {
    add(block.id)
    add("${block.initialLocation.name} -> ${block.finalLocation.name}")
    if (block.isAbstractionPossible) {
      add("vcond: ${block.violationConditionLocation.name}")
    }
  }
  return lines.joinToString("\\n") { dotEscape(it) }
}

private fun nodeStyle(block: Block): String =
  when {
    block.isRoot && block.isAbstractionPossible -> "peripheries=2, style=dashed"
    block.isRoot -> "peripheries=2"
    block.isAbstractionPossible -> "style=dashed"
    else -> ""
  }

private fun dotEscape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

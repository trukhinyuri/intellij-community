// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.log.graph.impl.permanent

import com.intellij.vcs.log.graph.GraphCommit
import com.intellij.vcs.log.graph.api.permanent.PermanentCommitsInfo
import com.intellij.vcs.log.graph.api.permanent.VcsLogGraphNodeId
import com.intellij.vcs.log.graph.impl.facade.RowsMapping
import com.intellij.vcs.log.graph.utils.TimestampGetter
import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.ints.IntSet
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
class PermanentCommitsInfoImpl<CommitId : Any> private constructor(private val rowsMapping: RowsMapping<CommitId>,
                                                                   private val notLoadedCommits: Int2ObjectMap<CommitId>) : PermanentCommitsInfo<CommitId> {
  val timestampGetter: TimestampGetter
    get() = rowsMapping

  override fun getCommitId(nodeId: VcsLogGraphNodeId): CommitId {
    if (nodeId < 0) return notLoadedCommits[nodeId]
    return rowsMapping.getCommitId(nodeId)
  }

  override fun getTimestamp(nodeId: VcsLogGraphNodeId): Long {
    if (nodeId < 0) return 0
    return rowsMapping.getTimestamp(nodeId)
  }

  // todo optimize with special map
  override fun getNodeId(commitId: CommitId): VcsLogGraphNodeId {
    val indexOf = rowsMapping.commitIdMapping.indexOf(commitId)
    if (indexOf != -1) return indexOf

    return getNotLoadNodeId(commitId)
  }

  private fun getNotLoadNodeId(commitId: CommitId): Int {
    for (entry in notLoadedCommits.int2ObjectEntrySet()) {
      if (entry.value == commitId) {
        return entry.intKey
      }
    }
    return -1
  }

  fun convertToCommitIdList(commitIndexes: Collection<VcsLogGraphNodeId>): List<CommitId> {
    return commitIndexes.map(this::getCommitId)
  }

  fun convertToCommitIdSet(commitIndexes: Collection<VcsLogGraphNodeId>): Set<CommitId> {
    return commitIndexes.mapTo(HashSet(), this::getCommitId)
  }

  override fun convertToNodeIds(commitIds: Collection<CommitId>): Set<VcsLogGraphNodeId> {
    return convertToNodeIds(commitIds, false)
  }

  internal fun convertToNodeIds(commitIds: Collection<CommitId>, skipNotLoadedCommits: Boolean): IntSet {
    val result = IntOpenHashSet()
    rowsMapping.commitIdMapping.forEachIndexed { i, commitId ->
      if (commitIds.contains(commitId)) {
        result.add(i)
      }
    }
    if (!skipNotLoadedCommits) {
      for (entry in notLoadedCommits.int2ObjectEntrySet()) {
        if (commitIds.contains(entry.value)) {
          result.add(entry.intKey)
        }
      }
    }
    return result
  }

  fun containsAll(commitIds: Collection<CommitId>): Boolean {
    return rowsMapping.commitIdMapping.containsAll(commitIds)
  }

  companion object {
    @JvmStatic
    fun <CommitId : Any> newInstance(graphCommits: List<GraphCommit<CommitId>>,
                                     notLoadedCommits: Int2ObjectMap<CommitId>): PermanentCommitsInfoImpl<CommitId> {
      val isIntegerCase = !graphCommits.isEmpty() && graphCommits[0].id is Int

      val rowsMapping = RowsMapping<CommitId>(graphCommits.size, isIntegerCase)
      graphCommits.forEach {
        rowsMapping.add(it.id, it.timestamp)
      }

      return PermanentCommitsInfoImpl(rowsMapping, notLoadedCommits)
    }
  }
}

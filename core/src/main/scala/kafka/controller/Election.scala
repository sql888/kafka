/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.controller

import kafka.api.LeaderAndIsr
import org.apache.kafka.common.TopicPartition

import scala.collection.Seq

case class ElectionResult(topicPartition: TopicPartition, leaderAndIsr: Option[LeaderAndIsr], liveReplicas: Seq[Int])

object Election {

  /**
    * Try to elect leaders for multiple partitions.
    * Electing a leader for a partition updates partition state in zookeeper.
    *
    * @param actualAssignment The partition assignment that's in Zookeeper and Controller Context memory, which is not modified.
    * @return the masked partition assignment where the replicas in the LeaderDeprioritizedList moved to the end of the assignment (lower priority)
    *
    *         First filter out the brokers that is in leaderDeprioritizedList, so the ones not in the list gets bubbled up
    *         to the front of the Seq,  then append the brokers that are in leaderDeprioritizedList to the end,
    *         thus put them to the lowest priority when determining leadership.
    *
    *         if leaderDeprioritizedList is not set (null) or set to empty string ""
    *         OR all replicas of the partition assignment are in the leaderDeprioritizedList
    *         return the original partition assignment.
    *         e.g. assignment = (0,1,2) & leaderDeprioritizedList = (1,2,0), then return original assignment (0,1,2)
    */
  def maybeLeaderDeprioritizedAssignment(actualAssignment: Seq[Int], leaderDeprioritizedList: String): Seq[Int] = {
    // This dynamic config can also be set to empty string "", which is the default setting
    // To be safe, also take null into consideration and convert it to empty string ""
    val leaderDeprioritizedListSet = leaderDeprioritizedList.split(":").filter(_ != "").map(_.toInt).toSet
    System.out.println(s"leaderDeprioritizedListSet: ${leaderDeprioritizedListSet}")
    System.out.println(s"actualAssignment: ${actualAssignment}")
    val x = actualAssignment.sortBy(leaderDeprioritizedListSet.contains(_))
    System.out.println(s"actualAssignment.sortBy(leaderDeprioritizedListSet.contains(_)): ${x}")
    actualAssignment.sortBy(leaderDeprioritizedListSet.contains(_))
  }

  private def leaderForOffline(partition: TopicPartition,
                               leaderAndIsrOpt: Option[LeaderAndIsr],
                               uncleanLeaderElectionEnabled: Boolean,
                               controllerContext: ControllerContext,
                               leaderDeprioritizedList: String): ElectionResult = {

    //val assignment = controllerContext.partitionReplicaAssignment(partition)
    val assignment = maybeLeaderDeprioritizedAssignment(controllerContext.partitionReplicaAssignment(partition), leaderDeprioritizedList)
    val liveReplicas = assignment.filter(replica => controllerContext.isReplicaOnline(replica, partition))
    leaderAndIsrOpt match {
      case Some(leaderAndIsr) =>
        val isr = leaderAndIsr.isr
        val leaderOpt = PartitionLeaderElectionAlgorithms.offlinePartitionLeaderElection(
          assignment, isr, liveReplicas.toSet, uncleanLeaderElectionEnabled, controllerContext)
        val newLeaderAndIsrOpt = leaderOpt.map { leader =>
          val newIsr = if (isr.contains(leader)) isr.filter(replica => controllerContext.isReplicaOnline(replica, partition))
          else List(leader)
          leaderAndIsr.newLeaderAndIsr(leader, newIsr)
        }
        ElectionResult(partition, newLeaderAndIsrOpt, liveReplicas)

      case None =>
        ElectionResult(partition, None, liveReplicas)
    }
  }

  /**
   * Elect leaders for new or offline partitions.
   *
   * @param controllerContext Context with the current state of the cluster
   * @param partitionsWithUncleanLeaderElectionState A sequence of tuples representing the partitions
   *                                                 that need election, their leader/ISR state, and whether
   *                                                 or not unclean leader election is enabled
   *
   * @return The election results
   */
  def leaderForOffline(
    controllerContext: ControllerContext,
    partitionsWithUncleanLeaderElectionState: Seq[(TopicPartition, Option[LeaderAndIsr], Boolean)],
    leaderDeprioritizedList: String = ""
  ): Seq[ElectionResult] = {
    partitionsWithUncleanLeaderElectionState.map {
      case (partition, leaderAndIsrOpt, uncleanLeaderElectionEnabled) =>
        leaderForOffline(partition, leaderAndIsrOpt, uncleanLeaderElectionEnabled, controllerContext, leaderDeprioritizedList)
    }
  }

  private def leaderForReassign(partition: TopicPartition,
                                leaderAndIsr: LeaderAndIsr,
                                controllerContext: ControllerContext,
                                leaderDeprioritizedList: String): ElectionResult = {
    //val targetReplicas = controllerContext.partitionFullReplicaAssignment(partition).targetReplicas
    val targetReplicas = maybeLeaderDeprioritizedAssignment(controllerContext.partitionFullReplicaAssignment(partition).targetReplicas, leaderDeprioritizedList)
    val liveReplicas = targetReplicas.filter(replica => controllerContext.isReplicaOnline(replica, partition))
    val isr = leaderAndIsr.isr
    val leaderOpt = PartitionLeaderElectionAlgorithms.reassignPartitionLeaderElection(targetReplicas, isr, liveReplicas.toSet)
    val newLeaderAndIsrOpt = leaderOpt.map(leader => leaderAndIsr.newLeader(leader))
    ElectionResult(partition, newLeaderAndIsrOpt, targetReplicas)
  }

  /**
   * Elect leaders for partitions that are undergoing reassignment.
   *
   * @param controllerContext Context with the current state of the cluster
   * @param leaderAndIsrs A sequence of tuples representing the partitions that need election
   *                                     and their respective leader/ISR states
   *
   * @return The election results
   */
  def leaderForReassign(controllerContext: ControllerContext,
                        leaderAndIsrs: Seq[(TopicPartition, LeaderAndIsr)],
                        leaderDeprioritizedList: String = ""): Seq[ElectionResult] = {
    leaderAndIsrs.map { case (partition, leaderAndIsr) =>
      leaderForReassign(partition, leaderAndIsr, controllerContext, leaderDeprioritizedList)
    }
  }

  private def leaderForPreferredReplica(partition: TopicPartition,
                                        leaderAndIsr: LeaderAndIsr,
                                        controllerContext: ControllerContext,
                                        leaderDeprioritizedList: String): ElectionResult = {
    //val assignment = controllerContext.partitionReplicaAssignment(partition)
    val assignment = maybeLeaderDeprioritizedAssignment(controllerContext.partitionReplicaAssignment(partition), leaderDeprioritizedList)
    val liveReplicas = assignment.filter(replica => controllerContext.isReplicaOnline(replica, partition))
    val isr = leaderAndIsr.isr
    val leaderOpt = PartitionLeaderElectionAlgorithms.preferredReplicaPartitionLeaderElection(assignment, isr, liveReplicas.toSet)
    val newLeaderAndIsrOpt = leaderOpt.map(leader => leaderAndIsr.newLeader(leader))
    ElectionResult(partition, newLeaderAndIsrOpt, assignment)
  }

  /**
   * Elect preferred leaders.
   *
   * @param controllerContext Context with the current state of the cluster
   * @param leaderAndIsrs A sequence of tuples representing the partitions that need election
   *                                     and their respective leader/ISR states
   *
   * @return The election results
   */
  def leaderForPreferredReplica(controllerContext: ControllerContext,
                                leaderAndIsrs: Seq[(TopicPartition, LeaderAndIsr)],
                                leaderDeprioritizedList: String = ""): Seq[ElectionResult] = {
    leaderAndIsrs.map { case (partition, leaderAndIsr) =>
      leaderForPreferredReplica(partition, leaderAndIsr, controllerContext, leaderDeprioritizedList)
    }
  }

  private def leaderForControlledShutdown(partition: TopicPartition,
                                          leaderAndIsr: LeaderAndIsr,
                                          shuttingDownBrokerIds: Set[Int],
                                          controllerContext: ControllerContext,
                                          leaderDeprioritizedList: String): ElectionResult = {
    //val assignment = controllerContext.partitionReplicaAssignment(partition)
    val assignment = maybeLeaderDeprioritizedAssignment(controllerContext.partitionReplicaAssignment(partition), leaderDeprioritizedList)
    val liveOrShuttingDownReplicas = assignment.filter(replica =>
      controllerContext.isReplicaOnline(replica, partition, includeShuttingDownBrokers = true))
    val isr = leaderAndIsr.isr
    val leaderOpt = PartitionLeaderElectionAlgorithms.controlledShutdownPartitionLeaderElection(assignment, isr,
      liveOrShuttingDownReplicas.toSet, shuttingDownBrokerIds)
    val newIsr = isr.filter(replica => !shuttingDownBrokerIds.contains(replica))
    val newLeaderAndIsrOpt = leaderOpt.map(leader => leaderAndIsr.newLeaderAndIsr(leader, newIsr))
    ElectionResult(partition, newLeaderAndIsrOpt, liveOrShuttingDownReplicas)
  }

  /**
   * Elect leaders for partitions whose current leaders are shutting down.
   *
   * @param controllerContext Context with the current state of the cluster
   * @param leaderAndIsrs A sequence of tuples representing the partitions that need election
   *                                     and their respective leader/ISR states
   *
   * @return The election results
   */
  def leaderForControlledShutdown(controllerContext: ControllerContext,
                                  leaderAndIsrs: Seq[(TopicPartition, LeaderAndIsr)],
                                  leaderDeprioritizedList: String = ""): Seq[ElectionResult] = {
    val shuttingDownBrokerIds = controllerContext.shuttingDownBrokerIds.toSet
    leaderAndIsrs.map { case (partition, leaderAndIsr) =>
      leaderForControlledShutdown(partition, leaderAndIsr, shuttingDownBrokerIds, controllerContext, leaderDeprioritizedList)
    }
  }
}

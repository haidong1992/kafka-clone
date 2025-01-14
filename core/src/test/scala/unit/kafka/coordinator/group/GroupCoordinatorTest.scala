/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.coordinator.group

import java.util.Optional

import kafka.common.OffsetAndMetadata
import kafka.server.{DelayedOperationPurgatory, KafkaConfig, ReplicaManager}
import kafka.utils._
import kafka.utils.timer.MockTimer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.record.{MemoryRecords, RecordBatch}
import org.apache.kafka.common.requests.ProduceResponse.PartitionResponse
import org.apache.kafka.common.requests.{JoinGroupRequest, OffsetCommitRequest, OffsetFetchResponse, TransactionResult}
import org.easymock.{Capture, EasyMock, IAnswer}
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

import kafka.cluster.Partition
import kafka.log.AppendOrigin
import kafka.zk.KafkaZkClient
import org.apache.kafka.common.internals.Topic
import org.junit.Assert._
import org.junit.{After, Assert, Before, Test}
import org.scalatest.junit.JUnitSuite

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise, TimeoutException}

class GroupCoordinatorTest extends JUnitSuite {
  type JoinGroupCallback = JoinGroupResult => Unit
  type SyncGroupCallbackParams = (Array[Byte], Errors)
  type SyncGroupCallback = (Array[Byte], Errors) => Unit
  type HeartbeatCallbackParams = Errors
  type HeartbeatCallback = Errors => Unit
  type CommitOffsetCallbackParams = Map[TopicPartition, Errors]
  type CommitOffsetCallback = Map[TopicPartition, Errors] => Unit
  type LeaveGroupCallbackParams = Errors
  type LeaveGroupCallback = Errors => Unit

  val ClientId = "consumer-test"
  val ClientHost = "localhost"
  val GroupMinSessionTimeout = 10
  val GroupMaxSessionTimeout = 10 * 60 * 1000
  val GroupMaxSize = 3
  val DefaultRebalanceTimeout = 500
  val DefaultSessionTimeout = 500
  val GroupInitialRebalanceDelay = 50
  var timer: MockTimer = null
  var groupCoordinator: GroupCoordinator = null
  var replicaManager: ReplicaManager = null
  var scheduler: KafkaScheduler = null
  var zkClient: KafkaZkClient = null

  private val groupId = "groupId"
  private val protocolType = "consumer"
  private val memberId = "memberId"
  private val metadata = Array[Byte]()
  private val protocols = List(("range", metadata))
  private var groupPartitionId: Int = -1

  // we use this string value since its hashcode % #.partitions is different
  private val otherGroupId = "otherGroup"

  @Before
  def setUp() {
    val props = TestUtils.createBrokerConfig(nodeId = 0, zkConnect = "")
    props.setProperty(KafkaConfig.GroupMinSessionTimeoutMsProp, GroupMinSessionTimeout.toString)
    props.setProperty(KafkaConfig.GroupMaxSessionTimeoutMsProp, GroupMaxSessionTimeout.toString)
    props.setProperty(KafkaConfig.GroupMaxSizeProp, GroupMaxSize.toString)
    props.setProperty(KafkaConfig.GroupInitialRebalanceDelayMsProp, GroupInitialRebalanceDelay.toString)
    // make two partitions of the group topic to make sure some partitions are not owned by the coordinator
    val ret = mutable.Map[String, Map[Int, Seq[Int]]]()
    ret += (Topic.GROUP_METADATA_TOPIC_NAME -> Map(0 -> Seq(1), 1 -> Seq(1)))

    replicaManager = EasyMock.createNiceMock(classOf[ReplicaManager])

    zkClient = EasyMock.createNiceMock(classOf[KafkaZkClient])
    // make two partitions of the group topic to make sure some partitions are not owned by the coordinator
    EasyMock.expect(zkClient.getTopicPartitionCount(Topic.GROUP_METADATA_TOPIC_NAME)).andReturn(Some(2))
    EasyMock.replay(zkClient)

    timer = new MockTimer

    val config = KafkaConfig.fromProps(props)

    val heartbeatPurgatory = new DelayedOperationPurgatory[DelayedHeartbeat]("Heartbeat", timer, config.brokerId, reaperEnabled = false)
    val joinPurgatory = new DelayedOperationPurgatory[DelayedJoin]("Rebalance", timer, config.brokerId, reaperEnabled = false)

    groupCoordinator = GroupCoordinator(config, zkClient, replicaManager, heartbeatPurgatory, joinPurgatory, timer.time)
    groupCoordinator.startup(enableMetadataExpiration = false)

    // add the partition into the owned partition list
    groupPartitionId = groupCoordinator.partitionFor(groupId)
    groupCoordinator.groupManager.addPartitionOwnership(groupPartitionId)
  }

  @After
  def tearDown() {
    EasyMock.reset(replicaManager)
    if (groupCoordinator != null)
      groupCoordinator.shutdown()
  }

  @Test
  def testRequestHandlingWhileLoadingInProgress(): Unit = {
    val otherGroupPartitionId = groupCoordinator.groupManager.partitionFor(otherGroupId)
    assertTrue(otherGroupPartitionId != groupPartitionId)

    groupCoordinator.groupManager.addLoadingPartition(otherGroupPartitionId)
    assertTrue(groupCoordinator.groupManager.isGroupLoading(otherGroupId))

    // JoinGroup
    var joinGroupResponse: Option[JoinGroupResult] = None
    groupCoordinator.handleJoinGroup(otherGroupId, memberId, true, "clientId", "clientHost", 60000, 10000, "consumer",
      List("range" -> new Array[Byte](0)), result => { joinGroupResponse = Some(result)})
    assertEquals(Some(Errors.COORDINATOR_LOAD_IN_PROGRESS), joinGroupResponse.map(_.error))

    // SyncGroup
    var syncGroupResponse: Option[Errors] = None
    groupCoordinator.handleSyncGroup(otherGroupId, 1, memberId, Map.empty[String, Array[Byte]],
      (_, error)=> syncGroupResponse = Some(error))
    assertEquals(Some(Errors.REBALANCE_IN_PROGRESS), syncGroupResponse)

    // OffsetCommit
    val topicPartition = new TopicPartition("foo", 0)
    var offsetCommitErrors = Map.empty[TopicPartition, Errors]
    groupCoordinator.handleCommitOffsets(otherGroupId, memberId, 1,
      Map(topicPartition -> offsetAndMetadata(15L)), result => { offsetCommitErrors = result })
    assertEquals(Some(Errors.COORDINATOR_LOAD_IN_PROGRESS), offsetCommitErrors.get(topicPartition))

    // Heartbeat
    var heartbeatError: Option[Errors] = None
    groupCoordinator.handleHeartbeat(otherGroupId, memberId, 1, error => { heartbeatError = Some(error) })
    assertEquals(Some(Errors.NONE), heartbeatError)

    // DescribeGroups
    val (describeGroupError, _) = groupCoordinator.handleDescribeGroup(otherGroupId)
    assertEquals(Errors.COORDINATOR_LOAD_IN_PROGRESS, describeGroupError)

    // ListGroups
    val (listGroupsError, _) = groupCoordinator.handleListGroups()
    assertEquals(Errors.COORDINATOR_LOAD_IN_PROGRESS, listGroupsError)

    // DeleteGroups
    val deleteGroupsErrors = groupCoordinator.handleDeleteGroups(Set(otherGroupId))
    assertEquals(Some(Errors.COORDINATOR_LOAD_IN_PROGRESS), deleteGroupsErrors.get(otherGroupId))

    // Check that non-loading groups are still accessible
    assertEquals(Errors.NONE, groupCoordinator.handleDescribeGroup(groupId)._1)

    // After loading, we should be able to access the group
    val otherGroupMetadataTopicPartition = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, otherGroupPartitionId)
    EasyMock.reset(replicaManager)
    EasyMock.expect(replicaManager.getLog(otherGroupMetadataTopicPartition)).andReturn(None)
    EasyMock.replay(replicaManager)
    groupCoordinator.groupManager.loadGroupsAndOffsets(otherGroupMetadataTopicPartition, group => {})
    assertEquals(Errors.NONE, groupCoordinator.handleDescribeGroup(otherGroupId)._1)
  }

  @Test
  def testOffsetsRetentionMsIntegerOverflow() {
    val props = TestUtils.createBrokerConfig(nodeId = 0, zkConnect = "")
    props.setProperty(KafkaConfig.OffsetsRetentionMinutesProp, Integer.MAX_VALUE.toString)
    val config = KafkaConfig.fromProps(props)
    val offsetConfig = GroupCoordinator.offsetConfig(config)
    assertEquals(offsetConfig.offsetsRetentionMs, Integer.MAX_VALUE * 60L * 1000L)
  }

  @Test
  def testJoinGroupWrongCoordinator() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val joinGroupResult = joinGroup(otherGroupId, memberId, protocolType, protocols)
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.NOT_COORDINATOR, joinGroupError)
  }

  @Test
  def testJoinGroupShouldReceiveErrorIfGroupOverMaxSize() {
    var futures = ArrayBuffer[Future[JoinGroupResult]]()
    val rebalanceTimeout = GroupInitialRebalanceDelay * 2

    for (i <- 1.to(GroupMaxSize)) {
      futures += sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols, rebalanceTimeout)
      if (i != 1)
        timer.advanceClock(GroupInitialRebalanceDelay)
      EasyMock.reset(replicaManager)
    }
    // advance clock beyond rebalanceTimeout
    timer.advanceClock(GroupInitialRebalanceDelay + 1)
    for (future <- futures) {
      assertEquals(Errors.NONE, await(future, 1).error)
    }

    // Should receive an error since the group is full
    val errorFuture = sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols, rebalanceTimeout)
    assertEquals(Errors.GROUP_MAX_SIZE_REACHED, await(errorFuture, 1).error)
  }

  @Test
  def testJoinGroupSessionTimeoutTooSmall() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols, sessionTimeout = GroupMinSessionTimeout - 1)
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.INVALID_SESSION_TIMEOUT, joinGroupError)
  }

  @Test
  def testJoinGroupSessionTimeoutTooLarge() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols, sessionTimeout = GroupMaxSessionTimeout + 1)
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.INVALID_SESSION_TIMEOUT, joinGroupError)
  }

  @Test
  def testJoinGroupUnknownConsumerNewGroup() {
    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.UNKNOWN_MEMBER_ID, joinGroupError)
  }

  @Test
  def testInvalidGroupId() {
    val groupId = ""
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    assertEquals(Errors.INVALID_GROUP_ID, joinGroupResult.error)
  }

  @Test
  def testValidJoinGroup() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.NONE, joinGroupError)
  }

  @Test
  def testJoinGroupInconsistentProtocolType() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID
    val otherMemberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    assertEquals(Errors.NONE, joinGroupResult.error)

    EasyMock.reset(replicaManager)
    val otherJoinGroupResult = await(sendJoinGroup(groupId, otherMemberId, "connect", protocols), 1)
    assertEquals(Errors.INCONSISTENT_GROUP_PROTOCOL, otherJoinGroupResult.error)
  }

  @Test
  def testJoinGroupWithEmptyProtocolType() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val joinGroupResult = joinGroup(groupId, memberId, "", protocols)
    assertEquals(Errors.INCONSISTENT_GROUP_PROTOCOL, joinGroupResult.error)
  }

  @Test
  def testJoinGroupWithEmptyGroupProtocol() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, List())
    assertEquals(Errors.INCONSISTENT_GROUP_PROTOCOL, joinGroupResult.error)
  }

  @Test
  def testNewMemberTimeoutCompletion(): Unit = {
    val sessionTimeout = GroupCoordinator.NewMemberJoinTimeoutMs + 5000
    val responseFuture = sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols, sessionTimeout, DefaultRebalanceTimeout, false)

    timer.advanceClock(GroupInitialRebalanceDelay + 1)

    val joinResult = Await.result(responseFuture, Duration(DefaultRebalanceTimeout + 100, TimeUnit.MILLISECONDS))
    val group = groupCoordinator.groupManager.getGroup(groupId).get
    val memberId = joinResult.memberId

    assertEquals(Errors.NONE, joinResult.error)
    assertEquals(0, group.allMemberMetadata.count(_.isNew))

    EasyMock.reset(replicaManager)
    val syncGroupResult = syncGroupLeader(groupId, joinResult.generationId, memberId, Map(memberId -> Array[Byte]()))
    val syncGroupError = syncGroupResult._2

    assertEquals(Errors.NONE, syncGroupError)
    assertEquals(1, group.size)

    timer.advanceClock(GroupCoordinator.NewMemberJoinTimeoutMs + 100)

    // Make sure the NewMemberTimeout is not still in effect, and the member is not kicked
    assertEquals(1, group.size)

    timer.advanceClock(sessionTimeout + 100)
    assertEquals(0, group.size)
  }

  @Test
  def testNewMemberJoinExpiration(): Unit = {
    // This tests new member expiration during a protracted rebalance. We first create a
    // group with one member which uses a large value for session timeout and rebalance timeout.
    // We then join with one new member and let the rebalance hang while we await the first member.
    // The new member join timeout expires and its JoinGroup request is failed.

    val sessionTimeout = GroupCoordinator.NewMemberJoinTimeoutMs + 5000
    val rebalanceTimeout = GroupCoordinator.NewMemberJoinTimeoutMs * 2

    val firstJoinResult = joinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols,
      sessionTimeout, rebalanceTimeout)
    val firstMemberId = firstJoinResult.memberId
    assertEquals(firstMemberId, firstJoinResult.leaderId)
    assertEquals(Errors.NONE, firstJoinResult.error)

    val groupOpt = groupCoordinator.groupManager.getGroup(groupId)
    assertTrue(groupOpt.isDefined)
    val group = groupOpt.get
    assertEquals(0, group.allMemberMetadata.count(_.isNew))

    EasyMock.reset(replicaManager)

    val responseFuture = sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols, sessionTimeout, rebalanceTimeout)
    assertFalse(responseFuture.isCompleted)

    assertEquals(2, group.allMembers.size)
    assertEquals(1, group.allMemberMetadata.count(_.isNew))

    val newMember = group.allMemberMetadata.find(_.isNew).get
    assertNotEquals(firstMemberId, newMember.memberId)

    timer.advanceClock(GroupCoordinator.NewMemberJoinTimeoutMs + 1)
    assertTrue(responseFuture.isCompleted)

    val response = Await.result(responseFuture, Duration(0, TimeUnit.MILLISECONDS))
    assertEquals(Errors.UNKNOWN_MEMBER_ID, response.error)
    assertEquals(1, group.allMembers.size)
    assertEquals(0, group.allMemberMetadata.count(_.isNew))
    assertEquals(firstMemberId, group.allMembers.head)
  }

  @Test
  def testNewMemberFailureAfterJoinGroupCompletion(): Unit = {
    // For old versions of the JoinGroup protocol, new members were subject
    // to expiration if the rebalance took long enough. This test case ensures
    // that following completion of the JoinGroup phase, new members follow
    // normal heartbeat expiration logic.

    val firstJoinResult = joinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)
    val firstMemberId = firstJoinResult.memberId
    val firstGenerationId = firstJoinResult.generationId
    assertEquals(firstMemberId, firstJoinResult.leaderId)
    assertEquals(Errors.NONE, firstJoinResult.error)

    EasyMock.reset(replicaManager)
    val firstSyncResult = syncGroupLeader(groupId, firstGenerationId, firstMemberId,
      Map(firstMemberId -> Array[Byte]()))
    assertEquals(Errors.NONE, firstSyncResult._2)

    EasyMock.reset(replicaManager)
    val otherJoinFuture = sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)

    EasyMock.reset(replicaManager)
    val joinFuture = sendJoinGroup(groupId, firstMemberId, protocolType, protocols,
      requireKnownMemberId = false)

    val joinResult = await(joinFuture, DefaultSessionTimeout+100)
    val otherJoinResult = await(otherJoinFuture, DefaultSessionTimeout+100)
    assertEquals(Errors.NONE, joinResult.error)
    assertEquals(Errors.NONE, otherJoinResult.error)

    verifySessionExpiration(groupId)
  }

  @Test
  def testNewMemberFailureAfterSyncGroupCompletion(): Unit = {
    // For old versions of the JoinGroup protocol, new members were subject
    // to expiration if the rebalance took long enough. This test case ensures
    // that following completion of the SyncGroup phase, new members follow
    // normal heartbeat expiration logic.

    val firstJoinResult = joinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)
    val firstMemberId = firstJoinResult.memberId
    val firstGenerationId = firstJoinResult.generationId
    assertEquals(firstMemberId, firstJoinResult.leaderId)
    assertEquals(Errors.NONE, firstJoinResult.error)

    EasyMock.reset(replicaManager)
    val firstSyncResult = syncGroupLeader(groupId, firstGenerationId, firstMemberId,
      Map(firstMemberId -> Array[Byte]()))
    assertEquals(Errors.NONE, firstSyncResult._2)

    EasyMock.reset(replicaManager)
    val otherJoinFuture = sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)

    EasyMock.reset(replicaManager)
    val joinFuture = sendJoinGroup(groupId, firstMemberId, protocolType, protocols,
      requireKnownMemberId = false)

    val joinResult = await(joinFuture, DefaultSessionTimeout+100)
    val otherJoinResult = await(otherJoinFuture, DefaultSessionTimeout+100)
    assertEquals(Errors.NONE, joinResult.error)
    assertEquals(Errors.NONE, otherJoinResult.error)
    val secondGenerationId = joinResult.generationId
    val secondMemberId = otherJoinResult.memberId

    EasyMock.reset(replicaManager)
    sendSyncGroupFollower(groupId, secondGenerationId, secondMemberId)

    EasyMock.reset(replicaManager)
    val syncGroupResult = syncGroupLeader(groupId, secondGenerationId, firstMemberId,
      Map(firstMemberId -> Array.emptyByteArray, secondMemberId -> Array.emptyByteArray))
    assertEquals(Errors.NONE, syncGroupResult._2)

    verifySessionExpiration(groupId)
  }

  private def verifySessionExpiration(groupId: String): Unit = {
    EasyMock.reset(replicaManager)
    EasyMock.expect(replicaManager.getMagic(EasyMock.anyObject()))
      .andReturn(Some(RecordBatch.CURRENT_MAGIC_VALUE)).anyTimes()
    EasyMock.replay(replicaManager)

    timer.advanceClock(DefaultSessionTimeout + 1)

    val groupMetadata = group(groupId)
    assertEquals(Empty, groupMetadata.currentState)
    assertEquals(0, groupMetadata.allMembers.size)
  }

  @Test
  def testJoinGroupInconsistentGroupProtocol(): Unit = {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val otherMemberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val joinGroupFuture = sendJoinGroup(groupId, memberId, protocolType, List(("range", metadata)))

    EasyMock.reset(replicaManager)
    val otherJoinGroupResult = joinGroup(groupId, otherMemberId, protocolType, List(("roundrobin", metadata)))
    timer.advanceClock(GroupInitialRebalanceDelay + 1)

    val joinGroupResult = await(joinGroupFuture, 1)
    assertEquals(Errors.NONE, joinGroupResult.error)
    assertEquals(Errors.INCONSISTENT_GROUP_PROTOCOL, otherJoinGroupResult.error)
  }

  @Test
  def testJoinGroupUnknownConsumerExistingGroup() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID
    val otherMemberId = "memberId"

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    assertEquals(Errors.NONE, joinGroupResult.error)

    EasyMock.reset(replicaManager)
    val otherJoinGroupResult = await(sendJoinGroup(groupId, otherMemberId, protocolType, protocols), 1)
    assertEquals(Errors.UNKNOWN_MEMBER_ID, otherJoinGroupResult.error)
  }

  @Test
  def testJoinGroupUnknownConsumerDeadGroup() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val deadGroupId = "deadGroupId"

    groupCoordinator.groupManager.addGroup(new GroupMetadata(deadGroupId, Dead, new MockTime()))
    val joinGroupResult = joinGroup(deadGroupId, memberId, protocolType, protocols)
    assertEquals(Errors.UNKNOWN_MEMBER_ID, joinGroupResult.error)
  }

  @Test
  def testJoinGroupSecondJoinInconsistentProtocol() {
    var responseFuture = sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols, requireKnownMemberId = true)
    var joinGroupResult = Await.result(responseFuture, Duration(DefaultRebalanceTimeout + 1, TimeUnit.MILLISECONDS))
    assertEquals(Errors.MEMBER_ID_REQUIRED, joinGroupResult.error)
    val memberId = joinGroupResult.memberId

    // Sending an inconsistent protocol shall be refused
    EasyMock.reset(replicaManager)
    responseFuture = sendJoinGroup(groupId, memberId, protocolType, List(), requireKnownMemberId = true)
    joinGroupResult = Await.result(responseFuture, Duration(DefaultRebalanceTimeout + 1, TimeUnit.MILLISECONDS))
    assertEquals(Errors.INCONSISTENT_GROUP_PROTOCOL, joinGroupResult.error)

    // Sending consistent protocol shall be accepted
    EasyMock.reset(replicaManager)
    responseFuture = sendJoinGroup(groupId, memberId, protocolType, protocols, requireKnownMemberId = true)
    timer.advanceClock(GroupInitialRebalanceDelay + 1)
    joinGroupResult = Await.result(responseFuture, Duration(DefaultRebalanceTimeout + 1, TimeUnit.MILLISECONDS))
    assertEquals(Errors.NONE, joinGroupResult.error)
  }

  @Test
  def testHeartbeatWrongCoordinator() {
    val heartbeatResult = heartbeat(otherGroupId, memberId, -1)
    assertEquals(Errors.NOT_COORDINATOR, heartbeatResult)
  }

  @Test
  def testHeartbeatUnknownGroup() {

    val heartbeatResult = heartbeat(groupId, memberId, -1)
    assertEquals(Errors.UNKNOWN_MEMBER_ID, heartbeatResult)
  }

  @Test
  def testHeartbeatUnknownConsumerExistingGroup() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID
    val otherMemberId = "memberId"

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    val assignedMemberId = joinGroupResult.memberId
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.NONE, joinGroupError)

    EasyMock.reset(replicaManager)
    val syncGroupResult = syncGroupLeader(groupId, joinGroupResult.generationId, assignedMemberId, Map(assignedMemberId -> Array[Byte]()))
    val syncGroupError = syncGroupResult._2
    assertEquals(Errors.NONE, syncGroupError)

    EasyMock.reset(replicaManager)
    val heartbeatResult = heartbeat(groupId, otherMemberId, 1)
    assertEquals(Errors.UNKNOWN_MEMBER_ID, heartbeatResult)
  }

  @Test
  def testHeartbeatRebalanceInProgress() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    val assignedMemberId = joinGroupResult.memberId
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.NONE, joinGroupError)

    EasyMock.reset(replicaManager)
    val heartbeatResult = heartbeat(groupId, assignedMemberId, 2)
    assertEquals(Errors.REBALANCE_IN_PROGRESS, heartbeatResult)
  }

  @Test
  def testHeartbeatIllegalGeneration() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    val assignedMemberId = joinGroupResult.memberId
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.NONE, joinGroupError)

    EasyMock.reset(replicaManager)
    val syncGroupResult = syncGroupLeader(groupId, joinGroupResult.generationId, assignedMemberId, Map(assignedMemberId -> Array[Byte]()))
    val syncGroupError = syncGroupResult._2
    assertEquals(Errors.NONE, syncGroupError)

    EasyMock.reset(replicaManager)
    val heartbeatResult = heartbeat(groupId, assignedMemberId, 2)
    assertEquals(Errors.ILLEGAL_GENERATION, heartbeatResult)
  }

  @Test
  def testValidHeartbeat() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    val assignedConsumerId = joinGroupResult.memberId
    val generationId = joinGroupResult.generationId
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.NONE, joinGroupError)

    EasyMock.reset(replicaManager)
    val syncGroupResult = syncGroupLeader(groupId, generationId, assignedConsumerId, Map(assignedConsumerId -> Array[Byte]()))
    val syncGroupError = syncGroupResult._2
    assertEquals(Errors.NONE, syncGroupError)

    EasyMock.reset(replicaManager)
    val heartbeatResult = heartbeat(groupId, assignedConsumerId, 1)
    assertEquals(Errors.NONE, heartbeatResult)
  }

  @Test
  def testSessionTimeout() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    val assignedConsumerId = joinGroupResult.memberId
    val generationId = joinGroupResult.generationId
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.NONE, joinGroupError)

    EasyMock.reset(replicaManager)
    val (_, syncGroupError) = syncGroupLeader(groupId, generationId, assignedConsumerId, Map(assignedConsumerId -> Array[Byte]()))
    assertEquals(Errors.NONE, syncGroupError)

    EasyMock.reset(replicaManager)
    EasyMock.expect(replicaManager.getPartition(new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId))).andReturn(None)
    EasyMock.expect(replicaManager.getMagic(EasyMock.anyObject())).andReturn(Some(RecordBatch.MAGIC_VALUE_V1)).anyTimes()
    EasyMock.replay(replicaManager)

    timer.advanceClock(DefaultSessionTimeout + 100)

    EasyMock.reset(replicaManager)
    val heartbeatResult = heartbeat(groupId, assignedConsumerId, 1)
    assertEquals(Errors.UNKNOWN_MEMBER_ID, heartbeatResult)
  }

  @Test
  def testHeartbeatMaintainsSession() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID
    val sessionTimeout = 1000

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols,
      rebalanceTimeout = sessionTimeout, sessionTimeout = sessionTimeout)
    val assignedConsumerId = joinGroupResult.memberId
    val generationId = joinGroupResult.generationId
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.NONE, joinGroupError)

    EasyMock.reset(replicaManager)
    val (_, syncGroupError) = syncGroupLeader(groupId, generationId, assignedConsumerId, Map(assignedConsumerId -> Array[Byte]()))
    assertEquals(Errors.NONE, syncGroupError)

    timer.advanceClock(sessionTimeout / 2)

    EasyMock.reset(replicaManager)
    var heartbeatResult = heartbeat(groupId, assignedConsumerId, 1)
    assertEquals(Errors.NONE, heartbeatResult)

    timer.advanceClock(sessionTimeout / 2 + 100)

    EasyMock.reset(replicaManager)
    heartbeatResult = heartbeat(groupId, assignedConsumerId, 1)
    assertEquals(Errors.NONE, heartbeatResult)
  }

  @Test
  def testCommitMaintainsSession() {
    val sessionTimeout = 1000
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID
    val tp = new TopicPartition("topic", 0)
    val offset = offsetAndMetadata(0)

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols,
      rebalanceTimeout = sessionTimeout, sessionTimeout = sessionTimeout)
    val assignedConsumerId = joinGroupResult.memberId
    val generationId = joinGroupResult.generationId
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.NONE, joinGroupError)

    EasyMock.reset(replicaManager)
    val (_, syncGroupError) = syncGroupLeader(groupId, generationId, assignedConsumerId, Map(assignedConsumerId -> Array[Byte]()))
    assertEquals(Errors.NONE, syncGroupError)

    timer.advanceClock(sessionTimeout / 2)

    EasyMock.reset(replicaManager)
    val commitOffsetResult = commitOffsets(groupId, assignedConsumerId, generationId, Map(tp -> offset))
    assertEquals(Errors.NONE, commitOffsetResult(tp))

    timer.advanceClock(sessionTimeout / 2 + 100)

    EasyMock.reset(replicaManager)
    val heartbeatResult = heartbeat(groupId, assignedConsumerId, 1)
    assertEquals(Errors.NONE, heartbeatResult)
  }

  @Test
  def testSessionTimeoutDuringRebalance() {
    // create a group with a single member
    val firstJoinResult = joinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols,
      rebalanceTimeout = 2000, sessionTimeout = 1000)
    val firstMemberId = firstJoinResult.memberId
    val firstGenerationId = firstJoinResult.generationId
    assertEquals(firstMemberId, firstJoinResult.leaderId)
    assertEquals(Errors.NONE, firstJoinResult.error)

    EasyMock.reset(replicaManager)
    val firstSyncResult = syncGroupLeader(groupId, firstGenerationId, firstMemberId, Map(firstMemberId -> Array[Byte]()))
    assertEquals(Errors.NONE, firstSyncResult._2)

    // now have a new member join to trigger a rebalance
    EasyMock.reset(replicaManager)
    val otherJoinFuture = sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)

    timer.advanceClock(500)

    EasyMock.reset(replicaManager)
    var heartbeatResult = heartbeat(groupId, firstMemberId, firstGenerationId)
    assertEquals(Errors.REBALANCE_IN_PROGRESS, heartbeatResult)

    // letting the session expire should make the member fall out of the group
    timer.advanceClock(1100)

    EasyMock.reset(replicaManager)
    heartbeatResult = heartbeat(groupId, firstMemberId, firstGenerationId)
    assertEquals(Errors.UNKNOWN_MEMBER_ID, heartbeatResult)

    // and the rebalance should complete with only the new member
    val otherJoinResult = await(otherJoinFuture, DefaultSessionTimeout+100)
    assertEquals(Errors.NONE, otherJoinResult.error)
  }

  @Test
  def testRebalanceCompletesBeforeMemberJoins() {
    // create a group with a single member
    val firstJoinResult = joinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols,
      rebalanceTimeout = 1200, sessionTimeout = 1000)
    val firstMemberId = firstJoinResult.memberId
    val firstGenerationId = firstJoinResult.generationId
    assertEquals(firstMemberId, firstJoinResult.leaderId)
    assertEquals(Errors.NONE, firstJoinResult.error)

    EasyMock.reset(replicaManager)
    val firstSyncResult = syncGroupLeader(groupId, firstGenerationId, firstMemberId, Map(firstMemberId -> Array[Byte]()))
    assertEquals(Errors.NONE, firstSyncResult._2)

    // now have a new member join to trigger a rebalance
    EasyMock.reset(replicaManager)
    val otherJoinFuture = sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)

    // send a couple heartbeats to keep the member alive while the rebalance finishes
    timer.advanceClock(500)
    EasyMock.reset(replicaManager)
    var heartbeatResult = heartbeat(groupId, firstMemberId, firstGenerationId)
    assertEquals(Errors.REBALANCE_IN_PROGRESS, heartbeatResult)

    timer.advanceClock(500)
    EasyMock.reset(replicaManager)
    heartbeatResult = heartbeat(groupId, firstMemberId, firstGenerationId)
    assertEquals(Errors.REBALANCE_IN_PROGRESS, heartbeatResult)

    // now timeout the rebalance
    timer.advanceClock(500)
    val otherJoinResult = await(otherJoinFuture, DefaultSessionTimeout+100)
    val otherMemberId = otherJoinResult.memberId
    val otherGenerationId = otherJoinResult.generationId
    EasyMock.reset(replicaManager)
    val syncResult = syncGroupLeader(groupId, otherGenerationId, otherMemberId, Map(otherMemberId -> Array[Byte]()))
    assertEquals(Errors.NONE, syncResult._2)

    // the unjoined member should be kicked out from the group
    assertEquals(Errors.NONE, otherJoinResult.error)
    EasyMock.reset(replicaManager)
    heartbeatResult = heartbeat(groupId, firstMemberId, firstGenerationId)
    assertEquals(Errors.UNKNOWN_MEMBER_ID, heartbeatResult)

    // the joined member should get heart beat response with no error. Let the new member keep heartbeating for a while
    // to verify that no new rebalance is triggered unexpectedly
    for ( _ <-  1 to 20) {
      timer.advanceClock(500)
      EasyMock.reset(replicaManager)
      heartbeatResult = heartbeat(groupId, otherMemberId, otherGenerationId)
      assertEquals(Errors.NONE, heartbeatResult)
    }
  }

  @Test
  def testSyncGroupEmptyAssignment() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    val assignedConsumerId = joinGroupResult.memberId
    val generationId = joinGroupResult.generationId
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.NONE, joinGroupError)

    EasyMock.reset(replicaManager)
    val syncGroupResult = syncGroupLeader(groupId, generationId, assignedConsumerId, Map())
    val syncGroupError = syncGroupResult._2
    assertEquals(Errors.NONE, syncGroupError)
    assertTrue(syncGroupResult._1.isEmpty)

    EasyMock.reset(replicaManager)
    val heartbeatResult = heartbeat(groupId, assignedConsumerId, 1)
    assertEquals(Errors.NONE, heartbeatResult)
  }

  @Test
  def testSyncGroupNotCoordinator() {
    val generation = 1

    val syncGroupResult = syncGroupFollower(otherGroupId, generation, memberId)
    assertEquals(Errors.NOT_COORDINATOR, syncGroupResult._2)
  }

  @Test
  def testSyncGroupFromUnknownGroup() {
    val generation = 1

    val syncGroupResult = syncGroupFollower(groupId, generation, memberId)
    assertEquals(Errors.UNKNOWN_MEMBER_ID, syncGroupResult._2)
  }

  @Test
  def testSyncGroupFromUnknownMember() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    val assignedConsumerId = joinGroupResult.memberId
    val generationId = joinGroupResult.generationId
    assertEquals(Errors.NONE, joinGroupResult.error)

    EasyMock.reset(replicaManager)
    val syncGroupResult = syncGroupLeader(groupId, generationId, assignedConsumerId, Map(assignedConsumerId -> Array[Byte]()))
    val syncGroupError = syncGroupResult._2
    assertEquals(Errors.NONE, syncGroupError)

    EasyMock.reset(replicaManager)
    val unknownMemberId = "blah"
    val unknownMemberSyncResult = syncGroupFollower(groupId, generationId, unknownMemberId)
    assertEquals(Errors.UNKNOWN_MEMBER_ID, unknownMemberSyncResult._2)
  }

  @Test
  def testSyncGroupFromIllegalGeneration() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    val assignedConsumerId = joinGroupResult.memberId
    val generationId = joinGroupResult.generationId
    assertEquals(Errors.NONE, joinGroupResult.error)

    EasyMock.reset(replicaManager)
    // send the sync group with an invalid generation
    val syncGroupResult = syncGroupLeader(groupId, generationId+1, assignedConsumerId, Map(assignedConsumerId -> Array[Byte]()))
    assertEquals(Errors.ILLEGAL_GENERATION, syncGroupResult._2)
  }

  @Test
  def testJoinGroupFromUnchangedFollowerDoesNotRebalance() {
    // to get a group of two members:
    // 1. join and sync with a single member (because we can't immediately join with two members)
    // 2. join and sync with the first member and a new member

    val firstJoinResult = joinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)
    val firstMemberId = firstJoinResult.memberId
    val firstGenerationId = firstJoinResult.generationId
    assertEquals(firstMemberId, firstJoinResult.leaderId)
    assertEquals(Errors.NONE, firstJoinResult.error)

    EasyMock.reset(replicaManager)
    val firstSyncResult = syncGroupLeader(groupId, firstGenerationId, firstMemberId, Map(firstMemberId -> Array[Byte]()))
    assertEquals(Errors.NONE, firstSyncResult._2)

    EasyMock.reset(replicaManager)
    val otherJoinFuture = sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)

    EasyMock.reset(replicaManager)
    val joinFuture = sendJoinGroup(groupId, firstMemberId, protocolType, protocols)

    val joinResult = await(joinFuture, DefaultSessionTimeout+100)
    val otherJoinResult = await(otherJoinFuture, DefaultSessionTimeout+100)
    assertEquals(Errors.NONE, joinResult.error)
    assertEquals(Errors.NONE, otherJoinResult.error)
    assertTrue(joinResult.generationId == otherJoinResult.generationId)

    assertEquals(firstMemberId, joinResult.leaderId)
    assertEquals(firstMemberId, otherJoinResult.leaderId)

    val nextGenerationId = joinResult.generationId

    // this shouldn't cause a rebalance since protocol information hasn't changed
    EasyMock.reset(replicaManager)
    val followerJoinResult = await(sendJoinGroup(groupId, otherJoinResult.memberId, protocolType, protocols), 1)

    assertEquals(Errors.NONE, followerJoinResult.error)
    assertEquals(nextGenerationId, followerJoinResult.generationId)
  }

  @Test
  def testJoinGroupFromUnchangedLeaderShouldRebalance() {
    val firstJoinResult = joinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)
    val firstMemberId = firstJoinResult.memberId
    val firstGenerationId = firstJoinResult.generationId
    assertEquals(firstMemberId, firstJoinResult.leaderId)
    assertEquals(Errors.NONE, firstJoinResult.error)

    EasyMock.reset(replicaManager)
    val firstSyncResult = syncGroupLeader(groupId, firstGenerationId, firstMemberId, Map(firstMemberId -> Array[Byte]()))
    assertEquals(Errors.NONE, firstSyncResult._2)

    // join groups from the leader should force the group to rebalance, which allows the
    // leader to push new assignments when local metadata changes

    EasyMock.reset(replicaManager)
    val secondJoinResult = await(sendJoinGroup(groupId, firstMemberId, protocolType, protocols), 1)

    assertEquals(Errors.NONE, secondJoinResult.error)
    assertNotEquals(firstGenerationId, secondJoinResult.generationId)
  }

  /**
    * Test if the following scenario completes a rebalance correctly: A new member starts a JoinGroup request with
    * an UNKNOWN_MEMBER_ID, attempting to join a stable group. But never initiates the second JoinGroup request with
    * the provided member ID and times out. The test checks if original member remains the sole member in this group,
    * which should remain stable throughout this test.
    */
  @Test
  def testSecondMemberPartiallyJoinAndTimeout() {
    val firstJoinResult = joinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)
    val firstMemberId = firstJoinResult.memberId
    val firstGenerationId = firstJoinResult.generationId
    assertEquals(firstMemberId, firstJoinResult.leaderId)
    assertEquals(Errors.NONE, firstJoinResult.error)

    //Starting sync group leader
    EasyMock.reset(replicaManager)
    val firstSyncResult = syncGroupLeader(groupId, firstGenerationId, firstMemberId, Map(firstMemberId -> Array[Byte]()))
    assertEquals(Errors.NONE, firstSyncResult._2)
    timer.advanceClock(100)
    assertEquals(1, groupCoordinator.groupManager.getGroup(groupId).get.allMembers.size)
    assertEquals(0, groupCoordinator.groupManager.getGroup(groupId).get.numPending)
    val group = groupCoordinator.groupManager.getGroup(groupId).get

    // ensure the group is stable before a new member initiates join request
    assertEquals(Stable, group.currentState)

    // new member initiates join group
    EasyMock.reset(replicaManager)
    val secondJoinResult = joinGroupPartial(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)
    assertEquals(Errors.MEMBER_ID_REQUIRED, secondJoinResult.error)
    assertEquals(1, group.numPending)
    assertEquals(Stable, group.currentState)

    EasyMock.reset(replicaManager)
    EasyMock.expect(replicaManager.getMagic(EasyMock.anyObject())).andReturn(Some(RecordBatch.MAGIC_VALUE_V1)).anyTimes()
    EasyMock.replay(replicaManager)

    // advance clock to timeout the pending member
    assertEquals(1, group.allMembers.size)
    assertEquals(1, group.numPending)
    timer.advanceClock(300)

    // original (firstMember) member sends heartbeats to prevent session timeouts.
    EasyMock.reset(replicaManager)
    val heartbeatResult = heartbeat(groupId, firstMemberId, 1)
    assertEquals(Errors.NONE, heartbeatResult)

    // timeout the pending member
    timer.advanceClock(300)

    // at this point the second member should have been removed from pending list (session timeout),
    // and the group should be in Stable state with only the first member in it.
    assertEquals(1, group.allMembers.size)
    assertEquals(0, group.numPending)
    assertEquals(Stable, group.currentState)
    assertTrue(group.has(firstMemberId))
  }

  /**
    * Create a group with two members in Stable state. Create a third pending member by completing it's first JoinGroup
    * request without a member id.
    */
  private def setupGroupWithPendingMember(): JoinGroupResult = {
    // add the first member
    val joinResult1 = joinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)
    assertGroupState(groupState = CompletingRebalance)

    // now the group is stable, with the one member that joined above
    EasyMock.reset(replicaManager)
    val firstSyncResult = syncGroupLeader(groupId, joinResult1.generationId, joinResult1.memberId, Map(joinResult1.memberId -> Array[Byte]()))
    assertEquals(Errors.NONE, firstSyncResult._2)
    assertGroupState(groupState = Stable)

    // start the join for the second member
    EasyMock.reset(replicaManager)
    val secondJoinFuture = sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)

    // rejoin the first member back into the group
    EasyMock.reset(replicaManager)
    val firstJoinFuture = sendJoinGroup(groupId, joinResult1.memberId, protocolType, protocols)
    val firstMemberJoinResult = await(firstJoinFuture, DefaultSessionTimeout+100)
    val secondMemberJoinResult = await(secondJoinFuture, DefaultSessionTimeout+100)
    assertGroupState(groupState = CompletingRebalance)

    // stabilize the group
    EasyMock.reset(replicaManager)
    val secondSyncResult = syncGroupLeader(groupId, firstMemberJoinResult.generationId, joinResult1.memberId, Map(joinResult1.memberId -> Array[Byte]()))
    assertEquals(Errors.NONE, secondSyncResult._2)
    assertGroupState(groupState = Stable)

    // re-join an existing member, to transition the group to PreparingRebalance state.
    EasyMock.reset(replicaManager)
    sendJoinGroup(groupId, firstMemberJoinResult.memberId, protocolType, protocols)
    assertGroupState(groupState = PreparingRebalance)

    // create a pending member in the group
    EasyMock.reset(replicaManager)
    var pendingMember = joinGroupPartial(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols, sessionTimeout=100)
    assertEquals(1, groupCoordinator.groupManager.getGroup(groupId).get.numPending)

    // re-join the second existing member
    EasyMock.reset(replicaManager)
    sendJoinGroup(groupId, secondMemberJoinResult.memberId, protocolType, protocols)
    assertGroupState(groupState = PreparingRebalance)
    assertEquals(1, groupCoordinator.groupManager.getGroup(groupId).get.numPending)

    pendingMember
  }

  /**
    * Setup a group in with a pending member. The test checks if the a pending member joining completes the rebalancing
    * operation
    */
  @Test
  def testJoinGroupCompletionWhenPendingMemberJoins() {
    val pendingMember = setupGroupWithPendingMember()

    // compete join group for the pending member
    EasyMock.reset(replicaManager)
    val pendingMemberJoinFuture = sendJoinGroup(groupId, pendingMember.memberId, protocolType, protocols)
    await(pendingMemberJoinFuture, DefaultSessionTimeout+100)

    assertGroupState(groupState = CompletingRebalance)
    assertEquals(3, group().allMembers.size)
    assertEquals(0, group().numPending)
  }

  /**
    * Setup a group in with a pending member. The test checks if the timeout of the pending member will
    * cause the group to return to a CompletingRebalance state.
    */
  @Test
  def testJoinGroupCompletionWhenPendingMemberTimesOut() {
    setupGroupWithPendingMember()

    // Advancing Clock by > 100 (session timeout for third and fourth member)
    // and < 500 (for first and second members). This will force the coordinator to attempt join
    // completion on heartbeat expiration (since we are in PendingRebalance stage).
    EasyMock.reset(replicaManager)
    EasyMock.expect(replicaManager.getMagic(EasyMock.anyObject())).andReturn(Some(RecordBatch.MAGIC_VALUE_V1)).anyTimes()
    EasyMock.replay(replicaManager)
    timer.advanceClock(120)

    assertGroupState(groupState = CompletingRebalance)
    assertEquals(2, group().allMembers.size)
    assertEquals(0, group().numPending)
  }

  @Test
  def testPendingMembersLeavesGroup(): Unit = {
    val pending = setupGroupWithPendingMember()

    EasyMock.reset(replicaManager)
    val leaveGroupResult = leaveGroup(groupId, pending.memberId)
    assertEquals(Errors.NONE, leaveGroupResult)

    assertGroupState(groupState = CompletingRebalance)
    assertEquals(2, group().allMembers.size)
    assertEquals(0, group().numPending)
  }

  private def group(groupId: String = groupId) = {
    groupCoordinator.groupManager.getGroup(groupId) match {
      case Some(g) => g
      case None => null
    }
  }

  private def assertGroupState(groupId: String = groupId,
                               groupState: GroupState): Unit = {
    groupCoordinator.groupManager.getGroup(groupId) match {
      case Some(group) => assertEquals(groupState, group.currentState)
      case None => fail(s"Group $groupId not found in coordinator")
    }
  }

  private def joinGroupPartial(groupId: String,
                               memberId: String,
                               protocolType: String,
                               protocols: List[(String, Array[Byte])],
                               sessionTimeout: Int = DefaultSessionTimeout,
                               rebalanceTimeout: Int = DefaultRebalanceTimeout): JoinGroupResult = {
    val requireKnownMemberId = true
    val responseFuture = sendJoinGroup(groupId, memberId, protocolType, protocols, sessionTimeout, rebalanceTimeout, requireKnownMemberId)
    Await.result(responseFuture, Duration(rebalanceTimeout + 100, TimeUnit.MILLISECONDS))
  }

  @Test
  def testLeaderFailureInSyncGroup() {
    // to get a group of two members:
    // 1. join and sync with a single member (because we can't immediately join with two members)
    // 2. join and sync with the first member and a new member

    val firstJoinResult = joinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)
    val firstMemberId = firstJoinResult.memberId
    val firstGenerationId = firstJoinResult.generationId
    assertEquals(firstMemberId, firstJoinResult.leaderId)
    assertEquals(Errors.NONE, firstJoinResult.error)

    EasyMock.reset(replicaManager)
    val firstSyncResult = syncGroupLeader(groupId, firstGenerationId, firstMemberId, Map(firstMemberId -> Array[Byte]()))
    assertEquals(Errors.NONE, firstSyncResult._2)

    EasyMock.reset(replicaManager)
    val otherJoinFuture = sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)

    EasyMock.reset(replicaManager)
    val joinFuture = sendJoinGroup(groupId, firstMemberId, protocolType, protocols)

    val joinResult = await(joinFuture, DefaultSessionTimeout+100)
    val otherJoinResult = await(otherJoinFuture, DefaultSessionTimeout+100)
    assertEquals(Errors.NONE, joinResult.error)
    assertEquals(Errors.NONE, otherJoinResult.error)
    assertTrue(joinResult.generationId == otherJoinResult.generationId)

    assertEquals(firstMemberId, joinResult.leaderId)
    assertEquals(firstMemberId, otherJoinResult.leaderId)

    val nextGenerationId = joinResult.generationId

    // with no leader SyncGroup, the follower's request should fail with an error indicating
    // that it should rejoin
    EasyMock.reset(replicaManager)
    val followerSyncFuture = sendSyncGroupFollower(groupId, nextGenerationId, otherJoinResult.memberId)

    timer.advanceClock(DefaultSessionTimeout + 100)

    val followerSyncResult = await(followerSyncFuture, DefaultSessionTimeout+100)
    assertEquals(Errors.REBALANCE_IN_PROGRESS, followerSyncResult._2)
  }

  @Test
  def testSyncGroupFollowerAfterLeader() {
    // to get a group of two members:
    // 1. join and sync with a single member (because we can't immediately join with two members)
    // 2. join and sync with the first member and a new member

    val firstJoinResult = joinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)
    val firstMemberId = firstJoinResult.memberId
    val firstGenerationId = firstJoinResult.generationId
    assertEquals(firstMemberId, firstJoinResult.leaderId)
    assertEquals(Errors.NONE, firstJoinResult.error)

    EasyMock.reset(replicaManager)
    val firstSyncResult = syncGroupLeader(groupId, firstGenerationId, firstMemberId, Map(firstMemberId -> Array[Byte]()))
    assertEquals(Errors.NONE, firstSyncResult._2)

    EasyMock.reset(replicaManager)
    val otherJoinFuture = sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)

    EasyMock.reset(replicaManager)
    val joinFuture = sendJoinGroup(groupId, firstMemberId, protocolType, protocols)

    val joinResult = await(joinFuture, DefaultSessionTimeout+100)
    val otherJoinResult = await(otherJoinFuture, DefaultSessionTimeout+100)
    assertEquals(Errors.NONE, joinResult.error)
    assertEquals(Errors.NONE, otherJoinResult.error)
    assertTrue(joinResult.generationId == otherJoinResult.generationId)

    assertEquals(firstMemberId, joinResult.leaderId)
    assertEquals(firstMemberId, otherJoinResult.leaderId)

    val nextGenerationId = joinResult.generationId
    val leaderId = firstMemberId
    val leaderAssignment = Array[Byte](0)
    val followerId = otherJoinResult.memberId
    val followerAssignment = Array[Byte](1)

    EasyMock.reset(replicaManager)
    val leaderSyncResult = syncGroupLeader(groupId, nextGenerationId, leaderId,
      Map(leaderId -> leaderAssignment, followerId -> followerAssignment))
    assertEquals(Errors.NONE, leaderSyncResult._2)
    assertEquals(leaderAssignment, leaderSyncResult._1)

    EasyMock.reset(replicaManager)
    val followerSyncResult = syncGroupFollower(groupId, nextGenerationId, otherJoinResult.memberId)
    assertEquals(Errors.NONE, followerSyncResult._2)
    assertEquals(followerAssignment, followerSyncResult._1)
  }

  @Test
  def testSyncGroupLeaderAfterFollower() {
    // to get a group of two members:
    // 1. join and sync with a single member (because we can't immediately join with two members)
    // 2. join and sync with the first member and a new member

    val joinGroupResult = joinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)
    val firstMemberId = joinGroupResult.memberId
    val firstGenerationId = joinGroupResult.generationId
    assertEquals(firstMemberId, joinGroupResult.leaderId)
    assertEquals(Errors.NONE, joinGroupResult.error)

    EasyMock.reset(replicaManager)
    val syncGroupResult = syncGroupLeader(groupId, firstGenerationId, firstMemberId, Map(firstMemberId -> Array[Byte]()))
    val syncGroupError = syncGroupResult._2
    assertEquals(Errors.NONE, syncGroupError)

    EasyMock.reset(replicaManager)
    val otherJoinFuture = sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)

    EasyMock.reset(replicaManager)
    val joinFuture = sendJoinGroup(groupId, firstMemberId, protocolType, protocols)

    val joinResult = await(joinFuture, DefaultSessionTimeout+100)
    val otherJoinResult = await(otherJoinFuture, DefaultSessionTimeout+100)
    assertEquals(Errors.NONE, joinResult.error)
    assertEquals(Errors.NONE, otherJoinResult.error)
    assertTrue(joinResult.generationId == otherJoinResult.generationId)

    val nextGenerationId = joinResult.generationId
    val leaderId = joinResult.leaderId
    val leaderAssignment = Array[Byte](0)
    val followerId = otherJoinResult.memberId
    val followerAssignment = Array[Byte](1)

    assertEquals(firstMemberId, joinResult.leaderId)
    assertEquals(firstMemberId, otherJoinResult.leaderId)

    EasyMock.reset(replicaManager)
    val followerSyncFuture = sendSyncGroupFollower(groupId, nextGenerationId, followerId)

    EasyMock.reset(replicaManager)
    val leaderSyncResult = syncGroupLeader(groupId, nextGenerationId, leaderId,
      Map(leaderId -> leaderAssignment, followerId -> followerAssignment))
    assertEquals(Errors.NONE, leaderSyncResult._2)
    assertEquals(leaderAssignment, leaderSyncResult._1)

    val followerSyncResult = await(followerSyncFuture, DefaultSessionTimeout+100)
    assertEquals(Errors.NONE, followerSyncResult._2)
    assertEquals(followerAssignment, followerSyncResult._1)
  }

  @Test
  def testCommitOffsetFromUnknownGroup() {
    val generationId = 1
    val tp = new TopicPartition("topic", 0)
    val offset = offsetAndMetadata(0)

    val commitOffsetResult = commitOffsets(groupId, memberId, generationId, Map(tp -> offset))
    assertEquals(Errors.ILLEGAL_GENERATION, commitOffsetResult(tp))
  }

  @Test
  def testCommitOffsetWithDefaultGeneration() {
    val tp = new TopicPartition("topic", 0)
    val offset = offsetAndMetadata(0)

    val commitOffsetResult = commitOffsets(groupId, OffsetCommitRequest.DEFAULT_MEMBER_ID,
      OffsetCommitRequest.DEFAULT_GENERATION_ID, Map(tp -> offset))
    assertEquals(Errors.NONE, commitOffsetResult(tp))
  }

  @Test
  def testCommitOffsetsAfterGroupIsEmpty(): Unit = {
    // Tests the scenario where the reset offset tool modifies the offsets
    // of a group after it becomes empty

    // A group member joins
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID
    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    val assignedMemberId = joinGroupResult.memberId
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.NONE, joinGroupError)

    // and leaves.
    EasyMock.reset(replicaManager)
    val leaveGroupResult = leaveGroup(groupId, assignedMemberId)
    assertEquals(Errors.NONE, leaveGroupResult)

    // The simple offset commit should now fail
    EasyMock.reset(replicaManager)
    val tp = new TopicPartition("topic", 0)
    val offset = offsetAndMetadata(0)
    val commitOffsetResult = commitOffsets(groupId, OffsetCommitRequest.DEFAULT_MEMBER_ID,
      OffsetCommitRequest.DEFAULT_GENERATION_ID, Map(tp -> offset))
    assertEquals(Errors.NONE, commitOffsetResult(tp))

    val (error, partitionData) = groupCoordinator.handleFetchOffsets(groupId, Some(Seq(tp)))
    assertEquals(Errors.NONE, error)
    assertEquals(Some(0), partitionData.get(tp).map(_.offset))
  }

  @Test
  def testFetchOffsets(): Unit = {
    val tp = new TopicPartition("topic", 0)
    val offset = 97L
    val metadata = "some metadata"
    val leaderEpoch = Optional.of[Integer](15)
    val offsetAndMetadata = OffsetAndMetadata(offset, leaderEpoch, metadata, timer.time.milliseconds())

    val commitOffsetResult = commitOffsets(groupId, OffsetCommitRequest.DEFAULT_MEMBER_ID,
      OffsetCommitRequest.DEFAULT_GENERATION_ID, Map(tp -> offsetAndMetadata))
    assertEquals(Errors.NONE, commitOffsetResult(tp))

    val (error, partitionData) = groupCoordinator.handleFetchOffsets(groupId, Some(Seq(tp)))
    assertEquals(Errors.NONE, error)

    val maybePartitionData = partitionData.get(tp)
    assertTrue(maybePartitionData.isDefined)
    assertEquals(offset, maybePartitionData.get.offset)
    assertEquals(metadata, maybePartitionData.get.metadata)
    assertEquals(leaderEpoch, maybePartitionData.get.leaderEpoch)
  }

  @Test
  def testCommitAndFetchOffsetsWithEmptyGroup() {
    // For backwards compatibility, the coordinator supports committing/fetching offsets with an empty groupId.
    // To allow inspection and removal of the empty group, we must also support DescribeGroups and DeleteGroups

    val tp = new TopicPartition("topic", 0)
    val offset = offsetAndMetadata(0)
    val groupId = ""

    val commitOffsetResult = commitOffsets(groupId, OffsetCommitRequest.DEFAULT_MEMBER_ID,
      OffsetCommitRequest.DEFAULT_GENERATION_ID, Map(tp -> offset))
    assertEquals(Errors.NONE, commitOffsetResult(tp))

    val (fetchError, partitionData) = groupCoordinator.handleFetchOffsets(groupId, Some(Seq(tp)))
    assertEquals(Errors.NONE, fetchError)
    assertEquals(Some(0), partitionData.get(tp).map(_.offset))

    val (describeError, summary) = groupCoordinator.handleDescribeGroup(groupId)
    assertEquals(Errors.NONE, describeError)
    assertEquals(Empty.toString, summary.state)

    val groupTopicPartition = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId)
    val partition: Partition = EasyMock.niceMock(classOf[Partition])

    EasyMock.reset(replicaManager)
    EasyMock.expect(replicaManager.getMagic(EasyMock.anyObject())).andStubReturn(Some(RecordBatch.CURRENT_MAGIC_VALUE))
    EasyMock.expect(replicaManager.getPartition(groupTopicPartition)).andStubReturn(Some(partition))
    EasyMock.expect(replicaManager.nonOfflinePartition(groupTopicPartition)).andStubReturn(Some(partition))
    EasyMock.replay(replicaManager, partition)

    val deleteErrors = groupCoordinator.handleDeleteGroups(Set(groupId))
    assertEquals(Errors.NONE, deleteErrors(groupId))

    val (err, data) = groupCoordinator.handleFetchOffsets(groupId, Some(Seq(tp)))
    assertEquals(Errors.NONE, err)
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), data.get(tp).map(_.offset))
  }

  @Test
  def testBasicFetchTxnOffsets() {
    val tp = new TopicPartition("topic", 0)
    val offset = offsetAndMetadata(0)
    val producerId = 1000L
    val producerEpoch : Short = 2

    val commitOffsetResult = commitTransactionalOffsets(groupId, producerId, producerEpoch, Map(tp -> offset))
    assertEquals(Errors.NONE, commitOffsetResult(tp))

    val (error, partitionData) = groupCoordinator.handleFetchOffsets(groupId, Some(Seq(tp)))

    // Validate that the offset isn't materialjzed yet.
    assertEquals(Errors.NONE, error)
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), partitionData.get(tp).map(_.offset))

    val offsetsTopic = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId)

    // Send commit marker.
    handleTxnCompletion(producerId, List(offsetsTopic), TransactionResult.COMMIT)

    // Validate that committed offset is materialized.
    val (secondReqError, secondReqPartitionData) = groupCoordinator.handleFetchOffsets(groupId, Some(Seq(tp)))
    assertEquals(Errors.NONE, secondReqError)
    assertEquals(Some(0), secondReqPartitionData.get(tp).map(_.offset))
  }

  @Test
  def testFetchTxnOffsetsWithAbort() {
    val tp = new TopicPartition("topic", 0)
    val offset = offsetAndMetadata(0)
    val producerId = 1000L
    val producerEpoch : Short = 2

    val commitOffsetResult = commitTransactionalOffsets(groupId, producerId, producerEpoch, Map(tp -> offset))
    assertEquals(Errors.NONE, commitOffsetResult(tp))

    val (error, partitionData) = groupCoordinator.handleFetchOffsets(groupId, Some(Seq(tp)))
    assertEquals(Errors.NONE, error)
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), partitionData.get(tp).map(_.offset))

    val offsetsTopic = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId)

    // Validate that the pending commit is discarded.
    handleTxnCompletion(producerId, List(offsetsTopic), TransactionResult.ABORT)

    val (secondReqError, secondReqPartitionData) = groupCoordinator.handleFetchOffsets(groupId, Some(Seq(tp)))
    assertEquals(Errors.NONE, secondReqError)
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), secondReqPartitionData.get(tp).map(_.offset))
  }

  @Test
  def testFetchTxnOffsetsIgnoreSpuriousCommit() {
    val tp = new TopicPartition("topic", 0)
    val offset = offsetAndMetadata(0)
    val producerId = 1000L
    val producerEpoch : Short = 2

    val commitOffsetResult = commitTransactionalOffsets(groupId, producerId, producerEpoch, Map(tp -> offset))
    assertEquals(Errors.NONE, commitOffsetResult(tp))

    val (error, partitionData) = groupCoordinator.handleFetchOffsets(groupId, Some(Seq(tp)))
    assertEquals(Errors.NONE, error)
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), partitionData.get(tp).map(_.offset))

    val offsetsTopic = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId)
    handleTxnCompletion(producerId, List(offsetsTopic), TransactionResult.ABORT)

    val (secondReqError, secondReqPartitionData) = groupCoordinator.handleFetchOffsets(groupId, Some(Seq(tp)))
    assertEquals(Errors.NONE, secondReqError)
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), secondReqPartitionData.get(tp).map(_.offset))

    // Ignore spurious commit.
    handleTxnCompletion(producerId, List(offsetsTopic), TransactionResult.COMMIT)

    val (thirdReqError, thirdReqPartitionData) = groupCoordinator.handleFetchOffsets(groupId, Some(Seq(tp)))
    assertEquals(Errors.NONE, thirdReqError)
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), thirdReqPartitionData.get(tp).map(_.offset))
  }

  @Test
  def testFetchTxnOffsetsOneProducerMultipleGroups() {
    // One producer, two groups located on separate offsets topic partitions.
    // Both group have pending offset commits.
    // Marker for only one partition is received. That commit should be materialized while the other should not.

    val partitions = List(new TopicPartition("topic1", 0), new TopicPartition("topic2", 0))
    val offsets = List(offsetAndMetadata(10), offsetAndMetadata(15))
    val producerId = 1000L
    val producerEpoch: Short = 3

    val groupIds = List(groupId, otherGroupId)
    val offsetTopicPartitions = List(new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupCoordinator.partitionFor(groupId)),
      new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupCoordinator.partitionFor(otherGroupId)))

    groupCoordinator.groupManager.addPartitionOwnership(offsetTopicPartitions(1).partition)
    val errors = mutable.ArrayBuffer[Errors]()
    val partitionData = mutable.ArrayBuffer[scala.collection.Map[TopicPartition, OffsetFetchResponse.PartitionData]]()

    val commitOffsetResults = mutable.ArrayBuffer[CommitOffsetCallbackParams]()

    // Ensure that the two groups map to different partitions.
    assertNotEquals(offsetTopicPartitions(0), offsetTopicPartitions(1))

    commitOffsetResults.append(commitTransactionalOffsets(groupId, producerId, producerEpoch, Map(partitions(0) -> offsets(0))))
    assertEquals(Errors.NONE, commitOffsetResults(0)(partitions(0)))
    commitOffsetResults.append(commitTransactionalOffsets(otherGroupId, producerId, producerEpoch, Map(partitions(1) -> offsets(1))))
    assertEquals(Errors.NONE, commitOffsetResults(1)(partitions(1)))

    // We got a commit for only one __consumer_offsets partition. We should only materialize it's group offsets.
    handleTxnCompletion(producerId, List(offsetTopicPartitions(0)), TransactionResult.COMMIT)
    groupCoordinator.handleFetchOffsets(groupIds(0), Some(partitions)) match {
      case (error, partData) =>
        errors.append(error)
        partitionData.append(partData)
      case _ =>
    }

     groupCoordinator.handleFetchOffsets(groupIds(1), Some(partitions)) match {
      case (error, partData) =>
        errors.append(error)
        partitionData.append(partData)
      case _ =>
    }

    assertEquals(2, errors.size)
    assertEquals(Errors.NONE, errors(0))
    assertEquals(Errors.NONE, errors(1))

    // Exactly one offset commit should have been materialized.
    assertEquals(Some(offsets(0).offset), partitionData(0).get(partitions(0)).map(_.offset))
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), partitionData(0).get(partitions(1)).map(_.offset))
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), partitionData(1).get(partitions(0)).map(_.offset))
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), partitionData(1).get(partitions(1)).map(_.offset))

    // Now we receive the other marker.
    handleTxnCompletion(producerId, List(offsetTopicPartitions(1)), TransactionResult.COMMIT)
    errors.clear()
    partitionData.clear()
    groupCoordinator.handleFetchOffsets(groupIds(0), Some(partitions)) match {
      case (error, partData) =>
        errors.append(error)
        partitionData.append(partData)
      case _ =>
    }

     groupCoordinator.handleFetchOffsets(groupIds(1), Some(partitions)) match {
      case (error, partData) =>
        errors.append(error)
        partitionData.append(partData)
      case _ =>
    }
    // Two offsets should have been materialized
    assertEquals(Some(offsets(0).offset), partitionData(0).get(partitions(0)).map(_.offset))
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), partitionData(0).get(partitions(1)).map(_.offset))
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), partitionData(1).get(partitions(0)).map(_.offset))
    assertEquals(Some(offsets(1).offset), partitionData(1).get(partitions(1)).map(_.offset))
  }

  @Test
  def testFetchTxnOffsetsMultipleProducersOneGroup() {
    // One group, two producers
    // Different producers will commit offsets for different partitions.
    // Each partition's offsets should be materialized when the corresponding producer's marker is received.

    val partitions = List(new TopicPartition("topic1", 0), new TopicPartition("topic2", 0))
    val offsets = List(offsetAndMetadata(10), offsetAndMetadata(15))
    val producerIds = List(1000L, 1005L)
    val producerEpochs: Seq[Short] = List(3, 4)

    val offsetTopicPartition = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupCoordinator.partitionFor(groupId))

    val errors = mutable.ArrayBuffer[Errors]()
    val partitionData = mutable.ArrayBuffer[scala.collection.Map[TopicPartition, OffsetFetchResponse.PartitionData]]()

    val commitOffsetResults = mutable.ArrayBuffer[CommitOffsetCallbackParams]()

    // producer0 commits the offsets for partition0
    commitOffsetResults.append(commitTransactionalOffsets(groupId, producerIds(0), producerEpochs(0), Map(partitions(0) -> offsets(0))))
    assertEquals(Errors.NONE, commitOffsetResults(0)(partitions(0)))

    // producer1 commits the offsets for partition1
    commitOffsetResults.append(commitTransactionalOffsets(groupId, producerIds(1), producerEpochs(1), Map(partitions(1) -> offsets(1))))
    assertEquals(Errors.NONE, commitOffsetResults(1)(partitions(1)))

    // producer0 commits its transaction.
    handleTxnCompletion(producerIds(0), List(offsetTopicPartition), TransactionResult.COMMIT)
    groupCoordinator.handleFetchOffsets(groupId, Some(partitions)) match {
      case (error, partData) =>
        errors.append(error)
        partitionData.append(partData)
      case _ =>
    }

    assertEquals(Errors.NONE, errors(0))

    // We should only see the offset commit for producer0
    assertEquals(Some(offsets(0).offset), partitionData(0).get(partitions(0)).map(_.offset))
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), partitionData(0).get(partitions(1)).map(_.offset))

    // producer1 now commits its transaction.
    handleTxnCompletion(producerIds(1), List(offsetTopicPartition), TransactionResult.COMMIT)

    groupCoordinator.handleFetchOffsets(groupId, Some(partitions)) match {
      case (error, partData) =>
        errors.append(error)
        partitionData.append(partData)
      case _ =>
    }

    assertEquals(Errors.NONE, errors(1))

    // We should now see the offset commits for both producers.
    assertEquals(Some(offsets(0).offset), partitionData(1).get(partitions(0)).map(_.offset))
    assertEquals(Some(offsets(1).offset), partitionData(1).get(partitions(1)).map(_.offset))
  }

  @Test
  def testFetchOffsetForUnknownPartition(): Unit = {
    val tp = new TopicPartition("topic", 0)
    val (error, partitionData) = groupCoordinator.handleFetchOffsets(groupId, Some(Seq(tp)))
    assertEquals(Errors.NONE, error)
    assertEquals(Some(OffsetFetchResponse.INVALID_OFFSET), partitionData.get(tp).map(_.offset))
  }

  @Test
  def testFetchOffsetNotCoordinatorForGroup(): Unit = {
    val tp = new TopicPartition("topic", 0)
    val (error, partitionData) = groupCoordinator.handleFetchOffsets(otherGroupId, Some(Seq(tp)))
    assertEquals(Errors.NOT_COORDINATOR, error)
    assertTrue(partitionData.isEmpty)
  }

  @Test
  def testFetchAllOffsets() {
    val tp1 = new TopicPartition("topic", 0)
    val tp2 = new TopicPartition("topic", 1)
    val tp3 = new TopicPartition("other-topic", 0)
    val offset1 = offsetAndMetadata(15)
    val offset2 = offsetAndMetadata(16)
    val offset3 = offsetAndMetadata(17)

    assertEquals((Errors.NONE, Map.empty), groupCoordinator.handleFetchOffsets(groupId))

    val commitOffsetResult = commitOffsets(groupId, OffsetCommitRequest.DEFAULT_MEMBER_ID,
      OffsetCommitRequest.DEFAULT_GENERATION_ID, Map(tp1 -> offset1, tp2 -> offset2, tp3 -> offset3))
    assertEquals(Errors.NONE, commitOffsetResult(tp1))
    assertEquals(Errors.NONE, commitOffsetResult(tp2))
    assertEquals(Errors.NONE, commitOffsetResult(tp3))

    val (error, partitionData) = groupCoordinator.handleFetchOffsets(groupId)
    assertEquals(Errors.NONE, error)
    assertEquals(3, partitionData.size)
    assertTrue(partitionData.forall(_._2.error == Errors.NONE))
    assertEquals(Some(offset1.offset), partitionData.get(tp1).map(_.offset))
    assertEquals(Some(offset2.offset), partitionData.get(tp2).map(_.offset))
    assertEquals(Some(offset3.offset), partitionData.get(tp3).map(_.offset))
  }

  @Test
  def testCommitOffsetInCompletingRebalance() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID
    val tp = new TopicPartition("topic", 0)
    val offset = offsetAndMetadata(0)

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    val assignedMemberId = joinGroupResult.memberId
    val generationId = joinGroupResult.generationId
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.NONE, joinGroupError)

    EasyMock.reset(replicaManager)
    val commitOffsetResult = commitOffsets(groupId, assignedMemberId, generationId, Map(tp -> offset))
    assertEquals(Errors.REBALANCE_IN_PROGRESS, commitOffsetResult(tp))
  }

  @Test
  def testHeartbeatDuringRebalanceCausesRebalanceInProgress() {
    // First start up a group (with a slightly larger timeout to give us time to heartbeat when the rebalance starts)
    val joinGroupResult = joinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)
    val assignedConsumerId = joinGroupResult.memberId
    val initialGenerationId = joinGroupResult.generationId
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.NONE, joinGroupError)

    // Then join with a new consumer to trigger a rebalance
    EasyMock.reset(replicaManager)
    sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)

    // We should be in the middle of a rebalance, so the heartbeat should return rebalance in progress
    EasyMock.reset(replicaManager)
    val heartbeatResult = heartbeat(groupId, assignedConsumerId, initialGenerationId)
    assertEquals(Errors.REBALANCE_IN_PROGRESS, heartbeatResult)
  }

  @Test
  def testGenerationIdIncrementsOnRebalance() {
    val joinGroupResult = joinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)
    val initialGenerationId = joinGroupResult.generationId
    val joinGroupError = joinGroupResult.error
    val memberId = joinGroupResult.memberId
    assertEquals(1, initialGenerationId)
    assertEquals(Errors.NONE, joinGroupError)

    EasyMock.reset(replicaManager)
    val syncGroupResult = syncGroupLeader(groupId, initialGenerationId, memberId, Map(memberId -> Array[Byte]()))
    val syncGroupError = syncGroupResult._2
    assertEquals(Errors.NONE, syncGroupError)

    EasyMock.reset(replicaManager)
    val joinGroupFuture = sendJoinGroup(groupId, memberId, protocolType, protocols)
    val otherJoinGroupResult = await(joinGroupFuture, 1)

    val nextGenerationId = otherJoinGroupResult.generationId
    val otherJoinGroupError = otherJoinGroupResult.error
    assertEquals(2, nextGenerationId)
    assertEquals(Errors.NONE, otherJoinGroupError)
  }

  @Test
  def testLeaveGroupWrongCoordinator() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val leaveGroupResult = leaveGroup(otherGroupId, memberId)
    assertEquals(Errors.NOT_COORDINATOR, leaveGroupResult)
  }

  @Test
  def testLeaveGroupUnknownGroup() {

    val leaveGroupResult = leaveGroup(groupId, memberId)
    assertEquals(Errors.UNKNOWN_MEMBER_ID, leaveGroupResult)
  }

  @Test
  def testLeaveGroupUnknownConsumerExistingGroup() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID
    val otherMemberId = "consumerId"

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.NONE, joinGroupError)

    EasyMock.reset(replicaManager)
    val leaveGroupResult = leaveGroup(groupId, otherMemberId)
    assertEquals(Errors.UNKNOWN_MEMBER_ID, leaveGroupResult)
  }

  @Test
  def testValidLeaveGroup() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    val assignedMemberId = joinGroupResult.memberId
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.NONE, joinGroupError)

    EasyMock.reset(replicaManager)
    val leaveGroupResult = leaveGroup(groupId, assignedMemberId)
    assertEquals(Errors.NONE, leaveGroupResult)
  }

  @Test
  def testListGroupsIncludesStableGroups() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID
    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    val assignedMemberId = joinGroupResult.memberId
    val generationId = joinGroupResult.generationId
    assertEquals(Errors.NONE, joinGroupResult.error)

    EasyMock.reset(replicaManager)
    val syncGroupResult = syncGroupLeader(groupId, generationId, assignedMemberId, Map(assignedMemberId -> Array[Byte]()))
    val syncGroupError = syncGroupResult._2
    assertEquals(Errors.NONE, syncGroupError)

    val (error, groups) = groupCoordinator.handleListGroups()
    assertEquals(Errors.NONE, error)
    assertEquals(1, groups.size)
    assertEquals(GroupOverview("groupId", "consumer"), groups.head)
  }

  @Test
  def testListGroupsIncludesRebalancingGroups() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID
    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    assertEquals(Errors.NONE, joinGroupResult.error)

    val (error, groups) = groupCoordinator.handleListGroups()
    assertEquals(Errors.NONE, error)
    assertEquals(1, groups.size)
    assertEquals(GroupOverview("groupId", "consumer"), groups.head)
  }

  @Test
  def testDescribeGroupWrongCoordinator() {
    EasyMock.reset(replicaManager)
    val (error, _) = groupCoordinator.handleDescribeGroup(otherGroupId)
    assertEquals(Errors.NOT_COORDINATOR, error)
  }

  @Test
  def testDescribeGroupInactiveGroup() {
    EasyMock.reset(replicaManager)
    val (error, summary) = groupCoordinator.handleDescribeGroup(groupId)
    assertEquals(Errors.NONE, error)
    assertEquals(GroupCoordinator.DeadGroup, summary)
  }

  @Test
  def testDescribeGroupStable() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID
    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    val assignedMemberId = joinGroupResult.memberId
    val generationId = joinGroupResult.generationId
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.NONE, joinGroupError)

    EasyMock.reset(replicaManager)
    val syncGroupResult = syncGroupLeader(groupId, generationId, assignedMemberId, Map(assignedMemberId -> Array[Byte]()))

    val syncGroupError = syncGroupResult._2
    assertEquals(Errors.NONE, syncGroupError)

    EasyMock.reset(replicaManager)
    val (error, summary) = groupCoordinator.handleDescribeGroup(groupId)
    assertEquals(Errors.NONE, error)
    assertEquals(protocolType, summary.protocolType)
    assertEquals("range", summary.protocol)
    assertEquals(List(assignedMemberId), summary.members.map(_.memberId))
  }

  @Test
  def testDescribeGroupRebalancing() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID
    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.NONE, joinGroupError)

    EasyMock.reset(replicaManager)
    val (error, summary) = groupCoordinator.handleDescribeGroup(groupId)
    assertEquals(Errors.NONE, error)
    assertEquals(protocolType, summary.protocolType)
    assertEquals(GroupCoordinator.NoProtocol, summary.protocol)
    assertEquals(CompletingRebalance.toString, summary.state)
    assertTrue(summary.members.map(_.memberId).contains(joinGroupResult.memberId))
    assertTrue(summary.members.forall(_.metadata.isEmpty))
    assertTrue(summary.members.forall(_.assignment.isEmpty))
  }

  @Test
  def testDeleteNonEmptyGroup() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID
    joinGroup(groupId, memberId, protocolType, protocols)

    val result = groupCoordinator.handleDeleteGroups(Set(groupId))
    assert(result.size == 1 && result.contains(groupId) && result.get(groupId).contains(Errors.NON_EMPTY_GROUP))
  }

  @Test
  def testDeleteGroupWithInvalidGroupId() {
    val invalidGroupId = null
    val result = groupCoordinator.handleDeleteGroups(Set(invalidGroupId))
    assert(result.size == 1 && result.contains(invalidGroupId) && result.get(invalidGroupId).contains(Errors.INVALID_GROUP_ID))
  }

  @Test
  def testDeleteGroupWithWrongCoordinator() {
    val result = groupCoordinator.handleDeleteGroups(Set(otherGroupId))
    assert(result.size == 1 && result.contains(otherGroupId) && result.get(otherGroupId).contains(Errors.NOT_COORDINATOR))
  }

  @Test
  def testDeleteEmptyGroup() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID
    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)

    EasyMock.reset(replicaManager)
    val leaveGroupResult = leaveGroup(groupId, joinGroupResult.memberId)
    assertEquals(Errors.NONE, leaveGroupResult)

    val groupTopicPartition = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId)
    val partition: Partition = EasyMock.niceMock(classOf[Partition])

    EasyMock.reset(replicaManager)
    EasyMock.expect(replicaManager.getMagic(EasyMock.anyObject())).andStubReturn(Some(RecordBatch.CURRENT_MAGIC_VALUE))
    EasyMock.expect(replicaManager.getPartition(groupTopicPartition)).andStubReturn(Some(partition))
    EasyMock.expect(replicaManager.nonOfflinePartition(groupTopicPartition)).andStubReturn(Some(partition))
    EasyMock.replay(replicaManager, partition)

    val result = groupCoordinator.handleDeleteGroups(Set(groupId))
    assert(result.size == 1 && result.contains(groupId) && result.get(groupId).contains(Errors.NONE))
  }

  @Test
  def testDeleteEmptyGroupWithStoredOffsets() {
    val memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID

    val joinGroupResult = joinGroup(groupId, memberId, protocolType, protocols)
    val assignedMemberId = joinGroupResult.memberId
    val joinGroupError = joinGroupResult.error
    assertEquals(Errors.NONE, joinGroupError)

    EasyMock.reset(replicaManager)
    val syncGroupResult = syncGroupLeader(groupId, joinGroupResult.generationId, assignedMemberId, Map(assignedMemberId -> Array[Byte]()))
    val syncGroupError = syncGroupResult._2
    assertEquals(Errors.NONE, syncGroupError)

    EasyMock.reset(replicaManager)
    val tp = new TopicPartition("topic", 0)
    val offset = offsetAndMetadata(0)
    val commitOffsetResult = commitOffsets(groupId, assignedMemberId, joinGroupResult.generationId, Map(tp -> offset))
    assertEquals(Errors.NONE, commitOffsetResult(tp))

    val describeGroupResult = groupCoordinator.handleDescribeGroup(groupId)
    assertEquals(Stable.toString, describeGroupResult._2.state)
    assertEquals(assignedMemberId, describeGroupResult._2.members.head.memberId)

    EasyMock.reset(replicaManager)
    val leaveGroupResult = leaveGroup(groupId, assignedMemberId)
    assertEquals(Errors.NONE, leaveGroupResult)

    val groupTopicPartition = new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId)
    val partition: Partition = EasyMock.niceMock(classOf[Partition])

    EasyMock.reset(replicaManager)
    EasyMock.expect(replicaManager.getMagic(EasyMock.anyObject())).andStubReturn(Some(RecordBatch.CURRENT_MAGIC_VALUE))
    EasyMock.expect(replicaManager.getPartition(groupTopicPartition)).andStubReturn(Some(partition))
    EasyMock.expect(replicaManager.nonOfflinePartition(groupTopicPartition)).andStubReturn(Some(partition))
    EasyMock.replay(replicaManager, partition)

    val result = groupCoordinator.handleDeleteGroups(Set(groupId))
    assert(result.size == 1 && result.contains(groupId) && result.get(groupId).contains(Errors.NONE))

    assertEquals(Dead.toString, groupCoordinator.handleDescribeGroup(groupId)._2.state)
  }

  @Test
  def shouldDelayInitialRebalanceByGroupInitialRebalanceDelayOnEmptyGroup() {
    val firstJoinFuture = sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols)
    timer.advanceClock(GroupInitialRebalanceDelay / 2)
    verifyDelayedTaskNotCompleted(firstJoinFuture)
    timer.advanceClock((GroupInitialRebalanceDelay / 2) + 1)
    val joinGroupResult = await(firstJoinFuture, 1)
    assertEquals(Errors.NONE, joinGroupResult.error)
  }

  private def verifyDelayedTaskNotCompleted(firstJoinFuture: Future[JoinGroupResult]) = {
    try {
      await(firstJoinFuture, 1)
      Assert.fail("should have timed out as rebalance delay not expired")
    } catch {
      case _: TimeoutException => // ok
    }
  }

  @Test
  def shouldResetRebalanceDelayWhenNewMemberJoinsGroupInInitialRebalance() {
    val rebalanceTimeout = GroupInitialRebalanceDelay * 3
    val firstMemberJoinFuture = sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols, rebalanceTimeout = rebalanceTimeout)
    EasyMock.reset(replicaManager)
    timer.advanceClock(GroupInitialRebalanceDelay - 1)
    val secondMemberJoinFuture = sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols, rebalanceTimeout = rebalanceTimeout)
    EasyMock.reset(replicaManager)
    timer.advanceClock(2)

    // advance past initial rebalance delay and make sure that tasks
    // haven't been completed
    timer.advanceClock(GroupInitialRebalanceDelay / 2 + 1)
    verifyDelayedTaskNotCompleted(firstMemberJoinFuture)
    verifyDelayedTaskNotCompleted(secondMemberJoinFuture)
    // advance clock beyond updated delay and make sure the
    // tasks have completed
    timer.advanceClock(GroupInitialRebalanceDelay / 2)
    val firstResult = await(firstMemberJoinFuture, 1)
    val secondResult = await(secondMemberJoinFuture, 1)
    assertEquals(Errors.NONE, firstResult.error)
    assertEquals(Errors.NONE, secondResult.error)
  }

  @Test
  def shouldDelayRebalanceUptoRebalanceTimeout() {
    val rebalanceTimeout = GroupInitialRebalanceDelay * 2
    val firstMemberJoinFuture = sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols, rebalanceTimeout = rebalanceTimeout)
    EasyMock.reset(replicaManager)
    val secondMemberJoinFuture = sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols, rebalanceTimeout = rebalanceTimeout)
    timer.advanceClock(GroupInitialRebalanceDelay + 1)
    EasyMock.reset(replicaManager)
    val thirdMemberJoinFuture = sendJoinGroup(groupId, JoinGroupRequest.UNKNOWN_MEMBER_ID, protocolType, protocols, rebalanceTimeout = rebalanceTimeout)
    timer.advanceClock(GroupInitialRebalanceDelay)
    EasyMock.reset(replicaManager)

    verifyDelayedTaskNotCompleted(firstMemberJoinFuture)
    verifyDelayedTaskNotCompleted(secondMemberJoinFuture)
    verifyDelayedTaskNotCompleted(thirdMemberJoinFuture)

    // advance clock beyond rebalanceTimeout
    timer.advanceClock(1)

    val firstResult = await(firstMemberJoinFuture, 1)
    val secondResult = await(secondMemberJoinFuture, 1)
    val thirdResult = await(thirdMemberJoinFuture, 1)
    assertEquals(Errors.NONE, firstResult.error)
    assertEquals(Errors.NONE, secondResult.error)
    assertEquals(Errors.NONE, thirdResult.error)
  }

  private def setupJoinGroupCallback: (Future[JoinGroupResult], JoinGroupCallback) = {
    val responsePromise = Promise[JoinGroupResult]
    val responseFuture = responsePromise.future
    val responseCallback: JoinGroupCallback = responsePromise.success(_)
    (responseFuture, responseCallback)
  }

  private def setupSyncGroupCallback: (Future[SyncGroupCallbackParams], SyncGroupCallback) = {
    val responsePromise = Promise[SyncGroupCallbackParams]
    val responseFuture = responsePromise.future
    val responseCallback: SyncGroupCallback = (assignment, error) =>
      responsePromise.success((assignment, error))
    (responseFuture, responseCallback)
  }

  private def setupHeartbeatCallback: (Future[HeartbeatCallbackParams], HeartbeatCallback) = {
    val responsePromise = Promise[HeartbeatCallbackParams]
    val responseFuture = responsePromise.future
    val responseCallback: HeartbeatCallback = error => responsePromise.success(error)
    (responseFuture, responseCallback)
  }

  private def setupCommitOffsetsCallback: (Future[CommitOffsetCallbackParams], CommitOffsetCallback) = {
    val responsePromise = Promise[CommitOffsetCallbackParams]
    val responseFuture = responsePromise.future
    val responseCallback: CommitOffsetCallback = offsets => responsePromise.success(offsets)
    (responseFuture, responseCallback)
  }

  private def sendJoinGroup(groupId: String,
                            memberId: String,
                            protocolType: String,
                            protocols: List[(String, Array[Byte])],
                            sessionTimeout: Int = DefaultSessionTimeout,
                            rebalanceTimeout: Int = DefaultRebalanceTimeout,
                            requireKnownMemberId: Boolean = false): Future[JoinGroupResult] = {
    val (responseFuture, responseCallback) = setupJoinGroupCallback

    EasyMock.replay(replicaManager)

    groupCoordinator.handleJoinGroup(groupId, memberId, requireKnownMemberId, "clientId", "clientHost", rebalanceTimeout, sessionTimeout,
      protocolType, protocols, responseCallback)
    responseFuture
  }


  private def sendSyncGroupLeader(groupId: String,
                                  generation: Int,
                                  leaderId: String,
                                  assignment: Map[String, Array[Byte]]): Future[SyncGroupCallbackParams] = {
    val (responseFuture, responseCallback) = setupSyncGroupCallback

    val capturedArgument: Capture[scala.collection.Map[TopicPartition, PartitionResponse] => Unit] = EasyMock.newCapture()

    EasyMock.expect(replicaManager.appendRecords(EasyMock.anyLong(),
      EasyMock.anyShort(),
      internalTopicsAllowed = EasyMock.eq(true),
      origin = EasyMock.eq(AppendOrigin.Coordinator),
      EasyMock.anyObject().asInstanceOf[Map[TopicPartition, MemoryRecords]],
      EasyMock.capture(capturedArgument),
      EasyMock.anyObject().asInstanceOf[Option[ReentrantLock]],
      EasyMock.anyObject())).andAnswer(new IAnswer[Unit] {
      override def answer = capturedArgument.getValue.apply(
        Map(new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId) ->
          new PartitionResponse(Errors.NONE, 0L, RecordBatch.NO_TIMESTAMP, 0L)
        )
      )})
    EasyMock.expect(replicaManager.getMagic(EasyMock.anyObject())).andReturn(Some(RecordBatch.MAGIC_VALUE_V1)).anyTimes()
    EasyMock.replay(replicaManager)

    groupCoordinator.handleSyncGroup(groupId, generation, leaderId, assignment, responseCallback)
    responseFuture
  }

  private def sendSyncGroupFollower(groupId: String,
                                    generation: Int,
                                    memberId: String): Future[SyncGroupCallbackParams] = {
    val (responseFuture, responseCallback) = setupSyncGroupCallback

    EasyMock.replay(replicaManager)

    groupCoordinator.handleSyncGroup(groupId, generation, memberId, Map.empty[String, Array[Byte]], responseCallback)
    responseFuture
  }

  private def joinGroup(groupId: String,
                        memberId: String,
                        protocolType: String,
                        protocols: List[(String, Array[Byte])],
                        sessionTimeout: Int = DefaultSessionTimeout,
                        rebalanceTimeout: Int = DefaultRebalanceTimeout): JoinGroupResult = {
    val requireKnownMemberId = true
    var responseFuture = sendJoinGroup(groupId, memberId, protocolType, protocols, sessionTimeout, rebalanceTimeout, requireKnownMemberId)

    // Since member id is required, we need another bounce to get the successful join group result.
    if (memberId == JoinGroupRequest.UNKNOWN_MEMBER_ID && requireKnownMemberId) {
      val joinGroupResult = Await.result(responseFuture, Duration(rebalanceTimeout + 100, TimeUnit.MILLISECONDS))
      // If some other error is triggered, return the error immediately for caller to handle.
      if (joinGroupResult.error != Errors.MEMBER_ID_REQUIRED) {
        return joinGroupResult
      }
      EasyMock.reset(replicaManager)
      responseFuture = sendJoinGroup(groupId, joinGroupResult.memberId, protocolType, protocols, sessionTimeout, rebalanceTimeout, requireKnownMemberId)
    }
    timer.advanceClock(GroupInitialRebalanceDelay + 1)
    // should only have to wait as long as session timeout, but allow some extra time in case of an unexpected delay
    Await.result(responseFuture, Duration(rebalanceTimeout + 100, TimeUnit.MILLISECONDS))
  }


  private def syncGroupFollower(groupId: String,
                                generationId: Int,
                                memberId: String,
                                sessionTimeout: Int = DefaultSessionTimeout): SyncGroupCallbackParams = {
    val responseFuture = sendSyncGroupFollower(groupId, generationId, memberId)
    Await.result(responseFuture, Duration(sessionTimeout + 100, TimeUnit.MILLISECONDS))
  }

  private def syncGroupLeader(groupId: String,
                              generationId: Int,
                              memberId: String,
                              assignment: Map[String, Array[Byte]],
                              sessionTimeout: Int = DefaultSessionTimeout): SyncGroupCallbackParams = {
    val responseFuture = sendSyncGroupLeader(groupId, generationId, memberId, assignment)
    Await.result(responseFuture, Duration(sessionTimeout + 100, TimeUnit.MILLISECONDS))
  }

  private def heartbeat(groupId: String,
                        consumerId: String,
                        generationId: Int): HeartbeatCallbackParams = {
    val (responseFuture, responseCallback) = setupHeartbeatCallback

    EasyMock.replay(replicaManager)

    groupCoordinator.handleHeartbeat(groupId, consumerId, generationId, responseCallback)
    Await.result(responseFuture, Duration(40, TimeUnit.MILLISECONDS))
  }

  private def await[T](future: Future[T], millis: Long): T = {
    Await.result(future, Duration(millis, TimeUnit.MILLISECONDS))
  }

  private def commitOffsets(groupId: String,
                            consumerId: String,
                            generationId: Int,
                            offsets: Map[TopicPartition, OffsetAndMetadata]): CommitOffsetCallbackParams = {
    val (responseFuture, responseCallback) = setupCommitOffsetsCallback

    val capturedArgument: Capture[scala.collection.Map[TopicPartition, PartitionResponse] => Unit] = EasyMock.newCapture()

    EasyMock.expect(replicaManager.appendRecords(EasyMock.anyLong(),
      EasyMock.anyShort(),
      internalTopicsAllowed = EasyMock.eq(true),
      origin = EasyMock.eq(AppendOrigin.Coordinator),
      EasyMock.anyObject().asInstanceOf[Map[TopicPartition, MemoryRecords]],
      EasyMock.capture(capturedArgument),
      EasyMock.anyObject().asInstanceOf[Option[ReentrantLock]],
      EasyMock.anyObject())
    ).andAnswer(new IAnswer[Unit] {
      override def answer = capturedArgument.getValue.apply(
          Map(new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId) ->
            new PartitionResponse(Errors.NONE, 0L, RecordBatch.NO_TIMESTAMP, 0L)
          )
        )
      })
    EasyMock.expect(replicaManager.getMagic(EasyMock.anyObject())).andReturn(Some(RecordBatch.MAGIC_VALUE_V1)).anyTimes()
    EasyMock.replay(replicaManager)

    groupCoordinator.handleCommitOffsets(groupId, consumerId, generationId, offsets, responseCallback)
    Await.result(responseFuture, Duration(40, TimeUnit.MILLISECONDS))
  }

  private def commitTransactionalOffsets(groupId: String,
                                         producerId: Long,
                                         producerEpoch: Short,
                                         offsets: Map[TopicPartition, OffsetAndMetadata]): CommitOffsetCallbackParams = {
    val (responseFuture, responseCallback) = setupCommitOffsetsCallback

    val capturedArgument: Capture[scala.collection.Map[TopicPartition, PartitionResponse] => Unit] = EasyMock.newCapture()

    EasyMock.expect(replicaManager.appendRecords(EasyMock.anyLong(),
      EasyMock.anyShort(),
      internalTopicsAllowed = EasyMock.eq(true),
      origin = EasyMock.eq(AppendOrigin.Coordinator),
      EasyMock.anyObject().asInstanceOf[Map[TopicPartition, MemoryRecords]],
      EasyMock.capture(capturedArgument),
      EasyMock.anyObject().asInstanceOf[Option[ReentrantLock]],
      EasyMock.anyObject())
    ).andAnswer(new IAnswer[Unit] {
      override def answer = capturedArgument.getValue.apply(
        Map(new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupCoordinator.partitionFor(groupId)) ->
          new PartitionResponse(Errors.NONE, 0L, RecordBatch.NO_TIMESTAMP, 0L)
        )
      )})
    EasyMock.expect(replicaManager.getMagic(EasyMock.anyObject())).andReturn(Some(RecordBatch.MAGIC_VALUE_V2)).anyTimes()
    EasyMock.replay(replicaManager)

    groupCoordinator.handleTxnCommitOffsets(groupId, producerId, producerEpoch, offsets, responseCallback)
    val result = Await.result(responseFuture, Duration(40, TimeUnit.MILLISECONDS))
    EasyMock.reset(replicaManager)
    result
  }

  private def leaveGroup(groupId: String, consumerId: String): LeaveGroupCallbackParams = {
    val (responseFuture, responseCallback) = setupHeartbeatCallback

    EasyMock.expect(replicaManager.getPartition(new TopicPartition(Topic.GROUP_METADATA_TOPIC_NAME, groupPartitionId))).andReturn(None)
    EasyMock.expect(replicaManager.getMagic(EasyMock.anyObject())).andReturn(Some(RecordBatch.MAGIC_VALUE_V1)).anyTimes()
    EasyMock.replay(replicaManager)

    groupCoordinator.handleLeaveGroup(groupId, consumerId, responseCallback)
    Await.result(responseFuture, Duration(40, TimeUnit.MILLISECONDS))
  }

  def handleTxnCompletion(producerId: Long,
                          offsetsPartitions: Iterable[TopicPartition],
                          transactionResult: TransactionResult): Unit = {
    val isCommit = transactionResult == TransactionResult.COMMIT
    groupCoordinator.groupManager.handleTxnCompletion(producerId, offsetsPartitions.map(_.partition).toSet, isCommit)
  }

  private def offsetAndMetadata(offset: Long): OffsetAndMetadata = {
    OffsetAndMetadata(offset, "", timer.time.milliseconds())
  }

}

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
package kafka.admin

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kafka.common.AdminCommandFailedException
import kafka.server.KafkaConfig
import kafka.server.KafkaServer
import kafka.utils.TestUtils
import kafka.zk.ZooKeeperTestHarness
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.{AdminClient => JAdminClient}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException
import org.apache.kafka.common.network.ListenerName
import org.junit.After
import org.junit.Assert._
import org.junit.Before
import org.junit.Test
import scala.collection.JavaConverters._
import scala.collection.Seq
import scala.concurrent.duration._

final class LeaderElectionCommandTest extends ZooKeeperTestHarness {
  import LeaderElectionCommandTest._

  var servers = Seq.empty[KafkaServer]
  val broker1 = 0
  val broker2 = 1
  val broker3 = 2

  @Before
  override def setUp() {
    super.setUp()

    val brokerConfigs = TestUtils.createBrokerConfigs(3, zkConnect, false)
    servers = brokerConfigs.map { config =>
      config.setProperty("auto.leader.rebalance.enable", "false")
      config.setProperty("controlled.shutdown.enable", "true")
      config.setProperty("controlled.shutdown.max.retries", "1")
      config.setProperty("controlled.shutdown.retry.backoff.ms", "1000")
      TestUtils.createServer(KafkaConfig.fromProps(config))
    }
  }

  @After
  override def tearDown() {
    TestUtils.shutdownServers(servers)

    super.tearDown()
  }

  @Test
  def testAllTopicPartition(): Unit = {
    TestUtils.resource(JAdminClient.create(createConfig(servers).asJava)) { client =>
      val topic = "unclean-topic"
      val partition = 0
      val assignment = Seq(broker2, broker3)

      TestUtils.createTopic(zkClient, topic, Map(partition -> assignment), servers)

      val topicPartition = new TopicPartition(topic, partition)

      waitForLeaderToBecome(client, topicPartition, Option(broker2))

      servers(broker3).shutdown()
      waitForBrokerOutOfIsr(client, Set(topicPartition), broker3)
      servers(broker2).shutdown()
      waitForLeaderToBecome(client, topicPartition, None)
      servers(broker3).startup()

      LeaderElectionCommand.main(
        Array(
          "--bootstrap-server", bootstrapServers(servers),
          "--election-type", "unclean",
          "--all-topic-partitions"
        )
      )

      assertEquals(Option(broker3), currentLeader(client, topicPartition))
    }
  }

  @Test
  def testTopicPartition(): Unit = {
    TestUtils.resource(JAdminClient.create(createConfig(servers).asJava)) { client =>
      val topic = "unclean-topic"
      val partition = 0
      val assignment = Seq(broker2, broker3)

      TestUtils.createTopic(zkClient, topic, Map(partition -> assignment), servers)

      val topicPartition = new TopicPartition(topic, partition)

      waitForLeaderToBecome(client, topicPartition, Option(broker2))

      servers(broker3).shutdown()
      waitForBrokerOutOfIsr(client, Set(topicPartition), broker3)
      servers(broker2).shutdown()
      waitForLeaderToBecome(client, topicPartition, None)
      servers(broker3).startup()

      LeaderElectionCommand.main(
        Array(
          "--bootstrap-server", bootstrapServers(servers),
          "--election-type", "unclean",
          "--topic", topic,
          "--partition", partition.toString
        )
      )

      assertEquals(Option(broker3), currentLeader(client, topicPartition))
    }
  }

  @Test
  def testPathToJsonFile(): Unit = {
    TestUtils.resource(JAdminClient.create(createConfig(servers).asJava)) { client =>
      val topic = "unclean-topic"
      val partition = 0
      val assignment = Seq(broker2, broker3)

      TestUtils.createTopic(zkClient, topic, Map(partition -> assignment), servers)

      val topicPartition = new TopicPartition(topic, partition)

      waitForLeaderToBecome(client, topicPartition, Option(broker2))

      servers(broker3).shutdown()
      waitForBrokerOutOfIsr(client, Set(topicPartition), broker3)
      servers(broker2).shutdown()
      waitForLeaderToBecome(client, topicPartition, None)
      servers(broker3).startup()

      val topicPartitionPath = tempTopicPartitionFile(Set(topicPartition))

      LeaderElectionCommand.main(
        Array(
          "--bootstrap-server", bootstrapServers(servers),
          "--election-type", "unclean",
          "--path-to-json-file", topicPartitionPath.toString
        )
      )

      assertEquals(Option(broker3), currentLeader(client, topicPartition))
    }
  }

  @Test
  def testPreferredReplicaElection(): Unit = {
    TestUtils.resource(JAdminClient.create(createConfig(servers).asJava)) { client =>
      val topic = "unclean-topic"
      val partition = 0
      val assignment = Seq(broker2, broker3)

      TestUtils.createTopic(zkClient, topic, Map(partition -> assignment), servers)

      val topicPartition = new TopicPartition(topic, partition)

      waitForLeaderToBecome(client, topicPartition, Option(broker2))

      servers(broker2).shutdown()
      waitForLeaderToBecome(client, topicPartition, Some(broker3))
      servers(broker2).startup()
      waitForBrokerInIsr(client, Set(topicPartition), broker2)

      LeaderElectionCommand.main(
        Array(
          "--bootstrap-server", bootstrapServers(servers),
          "--election-type", "preferred",
          "--all-topic-partitions"
        )
      )

      assertEquals(Option(broker2), currentLeader(client, topicPartition))
    }
  }

  @Test
  def testTopicWithoutPartition(): Unit = {
    try {
      LeaderElectionCommand.main(
        Array(
          "--bootstrap-server", bootstrapServers(servers),
          "--election-type", "unclean",
          "--topic", "some-topic"
        )
      )
      fail()
    } catch {
      case e: Throwable =>
        assertTrue(e.getMessage.startsWith("Missing required option(s)"))
        assertTrue(e.getMessage.contains(" partition"))
    }
  }

  @Test
  def testPartitionWithoutTopic(): Unit = {
    try {
      LeaderElectionCommand.main(
        Array(
          "--bootstrap-server", bootstrapServers(servers),
          "--election-type", "unclean",
          "--all-topic-partitions",
          "--partition", "0"
        )
      )
      fail()
    } catch {
      case e: Throwable =>
        assertEquals("Option partition is only allowed if topic is used", e.getMessage)
    }
  }

  @Test
  def testTopicDoesNotExist(): Unit = {
    try {
      LeaderElectionCommand.main(
        Array(
          "--bootstrap-server", bootstrapServers(servers),
          "--election-type", "preferred",
          "--topic", "unknown-topic-name",
          "--partition", "0"
        )
      )
      fail()
    } catch {
      case e: AdminCommandFailedException =>
        assertTrue(e.getSuppressed()(0).isInstanceOf[UnknownTopicOrPartitionException])
    }
  }

  @Test
  def testMissingElectionType(): Unit = {
    try {
      LeaderElectionCommand.main(
        Array(
          "--bootstrap-server", bootstrapServers(servers),
          "--topic", "some-topic",
          "--partition", "0"
        )
      )
      fail()
    } catch {
      case e: Throwable =>
        assertTrue(e.getMessage.startsWith("Missing required option(s)"))
        assertTrue(e.getMessage.contains(" election-type"))
    }
  }

  @Test
  def testMissingTopicPartitionSelection(): Unit = {
    try {
      LeaderElectionCommand.main(
        Array(
          "--bootstrap-server", bootstrapServers(servers),
          "--election-type", "preferrred"
        )
      )
      fail()
    } catch {
      case e: Throwable =>
        assertTrue(e.getMessage.startsWith("One and only one of the following options is required: "))
        assertTrue(e.getMessage.contains(" all-topic-partitions"))
        assertTrue(e.getMessage.contains(" topic"))
        assertTrue(e.getMessage.contains(" path-to-json-file"))
    }
  }

  @Test
  def testInvalidBroker(): Unit = {
    try {
      LeaderElectionCommand.run(
        Array(
          "--bootstrap-server", "example.com:1234",
          "--election-type", "unclean",
          "--all-topic-partitions"
        ),
        1.seconds
      )
      fail()
    } catch {
      case e: AdminCommandFailedException =>
        assertTrue(e.getCause.isInstanceOf[TimeoutException])
    }
  }
}

object LeaderElectionCommandTest {
  def createConfig(servers: Seq[KafkaServer]): Map[String, Object] = {
    Map(
      AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG -> bootstrapServers(servers),
      AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG -> "20000"
    )
  }

  def bootstrapServers(servers: Seq[KafkaServer]): String = {
    servers.map { server =>
      val port = server.socketServer.boundPort(ListenerName.normalised("PLAINTEXT"))
      s"localhost:$port"
    }.headOption.mkString(",")
  }

  def currentLeader(client: JAdminClient, topicPartition: TopicPartition): Option[Int] = {
    Option(
      client
        .describeTopics(List(topicPartition.topic).asJava)
        .all
        .get
        .get(topicPartition.topic)
        .partitions
        .get(topicPartition.partition)
        .leader
    ).map(_.id)
  }

  def waitForLeaderToBecome(client: JAdminClient, topicPartition: TopicPartition, leader: Option[Int]): Unit = {
    TestUtils.waitUntilTrue(
      () => currentLeader(client, topicPartition) == leader,
      s"Expected leader to become $leader", 10000
    )
  }

  def waitForBrokerOutOfIsr(client: JAdminClient, partitions: Set[TopicPartition], brokerId: Int): Unit = {
    TestUtils.waitUntilTrue(
      () => {
        val description = client.describeTopics(partitions.map(_.topic).asJava).all.get.asScala
        val isr = description.values.flatMap(_.partitions.asScala.flatMap(_.isr.asScala))
        isr.forall(_.id != brokerId)
      },
      s"Expect broker $brokerId to no longer be in any ISR for $partitions"
    )
  }

  def waitForBrokerInIsr(client: JAdminClient, partitions: Set[TopicPartition], brokerId: Int): Unit = {
    TestUtils.waitUntilTrue(
      () => {
        val description = client.describeTopics(partitions.map(_.topic).asJava).all.get.asScala
        val isr = description.values.flatMap(_.partitions.asScala.flatMap(_.isr.asScala))
        isr.exists(_.id == brokerId)
      },
      s"Expect broker $brokerId to no longer be in any ISR for $partitions"
    )
  }

  def tempTopicPartitionFile(partitions: Set[TopicPartition]): Path = {
    val file = File.createTempFile("leader-election-command", ".json")
    file.deleteOnExit()

    val jsonString = TestUtils.stringifyTopicPartitions(partitions)

    Files.write(file.toPath, jsonString.getBytes(StandardCharsets.UTF_8))

    file.toPath
  }
}

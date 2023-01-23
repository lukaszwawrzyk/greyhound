package com.wixpress.dst.greyhound.testkit

import com.wixpress.dst.greyhound.core.TopicConfig
import com.wixpress.dst.greyhound.core.admin.{AdminClient, AdminClientConfig}
import com.wixpress.dst.greyhound.core.metrics.GreyhoundMetrics.{MetricResult, _}
import com.wixpress.dst.greyhound.core.metrics.{GreyhoundMetric, GreyhoundMetrics}
import com.wixpress.dst.greyhound.testkit.ManagedKafkaMetric._
import kafka.server.{KafkaConfig, KafkaServer}
import org.apache.curator.test.TestingServer
import zio.ZIO.attemptBlocking
import zio.{RIO, Scope, Trace, UIO, ZIO}

import java.util.Properties
import scala.reflect.io.Directory
import scala.util.Random

trait ManagedKafka {
  def shutdown: RIO[Any, Unit]

  def bootstrapServers: String

  def createTopic(config: TopicConfig)(implicit trace: Trace): RIO[Any, Unit]

  def createTopics(configs: TopicConfig*)(implicit trace: Trace): RIO[Any, Unit]

  def adminClient: AdminClient
}

object ManagedKafka {

  def make(config: ManagedKafkaConfig): RIO[GreyhoundMetrics with Scope, ManagedKafka] = for {
    _       <- embeddedZooKeeper(config.zooKeeperPort)
    metrics <- ZIO.environment[GreyhoundMetrics]
    logDir  <- tempDirectory(s"target/kafka/logs/${config.kafkaPort}")
    kafka   <- embeddedKafka(KafkaServerConfig(config.kafkaPort, config.zooKeeperPort, config.brokerId, logDir, config.saslAttributes))
    admin   <- AdminClient.make(AdminClientConfig(s"localhost:${config.kafkaPort}"))
  } yield new ManagedKafka {

    override val bootstrapServers: String = s"localhost:${config.kafkaPort}"

    override def createTopic(config: TopicConfig)(implicit trace: Trace): RIO[Any, Unit] =
      adminClient.createTopics(Set(config)).unit.provideEnvironment(metrics)

    override def createTopics(configs: TopicConfig*)(implicit trace: Trace): RIO[Any, Unit] =
      adminClient.createTopics(configs.toSet).unit.provideEnvironment(metrics)

    override def adminClient: AdminClient = admin

    override def shutdown: RIO[Any, Unit] =
      ZIO.attempt(kafka.shutdown()) zipPar admin.shutdown
  }

  private def tempDirectory(path: String) = {
    val acquire = GreyhoundMetrics.report(CreatingTempDirectory(path)) *> attemptBlocking(Directory(path))
    ZIO.acquireRelease(acquire) { dir =>
      GreyhoundMetrics.report(DeletingTempDirectory(path)) *> attemptBlocking(dir.deleteRecursively()).ignore
    }
  }

  private def embeddedZooKeeper(port: Int) = {
    val acquire = GreyhoundMetrics.report(StartingZooKeeper(port)) *> attemptBlocking(new TestingServer(port))
    ZIO.acquireRelease(acquire) { server =>
      GreyhoundMetrics.report(StoppingZooKeeper(port)) *>
        attemptBlocking(server.stop())
          .reporting(StoppedZooKeeper(port, _))
          .ignore
    }
  }

  private def embeddedKafka(config: KafkaServerConfig) = {
    val acquire = for {
      _      <- ZIO.attempt(Directory(config.logDir).deleteRecursively())
      _      <- GreyhoundMetrics.report(StartingKafka(config.port))
      server <- attemptBlocking(new KafkaServer(config.toKafkaConfig))
      _      <- attemptBlocking(server.startup())
    } yield server
    ZIO.acquireRelease(acquire) { server =>
      GreyhoundMetrics.report(StoppingKafka(config.port)) *>
        attemptBlocking(server.shutdown())
          .reporting(StoppedKafka(config.port, _))
          .ignore
    }
  }

}

case class ManagedKafkaConfig(
  kafkaPort: Int,
  zooKeeperPort: Int,
  saslAttributes: Map[String, String],
  brokerId: Int = Random.nextInt(100000)
)

object ManagedKafkaConfig {
  val Default: ManagedKafkaConfig = ManagedKafkaConfig(6667, 2181, Map.empty)
}

case class KafkaServerConfig(port: Int, zooKeeperPort: Int, brokerId: Int, logDir: Directory, extraProperties: Map[String, String]) {

  def toKafkaConfig: KafkaConfig = KafkaConfig.fromProps {
    val props = new Properties
    props.setProperty("broker.id", brokerId.toString)
    props.setProperty("port", port.toString)
    props.setProperty("log.dir", logDir.path)
    props.setProperty("zookeeper.connect", s"localhost:$zooKeeperPort")
    props.setProperty("num.partitions", "8")
    props.setProperty("offsets.topic.num.partitions", "5")
    props.setProperty("zookeeper.session.timeout.ms", "15000")
    props.setProperty("host.name", "localhost")
    // force `listeners` to localhost, otherwise  Kaka will try and discover the hostname of the machine
    // running the tests and tell clients to use this to connect, which in some cases may not work.
    props.setProperty("listeners", s"PLAINTEXT://localhost:$port")
    props.setProperty("authorizer.class.name", "kafka.security.authorizer.AclAuthorizer")
    props.setProperty("super.users", "User:testadmin;")
    props.setProperty("allow.everyone.if.no.acl.found", "true")
    props.setProperty("offsets.topic.replication.factor", "1")
    props.setProperty("group.initial.rebalance.delay.ms", "0")
    props.setProperty("log.cleaner.min.compaction.lag.ms", "3600000")
    props.setProperty("transaction.state.log.replication.factor", "1")
    props.setProperty("transaction.state.log.min.isr", "1")
    props.setProperty("transaction.timeout.ms", "500")
    props.setProperty("transaction.max.timeout.ms", "15000")
    props.setProperty("reserved.broker.max.id", "99999999")
    props.setProperty("auto.create.topics.enable", "false")
    if (extraProperties.nonEmpty) {
      extraProperties.foreach(attribs => {
        props.setProperty(attribs._1, attribs._2)
      })
    }

    props
  }

}

sealed trait ManagedKafkaMetric extends GreyhoundMetric

object ManagedKafkaMetric {

  case class CreatingTempDirectory(path: String) extends ManagedKafkaMetric

  case class DeletingTempDirectory(path: String) extends ManagedKafkaMetric

  case class StartingZooKeeper(port: Int) extends ManagedKafkaMetric

  case class StoppingZooKeeper(port: Int) extends ManagedKafkaMetric

  case class StoppedZooKeeper(port: Int, result: MetricResult[Throwable, Unit]) extends ManagedKafkaMetric

  case class StartingKafka(port: Int) extends ManagedKafkaMetric

  case class StoppingKafka(port: Int) extends ManagedKafkaMetric

  case class StoppedKafka(port: Int, result: MetricResult[Throwable, Unit]) extends ManagedKafkaMetric

}

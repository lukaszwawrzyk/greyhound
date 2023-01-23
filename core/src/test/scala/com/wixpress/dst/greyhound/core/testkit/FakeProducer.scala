package com.wixpress.dst.greyhound.core.testkit

import com.wixpress.dst.greyhound.core.{Offset, PartitionInfo, Topic, TopicPartition}
import com.wixpress.dst.greyhound.core.producer.Producer.Producer
import com.wixpress.dst.greyhound.core.producer._
import zio._

case class FakeProducer(
  records: Queue[ProducerRecord[Chunk[Byte], Chunk[Byte]]],
  counterRef: Ref[Int],
  offsets: Ref[Map[TopicPartition, Offset]],
  config: ProducerConfig,
  beforeProduce: ProducerRecord[Chunk[Byte], Chunk[Byte]] => IO[ProducerError, ProducerRecord[Chunk[Byte], Chunk[Byte]]] =
    ZIO.succeed(_)(Trace.empty),
  beforeComplete: RecordMetadata => IO[ProducerError, RecordMetadata] = ZIO.succeed(_)(Trace.empty),
  override val attributes: Map[String, String] = Map.empty,
  onShutDown: UIO[Unit] = ZIO.unit
) extends Producer {

  def failing: FakeProducer = copy(config = ProducerConfig.Failing)

  override def produceAsync(
    record: ProducerRecord[Chunk[Byte], Chunk[Byte]]
  )(implicit trace: Trace): ZIO[Any, ProducerError, IO[ProducerError, RecordMetadata]] =
    config match {
      case ProducerConfig.Standard =>
        for {
          modified      <- beforeProduce(record)
          _             <- records.offer(modified)
          _             <- counterRef.update(_ + 1)
          topic          = modified.topic
          partition      = modified.partition.getOrElse(0)
          topicPartition = TopicPartition(topic, partition)
          offset        <- offsets.modify { offsets =>
                             val offset = offsets.get(topicPartition).fold(0L)(_ + 1)
                             (offset, offsets + (topicPartition -> offset))
                           }
          promise       <- Promise.make[ProducerError, RecordMetadata]
          _             <- promise.complete(beforeComplete(RecordMetadata(topic, partition, offset))).fork
        } yield promise.await

      case ProducerConfig.Failing =>
        ProducerError(new IllegalStateException("Oops")).flip.flatMap(error =>
          Promise
            .make[ProducerError, RecordMetadata]
            .tap(_.fail(error))
            .map(_.await)
        )
    }

  override def shutdown(implicit trace: Trace): UIO[Unit] = onShutDown

  def producedCount(implicit trace: Trace) = counterRef.get

  override def partitionsFor(topic: Topic)(implicit trace: Trace): RIO[Any, Seq[PartitionInfo]] =
    ZIO.succeed((1 to 3) map (p => PartitionInfo(topic, p, 1)))
}

object FakeProducer {
  def make(implicit trace: Trace): UIO[FakeProducer] = make()
  def make(
    beforeProduce: ProducerRecord[Chunk[Byte], Chunk[Byte]] => IO[ProducerError, ProducerRecord[Chunk[Byte], Chunk[Byte]]] =
      ZIO.succeed(_)(Trace.empty),
    beforeComplete: RecordMetadata => IO[ProducerError, RecordMetadata] = ZIO.succeed(_)(Trace.empty),
    attributes: Map[String, String] = Map.empty,
    onShutdown: UIO[Unit] = ZIO.unit
  )(implicit trace: Trace): UIO[FakeProducer] = for {
    records <- Queue.unbounded[ProducerRecord[Chunk[Byte], Chunk[Byte]]]
    offset  <- Ref.make(Map.empty[TopicPartition, Offset])
    counter <- Ref.make(0)
  } yield FakeProducer(records, counter, offset, ProducerConfig.Standard, beforeProduce, beforeComplete, attributes, onShutdown)
}

sealed trait ProducerConfig

object ProducerConfig {

  case object Standard extends ProducerConfig

  case object Failing extends ProducerConfig

}

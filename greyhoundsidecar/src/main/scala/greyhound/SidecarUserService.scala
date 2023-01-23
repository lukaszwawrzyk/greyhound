package greyhound

import com.wixpress.dst.greyhound.sidecar.api.v1.greyhoundsidecaruser.ZioGreyhoundsidecaruser.RGreyhoundSidecarUser
import com.wixpress.dst.greyhound.sidecar.api.v1.greyhoundsidecaruser.{HandleMessagesRequest, HandleMessagesResponse, Record}
import io.grpc.Status
import zio.Console.printLine
import zio.ZIO

class SidecarUserService extends RGreyhoundSidecarUser[Any]{

  override def handleMessages(request: HandleMessagesRequest): ZIO[Any, Status, HandleMessagesResponse] = {
    printLine(
      s"""~~~ Sidecar User CONSUMING ~~~
         |topic = ${request.topic}
         |group = ${request.group}
         |records =
         |${printRecords(request.records)}
         |~~~ End Message ~~~
         |""".stripMargin
    ).orDie *> failOrSucceed(request.records)
  }

  private def printRecords(records: Seq[Record]) =
    records.map(record =>
      s"""  key = ${record.key}
         |  payload = ${record.payload}
         |  headers = ${record.headers}
         |  offset = ${record.offset}
         |""".stripMargin).mkString

  private def failOrSucceed(records: Seq[Record]) =
    if (records.exists(_.payload.exists(_.contains("fail"))))
      ZIO.fail(Status.INTERNAL
        .withDescription("Failed to consume")
        .withCause(new RuntimeException("Something went wrong in the consumer handler")))
    else
      ZIO.succeed(HandleMessagesResponse())

}
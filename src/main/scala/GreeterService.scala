import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.{Uri, HttpResponse, HttpRequest}
import akka.http.scaladsl.model.ws.{UpgradeToWebsocket, BinaryMessage, TextMessage, Message}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Flow, Source}

/**
  * The greeter service.
  */
object GreeterService {
  def create(implicit system: ActorSystem, materializer: ActorMaterializer) : HttpRequest => HttpResponse = {
    val greetPrefix = Source.single("Hello, ")
    val greetSuffix = Source.single("!")

    val greeterWebSocketService =
      Flow[Message].mapConcat {
        case textMessage: TextMessage => TextMessage(
          greetPrefix ++ textMessage.textStream ++ greetSuffix
        ) :: Nil // end

        case binaryMessage: BinaryMessage =>
          binaryMessage.dataStream.runWith(Sink.ignore)

          Nil // end
      }

    val requestHandler: HttpRequest => HttpResponse = {
      case request @ HttpRequest(GET, Uri.Path("/greeter"), _, _, _)  =>
        request.header[UpgradeToWebsocket] match {
          case Some(upgrade) =>
            println(s"Valid request: $request")

            upgrade.handleMessages(greeterWebSocketService)

          case None =>
            println(s"Non-WebSocket request: $request")

            HttpResponse(400, entity = "Not a valid WebSocket request.")
        }

      case badRequest: HttpRequest =>
        println(s"Not found request: $badRequest")

        HttpResponse(404, entity = "Sorry, not found.")
    }

    requestHandler
  }
}

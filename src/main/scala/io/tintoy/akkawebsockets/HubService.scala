package io.tintoy.akkawebsockets

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.{Uri, HttpResponse, HttpRequest}
import akka.http.scaladsl.model.ws.{UpgradeToWebsocket, TextMessage, Message}
import akka.stream.scaladsl.Flow
import akka.stream.stage.{TerminationDirective, SyncDirective, Context, PushStage}

/**
  * Web service that hosts a [[Hub]].
  */
object HubService {
  /**
    * Create a new hub service in the specified actor system.
    * @param hubName A unique (per-[[ActorSystem]]) name for the hub.
    * @param system The [[ActorSystem]] in which to create the hub and its underlying actors.
    * @return The new hub service.
    */
  def apply(hubName: String)(implicit system: ActorSystem): HubService = new HubService(hubName, system)
}

/**
  * Web service that hosts a [[Hub]].
  * @param hubName A unique (per-[[ActorSystem]]) name for the hub.
  * @param system The [[ActorSystem]] in which to create the hub and its underlying actors.
  */
class HubService(val hubName: String, system: ActorSystem) {
  /**
    * The [[Hub]] hosted by the [[HubService]].
    * @note For now, only the sendXxxMessage functions are safe to call from outside the hub service.
    */
  val hub = Hub.create(system, hubName)

  /**
    * Route definitions for the hub service.
    */
  def routes =
    get {
      path("hubs" / hubName) {
        parameter('name) { clientName =>
          handleWebsocketMessages(
            connectWebSocketToHub(clientName)
          )
        }
      }
    }

  /**
    * Create a new [[Flow]] to connect a WebSocket to the hub.
    * @param clientName The name of the client whose connection the flow will represent.
    * @return The configured [[Flow]].
    */
  def connectWebSocketToHub(clientName: String): Flow[Message, Message, Unit] =
    Flow[Message]
      .collect {
        case TextMessage.Strict(rawMessage) => rawMessage
      }
      .via(
        hub.buildClientMessageFlow(clientName)
      )
      .map {
        case Hub.MessageToClient(client, message) => TextMessage.Strict(s"@$client $message")
        case Hub.MessageToGroup(group, message)   => TextMessage.Strict(s"#$group $message")
        case unexpectedMessage                    => TextMessage.Strict(s"Unexpected message (sorry about this): $unexpectedMessage")
      }
      .via(
        reportErrors { error =>
          println(s"WebSocket stream error: $error")
        }
      )

  /**
    * Create an error-reporting stage for a WebSocket [[Message]] flow.
    * @param onError A function used to report any error encountered.
    * @return A configured [[Flow]] representing the error reporter.
    */
  private def reportErrors(onError: Throwable => Unit): Flow[Message, Message, Unit] = {
    if (onError == null)
      throw new IllegalArgumentException("Argument 'onError' cannot be null (why use an error reporter if it doesn't do anything?).")

    Flow[Message].transform(
      () => new ReportErrorStage(onError)
    )
  }

  /**
    * Stream stage used to provide error-reporting functionality.
    * @param reporter A function that reports errors encountered by upstream components.
    * @tparam T The stream element type.
    */
  private[this] class ReportErrorStage[T](reporter: Throwable => Unit) extends PushStage[T, T] {
    /**
      * Called when an element is available from upstream and there is demand for it downstream.
      * @param element The element.
      * @param context The context for the current pipeline stage.
      * @return A [[SyncDirective]] representing the next action to be taken by the stream.
      */
    def onPush(element: T, context: Context[T]): SyncDirective = context.push(element)

    /**
      * Called when an upstream component encounters an error condition.
      * @param cause A [[Throwable]] representing the cause of the error.
      * @param context The context for the current pipeline stage.
      * @return A [[TerminationDirective]] representing the next action to be taken by the stream.
      */
    override def onUpstreamFailure(cause: Throwable, context: Context[T]): TerminationDirective = {
      reporter(cause)

      super.onUpstreamFailure(cause, context)
    }
  }
}
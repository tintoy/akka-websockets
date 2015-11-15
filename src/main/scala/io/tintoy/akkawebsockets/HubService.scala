package io.tintoy.akkawebsockets

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl.Flow

import util.StreamUtil._

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
}
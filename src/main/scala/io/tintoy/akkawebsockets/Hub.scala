package io.tintoy.akkawebsockets

import akka.actor._
import akka.event.{ActorEventBus, LookupClassification}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Sink, Source}

import scala.collection._
import scala.util.matching.Regex

// TODO: Make client and group names case-insensitive.

/**
  * Represents a hub for message exchange.
  */
trait Hub {
  import Hub._

  /**
    * Build a flow to represent the incoming flow of messages from a client.
    * @param clientName The client name.
    * @return The configured [[Flow]].
    */
  def buildClientMessageFlow(clientName: String): Flow[String, HubMessage, Unit]

  /**
    * Send a message to a specific client.
    * @param clientName The name of the client to which the message should be sent.
    * @param message The message text.
    */
  def sendMessageToClient(clientName: String, message: String): Unit

  /**
    * Send a message to a group of clients.
    * @param groupName The name of the client group to which the message should be sent.
    * @param message The message text.
    */
  def sendMessageToGroup(groupName: String, message: String): Unit

  /**
    * Shut the hub down.
    */
  def terminate(): Unit
}

/**
  * A hub is used to exchange messages between clients (either as individuals or as named groups).
  */
object Hub {
  /**
    * Create a new [[Hub]].
    * @param system The actor system in which the hub will be built.
    * @param hubName A name for the hub (and its actor).
    * @return The [[Hub]].
    */
  def create(system: ActorSystem, hubName: String = "hub", messageBus: HubMessageBus = new HubMessageBus()): Hub = {
    var clientsByName = immutable.Map[String, ActorRef]()
    var clientNames = immutable.Map[ActorRef, String]()

    val hubActor = system.actorOf(
      Props(new Actor {
        def receive: Receive = {
          case ClientConnected(clientName, client)    =>
            clientsByName += (clientName -> client)
            clientNames += (client -> clientName)
            context.watch(client) // We want to know if the client has been disconnected.

            messageBus.subscribeClientAsSelf(client, clientName)

          case ClientDisconnected(clientName)         =>
            clientsByName.get(clientName) match {
              case Some(client) =>
                context.unwatch(client)
                messageBus.unsubscribe(client)

              case None         => // Nothing to do.
            }

            clientsByName -= clientName

          case JoinGroup(clientName, groupName)       =>
            val client = clientsByName(clientName)

            messageBus.subscribeClientToGroup(client, groupName)

          case LeaveGroup(clientName, groupName)      =>
            val client = clientsByName(clientName)

            messageBus.unsubscribeClientFromGroup(client, groupName)

          case sendMessage: SendMessage               => messageBus.publish(sendMessage)

          case Terminated(clientRef)                  =>
            // Client's dead; clean up.
            val clientName = clientNames(clientRef)
            clientsByName -= clientName
            clientNames -= clientRef

            messageBus.unsubscribe(clientRef)

          case other                                  => unhandled(other)
        }
      }),
      name = hubName
    )

    /**
      * Create a [[Sink]] that delivers messages from the specified client to the hub actor.
      * @param clientName The name of the client from which the messages originate.
      * @return The configured [[Sink]].
      */
    def hubActorSink(clientName: String): Sink[HubMessage, Unit] =
      Sink.actorRef[HubMessage](hubActor, ClientDisconnected(clientName))

    new Hub {
      /**
        * Has the hub been terminated?
        */
      var isTerminated = false

      /**
        * Build a flow to represent the incoming flow of messages from a client.
        * @param clientName The client name.
        * @return The configured [[Flow]].
        */
      override def buildClientMessageFlow(clientName: String): Flow[String, HubMessage, Unit] = {
        checkTerminated()

        // Incoming stream of messages from the client's WebSocket.
        val input =
          Flow[String]
            .map(rawMessage => {
              // Parse raw message to extract destination client / group ('@' for client, '#' for group).
              val parser = new Regex("^([@#])(\\w+)\\s+(.*)")
              rawMessage match {
                case parser("@", targetClientName, message)  => MessageToClient(targetClientName, message)
                case parser("#", targetGroupName, message)   => MessageToGroup(targetGroupName, message)
                case otherwise                               => MessageToClient(clientName, "Message must start with @clientName or #groupName.")
              }
            })
            .to(hubActorSink(clientName))

        // Outgoing stream of parsed messages for the hub.
        val output =
          Source.actorRef[HubMessage](1, OverflowStrategy.fail)
            .mapMaterializedValue { clientWebSocket =>
              // When the stream is first materialised (i.e. the client connects), inject a ClientConnected message into the message stream.
              // This enables us to capture the actor representing the client's WebSocket.
              hubActor ! ClientConnected(clientName, clientWebSocket)
            }

        Flow.fromSinkAndSource(input, output)
      }

      /**
        * Send a message to a specific client.
        * @param clientName The name of the client to which the message should be sent.
        * @param message The message text.
        */
      override def sendMessageToClient(clientName: String, message: String): Unit = {
        checkTerminated()

        hubActor ! MessageToClient(clientName, message)
      }

      /**
        * Send a message to a group of clients.
        * @param groupName The name of the client group to which the message should be sent.
        * @param message The message text.
        */
      override def sendMessageToGroup(groupName: String, message: String): Unit = {
        checkTerminated()

        hubActor ! MessageToGroup(groupName, message)
      }

      /**
        * Shut down the hub and its supporting infrastructure.
        */
      override def terminate(): Unit = {
        if (!isTerminated) {
          synchronized {
            if (!isTerminated) {
              isTerminated = true

              system.stop(hubActor)
            }
          }
        }
      }

      /**
        * Check if the hub has been terminated.
        */
      private[this] def checkTerminated(): Unit =
        if (isTerminated)
          throw new IllegalStateException("Hub has been terminated.")
    }
  }

  /**
    * Simple message bus used to implement client groups.
    */
  class HubMessageBus extends ActorEventBus with LookupClassification {
    /**
      * The initial size for the client / group lookup table.
      */
    override protected def mapSize(): Int = 50

    /**
      * Subscribe a client to the bus as themselves.
      * @param client The client's [[ActorRef]].
      * @param clientName The client name.
      */
    def subscribeClientAsSelf(client: ActorRef, clientName: String): Unit = subscribe(client, classifierForClient(clientName))

    /**
      * Subscribe a client to the bus as a member of a client group.
      * @param client The client's [[ActorRef]].
      * @param groupName The group name.
      */
    def subscribeClientToGroup(client: ActorRef, groupName: String): Unit = subscribe(client, forGroup(groupName))

    /**
      * Unsubscribe a client from the bus as a member of a client group.
      * @param client The client's [[ActorRef]].
      * @param groupName The group name.
      */
    def unsubscribeClientFromGroup(client: ActorRef, groupName: String): Unit = unsubscribe(client, forGroup(groupName))

    /**
      * Determine the subset of subscribers to which the specified message should be published.
      * @param message The [[HubMessage]] to publish.
      * @return A string to be used as a message classifier ("c/ClientName" or "g/GroupName").
      */
    override protected def classify(message: HubMessage): String = {
      message match {
        case MessageToClient(clientName, _) => classifierForClient(clientName)
        case MessageToGroup(groupName, _)   => forGroup(groupName)
      }
    }

    /**
      * Publish a message to a subscriber.
      * @param message The [[HubMessage]] to publish.
      * @param subscriber The subscriber to which the message should be published.
      */
    override protected def publish(message: HubMessage, subscriber: ActorRef): Unit = {
      subscriber ! message
    }

    /**
      * The type used to classify the audience for a given message on the bus.
      */
    override type Classifier = String

    /**
      * The base type for all messages carried by the bus.
      */
    override type Event = HubMessage
  }

  /**
    * Create a message classifier for the specified client.
    * @param clientName The client name.
    * @return The message classifier.
    */
  private def classifierForClient(clientName: String): String = s"c/$clientName"

  /**
    * Create a message classifier for the specified client group.
    * @param groupName The group name.
    * @return The message classifier.
    */
  private def forGroup(groupName: String): String = s"g/$groupName"

  /**
    * Represents a message sent / received by a [[Hub]].
    */
  sealed trait HubMessage

  /**
    * A client has connected to the hub.
    * @param clientName The client name.
    * @param client An [[ActorRef]] representing the client.
    */
  case class ClientConnected(clientName: String, client: ActorRef) extends HubMessage

  /**
    * A client has disconnected from the hub.
    * @param clientName The client name.
    */
  case class ClientDisconnected(clientName: String) extends HubMessage

  /**
    * Client wants to join a group.
    * @param clientName The client name.
    * @param groupName The group name.
    */
  case class JoinGroup(clientName: String, groupName: String) extends HubMessage

  /**
    * Client wants to leave a group.
    * @param clientName The client name.
    * @param groupName The group name.
    */
  case class LeaveGroup(clientName: String, groupName: String) extends HubMessage

  // TODO: Include sending client name in MessageToXXX.

  /**
    * Represents a message from a client to another client or client group.
    */
  sealed trait SendMessage extends HubMessage

  /**
    * Client wants to send a message to another client.
    * @param targetClientName The name of the client to which the message should be sent.
    * @param messageText The message text.
    */
  case class MessageToClient(targetClientName: String, messageText: String) extends SendMessage

  /**
    * Client wants to send a message to all clients in a group.
    * @param targetGroupName The name of the client group to which the message should be sent.
    * @param messageText The message text.
    */
  case class MessageToGroup(targetGroupName: String, messageText: String) extends SendMessage
}

import akka.actor._
import akka.event.{ActorEventBus, LookupClassification}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Sink, Source}

import scala.collection._
import scala.util.matching.Regex

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
}

/**
  * A hub is used to exchange messages between clients (either as individuals or as named groups).
  */
object Hub {
  /**
    * Create a new [[Hub]].
    * @param system The actor system in which the hub will be built.
    * @return
    */
  def create(system: ActorSystem, hubName: String = "hub"): Hub = {
    var clientsByName = immutable.Map[String, ActorRef]()
    val messageBus = new HubMessageBus()

    val hubActor = system.actorOf(
      Props(new Actor {
        def receive: Receive = {
          case ClientConnected(clientName, client)    =>
            clientsByName += (clientName -> client)
            context.watch(client) // We want to know if the client has been disconnected.

            messageBus.subscribe(client,
              to = s"c/$clientName"
            )
          case ClientDisconnected(clientName)         =>
            val client = clientsByName(clientName)
            context.unwatch(client)

            clientsByName -= clientName

            messageBus.unsubscribe(client)

          case JoinGroup(clientName, groupName)       =>
            val client = clientsByName(clientName)

            messageBus.subscribe(client,
              to = s"g/$groupName"
            )

          case LeaveGroup(clientName, groupName)      =>
            val client = clientsByName(clientName)

            messageBus.unsubscribe(client,
              from = s"g/$groupName"
            )

          case MessageFromClient(clientName, message) =>


          case messageToClient: MessageToClient       => messageBus.publish(messageToClient)
          case messageToGroup: MessageToGroup         => messageBus.publish(messageToGroup)

          case Terminated(client)                     => messageBus.unsubscribe(client)

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
        * Build a flow to represent the incoming flow of messages from a client.
        * @param clientName The client name.
        * @return The configured [[Flow]].
        */
      override def buildClientMessageFlow(clientName: String): Flow[String, HubMessage, Unit] = {
        val parser = new Regex("^([@#])(\\w+)\\s+(.*)")

        // Incoming stream of messages from the client's WebSocket.
        val input =
          Flow[String]
            .map(rawMessage => {
              // Parse raw message to extract destination client / group ('@' for client, '#' for group).
              parser.pattern.split(rawMessage) match {
                case Array("@", clientName, message)  => MessageToClient(clientName, message)
                case Array("#", groupName, message)   => MessageToGroup(groupName, message)

                case otherwise =>
                  MessageToClient(clientName, "Message must start with @clientName or #groupName.")
              }
            })
            .to(hubActorSink(clientName))

        // Outgoing stream of parsed messages for the hub.
        val output =
          Source.actorRef[HubMessage](1, OverflowStrategy.fail)
            .mapMaterializedValue { clientWebSocket =>
              // When the source is first materialised, inject a ClientConnected message into the message stream.
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
      override def sendMessageToClient(clientName: String, message: String): Unit =
        hubActor ! MessageToClient(clientName, message)

      /**
        * Send a message to a group of clients.
        * @param groupName The name of the client group to which the message should be sent.
        * @param message The message text.
        */
      override def sendMessageToGroup(groupName: String, message: String): Unit =
        hubActor ! MessageToGroup(groupName, message)
    }
  }

  /**
    * Simple message bus used to implement client groups.
    */
  private class HubMessageBus extends ActorEventBus with LookupClassification {
    /**
      * The initial size for the client / group lookup table.
      */
    override protected def mapSize(): Int = 50

    /**
      * Determine the subset of subscribers to which the specified message should be published.
      * @param message The [[HubMessage]] to publish.
      * @return
      */
    override protected def classify(message: HubMessage): String = {
      message match {
        case MessageToClient(clientName, _) => forClient(clientName)
        case MessageToGroup(groupName, _)   => forGroup(groupName)
      }
    }

    /**
      * Publish a message to a subscriber.
      * @param message The [[HubMessage]] to publish.
      * @param subscriber The subscriber to which the message
      */
    override protected def publish(message: HubMessage, subscriber: ActorRef): Unit = {
      subscriber ! message
    }

    override type Classifier = String
    override type Event = HubMessage
  }

  private def forClient(clientName: String): String = s"c/$clientName"
  private def forGroup(groupName: String): String = s"g/$groupName"

  // Messages used by the hub.

  /**
    * Represents a message sent / received by a [[Hub]].
    */
  sealed trait HubMessage

  /**
    * A client has connected to the hub.
    * @param clientName The client name.
    * @param client An [[ActorRef]] representing the client.
    */
  private case class ClientConnected(clientName: String, client: ActorRef) extends HubMessage

  /**
    * A client has disconnected from the hub.
    * @param clientName The client name.
    */
  private case class ClientDisconnected(clientName: String) extends HubMessage
  private case class JoinGroup(clientName: String, groupName: String) extends HubMessage
  private case class LeaveGroup(clientName: String, groupName: String) extends HubMessage
  private case class MessageFromClient(clientName: String, message: String) extends HubMessage
  private case class MessageToClient(clientName: String, message: String) extends HubMessage
  private case class MessageToGroup(groupName: String, message: String) extends HubMessage
}

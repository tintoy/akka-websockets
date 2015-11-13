import akka.actor.ActorSystem
import akka.stream.{OverflowStrategy, ActorMaterializer}
import akka.stream.scaladsl.{Sink, Source, Flow}
import akka.testkit.{TestProbe, ImplicitSender, DefaultTimeout, TestKit}

import com.typesafe.config.ConfigFactory

import org.scalatest.{BeforeAndAfterEach, BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration.DurationInt

import Hub.{HubMessageBus, HubMessage}

/**
  * Tests for [[Hub]].
  */
class HubSpec
    extends TestKit(
      ActorSystem("HubSpec",
        ConfigFactory.defaultApplication()
      )
    ) with DefaultTimeout with ImplicitSender
    with WordSpecLike with Matchers
    with BeforeAndAfterAll {

  type ClientMessageFlow = Flow[String, HubMessage, Unit]

  implicit val materializer: ActorMaterializer = ActorMaterializer()

  "Hub with 2 clients" when {
    "sent a single textual message for another client" should {
      "deliver a single MessageToClient message to the other client" in new TwoClientMessageFlowTest {
        val testMessage = "Test Message"

        client1MessageStream ! s"@$client2Name $testMessage"

        client2Probe.expectMsg(10.seconds,
          Hub.MessageToClient(client2Name, testMessage)
        )

        terminateHub()
      }
    }
  }

  "Hub with 2 clients in the same group" when {
    "sent a single textual message to that group" should {
      "deliver that message to both clients" in new TwoClientMessageFlowTest {
        val testMessage = "Test Message"

        hubMessageBus.subscribeClientToGroup(client1Probe.ref, "TestClients")
        hubMessageBus.subscribeClientToGroup(client2Probe.ref, "TestClients")

        client1MessageStream ! s"#TestClients $testMessage"

        client1Probe.expectMsg(max = 5.seconds,
          Hub.MessageToGroup("TestClients", testMessage)
        )
        client2Probe.expectMsg(max = 5.seconds,
          Hub.MessageToGroup("TestClients", testMessage)
        )

        terminateHub()
      }
    }
  }

  /**
    * Tear-down after all tests are complete.
    */
  override protected def afterAll(): Unit = {
    shutdown()

    super.afterAll()
  }

  /**
    * Test probes to represent [[Hub]] clients.
    */
  trait ClientProbes {
    /**
      * The name for client 1 (and its associated test probe).
      */
    val client1Name = "TestClient1"

    /**
      * Test probe for client 1.
      */
    val client1Probe = TestProbe(client1Name)

    /**
      * The name for client 1 (and its associated test probe).
      */
    val client2Name = "TestClient2"

    /**
      * Test probe for client 2.
      */
    val client2Probe = TestProbe(client2Name)
  }

  /**
    * A test that involves a [[Hub]] with an exposed message bus.
    */
  trait HubMessageBusTest {
    /**
      * The hub message bus under test.
      */
    val hubMessageBus = new HubMessageBus()

    /**
      * The hub under test.
      */
    val hub = Hub.create(system, "TestHub", hubMessageBus)

    /**
      * Shut down the [[Hub]] under test (and its supporting infrastructure).
      */
    def terminateHub() = hub.terminate()
  }

  /**
    * A test with message flows for 2 clients (and supporting infrastructure).
    */
  trait TwoClientMessageFlowTest extends HubMessageBusTest with ClientProbes {
    /**
      * Message emitted when a message flow just before it completes.
      */
    case object StreamComplete

    // Both clients are always subscribed to the bus as themselves.
    hubMessageBus.subscribeClientAsSelf(client1Probe.ref, client1Name)
    hubMessageBus.subscribeClientAsSelf(client2Probe.ref, client2Name)

    // Message flows for clients.
    // Note that starting each stream will materialise its constituent actors (and triggers a ClientConnected message to introduce them to the hub actor)

    val client1MessageFlow: Flow[String, HubMessage, Unit] = hub.buildClientMessageFlow(client1Name)
    val client1MessageStream =
      Source.actorRef(1, OverflowStrategy.fail)
        .via(client1MessageFlow)
        .to(
          Sink.actorRef(client1Probe.ref, onCompleteMessage = StreamComplete)
        )
        .run()

    val client2MessageFlow: Flow[String, HubMessage, Unit] = hub.buildClientMessageFlow(client2Name)
    val client2MessageStream =
      Source.actorRef(1, OverflowStrategy.fail)
        .via(client2MessageFlow)
        .to(
          Sink.actorRef(client2Probe.ref, onCompleteMessage = StreamComplete)
        )
        .run()
  }
}

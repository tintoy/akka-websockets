import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
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
    with BeforeAndAfterAll with BeforeAndAfterEach {

  type ClientMessageFlow = Flow[String, HubMessage, Unit]

  implicit val materializer: ActorMaterializer = ActorMaterializer()

  var hubMessageBus: Hub.HubMessageBus = null
  var hub: Hub = null

  "Hub message flow for a newly-connected client" when {
    "sent a single textual message" should {
      "emit a single MessageToClient message" in {
        val testProbe = TestProbe()
        hubMessageBus.subscribeClientAsSelf(testProbe.ref, "TestClient2")

        val testMessageText = "Test Message"
        val testMessage = s"@TestClient2 $testMessageText"
        val testSource = Source.single(testMessage)

        val clientMessageFlow = hub.buildClientMessageFlow("TestClient1")

        testSource
          .via(
            hub.buildClientMessageFlow(clientName = "TestClient1")
          )
          .runWith(
            Sink.foreach { message =>
              info(s"Received message: $message")
            }
          )

        testProbe.expectMsg(10.seconds,
          Hub.MessageToClient("TestClient2", testMessageText)
        )
      }
    }
  }

  /**
    * Setup before test.
    */
  override protected def beforeEach(): Unit = {
    super.beforeEach()

    hubMessageBus = new HubMessageBus
    hub = Hub.create(system, "TestHub", hubMessageBus)
  }

  /**
    * Tear-down after each test.
    */
  override protected def afterEach(): Unit = {
    super.afterEach()
  }

  /**
    * Tear-down after all tests are complete.
    */
  override protected def afterAll(): Unit = {
    shutdown()

    super.afterAll()
  }
}

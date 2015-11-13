import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.io.StdIn

/**
  * Application startup.
  */
object Boot extends App {
  implicit val system = ActorSystem("akka-websockets")
  implicit val materializer = ActorMaterializer()

  val greeterService = new GreeterService()
  val bindingFuture = Http().bindAndHandleSync(
    greeterService.requestHandler,
    interface = "localhost",
    port = 19123
  )

  Await.result(bindingFuture, 5.seconds)
  println("Listening on http://localhost:19123/ (press enter to terminate).")
  StdIn.readLine()

  println("Shutting down...")
  system.terminate()
  Await.result(system.whenTerminated, 5.seconds)

  println("Done.")
}

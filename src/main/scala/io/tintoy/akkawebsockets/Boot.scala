package io.tintoy.akkawebsockets

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.io.StdIn

/**
  * Application startup.
  */
object Boot extends App {
  implicit val system = ActorSystem("akka-websockets")
  implicit val materializer = ActorMaterializer()

  val hubService = HubService("akka-websockets")
  val bindingFuture = Http().bindAndHandle(
    hubService.routes,
    interface = "localhost",
    port = 19123
  )

  Await.result(bindingFuture, 5.seconds)
  println(
    s"Listening on ws://localhost:19123/hubs/${hubService.hubName}?name=MyName (press enter to terminate)."
  )
  StdIn.readLine()

  println("Shutting down...")
  hubService.hub.terminate()
  system.terminate()
  Await.result(system.whenTerminated, 5.seconds)

  println("Done.")
}

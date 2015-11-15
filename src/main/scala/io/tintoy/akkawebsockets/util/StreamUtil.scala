package io.tintoy.akkawebsockets.util

import akka.http.scaladsl.model.ws.Message
import akka.stream.scaladsl.Flow
import akka.stream.stage.{TerminationDirective, Context, SyncDirective, PushStage}

/**
  * Utilities for working with Akka streams.
  */
object StreamUtil {
  /**
    * Create an error-reporting stage for a WebSocket [[Message]] flow.
    * @param onError A function used to report any error encountered.
    * @return A configured [[Flow]] representing the error reporter.
    */
  def reportErrors[TElement](onError: Throwable => Unit): Flow[TElement, TElement, Unit] = {
    if (onError == null)
      throw new IllegalArgumentException("Argument 'onError' cannot be null (why use an error reporter if it doesn't do anything?).")

    Flow[TElement].transform(
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



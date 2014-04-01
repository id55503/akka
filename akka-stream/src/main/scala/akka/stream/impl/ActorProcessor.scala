/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.collection.immutable
import scala.util.{ Failure, Success }
import scala.util.control.NonFatal
import org.reactivestreams.api.Processor
import org.reactivestreams.spi.Subscriber
import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import akka.stream.MaterializerSettings
import akka.event.LoggingReceive

/**
 * INTERNAL API
 */
private[akka] object ActorProcessor {
  import Ast._
  def props(settings: MaterializerSettings, op: AstNode): Props = op match {
    case t: Transform ⇒ Props(new TransformProcessorImpl(settings, t))
    case r: Recover   ⇒ Props(new RecoverProcessorImpl(settings, r))
    case s: SplitWhen ⇒ Props(new SplitWhenProcessorImpl(settings, s.p))
    case g: GroupBy   ⇒ Props(new GroupByProcessorImpl(settings, g.f))
    case m: Merge     ⇒ Props(new MergeImpl(settings, m.other))
    case z: Zip       ⇒ Props(new ZipImpl(settings, z.other))
    case c: Concat    ⇒ Props(new ConcatImpl(settings, c.next))
  }
}

class ActorProcessor[I, O]( final val impl: ActorRef) extends Processor[I, O] with ActorConsumerLike[I] with ActorProducerLike[O]

/**
 * INTERNAL API
 */
private[akka] abstract class ActorProcessorImpl(val settings: MaterializerSettings)
  extends Actor
  with SubscriberManagement[Any]
  with ActorLogging
  with SoftShutdown {

  import ActorBasedFlowMaterializer._

  type S = ActorSubscription[Any]

  override def maxBufferSize: Int = settings.maxFanOutBufferSize
  override def initialBufferSize: Int = settings.initialFanOutBufferSize
  override def createSubscription(subscriber: Subscriber[Any]): S = new ActorSubscription(self, subscriber)

  override def receive = waitingExposedPublisher

  protected var primaryInputs: Inputs = _

  //////////////////////  Startup phases //////////////////////

  var exposedPublisher: ActorPublisher[Any] = _

  def waitingExposedPublisher: Receive = {
    case ExposedPublisher(publisher) ⇒
      exposedPublisher = publisher
      publisherExposed()
      context.become(waitingForUpstream)
    case _ ⇒ throw new IllegalStateException("The first message must be ExposedPublisher")
  }

  // WARNING: DO NOT SEND messages from the constructor (that includes subscribing to other streams) since their reply
  // might arrive earlier than ExposedPublisher. Override this method to schedule such events.
  protected def publisherExposed(): Unit = ()

  def waitingForUpstream: Receive = downstreamManagement orElse {
    case OnComplete ⇒
      // Instead of introducing an edge case, handle it in the general way
      primaryInputs = EmptyInputs
      transitionToRunningWhenReady()
    case OnSubscribe(subscription) ⇒
      assert(subscription != null)
      primaryInputs = new BatchingInputBuffer(subscription, settings.initialInputBufferSize)
      transitionToRunningWhenReady()
    case OnError(cause) ⇒ failureReceived(cause)
  }

  def transitionToRunningWhenReady(): Unit =
    if (primaryInputs ne null) {
      primaryInputs.prefetch()
      transferState = initialTransferState
      context.become(running)
    }

  //////////////////////  Management of subscribers //////////////////////

  // All methods called here are implemented by SubscriberManagement
  def downstreamManagement: Receive = {
    case SubscribePending ⇒
      subscribePending()
    case RequestMore(subscription, elements) ⇒
      moreRequested(subscription.asInstanceOf[S], elements)
      pump()
    case Cancel(subscription) ⇒
      unregisterSubscription(subscription.asInstanceOf[S])
      pump()
  }

  private def subscribePending(): Unit =
    exposedPublisher.takePendingSubscribers() foreach registerSubscriber

  //////////////////////  Active state //////////////////////

  def running: Receive = LoggingReceive(downstreamManagement orElse {
    case OnNext(element) ⇒
      primaryInputs.enqueueInputElement(element)
      pump()
    case OnComplete ⇒
      primaryInputs.complete()
      flushAndComplete()
      pump()
    case OnError(cause) ⇒ failureReceived(cause)
  })

  // Called by SubscriberManagement when all subscribers are gone.
  // The method shutdown() is called automatically by SubscriberManagement after it called this method.
  override def cancelUpstream(): Unit = {
    if (primaryInputs ne null) primaryInputs.cancel()
    PrimaryOutputs.cancel()
  }

  // Called by SubscriberManagement whenever the output buffer is ready to accept additional elements
  override protected def requestFromUpstream(elements: Int): Unit = {
    // FIXME: Remove debug logging
    log.debug(s"received downstream demand from buffer: $elements")
    PrimaryOutputs.enqueueOutputDemand(elements)
  }

  def failureReceived(e: Throwable): Unit = fail(e)

  def fail(e: Throwable): Unit = {
    shutdownReason = Some(e)
    log.error(e, "failure during processing") // FIXME: escalate to supervisor instead
    abortDownstream(e)
    if (primaryInputs ne null) primaryInputs.cancel()
    exposedPublisher.shutdown(shutdownReason)
    softShutdown()
  }

  object PrimaryOutputs extends Outputs {
    private var downstreamBufferSpace = 0
    private var downstreamCompleted = false
    def demandAvailable = downstreamBufferSpace > 0

    def enqueueOutputDemand(demand: Int): Unit = downstreamBufferSpace += demand
    def enqueueOutputElement(elem: Any): Unit = {
      downstreamBufferSpace -= 1
      pushToDownstream(elem)
    }

    def complete(): Unit = downstreamCompleted = true
    def cancel(): Unit = downstreamCompleted = true
    def isClosed: Boolean = downstreamCompleted
    override val NeedsDemand: TransferState = new TransferState {
      def isReady = demandAvailable
      def isCompleted = downstreamCompleted
    }
    override def NeedsDemandOrCancel: TransferState = new TransferState {
      def isReady = demandAvailable || downstreamCompleted
      def isCompleted = false
    }
  }

  lazy val needsPrimaryInputAndDemand = primaryInputs.NeedsInput && PrimaryOutputs.NeedsDemand

  private var transferState: TransferState = NotInitialized
  protected def setTransferState(t: TransferState): Unit = transferState = t
  protected def initialTransferState: TransferState

  // Exchange input buffer elements and output buffer "requests" until one of them becomes empty.
  // Generate upstream requestMore for every Nth consumed input element
  final protected def pump(): Unit = {
    try while (transferState.isExecutable) {
      // FIXME: Remove debug logging
      log.debug(s"iterating the pump with state $transferState and buffer $bufferDebug")
      transferState = withCtx(context)(transfer())
    } catch { case NonFatal(e) ⇒ fail(e) }

    // FIXME: Remove debug logging
    log.debug(s"finished iterating the pump with state $transferState and buffer $bufferDebug")

    if (transferState.isCompleted) {
      if (!isShuttingDown) {
        // FIXME: Remove debug logging
        log.debug("shutting down the pump")
        if (primaryInputs.isOpen) primaryInputs.cancel()
        primaryInputs.clear()
        context.become(flushing)
        isShuttingDown = true
      }
      completeDownstream()
    }
  }

  // Needs to be implemented by Processor implementations. Transfers elements from the input buffer to the output
  // buffer.
  protected def transfer(): TransferState

  //////////////////////  Completing and Flushing  //////////////////////

  protected def flushAndComplete(): Unit = context.become(flushing)

  def flushing: Receive = downstreamManagement orElse {
    case OnSubscribe(subscription) ⇒ throw new IllegalStateException("Cannot subscribe shutdown subscriber")
    case _                         ⇒ // ignore everything else
  }

  //////////////////////  Shutdown and cleanup (graceful and abort) //////////////////////

  var isShuttingDown = false

  var shutdownReason: Option[Throwable] = ActorPublisher.NormalShutdownReason

  // Called by SubscriberManagement to signal that output buffer finished (flushed or aborted)
  override def shutdown(completed: Boolean): Unit = {
    isShuttingDown = true
    if (completed)
      shutdownReason = None
    PrimaryOutputs.complete()
    exposedPublisher.shutdown(shutdownReason)
    softShutdown()
  }

  override def postStop(): Unit = {
    if (exposedPublisher ne null)
      exposedPublisher.shutdown(shutdownReason)
    // Non-gracefully stopped, do our best here
    if (!isShuttingDown)
      abortDownstream(new IllegalStateException("Processor actor terminated abruptly"))

    // FIXME what about upstream subscription before we got 
    // case OnSubscribe(subscription) ⇒ subscription.cancel()  
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    throw new IllegalStateException("This actor cannot be restarted")
  }

}

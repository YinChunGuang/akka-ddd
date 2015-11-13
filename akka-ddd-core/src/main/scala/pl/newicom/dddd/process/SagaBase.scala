package pl.newicom.dddd.process

import akka.actor.{ActorLogging, ActorPath}
import akka.contrib.pattern.ReceivePipeline
import akka.persistence.{PersistentActor, AtLeastOnceDelivery}
import org.joda.time.DateTime
import pl.newicom.dddd.actor.GracefulPassivation
import pl.newicom.dddd.aggregate._
import pl.newicom.dddd.messaging.{Deduplication, Message}
import pl.newicom.dddd.messaging.MetaData._
import pl.newicom.dddd.messaging.command.CommandMessage
import pl.newicom.dddd.messaging.event.EventMessage
import pl.newicom.dddd.scheduling.ScheduleEvent

trait SagaBase extends BusinessEntity with GracefulPassivation with PersistentActor
  with AtLeastOnceDelivery with ReceivePipeline with Deduplication with ActorLogging {

  private var _lastEventMessage: Option[EventMessage] = None

  def sagaId = self.path.name

  override def id = sagaId

  override def persistenceId: String = sagaId

  def currentEventMsg: EventMessage = _lastEventMessage.get

  def schedulingOffice: Option[ActorPath] = None

  def sagaOffice: ActorPath = context.parent.path.parent

  def deliverMsg(office: ActorPath, msg: Message): Unit = {
    deliver(office)(deliveryId => {
      msg.withMetaAttribute(DeliveryId, deliveryId)
    })
  }

  def deliverCommand(office: ActorPath, command: Command): Unit = {
    deliverMsg(office, CommandMessage(command).causedBy(currentEventMsg))
  }

  def schedule(event: DomainEvent, deadline: DateTime, correlationId: EntityId = sagaId): Unit = {
    schedulingOffice.fold(throw new UnsupportedOperationException("Scheduling Office is not defined.")) { schOffice =>
      val command = ScheduleEvent("global", sagaOffice, deadline, event)
      deliverMsg(schOffice, CommandMessage(command).withCorrelationId(correlationId))
    }
  }

  protected def acknowledgeEvent(em: Message) {
    val deliveryReceipt = em.deliveryReceipt()
    sender() ! deliveryReceipt
    log.debug(s"Delivery receipt (for received event) sent ($deliveryReceipt)")
  }

  override def messageProcessed(m: Message): Unit = {
    _lastEventMessage = m match {
      case em: EventMessage =>
        Some(em)
      case _ => None
    }
    super.messageProcessed(m)
  }

  override def handleDuplicated(m: Message) =
    acknowledgeEvent(m)

}
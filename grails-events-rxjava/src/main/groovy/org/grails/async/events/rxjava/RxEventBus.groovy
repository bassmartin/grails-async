package org.grails.async.events.rxjava

import grails.async.events.Event
import grails.async.events.subscriber.EventSubscriber
import grails.async.events.trigger.EventTrigger
import grails.async.events.emitter.EventEmitter
import grails.async.events.subscriber.Subjects
import grails.async.events.subscriber.Subscription
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.grails.async.events.bus.AbstractEventBus
import org.grails.async.events.registry.ClosureSubscription
import org.grails.async.events.registry.EventSubscriberSubscription
import rx.Scheduler
import rx.functions.Action1
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.subjects.Subject

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * An EventBus implementation that uses RxJava
 *
 * @author Graeme Rocher
 * @since 3.3
 *
 */
@CompileStatic
@Slf4j
class RxEventBus extends AbstractEventBus {
    protected final Map<CharSequence, PublishSubject> subjects = new ConcurrentHashMap<CharSequence, PublishSubject>().withDefault {
        PublishSubject.create()
    }
    protected final Map<CharSequence, Collection<Subscription>> subscriptions = new ConcurrentHashMap<CharSequence, Collection<Subscription>>().withDefault {
        new ConcurrentLinkedQueue<Subscription>()
    }


    final Scheduler scheduler

    RxEventBus(Scheduler scheduler = Schedulers.io()) {
        this.scheduler = scheduler
    }

    @Override
    Subscription on(CharSequence eventId, Closure subscriber) {
        String eventKey = eventId.toString()
        Subject subject = subjects.get(eventKey)
        return new RxClosureSubscription(eventId, subscriptions, subscriber, subject, scheduler)
    }

    @Override
    Subscription subscribe(CharSequence eventId, EventSubscriber subscriber) {
        String eventKey = eventId.toString()
        Subject subject = subjects.get(eventKey)

        return new RxEventSubscriberSubscription(eventId, subscriptions, subscriber, subject, scheduler)
    }

    @Override
    Subjects unsubscribeAll(CharSequence event) {
        String eventKey = event.toString()
        Collection<Subscription> subs = subscriptions.get(eventKey)
        for(sub in subs) {
            if(!sub.isCancelled()) {
                sub.cancel()
            }
        }
        subs.clear()
        return this
    }

    @Override
    EventEmitter notify(Event event) {
        PublishSubject sub = subjects.get(event.id)
        if(sub.hasObservers() && !sub.hasCompleted()) {
            sub.onNext(event)
        }
        return this
    }

    @Override
    EventEmitter sendAndReceive(Event event, Closure reply) {
        PublishSubject sub = subjects.get(event.id)
        if(sub.hasObservers() && !sub.hasCompleted()) {
            sub.onNext(new EventWithReply(event, reply))
        }
        return this
    }

    private static class RxClosureSubscription extends ClosureSubscription {
        final rx.Subscription subscription

        RxClosureSubscription(CharSequence eventId, Map<CharSequence, Collection<Subscription>> subscriptions, Closure subscriber, Subject subject, Scheduler scheduler) {
            super(eventId, subscriptions, subscriber)
            this.subscription = subject.observeOn(scheduler)
                                       .subscribe( { eventObject ->

                Event event
                Closure reply = null
                if(eventObject  instanceof EventWithReply) {
                    def eventWithReply = (EventWithReply) eventObject
                    event = eventWithReply.event
                    reply = eventWithReply.reply
                }
                else {
                    event = (Event)eventObject
                }

                EventTrigger trigger = buildTrigger(event, reply)
                trigger.proceed()
            }  as Action1, { Throwable t ->
                log.error("Error occurred triggering event listener for event [$eventId]: ${t.message}", t)
            } as Action1<Throwable>)
        }

        @Override
        Subscription cancel() {
            if(!subscription.isUnsubscribed()) {
                subscription.unsubscribe()
            }
            return super.cancel()
        }

        @Override
        boolean isCancelled() {
            return subscription.isUnsubscribed()
        }
    }

    private static class RxEventSubscriberSubscription extends EventSubscriberSubscription {
        final rx.Subscription subscription

        RxEventSubscriberSubscription(CharSequence eventId, Map<CharSequence, Collection<Subscription>> subscriptions, EventSubscriber subscriber, Subject subject, Scheduler scheduler) {
            super(eventId, subscriptions, subscriber)
            this.subscription = subject.observeOn(scheduler)
                    .subscribe( { Event event ->
                EventTrigger trigger = buildTrigger(event)
                trigger.proceed()
            }  as Action1, { Throwable t ->
                log.error("Error occurred triggering event listener for event [$eventId]: ${t.message}", t)
            } as Action1<Throwable>)
        }

        @Override
        Subscription cancel() {
            if(!subscription.isUnsubscribed()) {
                subscription.unsubscribe()
            }
            return super.cancel()
        }

        @Override
        boolean isCancelled() {
            return subscription.isUnsubscribed()
        }
    }
}
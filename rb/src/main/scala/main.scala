import akka.actor._
import akka.dispatch.Dispatcher
import akka.dispatch.verification._
import scala.concurrent.duration._
import akka.dispatch.verification.algorithm._
import scala.collection.mutable.{HashSet, Stack, Queue, MutableList}
import scala.collection.immutable.HashMap
import scala.collection.MapLike
import scala.concurrent.Await
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import scala.annotation.tailrec

// Failure detector messages (the FD is perfect in this case)
final case class Killed (name: String) {}
final case class Started (name: String) {}
final case class GroupMembership (members: Iterable[String]) {}

object Test extends App {
  def verifyState (actors: Array[String], 
                   states: Map[String, BCastState]) : Boolean = {
    var same = true
    for (act <- actors) {
      for (act2 <- actors) {
        if (states(act).messages != states(act2).messages) {
          println(act + "  " + act2 + " differ")
          println(states(act).messages + "   " + states(act2).messages)
          same = false
        }
      }
    }
    same
  }
  val actors = Array("bcast1",
                     "bcast2",
                     "bcast3",
                     "bcast4",
                     "bcast5",
                     "bcast6",
                     "bcast7",
                     "bcast8")
  val state = HashMap[String, BCastState]() ++
              actors.map((_, new BCastState()))

  val trace0 = Array[ExternalEvent]() ++
    actors.map(
      act => Start(Props.create(classOf[ReliableBCast],
            state(act)), act)) ++
    actors.map(Send(_, GroupMembership(actors))) ++ 
    Array[ExternalEvent](
    WaitQuiescence,
    Partition("bcast8", "bcast1"),
    Send("bcast5", Bcast(null, Msg("Foo", 1))),
    Send("bcast8", Bcast(null, Msg("Bar", 2))),
    Partition("bcast8", "bcast2"),
    Partition("bcast8", "bcast3"),
    Partition("bcast8", "bcast4"),
    Partition("bcast8", "bcast5"),
    Partition("bcast8", "bcast6"),
    Partition("bcast8", "bcast7")
  )

  val state1 = HashMap[String, BCastState]() ++
              actors.map((_, new BCastState()))

  val actorProps = HashMap[String, Props]() ++
                   actors.map(name => (name, Props.create(classOf[ReliableBCast],
            state(name))))

  val trace1 = Array[ExternalEvent]() ++
    actors.map(
      act => Start(Props.create(classOf[ReliableBCast],
            state1(act)), act)) ++
    actors.map(Send(_, GroupMembership(actors))) ++ 
    Array[ExternalEvent](
    WaitQuiescence,
    Partition("bcast8", "bcast1"),
    Send("bcast5", Bcast(null, Msg("Foo", 1))),
    Partition("bcast8", "bcast2"),
    Partition("bcast8", "bcast3"),
    Partition("bcast8", "bcast4"),
    Partition("bcast8", "bcast5"),
    Partition("bcast8", "bcast6"),
    Partition("bcast8", "bcast7")
  )
  val removed = Array() ++
                Array(trace0.indexOf(Send("bcast8", Bcast(null, Msg("Bar", 2)))))
                //Array(trace0.indexOf(Send("bcast5", Bcast(null, Msg("Foo", 1)))))

  val sched = new PeekScheduler
  Instrumenter().scheduler = sched
  val events = sched.peek(trace0)
  println("Returned to main with events")
  println("Shutting down")
  sched.shutdown
  println("Shutdown successful")
  verifyState(actors, state) 

  println("Now playing shorter trace")
  val events1 = sched.peek(trace1)
  sched.shutdown
  verifyState(actors, state1)
  for (st <- state.values) {
    st.reset
  }

  val newSched = DependencyGraph.recreateSchedule(trace0, removed, events, events1) 
  val replaySched = new ReplayScheduler()
  Instrumenter().scheduler = replaySched
  replaySched.replay(newSched.toArray, actorProps)
  verifyState(actors, state)
}
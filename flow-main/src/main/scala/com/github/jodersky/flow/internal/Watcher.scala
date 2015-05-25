package com.github.jodersky.flow
package internal

import akka.actor.{ Actor, ActorRef, Props, Terminated }
import java.nio.file.{ ClosedWatchServiceException, FileSystems, Files, Path, Paths, WatchEvent, WatchKey }
import java.nio.file.StandardWatchEventKinds._
import scala.collection.JavaConversions._
import scala.collection.mutable.{ HashMap, Map, MultiMap, Set }
import scala.util.{ Failure, Success, Try }

class Watcher(from: Option[ActorRef]) extends Actor {

  private val watcher = new Watcher.WatcherThread(self)

  //directory -> subscribers
  private val clients: MultiMap[String, ActorRef] = new HashMap[String, Set[ActorRef]] with MultiMap[String, ActorRef]

  //directory -> watchkey
  private val keys: Map[String, WatchKey] = Map.empty

  def subscribe(directory: String, client: ActorRef): WatchKey = {
    val normal = Paths.get(directory).toAbsolutePath
    val index = normal.toString
    val key = keys.getOrElseUpdate(index, watcher.register(normal))
    clients addBinding (index, client)
    key
  }

  def unsubscribe(directory: String, client: ActorRef): Unit = {
    val index = Paths.get(directory).toAbsolutePath.toString

    clients removeBinding (index, sender)

    if (clients.get(index).isEmpty && keys.get(index).isDefined) {
      keys(index).cancel()
      keys -= index
    }
  }

  def reply(msg: Any, sender: ActorRef) = {
    val origin = from match {
      case Some(ref) => ref
      case None => self
    }
    sender.tell(msg, origin)
  }

  override def preStart() = {
    watcher.setDaemon(true)
    watcher.setName("flow-watcher")
    watcher.start()
  }

  def receive = {

    case w @ Serial.Watch(directory, skipInitial) =>
      val normalPath = Paths.get(directory).toAbsolutePath
      val normal = normalPath.toString

      Try {
        subscribe(directory, sender)
      } match {
        case Failure(err) => reply(Serial.CommandFailed(w, err), sender)
        case Success(key) =>
          context watch sender
          if (!skipInitial) {
            Files.newDirectoryStream(normalPath) foreach { path =>
              if (!Files.isDirectory(path)) {
                reply(Serial.Connected(path.toString), sender)
              }
            }
          }
      }

    case u @ Serial.Unwatch(directory) =>
      val normal = Paths.get(directory).toAbsolutePath.toString

      clients.removeBinding(normal, sender)

      if (clients.get(normal).isEmpty && keys.get(normal).isDefined) {
        keys(normal).cancel()
        keys -= normal
      }

    case Terminated(client) =>
      for ((directory, c) <- clients if c == client) {
        unsubscribe(directory, client)
      }

    case Watcher.NewFile(directory, file) =>
      val normal = directory.toAbsolutePath
      val absFile = normal resolve file
      clients.getOrElse(normal.toString, Set.empty) foreach { client =>
        reply(Serial.Connected(absFile.toString), client)
      }

    case ThreadDied(`watcher`, err) => throw err //go down with watcher thread

  }

  override def postStop() = {
    watcher.close()
  }

}

object Watcher {
  private case class NewFile(directory: Path, file: Path)

  private class WatcherThread(actor: ActorRef) extends Thread {

    private val service = FileSystems.getDefault().newWatchService()

    def register(directory: Path) = directory.register(service, ENTRY_CREATE)

    override def run(): Unit = {
      var stop = false
      while (!stop) {
        try {
          val key = service.take()
          key.pollEvents() foreach { ev =>
            val event = ev.asInstanceOf[WatchEvent[Path]]
            if (event.kind == ENTRY_CREATE) {
              val directory = key.watchable().asInstanceOf[Path]
              val file = event.context()
              actor.tell(NewFile(directory, file), Actor.noSender)
            }
          }
          key.reset()
        } catch {
          case _: InterruptedException => stop = true
          case _: ClosedWatchServiceException => stop = true
          case ex: Exception => actor.tell(ThreadDied(this, ex), Actor.noSender)
        }
      }
    }

    def close() = service.close //causes the service to throw a ClosedWatchServiceException
  }

  def apply(from: ActorRef) = Props(classOf[Watcher], Some(from))

}

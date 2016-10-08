package wechat.actors

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer

/**
  * Created by libin on 16/10/8.
  */
object Launcher extends App{

  implicit val system = ActorSystem("wechat")

  implicit val materializer = ActorMaterializer()

  val bootstrap = system.actorOf(Props[Bootstrap])

  Http().bindAndHandle(new RestService(bootstrap).route,"0.0.0.0",8080)
}

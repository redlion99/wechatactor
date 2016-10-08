package wechat.actors

import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server._
import akka.util.Timeout
import akka.pattern.ask
import org.joda.time.DateTime

import scala.util.{Failure, Success}

/**
  * Created by libin on 16/10/8.
  */
class RestService(bootstrap: ActorRef) extends Directives with SprayJsonSupport{

  implicit val timeout = Timeout(10,TimeUnit.SECONDS)
  def route:Route = {
    path("wechat"/IntNumber/"session"){
      tenantId =>
      get{
        onComplete(bootstrap ? ("start", sessionId(tenantId)) ) {
          case Success(qrcode : QrCode) =>
            complete(qrcode.toString)
          case Failure(e) =>
            failWith(e)
        }
      }
    }
  }

  def sessionId(tenantId:Int):String={
    DateTime.now().toString("MMddHH") + (~tenantId)
  }
}

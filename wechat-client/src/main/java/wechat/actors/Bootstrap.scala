package wechat.actors

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.actor.Actor.Receive
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.{Directives, Route}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.joda.time.DateTime
import spray.json.JsObject

import scala.util.{Failure, Success}

/**
  * Created by libin on 16/9/28.
  */


trait LoginRegion extends Actor{

  val numberOfShards = 100

  private val loginHttpPool = new HttpPool("login.weixin.qq.com")(context.system)

  private val wxHttpPool = new HttpPool("wx.qq.com")(context.system)

  def shardId(str:String):String={
    (str.hashCode%100).toString
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case event@(command:String,sessionId:String)=>
      (sessionId.hashCode%numberOfShards).toString
  }
  val extractEntityId: ShardRegion.ExtractEntityId = {
    case event@(command:String,sessionId:String)=>
      (sessionId,event)
  }

  def createRegion(implicit system: ActorSystem):ActorRef={
    ClusterSharding(system).start(
      typeName = "WechatRegion",
      entityProps = Props(new Login(loginHttpPool,Props(new InitActor(wxHttpPool)))),
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId)
  }
}


class Bootstrap extends Actor with LoginRegion{
  System.setProperty("jsse.enableSNIExtension", "false")
  val region = createRegion(context.system)
  override def receive: Receive = {
    case ("start",sessionId:String) =>
      region.forward("start",sessionId)
    case _ =>
  }
}

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


object Launcher extends App{

  implicit val system = ActorSystem("wechat")

  implicit val materializer = ActorMaterializer()

  val bootstrap = system.actorOf(Props[Bootstrap])

  Http().bindAndHandle(new RestService(bootstrap).route,"0.0.0.0",8080)
}

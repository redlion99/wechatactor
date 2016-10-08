package wechat.actors


import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}


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






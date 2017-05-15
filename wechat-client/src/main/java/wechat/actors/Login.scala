package wechat.actors

import akka.actor.{Actor, ActorLogging, PoisonPill, Props, ReceiveTimeout}
import akka.actor.Actor.Receive
import akka.http.scaladsl.model.{HttpHeader, HttpMethods, HttpRequest}
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.RawHeader
import org.joda.time.{DateTime, DateTimeZone}
import spray.json.{JsString, _}

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Failure, Success}
import scala.xml._
import scala.collection.{immutable, mutable}
import scala.concurrent.Future

/**
  * Created by libin on 16/9/28.
  */

case class QrCode(uuid: String) {
  override def toString: String = "https://login.weixin.qq.com/qrcode/" + uuid
}

case class CheckLogin(uuid: String, tip: String = "0", tries: Int = 0)

class Login(val httpPool: HttpPool, props: Props) extends HttpClient with ActorLogging {
  implicit val dispatcher = httpPool.httpDispatcher

  context.setReceiveTimeout(Duration(1,"minute"))

  override def receive: Receive = {
    case ("start", sessionId: String) =>
      val me = self
      val oSender = sender()
      httpPool.sendRequest(
        GetParams("https://login.weixin.qq.com/jslogin"
          , Map("appid" -> "wx782c26e4c19acffb", "fun" -> "new", "lang" -> "zh_CN", "_" -> DateTime.now().getMillis.toString))
        , "login").map(parseTextResponse(_)).filter(_.contains(("window.QRLogin.code", "200"))).map(_.get("window.QRLogin.uuid")).onComplete {
        case Success(Some(uuid)) =>
          me ! QrCode(uuid.slice(1, uuid.length - 1))
          oSender ! QrCode(uuid.slice(1, uuid.length - 1))
        case Failure(e) =>
          log.error(e, "get qrcode failed")
      }
    case qr@QrCode(uuid: String) =>
      log.info(qr.toString)
      context.system.scheduler.scheduleOnce(FiniteDuration(10, "seconds"), self, CheckLogin(uuid))
    case CheckLogin(uuid: String, tip, tries) =>
      httpPool.sendRequest(GetParams("https://login.weixin.qq.com/cgi-bin/mmwebwx-bin/login"
        , Map("tip" -> tip, "uuid" -> uuid, "_" -> DateTime.now().getMillis.toString)), "check")
        .map(parseTextResponse(_)).onComplete {
        case Success(q: Query) if q.contains("window.code" -> "408") =>
          log.info(s" wait for auth $tip $tries ")
          if (tries < 10)
            context.system.scheduler.scheduleOnce(FiniteDuration(1, "seconds"), self, CheckLogin(uuid, tip, tries + 1))
          else
            log.warning("login timeout session stoped")
        case Success(q: Query) if q.contains("window.code" -> "201") =>
          context.system.scheduler.scheduleOnce(FiniteDuration(1, "seconds"), self, CheckLogin(uuid, tip, tries + 1))
        case Success(q: Query) if q.contains("window.code" -> "200") =>
          q.get("window.redirect_uri").foreach {
            url =>
              context.actorOf(props, sessionId) !("start", sessionId, url.slice(1, url.length - 1))
          }
        case Failure(e) =>
          log.error(e, "login check")
          if (tries < 10)
            context.system.scheduler.scheduleOnce(FiniteDuration(1, "seconds"), self, CheckLogin(uuid, tip, tries + 1))
          else
            log.warning("login timeout")
      }
    case ReceiveTimeout =>
      context.children.foreach(_!PoisonPill)
      self ! PoisonPill
    case _ =>
  }

  val sessionId: String = {
    self.path.name
  }
}


case class Contact(userName: String, nickName: String, contactFlag: Int)


case class SessionState(pass_ticket: String = "", skey: String = "", uin: String = "", sid: String = "")

case class ClientState(user: JsObject, clientVersion: String)

case class SyncKey(key: JsObject) {
  override def toString: String = {
    (key.getFields("List") match {
      case Seq(m: JsArray) =>
        m.elements.map(_.asJsObject.getFields("Key", "Val") match {
          case Seq(JsNumber(k), JsNumber(v)) =>
            k.toInt + "_" + v.toInt
        })
    }).mkString("|")
  }
}

case class SyncCheck(syncKey: SyncKey)

case class Sync(syncKey: SyncKey)

class InitActor(val httpPool: HttpPool) extends HttpClient with ActorLogging {
  implicit val dispatcher = httpPool.httpDispatcher
  context.setReceiveTimeout(Duration(1,"minute"))
  var state = SessionState()
  var clientState: Option[ClientState] = None

  var cookieHeaders: immutable.Seq[RawHeader] = immutable.Seq.empty

  var contactListSaved: Map[String, Contact] = Map.empty

  var loginUrl = ""

  val groupToInvite="粉丝俱乐部"

  def baseRequest(): JsObject = {
    JsObject("Uin" -> JsString(state.uin), "Sid" -> JsString(state.sid)
      , "SKey" -> JsString(state.skey), "DeviceID" -> JsString(sessionId))
  }

  override def receive: Receive = {
    case ("start", sessionId: String, url: String) =>
      val me = self
      loginUrl = url
      httpPool.sendRequestRequireCookies(HttpRequest(HttpMethods.GET, url), "login confirm").onComplete {
        case Success((response, cookieOption)) =>
          val xml = XML.loadString(response)
          log.info(xml.toString())
          (xml \\ "ret").text match {
            case "0" =>
              val skey = (xml \\ "skey").text
              val wxsid = (xml \\ "wxsid").text
              val wxuin = (xml \\ "wxuin").text
              val pass_ticket = (xml \\ "pass_ticket").text
              me ! cookieOption.map(new RawHeader("Cookie", _))

              me ! SessionState(pass_ticket, skey, wxuin, wxsid)
            case _ =>
              me ! PoisonPill

          }
        case Failure(e) =>
          log.error(e, "login confirm")

      }
    case cookies: immutable.Seq[RawHeader] =>
      cookieHeaders = cookies
    case s@SessionState(pass_ticket, skey, uid, wxsid) =>
      log.info("state: " + s)
      state = s
      val me = self
      val json = JsObject("BaseRequest" -> baseRequest())
      httpPool.sendRequest(
        PostJson(s"https://wx.qq.com/cgi-bin/mmwebwx-bin/webwxinit?pass_ticket=${state.pass_ticket}&lang=zh_CN&r=${~DateTime.now().getMillis}"
          , json).withHeaders(cookieHeaders), "init").onComplete {
        case Success(response) =>
          val jsObject = response.parseJson.asJsObject
          jsObject.getFields("User", "ContactList", "ClientVersion", "SyncKey") match {
            case Seq(user: JsObject, contactList: JsArray, JsNumber(version), key: JsObject) =>
              me ! ClientState(user, version.toString())
              me ! SyncCheck(SyncKey(key))
              me !("ContactList", contactList)
              getContact
            case x =>
              log.warning(s"unknown $x")
          }
        case Failure(e) =>
          log.error(e, "session init")
      }

    case s: ClientState =>
      log.info("client info: " + s)
      clientState = Some(s)
    case ("ContactList", contactList: JsArray) =>
      log.info("updating contact list")
      contactListSaved = contactListSaved ++ (contactList.elements.map(_.asJsObject.getFields("UserName", "NickName", "ContactFlag") match {
        case Seq(JsString(userName), JsString(nickName), JsNumber(contactFlag)) =>
          (userName, Contact(userName, nickName, contactFlag.toInt))
      }) ++ clientState.map(_.user).map(_.getFields("UserName", "NickName") match {
        case Seq(JsString(userName), JsString(nickName)) =>
          (userName, Contact(userName, nickName, 0))
      })).toMap
    case SyncCheck(syncKey: SyncKey) =>
      context.parent ! "SyncCheck"
      val me = self
      httpPool.sendRequest(GetParams("https://webpush.weixin.qq.com/cgi-bin/mmwebwx-bin/synccheck"
        , Map("r" -> DateTime.now().getMillis.toString, "skey" -> state.skey, "uin" -> state.uin, "sid" -> state.sid, "deviceid" -> sessionId, "synckey" -> syncKey.toString, "_" -> DateTime.now().getMillis.toString))
        .withHeaders(cookieHeaders), "SyncCheck")
        .map(parseTextResponse(_)).onComplete {
        case Success(q: Query) =>
          log.info("window.synccheck = "+q.get("window.synccheck").toString + self.path)
          q.get("window.synccheck").map(x => (x.slice(1, x.length - 1)).split(","))
            .foreach {
              case Array("retcode:\"1101\"", _) =>
                log.warning(s"synccheck failed $q")
                me ! ("start", sessionId, loginUrl)
              case Array(_, "selector:\"0\"") =>
                log.info(s"nothing to get ")
                me ! SyncCheck(syncKey)
              case s =>
                log.info("fetch new messages ")
                me ! Sync(syncKey)
            }

        case Failure(e) =>
          log.error(e.getMessage)
          me ! SyncCheck(syncKey)
      }
    case Sync(syncKey: SyncKey) =>
      val me = self
      val json = JsObject("BaseRequest" -> baseRequest(), "SyncKey" -> syncKey.key, "rr" -> JsNumber(~DateTime.now().getMillis))
      //log.info(json.prettyPrint)
      httpPool.sendRequest(PostJson(s"https://wx.qq.com/cgi-bin/mmwebwx-bin/webwxsync?sid=${state.sid}&skey=${state.skey}&pass_ticket=${state.pass_ticket}&lang=zh_CN&r=${DateTime.now().getMillis.toString}"
        , json).withHeaders(cookieHeaders), "SyncMsg")
        .onComplete {
          case Success(response: String) =>
            response.parseJson.asJsObject.getFields("BaseResponse", "AddMsgList", "SyncKey", "ModContactList") match {
              case x@Seq(base: JsObject, msgList: JsArray, key: JsObject, newContactList:JsArray) =>
                log.info(x.toString())
                contactListSaved = contactListSaved ++ (newContactList.elements.map(_.asJsObject.getFields("UserName", "NickName", "ContactFlag") match {
                  case Seq(JsString(userName), JsString(nickName), JsNumber(contactFlag)) =>
                    (userName, Contact(userName, nickName, contactFlag.toInt))
                }) ++ clientState.map(_.user).map(_.getFields("UserName", "NickName") match {
                  case Seq(JsString(userName), JsString(nickName)) =>
                    (userName, Contact(userName, nickName, 0))
                })).map{
                  case (userName,contact)=>
                    if(!contactListSaved.contains(userName)){
                      log.info("new member added "+userName)
                      contactListSaved.find{case (s,c)=> c.nickName.contains(groupToInvite)} foreach  {
                        case (gid,_) =>
                          log.info(s" invite $userName to $gid")
                          inviteFriendToGroup(userName,gid).onComplete{
                            case Success(x)=>
                              x.map(x=> log.info(x.prettyPrint))
                            case Failure(e)=>
                              log.error(e,"invite friend to group failed")
                          }
                      }
                    }
                    (userName,contact)
                }.toMap
                base.getFields("Ret") match {
                  case Seq(JsNumber(ret)) if ret == 0 =>
                    msgList.elements.map(_.asJsObject.getFields("Content", "FromUserName", "ToUserName")).foreach {
                      case Seq(c@JsString(content), f@JsString(from), t@JsString(to)) =>
                        val msg = (contactListSaved.get(from), contactListSaved.get(to)) match {
                          case (Some(contactFrom), Some(contactTo)) =>
                            JsObject("userName" -> f, "content" -> c, "timestamp" -> JsString(DateTime.now().withZone(DateTimeZone.UTC).toString)
                              , "contactFrom" -> JsString(contactFrom.nickName), "contactTo" -> JsString(contactTo.nickName))
                          case (Some(contactFrom), _) =>
                            JsObject("userName" -> f, "content" -> c, "timestamp" -> JsString(DateTime.now().withZone(DateTimeZone.UTC).toString)
                              , "contactFrom" -> JsString(contactFrom.nickName))
                          case (_, Some(contactTo)) =>
                            JsObject("userName" -> f, "content" -> c, "timestamp" -> JsString(DateTime.now().withZone(DateTimeZone.UTC).toString)
                              , "contactTo" -> JsString(contactTo.nickName))
                          case _ =>
                            JsObject("userName" -> f, "content" -> c, "timestamp" -> JsString(DateTime.now().withZone(DateTimeZone.UTC).toString))
                        }
                        log.info(msg.prettyPrint)

                    }
                    //if(msgList.elements.size>0){
                      me ! SyncCheck(SyncKey(key))
                    //}

                  case _ =>
                }


            }
          case Failure(e) =>
            log.error(e.getMessage)
            me ! SyncCheck(syncKey)
        }
    case ReceiveTimeout =>
      self ! PoisonPill
    case _ =>
  }

  def inviteFriendToGroup(uid:String,gid:String): Future[Option[JsObject]] = {
    val json = JsObject("BaseRequest" -> baseRequest(), "ChatRoomName" -> JsString(gid), "InviteMemberList" -> JsString(uid))
    log.info(json.prettyPrint)
    httpPool.sendRequest(PostJson(s"https://wx.qq.com/cgi-bin/mmwebwx-bin/webwxupdatechatroom?fun=invitemember&pass_ticket=${state.pass_ticket}"
      , json).withHeaders(cookieHeaders), "inviteFriendToGroup").map(_.parseJson.asJsObject.getFields("BaseResponse").headOption.map(_.asJsObject))

  }

  def getContact= {
    val json = JsObject()
    val me = self
    httpPool.sendRequest(PostJson(s"https://wx.qq.com/cgi-bin/mmwebwx-bin/webwxgetcontact?pass_ticket=${state.pass_ticket}&skey=${state.skey}&r=${DateTime.now().getMillis.toString}"
      , json).withHeaders(cookieHeaders), "getContact").map(_.parseJson.asJsObject.getFields("MemberList").headOption.map(_.asInstanceOf[JsArray])).foreach{
      case Some(contactList:JsArray)=>
        me !("ContactList", contactList)
      case _ =>
    }

  }

  val sessionId: String = {
    self.path.name
  }
}


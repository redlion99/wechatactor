package wechat.actors

import akka.actor.{Actor, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import spray.json._

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  * Created by libin on 16/9/28.
  */


class HttpPool(host:String)(implicit val system: ActorSystem){

  implicit val httpDispatcher = system.dispatchers.lookup("http-dispatcher")

  implicit val materializer = ActorMaterializer()



  val poolClientFlow = Http().cachedHostConnectionPoolHttps[String](host)
  def sendRequest(httpRequest:HttpRequest,context:String): Future[String] ={
    println(httpRequest.uri)
    Source.single((httpRequest, context))
      .via(poolClientFlow)
      .runWith(Sink.head).flatMap {
      case (Success(response), x) if response.status.isSuccess() =>
        response.entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.decodeString("UTF-8"))
      case (Success(response), x) =>
        throw new Exception(s"http request failed: *$x* "+ response.status.reason())
      case (Failure(e), x) =>
        throw new Exception(s"http request failed *$x* "+ e.getMessage, e)
    }
  }

  def sendRequestRequireCookies(httpRequest:HttpRequest,context:String): Future[(String,immutable.Seq[String])] ={
    Source.single((httpRequest, context))
      .via(poolClientFlow)
      .runWith(Sink.head).flatMap {
      case (Success(response), x) if response.status.isSuccess() =>

        response.entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.decodeString("UTF-8")).map(x=>(x,response.headers.filter(_.lowercaseName()=="set-cookie").map(_.value())))
      case (Success(response), x) =>
        throw new Exception(s"http request failed: *$x* "+ response.status.reason())
      case (Failure(e), x) =>
        throw new Exception(s"http request failed *$x* "+ e.getMessage, e)
    }
  }



}

trait HttpClient extends Actor{
  val httpPool:HttpPool
  def PostJson(url:String, body:JsObject): HttpRequest ={
    HttpRequest(HttpMethods.POST,uri = url).withEntity(ContentTypes.`application/json`,body.compactPrint.getBytes("UTF-8"))
  }

  def PostParams(url:String, body:Map[String,String]): HttpRequest ={
    val urlEncodedBody = Query(body).toString()
    HttpRequest(HttpMethods.POST,uri = url).withEntity(MediaTypes.`application/x-www-form-urlencoded`.toContentType(HttpCharsets.`UTF-8`), urlEncodedBody)
  }
  def GetParams(url:String, body:Map[String,String]): HttpRequest ={
    val uri = Uri(url).withQuery(Query(body))
    HttpRequest(HttpMethods.POST,uri = uri)
  }

  def parseTextResponse(str:String, schar:String=";"): Query ={
    Query(str.trim.split(schar).map(x => x.splitAt(x.indexOf("="))).map{
      case (k,v) =>
        (k.trim,v.drop(1).trim)
    } toMap)

  }


}


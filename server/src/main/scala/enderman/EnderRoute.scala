package enderman

import akka.actor.ActorSystem
import akka.event.Logging

import scala.concurrent.duration._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ Directive0, Directive1, Route }
import akka.http.scaladsl.server.directives.MethodDirectives.get
import akka.http.scaladsl.server.directives.MethodDirectives.post
import akka.http.scaladsl.server.directives.RouteDirectives.complete
import akka.http.scaladsl.server.directives.PathDirectives.path
import akka.http.scaladsl.model._
import enderman.models.{ ContextScript, repository }
import akka.util.{ ByteString, Timeout }
import java.util.UUID.randomUUID
import java.util.concurrent.TimeUnit

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpEntity.Strict

import scala.util.{ Failure, Success }
import akka.http.scaladsl.model.headers.{ HttpCookie, HttpCookiePair, RawHeader, `Content-Type` }
import akka.stream.ActorMaterializer
import akka.pattern.pipe
import org.bson.types.ObjectId
import spray.json.{ JsArray, JsValue, JsonParser, deserializationError }

import scala.concurrent.{ ExecutionContext, Future }

trait EnderRoute extends JsonSupport {

  implicit def system: ActorSystem
  implicit def materializer: ActorMaterializer
  implicit def ec: ExecutionContext

  lazy val log = Logging(system, classOf[EnderRoute])

  implicit lazy val timeout = Timeout(5.seconds) // usually we'd obtain the timeout from the system's configuration

  private val sessionIdKey = "sessionId"

  private val optionalSessionCookieDirective: Directive1[Option[HttpCookiePair]] =
    optionalCookie(sessionIdKey)

  // check the existence sessionId
  // generate new sessionId if not exist
  private val sessionDirective: Directive1[String] =
    optionalSessionCookieDirective.flatMap {
      case Some(cookie) => provide(cookie.value)
      case None => {
        val newId = randomUUID().toString

        setCookie(HttpCookie(
          sessionIdKey,
          newId,
          expires = Some(DateTime.now + TimeUnit.DAYS.toMillis(365 * 2)),
          domain = Some(".langchao.org"))).tmap(_ => newId)
      };
    }

  private lazy val checkOrigin = Config.config.getString("enderman.trackOrigin")

  private val originHeaderDirective: Directive0 =
    headerValueByName("Origin").flatMap { value =>
      if (value == checkOrigin) {
        pass
      } else {
        log.error("not a request from " + checkOrigin)
        reject
      }
    }

  private val clientInfoDirective: Directive1[models.ClientInfo] =
    sessionDirective.flatMap { sessionId =>
      extractClientIP.flatMap { clientIp =>
        headerValueByName("User-Agent").map { userAgent =>
          models.ClientInfo(
            clientIp.toOption.map(_.getHostAddress).getOrElse("unknown"),
            userAgent,
            sessionId)
        }
      }
    }

  def durationRepo: repository.DurationRepository
  def locationRepo: repository.LocationRepository
  def businessRepo: repository.BusinessRepository
  def contextScriptRepo: repository.ContextScriptRepository

  private def decodeBase64(content: String) =
    new String(java.util.Base64.getDecoder.decode(content))

  lazy val enderRoutes: Route =
    concat(
      pathPrefix("v2land") {
        originHeaderDirective {
          clientInfoDirective { clientInfo =>
            respondWithHeaders(List(
              RawHeader("Access-Control-Allow-Origin", "https://langchao.org"),
              RawHeader("Access-Control-Allow-Credentials", "true"))) {
              concat(
                path("duration") {
                  options {
                    complete("")
                  } ~
                    get {
                      parameters("userId".?, "actionType".as[Int]) { (userIdOpt, actionType) =>
                        val duration = models.Duration(
                          new ObjectId(),
                          actionType,
                          clientInfo.copy(userId = userIdOpt))
                        onComplete(durationRepo.insertOne(duration)) {
                          case Success(_) => complete("")
                          case Failure(e) => {
                            e.printStackTrace()
                            complete(StatusCodes.BadRequest)
                          }
                        }
                      }
                    }
                },
                path("location") {
                  options {
                    complete("")
                  } ~
                    get {
                      parameters("url", "userId".?, "redirectFrom".?, "referrer".?) {
                        (encodedUrl, userIdOpt, redirectFrom, referrer) =>
                          val url = decodeBase64(encodedUrl)
                          val location = models.Location(
                            new ObjectId(),
                            url,
                            redirectFrom.map(decodeBase64),
                            referrer.map(decodeBase64),
                            clientInfo.copy(userId = userIdOpt))
                          onComplete(locationRepo.insertOne(location)) {
                            case Success(_) => complete("")
                            case Failure(e) => {
                              e.printStackTrace()
                              complete(StatusCodes.BadRequest)
                            }
                          }
                      }
                    }
                },
                path("business") {
                  options {
                    complete("")
                  } ~
                    post {
                      entity(as[models.Business]) { business =>
                        onComplete(businessRepo.insertOne(business)) {
                          case Success(_) => complete("")
                          case Failure(e) => {
                            e.printStackTrace()
                            complete(StatusCodes.BadRequest)
                          }
                        }
                      }
                    }
                },
                path("chunk") {
                  options {
                    complete("")
                  } ~
                    post {
                      entity(as[String]) { jsonString =>
                        val jsonAst = JsonParser(jsonString)
                        jsonAst match {
                          case JsArray(elements: Vector[JsValue]) => {
                            val futures = elements.map { chunk =>
                              val obj = chunk.asJsObject("chunk must be a JsObject")
                              val chunkType = obj.fields("type").toString
                              chunkType match {
                                case "duration" =>
                                  durationRepo.insertOne(obj.fields("value").convertTo[models.Duration]);
                                case "location" =>
                                  locationRepo.insertOne(obj.fields("value").convertTo[models.Location]);
                                case "business" =>
                                  businessRepo.insertOne(obj.fields("value").convertTo[models.Business]);
                              }
                            }
                            val finalFuture = Future.sequence(futures)
                            onComplete(finalFuture) {
                              case Success(_) =>
                                complete("")
                              case Failure(e) => {
                                e.printStackTrace()
                                complete(StatusCodes.BadRequest)
                              }
                            }
                          }
                          case _ => deserializationError("Array expected")
                        }
                      }
                    }
                })
            }
          }
        }
      },
      pathPrefix("api") {
        ApiRoute.routes
      },
      pathPrefix("chart") {
        ChartRoute.routes
      },
      path("enderpearl" / Remaining) { filename =>
        val req = Http().singleRequest(
          HttpRequest(uri = s"${Config.staticHost}/js/$filename"))

        onSuccess(req) { resp =>
          complete(resp)
        }
      },
      path("enderpearl.js") {
        onComplete(contextScriptRepo.latestContent) {
          case Success(content) => complete(content)
          case Failure(e) => {
            e.printStackTrace()
            complete(StatusCodes.BadRequest)
          }
        }
      },
      path("public" / Remaining) { pathString =>
        if (pathString.endsWith(".js") || pathString.endsWith(".map")) {
          getFromResource(pathString)
        } else {
          getFromResource("static/" + pathString)
        }
      },
      path("") {
        complete(HttpResponse(
          200,
          entity = Strict(
            ContentType(MediaTypes.`text/html`, HttpCharsets.`UTF-8`),
            ByteString(Page.index))))
      }, {
        getFromResourceDirectory("static")
      })

}

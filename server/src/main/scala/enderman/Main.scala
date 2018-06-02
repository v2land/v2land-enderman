package enderman

import scala.concurrent.{ Await, ExecutionContext }
import scala.concurrent.duration.Duration
import akka.actor.{ ActorSystem, Props }
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import enderman.actors.DailyAnalysis
import enderman.util.DateHelper
import models.repository

import scala.concurrent.duration._

object Main extends App with EnderRoute {

  implicit val system: ActorSystem = ActorSystem("endermanServer")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  lazy val dailyAnalysisActor = system.actorOf(Props[DailyAnalysis], "dailyAnalysis")

  lazy val durationRepo = new repository.DurationRepository(mongo.durationCollection)
  lazy val locationRepo = new repository.LocationRepository(mongo.locationCollection)
  lazy val businessRepo = new repository.BusinessRepository(mongo.businessCollection)
  lazy val contextScriptRepo = new repository.ContextScriptRepository(mongo.contextScriptCollection)

  lazy val routes: Route = enderRoutes

  Http().bindAndHandle(routes, "0.0.0.0", 8080)

  println(s"Server online at http://0.0.0.0:8080/")

  system.scheduler.schedule(
    DateHelper.duration.delayToTomorrow,
    1 days,
    dailyAnalysisActor,
    DailyAnalysis.Tick)

  Await.result(system.whenTerminated, Duration.Inf)

}

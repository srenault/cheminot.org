package controllers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import play.api.mvc._
import play.api.libs.EventSource
import play.api.Play.current
import play.api.data._
import play.api.data.Forms._
import play.api.libs.json._
import cheminotorg._

object Cheminotm extends Controller {

  def init = Common.WithMaybeCtx { implicit request =>
    request.maybeCtx.foreach(ctx => cheminotm.Tasks.shutdown(ctx.sessionId))
    val sessionId = CheminotDB.Id.next
    cheminotm.CheminotcActor.openConnection(sessionId) map {
      case Right(meta) =>
        val result = Ok(meta)
        result.withCookies(Common.Session.create(sessionId))

      case Left(cheminotm.Tasks.Full) =>
        BadRequest(Json.obj("error" -> "full"))

      case _ =>
        BadRequest(Json.obj("error" -> "unknown"))
    }
  }

  def lookForBestTrip = Common.WithCtx { implicit request =>
    Form(tuple(
      "vsId" -> nonEmptyText,
      "veId" -> nonEmptyText,
      "at" -> nonEmptyText,
      "te" -> nonEmptyText,
      "max" -> nonEmptyText
    )).bindFromRequest.fold(
      form => Future successful BadRequest(form.errorsAsJson),
      {
        case (vsId, veId, at, te, max) =>
          cheminotm.CheminotcActor.lookForBestTrip(request.ctx.sessionId, vsId, veId, at.toInt, te.toInt, max.toInt) map {
            case Right(trip) => Ok(trip)
            case Left(cheminotm.Tasks.Full) => BadRequest(Json.obj("error" -> "full"))
            case Left(cheminotm.Tasks.Busy) => BadRequest(Json.obj("error" -> "busy"))
            case _ => BadRequest
          }
      }
    )
  }

  def lookForBestDirectTrip = Common.WithCtx { implicit request =>
    Form(tuple(
      "vsId" -> nonEmptyText,
      "veId" -> nonEmptyText,
      "at" -> nonEmptyText,
      "te" -> nonEmptyText
    )).bindFromRequest.fold(
      form => Future successful BadRequest(form.errorsAsJson),
      {
        case (vsId, veId, at, te) =>
          cheminotm.CheminotcActor.lookForBestDirectTrip(request.ctx.sessionId, vsId, veId, at.toInt, te.toInt) map {
            case Right(trip) => Ok(trip)
            case _ => BadRequest
          }
      }
    )
  }

  def abort = Common.WithCtx { implicit request =>
    cheminotm.CheminotcMonitorActor.abort(request.ctx.sessionId) map { _ =>
      Ok
    }
  }

  def trace = Common.WithCtx { implicit request =>
    cheminotm.CheminotcMonitorActor.trace(request.ctx.sessionId) map {
      case Right(enumerator) => Ok.chunked(enumerator &> EventSource()).as( "text/event-stream")
      case _ => BadRequest
    }
  }

  def stop(id: String) = Common.WithCtx { implicit request =>
    cheminotm.CheminotcMonitorActor.getStop(request.ctx.sessionId, id) map {
      case Right(stop) => Ok(stop)
      case _ => BadRequest
    }
  }

  def appRoot = Action { implicit request =>
    Redirect(routes.Cheminotm.app("index.html"))
  }

  def app(file: String) = Action { implicit request =>
    val f = new java.io.File(Config.cheminotmPath, file)
    val fgz = new java.io.File(Config.cheminotmPath, file + ".gz")
    if(fgz.exists) {
      Ok.sendFile(fgz).withHeaders("Content-Encoding" -> "gzip")
    } else if(f.exists) {
      Ok.sendFile(f, inline = true)
    } else {
      NotFound
    }
  }

  def signout = Common.WithCtx { implicit request =>
    cheminotm.Tasks.shutdown(request.ctx.sessionId)
    Future successful Ok.withNewSession
  }
}

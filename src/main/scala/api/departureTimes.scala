package org.cheminot.web.api

import rapture.json._, jsonBackends.jawn._
import rapture.html._, htmlSyntax._

import org.cheminot.web.{ Params, Config }

object DepartureTimes {

  def renderJson(departureTimes: List[DepartureTime])(implicit config: Config): Json = {
    val json = JsonBuffer.empty
    json.results = DepartureTime.toJsonSeq(departureTimes)
    json.as[Json]
  }

  def renderHtml(departureTimes: List[DepartureTime])(implicit config: Config): HtmlDoc = {
    HtmlDoc {
      Html(
        Head(
          Style(
            """
            table td {
              padding: 10px;
              border: 1px solid;
            }
            """
          )
        ),
        Body(
          H1("Departures"),
          departureTimes.map { departureTime =>
            val minutes = departureTime.at.getMinutes
            val at = DepartureTime.formatMinutes(departureTime.at)
            Section(
              Dl(
                Dt("minutes"),
                Dd(minutes.toString),
                Dt("horaire"),
                Dd(at)
              ),
              Calendar.toHtml(departureTime.calendar),
              Hr
            )
          }
        )
      )
    }
  }
}

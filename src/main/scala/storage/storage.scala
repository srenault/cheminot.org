package org.cheminot.web.storage

import org.joda.time.DateTime
import rapture.json._, jsonBackends.jawn._
import org.cheminot.web.Params
import org.cheminot.misc
import org.cheminot.web.Config

object Storage {

  private val FETCH_TRIPS_MAX_LIMIT = 20

  private val FETCH_TRIPS_DEFAULT_LIMIT = 10

  private def fetch[A](statement: Statement)(f: Row => A)(implicit config: Config): List[A] = {
    val response = Cypher.commit(statement)
    for {
      result <- response.results.as[List[Json]]
      data <- result.data.as[List[Json]]
    } yield {
      f(data.row.as[List[Json]])
    }
  }

  def fetchMeta()(implicit config: Config): Meta = {
    val query = "match p=(s:Meta)-[:HAS]->(m:MetaSubset) return s as Meta, m as MetaSubset;"
    fetch(Statement(query)) { row =>
      val subset = MetaSubset.fromJson(row(1))
      val id = row(0).metaid.as[String]
      val bundleDate = misc.DateTime.fromSecs(row(0).bundledate.as[Long])
      Meta(id, bundleDate, Seq(subset))
    }.groupBy(_.metaid).headOption.flatMap {
      case (_, meta :: rest) => Option(
        meta.copy(subsets = rest.flatMap(_.subsets) ++: meta.subsets)
      )
      case _ => None
    } getOrElse sys.error("Unable to fetch meta")
  }

  private def isParentStation(stationId: String)(implicit config: Config): Boolean = {
    val query = s"match (s:ParentStation {parentstationid: '${stationId}'}) return s;"
    fetch(Statement(query))(identity).headOption.isDefined
  }

  private def fetchTrips(params: Params.FetchTrips, filter: DateTime => String, nextAt: (Seq[Trip], DateTime) => DateTime, sortBy: String)(implicit config: Config): List[Trip] = {

    val l = if(params.limit.exists(_ > FETCH_TRIPS_MAX_LIMIT)) {
      FETCH_TRIPS_MAX_LIMIT
    } else {
      params.limit getOrElse FETCH_TRIPS_DEFAULT_LIMIT
    }

    def f(at: DateTime, limit: Int): List[Trip] = {
      val day = misc.DateTime.forPattern("EEEE").print(at).toLowerCase
      val start = at.withTimeAtStartOfDay.getMillis / 1000
      val end = at.withTimeAtStartOfDay.plusDays(1).getMillis / 1000
      val vsfield = if (isParentStation(params.vs)) "parentid" else "stationid"
      val vefield = if (isParentStation(params.ve)) "parentid" else "stationid"
      val query = s"""
      MATCH path=(trip:Trip)-[:GOES_TO*1..]->(a:Stop { ${vsfield}: '${params.vs}' })-[stoptimes:GOES_TO*1..]->(b:Stop { ${vefield}: '${params.ve}' })
      WITH trip, tail(nodes(path)) AS stops, relationships(path) AS allstoptimes, stoptimes
      OPTIONAL MATCH (trip)-[:SCHEDULED_AT*0..]->(c:Calendar { serviceid: trip.serviceid })
      WITH trip, stops, allstoptimes, head(stoptimes) AS vs, c
      WHERE ${filter(at)}
        AND ((c IS NOT NULL AND (c.${day} = true AND c.startdate <= ${start} AND c.enddate > ${end} AND NOT (trip)-[:OFF]->(:CalendarDate { date: ${start} })))
        OR (trip)-[:ON]->(:CalendarDate { date: ${start} }))
      RETURN distinct(trip), stops, allstoptimes, vs, c
      ORDER BY $sortBy
      LIMIT ${l * 2};
      """

      val trips = fetch(Statement(query)) { row =>
        val tripId = row(0).tripid.as[String]
        val serviceId = row(0).serviceid.as[String]
        val stops = row(1).as[List[Stop]]
        val goesTo = row(2).as[List[Json]].map(GoesTo.fromJson(_, at))
        val maybeCalendar = row(4).as[Option[Json]].map(Calendar.fromJson)
        (tripId, serviceId, goesTo, stops, maybeCalendar)
      }

      val stationIds = trips.flatMap {
        case (_, _, _, stops, _) =>
          stops.map(_.stationid)
      }.distinct

      val stations = fetchStationsById(stationIds).map { station =>
        station.stationid -> station
      }.toMap

      trips.map {
        case (tripId, serviceId, goesTo, stops, maybeCalendar) =>
          val tripStations = stops.flatMap(s => stations.get(s.stationid).toList)
          val stopTimes = goesTo.zip(tripStations).dropWhile {
            case (_, s) => s.stationid != params.vs
          }
          Trip(tripId, serviceId, stopTimes, maybeCalendar)
      }
    }

    scalaz.Scalaz.unfold((params.at, l, 3)) {
      case (at, todo, counter) =>
        if(todo <= 0 || counter <= 0) {
          None
        } else {
          val trips = f(at, todo)
          val distinctTrips = trips.distinct
          val remaining = todo - distinctTrips.size
          val retries = if(trips.isEmpty) counter - 1 else counter
          Option((distinctTrips, (nextAt(trips, at), remaining, retries)))
        }
    }.toList.flatten.take(l)
  }

  private def formatTime(time: DateTime): String =
    org.cheminot.misc.DateTime.minutesOfDay(time).toString

  def fetchPreviousTrips(params: Params.FetchTrips)(implicit config: Config): List[Trip] = {
    val filter = (t: DateTime) => {
      s"vs.departure < ${formatTime(t)}"
    }
    val nextAt = (trips: Seq[Trip], t: DateTime) => {
      val distinctTrips = trips.distinct
      val e = trips.size - distinctTrips.size
      if(e > 0) {
        (for {
          lastTrip <- trips.lastOption
          departure <- lastTrip.stopTimes.lift(1).flatMap(_._1.departure)
        } yield {
          departure.minusMinutes(1)
        }) getOrElse sys.error("Unable to compute nextAt")
      } else {
        params.at.minusDays(1).withTime(23, 59, 59, 999)
      }
    }
    fetchTrips(params, filter = filter, nextAt = nextAt, sortBy = "-vs.departure").reverse
  }

  def fetchNextTrips(params: Params.FetchTrips)(implicit config: Config): List[Trip] = {
    val filter = (t: DateTime) => {
      s"vs.departure > ${formatTime(t)}"
    }
    val nextAt = (trips: Seq[Trip], t: DateTime) => {
      val distinctTrips = trips.distinct
      val e = trips.size - distinctTrips.size
      if(e > 0) {
        (for {
          lastTrip <- trips.lastOption
          departure <- lastTrip.stopTimes.lift(1).flatMap(_._1.departure)
        } yield {
          departure.plusMinutes(1)
        }) getOrElse sys.error("Unable to compute nextAt")
      } else {
        params.at.plusDays(1).withTimeAtStartOfDay
      }
    }
    fetchTrips(params, filter = filter, nextAt = nextAt, sortBy = "vs.departure")
  }

  def fetchStationsById(stationIds: Seq[String])(implicit config: Config): List[Station] = {
    if(!stationIds.isEmpty) {
      val ids = stationIds.map(s => s""""$s"""").mkString(",")
      val query =
        s"""MATCH (station:Station)
          WHERE station.stationid IN [$ids]
          return DISTINCT station;
        """;
      fetch(Statement(query))(_(0).as[Station])
    } else Nil
  }
}

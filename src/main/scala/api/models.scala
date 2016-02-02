package org.cheminot.api

import org.joda.time.format.ISODateTimeFormat
import org.joda.time.DateTime
import org.cheminot.storage
import rapture.json._, jsonBackends.jawn._

case class StopTime(id: String, name: String, lat: Double, lng: Double, arrival: DateTime, departure: Option[DateTime])

case class Trip(id: String, serviceid: String, stopTimes: List[StopTime])

case class ApiEntry(ref: String, buildDate: DateTime, subsets: Seq[Subset])

case class Subset(
  id: String,
  name: String,
  updatedDate: Option[DateTime],
  startDate: Option[DateTime],
  endDate: Option[DateTime]
)

object ApiEntry {

  def apply(m: storage.Meta): ApiEntry = {
    ApiEntry(m.metaid, m.bundledate, m.subsets.map(Subset.apply))
  }
}

object Subset {

  def apply(s: storage.MetaSubset): Subset =
    Subset(s.metasubsetid, s.metasubsetname, s.updateddate, s.startdate, s.enddate)

  def formatDateTime(dateTime: DateTime): String =
    ISODateTimeFormat.dateTime.print(dateTime)

  def toJson(subset: Subset): Json = {
    val json = JsonBuffer.empty

    json.id = subset.id

    subset.updatedDate.foreach { date =>
      json.updatedDate = formatDateTime(date)
    }

    subset.startDate.foreach { date =>
      json.startDate = formatDateTime(date)
    }

    subset.endDate.foreach { date =>
      json.endDate = formatDateTime(date)
    }

    json.as[Json]
  }
}

object Trip {

  private def withDate(at: DateTime, time: Int): DateTime = {
    val (hours, minutes) = {
      val str = time.toString
      str.splitAt(if (str.length > 3) 2 else 1)
    }
    val d = at.withHourOfDay(hours.toInt).withMinuteOfHour(minutes.toInt)
    if(d.isAfter(at)) d else d.plusDays(1)
  }

  def apply(trip: storage.Trip, at: DateTime): Trip = {
    val (goesTo, _) = trip.stopTimes.unzip
    val stopTimes = trip.stopTimes.zipWithIndex.map {
      case ((to, stop), index) =>
        val departure = goesTo.lift(index + 1).flatMap(_.departure)
        StopTime(
          stop.stationid,
          stop.name,
          stop.lat,
          stop.lng,
          withDate(at, to.arrival),
          departure.map(withDate(at, _))
        )
    }
    Trip(trip.tripid, trip.serviceid, stopTimes)
  }

  def toJson(trip: Trip): Json = {
    val json = JsonBuffer.empty
    json.as[Json]
  }
}

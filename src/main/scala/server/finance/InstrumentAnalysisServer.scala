package org.chicagoscala.awse.server.finance
import org.chicagoscala.awse.util.json.JSONMap._
import org.chicagoscala.awse.server._
import org.chicagoscala.awse.server.persistence._
import org.chicagoscala.awse.persistence._
import org.chicagoscala.awse.persistence.inmemory._
import org.chicagoscala.awse.persistence.mongodb._
import org.chicagoscala.awse.domain.finance._
import org.chicagoscala.awse.domain.finance.FinanceJSONConverter._
import se.scalablesolutions.akka.actor._
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.util.Logging
import org.joda.time._
import net.liftweb.json.JsonAST._
import net.liftweb.json.JsonDSL._
import org.chicagoscala.awse.util._
import net.lag.logging.Level

class DataStorageNotAvailable(service: String) extends RuntimeException(
  "Could not get a DataStorageServer for "+service)
  
/**
 * InstrumentAnalysisServer is a worker that calculates (or simply fetches...) statistics for financial instruments.
 * It reads data from and writes results to a DataStorageServer, which it supervises.
 */
class InstrumentAnalysisServer(val service: String) extends Transactor with ActorSupervision with ActorUtil with PingHandler with Logging {
  
  val actorName = "InstrumentAnalysisServer("+service+")"
  
  /**
   * The message handler calls the "pingHandler" first. If it doesn't match on the
   * message (because it is a PartialFunction), then its own "defaultHandler" is tried,
   * and finally "unrecognizedMessageHandler" (from the ActoruUtil trait) is tried.
   */
  def receive = pingHandler orElse defaultHandler orElse unrecognizedMessageHandler

  def defaultHandler: PartialFunction[Any, Unit] = {

    case CalculateStatistics(criteria) => self.reply(helper.calculateStatistics(criteria))
        
    case message => 
      log.debug(actorName + ": unexpected message: " + message)
      self.reply(toJValue(Map("error" -> ("Unexpected message: "+message.toString+". Did you forgot to wrap it in a CalculateStatistics object?"))))
  }
  
  lazy val dataStorageServer = getOrMakeActorFor(service+"_data_storage_server") {
    name => new DataStorageServer(name)
  }
  
  override protected def subordinatesToPing: List[ActorRef] = List(dataStorageServer)
  
  val helper = new InstrumentAnalysisServerHelper(dataStorageServer)
}

/**
 * A separate helper so we can decouple (most of) the actor-specific code and the logic it performs.
 * TODO: Handle instruments and statistics criteria.
 @ param dataStorageServer by-name parameter to make it lazy!
 */
class InstrumentAnalysisServerHelper(dataStorageServer: => ActorRef) {
  
  def calculateStatistics(criteria: CriteriaMap): JValue = criteria match {
    case CriteriaMap(instruments, statistics, start, end) => 
       fetchPrices(instruments, statistics, start, end)
    case _ =>
      Pair("error", "Invalid criteria: " + criteria)
  }

  /**
   * Fetch the instrument prices between the time range. Must make a synchronous call to the data store server
   * becuase clients calling this actor need a synchronous response.
   * TODO: Handle instruments and statistics criteria.
   */
  protected def fetchPrices(
        instruments: List[Instrument], statistics: List[InstrumentStatistic], 
        start: DateTime, end: DateTime): JValue = {
    val startMillis = start.getMillis
    val endMillis   = end.getMillis
    log.info("""dataStorageServer !!! Get(("start" -> """+startMillis+""") ~ ("end" -> """+endMillis+")))")
    (dataStorageServer !! Get(("start" -> startMillis) ~ ("end" -> endMillis))) match {
      case None => 
        Pair("warning", "Nothing returned for query (start, end) = (" + start + ", " + end + ")")
      case Some(result) => 
        formatPriceResults(filter(result), instruments, statistics, startMillis, endMillis)
    }
  }
  
  // TODO: Handle instruments and statistics criteria.
  protected def filter(json: JValue): JValue = json

  protected def formatPriceResults(
      json: JValue, instruments: List[Instrument], statistics: List[InstrumentStatistic], start: Long, end: Long): JValue = {
    val results = json match {
      case JNothing => toJValue(Nil)  // Use an empty array as the result
      case x => x
    }
    val fullResults = toJValue(Map("criteria" -> toNiceFormat(instruments, statistics, start, end), "results" -> results))
    log.info("b: "+fullResults)
    fullResults
  }
  
  // Extract and format the data so it's more convenient for the UI
  protected def toNiceFormat(instruments: List[Instrument], statistics: List[InstrumentStatistic], start: Long, end: Long): Map[String, Any] = 
    Map(
      "instruments" -> Instrument.toSymbolNames(instruments),
      "statistics"  -> statistics,
      "start"       -> start,
      "end"         -> end)
}

package org.chicagoscala.awse.server.persistence
import org.chicagoscala.awse.server._
import org.chicagoscala.awse.persistence._
import org.chicagoscala.awse.persistence.inmemory._
import org.chicagoscala.awse.persistence.mongodb._
import org.chicagoscala.awse.util._
import se.scalablesolutions.akka.actor._
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.stm.Transaction._
import se.scalablesolutions.akka.util.Logging
import net.liftweb.json.JsonAST._
import net.liftweb.json.JsonDSL._
import org.joda.time._

/**
 * DataStorageServer manages storage of time-oriented data, stored as JSON.
 * TODO: Currently, the query capabilities are limited to date-time range queries.
 */
class DataStorageServer(val service: String) extends Actor with PingHandler with Logging {

  val actorName = "DataStoreServer("+service+")"

  protected lazy val dataStore = DataStorageServer.makeDefaultDataStore(service)  

  log.info("Creating: "+actorName)
  
  def receive = defaultHandler orElse pingHandler
  
  def defaultHandler: PartialFunction[Any, Unit] = {

    case Get(criteria) => self.reply(getData(criteria))
            
    case Put(jsonRecord) => self.reply(putData(jsonRecord))

    case Stop => 
      log.info (actorName + ": Received Stop message.")
      self stop

    case x => 
      val message = actorName + ": unknown message received: " + x
      log.info (message)
      self.reply (("error", message))
  }
  
  // TODO: Support other query criteria besides time ranges.
  protected[persistence] def getData(criteria: JValue) = {
    val start = extractTime(criteria, "start", 0)
    val end   = extractTime(criteria, "end",   (new DateTime).getMillis)
    try {
      val data = for {
        json <- dataStore.range(start, end)
      } yield json
      val result = toJSONString(data toList)
      log.debug(actorName + ": GET returning response for start, end, size = " + 
        start + ", " + end + ", " + result.size)
      result
    } catch {
      case th => 
        log.error(actorName + ": Exception thrown: ", th)
        th.printStackTrace
        throw th
    }
  }

  protected[persistence] def putData(jsonRecord: JSONRecord) = {
    log.info(actorName + " PUT: storing JSON: " + jsonShortStr(jsonRecord.toString))
      
    try {
      dataStore.add(jsonRecord)
      Pair("message", "Put received and data storage started.")
    } catch {
      case ex => 
        log.error(actorName + ": PUT: exception thrown while attempting to add JSON to the data store: "+jsonRecord)
        ex.printStackTrace();
        throw ex
    }
  }

  protected def extractTime(json: JValue, key: String, default: => Long): Long = (json \ key) match {
    case JField(key, value) => value match {
      case JInt(millis) => millis.longValue
      case _ => default
    }
    case _ => default
  } 

  protected def toJSONString(data: List[JSONRecord]): String = data.size match {
    case 0 => "{}"
    case _ => compact(render(data reduceLeft { _ ++ _ } json))
  }
  
  private def jsonShortStr(jstr: String) = 
    if (jstr.length > 100) jstr.substring(0,100) + "..." else jstr
}

object DataStorageServer extends Logging {

  import se.scalablesolutions.akka.config.Config.config

  // protected def makeActor(actorName: String): Actor = new DataStorageServer(actorName)

  def getAllDataStorageServers: List[ActorRef] = 
    ActorRegistry.actorsFor(classOf[DataStorageServer]).toList 

  /**
   * Instantiate the default type of datastore: an InMemoryDataStore with an upper limit on values.
   */
  def makeDefaultDataStore(storeName: String): DataStore[JSONRecord] = {
    val db = System.getProperty("app.datastore.type", config.getString("app.datastore.type", "mongodb"))
    if (db.toLowerCase.trim == "mongodb") {
      log.info("Using MongoDB-backed data storage.")
      new MongoDBDataStore(storeName)
    } else {
      log.info("Using in-memory data storage.")
      new InMemoryDataStore[JSONRecord](storeName)
    }
  }
}

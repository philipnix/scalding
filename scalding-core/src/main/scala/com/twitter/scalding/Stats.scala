package com.twitter.scalding

import cascading.stats.{ CascadeStats, CascadingStats }
import cascading.flow.FlowProcess
import cascading.stats.FlowStats

import scala.collection.JavaConverters._

import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.ConcurrentHashMap

case class Stat(name: String, group: String = Stats.ScaldingGroup)(@transient implicit val uniqueIdCont: Job#UniqueID) {
  @transient private lazy val logger: Logger = LoggerFactory.getLogger(this.getClass)
  val uniqueId = uniqueIdCont.get
  lazy val flowProcess: FlowProcess[_] = RuntimeStats.getFlowProcessForUniqueId(uniqueId)

// Use this if a map or reduce phase takes a while before emitting tuples.
  def keepAlive: Unit =
    // We do this in a tight loop, and the var is private, so just be really careful and do null check
    if(null != flowProcess) {
      flowProcess.keepAlive
    }
    else {
      logger.warn("no flowProcess while calling keepAlive")
    }

  def incrBy(amount: Long) =
      if(null != flowProcess) {
        flowProcess.increment(group, name, amount)
        }
      else {
          logger.warn("no flowProcess while calling incrBy")
        }
  def incr = incrBy(1L)

}
/**
 * Wrapper around a FlowProcess useful, for e.g. incrementing counters.
 */
object RuntimeStats extends java.io.Serializable {
  @transient private lazy val logger: Logger = LoggerFactory.getLogger(this.getClass)

  val flowMappingStore = new ConcurrentHashMap[String, FlowProcess[_]]
  def getFlowProcessForUniqueId(uniqueId: String): FlowProcess[_] = {
    val ret = flowMappingStore.get(uniqueId)
    if (ret == null) {
      sys.error("Error in job deployment, the FlowProcess for unique id %s isn't available".format(uniqueId))
    }
    ret
  }

  def addFlowProcess(fp: FlowProcess[_]) = {
    val uniqueId = fp.getProperty("scading.job.uniqueId").asInstanceOf[String]
    logger.debug("Adding flow process id: " + uniqueId)
    flowMappingStore.put(uniqueId, fp)
  }
}

object Stats {
  @transient private lazy val logger: Logger = LoggerFactory.getLogger(this.getClass)
  private var flowStats: Option[FlowStats] = None
  private var cascadeStats: Option[CascadeStats] = None

  // This is the group that we assign all custom counters to
  val ScaldingGroup = "Scalding Custom"

  def setFlowStats(fs: FlowStats) = flowStats = Some(fs)
  def setCascadeStats(cs: CascadeStats) = cascadeStats = Some(cs)

  private[this] def statsClass: Option[CascadingStats] = (cascadeStats, flowStats) match {
    case (Some(_), _) => cascadeStats
    case (_, Some(_)) => flowStats
    case _ => None
  }

  // When getting a counter value, cascadeStats takes precedence (if set) and
  // flowStats is used after that. Returns None if neither is defined.
  def getCounterValue(counter: String, group: String = ScaldingGroup): Option[Long] =
    statsClass.map { _.getCounterValue(ScaldingGroup, counter) }

  // Returns a map of all custom counter names and their counts.
  def getAllCustomCounters: Map[String, Long] = {
    val counts = for {
      s <- statsClass.toSeq
      counter <- s.getCountersFor(ScaldingGroup).asScala
      value <- getCounterValue(counter).toSeq
    } yield (counter, value)
    counts.toMap
  }
}
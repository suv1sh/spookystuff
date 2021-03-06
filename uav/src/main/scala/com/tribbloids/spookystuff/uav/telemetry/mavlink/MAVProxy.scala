package com.tribbloids.spookystuff.uav.telemetry.mavlink

import com.tribbloids.spookystuff.session.ConflictDetection
import com.tribbloids.spookystuff.uav.UAVConf
import com.tribbloids.spookystuff.utils.CommonUtils
import com.tribbloids.spookystuff.utils.lifespan.{Lifespan, LocalCleanable}

import scala.collection.mutable.ArrayBuffer
import scala.util.Try

object MAVProxy {

  val _OPTIONS = Seq(
    "--state-basedir=temp",
    "--daemon",
    "--default-modules=\"link\""
  )

  //only use reflection to find UNIXProcess.pid.
  @throws[NoSuchFieldException]
  @throws[IllegalAccessException]
  private def findPid(process: Process) = {
    var pid = -1L
    if (process.getClass.getName == "java.lang.UNIXProcess") {
      val f = process.getClass.getDeclaredField("pid")
      f.setAccessible(true)
      pid = f.getLong(process)
      f.setAccessible(false)
    }
    pid
  }

  private def doClean(p: Process, pid: Long) = {
    Try {
      p.destroy()
    }
      .recoverWith {
        case e: Throwable =>
          Try {
            p.destroyForcibly()
          }
      }
      .recover {
        case e: Throwable =>
          if (pid > -1) {
            Runtime.getRuntime.exec("kill -SIGINT " + pid)
          }
          else {
            p.destroyForcibly()
          }
      }
  }
}

/**
  * MAVProxy: https://github.com/ArduPilot/MAVProxy
  * outlives any python driver
  * not to be confused with dsl.WebProxy
  * CAUTION: each MAVProxy instance contains 2 python processes, keep that in mind when debugging
  */
//TODO: MAVProxy supports multiple master for multiple telemetry backup
case class MAVProxy(
                     master: String,
                     outs: Seq[String], //first member is always used by DK.
                     baudRate: Int,
                     ssid: Int = UAVConf.PROXY_SSID,
                     name: String
                   )
  extends LocalCleanable
    with ConflictDetection {

  assert(!outs.contains(master))

  override lazy val _resourceIDs = Map(
    "master" -> Set(master),
    "firstOut" -> outs.headOption.toSet //need at least 1 out for executor
  )

  override def _lifespan = new Lifespan.JVM(
    nameOpt = Some(this.getClass.getSimpleName)
  )

  @transient var _process_pid: (Process, Long) = _

  def process_pidOpt: Option[(Process, Long)] = Option(_process_pid).flatMap {
    v =>
      if (!v._1.isAlive) {
        MAVProxy.doClean(v._1, v._2)
        _process_pid = null
        None
      }
      else Some(v)
  }

  val commandStrs = {
    val MAVPROXY = sys.env.getOrElse("MAVPROXY_CMD", "mavproxy.py")

    val strs = new ArrayBuffer[String]()
    strs append MAVPROXY
    strs append s"--master=$master"
    for (out <- outs) {
      strs append s"--out=$out"
    }
    strs append s"--baudrate=$baudRate"
    strs append s"--source-system=$ssid"
    strs appendAll MAVProxy._OPTIONS

    //    LoggerFactory.getLogger(classOf[Proxy]).info(strs.mkString(" "))
    strs
  }

  def open(): Unit = {
    process_pidOpt.getOrElse {
      CommonUtils.retry(2, 1000) {
        _doOpen()
      }
    }
    Thread.sleep(2000)
    assert(process_pidOpt.nonEmpty, "MAVProxy is terminated! perhaps due to non-existing master URI")
  }

  def _doOpen(): Unit = {
    val builder = new ProcessBuilder(commandStrs: _*)
    builder.redirectErrorStream(true)
    val process = builder.start

    val pid = try
      MAVProxy.findPid(process)
    catch {
      case e: Exception =>
        -1
    }
    _process_pid = process -> pid
  }

  def closeProcess(): Unit = {

    process_pidOpt.foreach {
      v =>
        MAVProxy.doClean(v._1, v._2)
        _process_pid = null
    }

    assert(process_pidOpt.isEmpty)
  }

  override protected def cleanImpl(): Unit = {
    closeProcess()
  }
}

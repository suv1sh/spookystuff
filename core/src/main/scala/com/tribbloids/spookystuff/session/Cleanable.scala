package com.tribbloids.spookystuff.session

import com.tribbloids.spookystuff.caching._
import com.tribbloids.spookystuff.utils.{IDMixin, NOTSerializable}
import org.apache.spark.TaskContext
import org.openqa.selenium.NoSuchSessionException
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.language.implicitConversions

class Lifespan extends IDMixin with Serializable {

  {
    _id //always getID on construction
  }

  def nameOpt: Option[String] = None
  def name = nameOpt.getOrElse {
    _id match {
      case Left(v) => "Task-" + v
      case Right(v) => "Thread-" + v
    }
  }

  {
    if (!Cleanable.uncleaned.contains(_id)) {
      addCleanupHook {
        () =>
          Cleanable.cleanSweep(_id)
      }
    }
  }

  type ID = Either[Long, Long]

  def isTask = _id.isLeft
  def isThread = _id.isRight

  def getID: Either[Long, Long] = Option(TaskContext.get()) match {
    case Some(tc) =>
      Lifespan.taskID(tc)
    case None =>
      Lifespan.threadID
  }

  @transient lazy val _id = {
    getID
  }

  def readObject(in: java.io.ObjectInputStream): Unit = {
    in.defaultReadObject()
    _id
  }

  override def toString = name

  def addCleanupHook(fn: () => Unit): Unit = {
    _id match {
      case Left(_) =>
        TaskContext.get().addTaskCompletionListener {
          tc =>
            fn()
        }
      case Right(_) =>
        sys.addShutdownHook {
          fn()
        }
    }
  }
}

object Lifespan {

  def taskID(tc: TaskContext): Left[Long, Long] = {
    Left(tc.taskAttemptId)
  }

  def threadID: Right[Long, Long] = {
    Right(Thread.currentThread().getId)
  }

  //CAUTION: keep the empty constructor! Kryo deserializer use them to initialize object
  case class Auto(override val nameOpt: Option[String]) extends Lifespan {
    def this() = this(None)
  }

  case class Task(override val nameOpt: Option[String]) extends Lifespan {
    def this() = this(None)

    require(_id.isLeft, "Not inside any Spark Task")
  }

  case class JVM(override val nameOpt: Option[String]) extends Lifespan {
    def this() = this(None)

    override def getID: ID = threadID
  }

  case class Custom(threadID: Long = 0, override val nameOpt: Option[String] = None) extends Lifespan {
    def this() = this(0, None)

    override def getID: ID = Right(threadID)

    override def addCleanupHook(fn: () => Unit): Unit = {}
  }
}

sealed trait AbstractCleanable {

  final protected val defaultLifespan = new Lifespan.JVM()

  LoggerFactory.getLogger(this.getClass).debug(s"$logPrefix Creating")

  //each can only be cleaned once
  @volatile var isCleaned: Boolean = false

  def logPrefix: String

  //  private object CleanupLock
  //avoid double cleaning, this lock is not shared with any other invocation, PARTICULARLY subclasses
  def clean(silent: Boolean = false): Unit = {
    if (!isCleaned){
      isCleaned = true
      cleanImpl()
      if (!silent) LoggerFactory.getLogger(this.getClass).info(s"$logPrefix Cleaned up")
    }
  }

  protected def cleanImpl(): Unit

  def tryClean(silent: Boolean = false): Unit = {
    try {
      clean(silent)
    }
    catch {
      case e: NoSuchSessionException => //already cleaned before
      case e: Throwable =>
        val ee = e
        LoggerFactory.getLogger(this.getClass).warn(
          s"$logPrefix !!! FAIL TO CLEAN UP !!!\n" + ee
        )
    }
    finally {
      super.finalize()
    }
  }

  override protected def finalize() = tryClean(false)
}

/**
  * This is a trait that unifies resource cleanup on both Spark Driver & Executors
  * instances created on Executors are cleaned by Spark TaskCompletionListener
  * instances created otherwise are cleaned by JVM shutdown hook
  * finalizer helps but is not always reliable
  * can be serializable, but in which case implementation has to allow deserialized copy on a different machine to be cleanable as well.
  */
trait Cleanable extends AbstractCleanable {

  /**
    * taskOrThreadOnCreation is incorrect in withDeadline or threads not created by Spark
    * Override this to correct such problem
    */
  def lifespan: Lifespan = defaultLifespan
  lifespan //initialize lazily

  uncleanedInBatch += this

  override def logPrefix = {
    s"${lifespan.toString} \t| "
  }

  @transient lazy val uncleanedInBatch: ConcurrentSet[Cleanable] = {
    // This weird implementation is to mitigate thread-unsafe competition: 2 empty Set being inserted simultaneously
    Cleanable.uncleaned
      .getOrElseUpdate(
        lifespan._id,
        Cleanable.synchronized{
          Cleanable.uncleaned.getOrElse(
            lifespan._id,
            ConcurrentSet()
          )
        }
      )
  }

  override def clean(silent: Boolean): Unit = {
    super.clean(silent)
    uncleanedInBatch -= this
  }
}

object Cleanable {

  val uncleaned: ConcurrentMap[Lifespan#ID, ConcurrentSet[Cleanable]] = ConcurrentMap()

  def getByLifespan(tt: Lifespan#ID, condition: (Cleanable) => Boolean): (ConcurrentSet[Cleanable], List[Cleanable]) = {
    val set = uncleaned.getOrElse(tt, mutable.Set.empty)
    val filtered = set.toList
      .filter(condition)
    (set, filtered)
  }
  def getAll(condition: (Cleanable) => Boolean): Seq[Cleanable] = {
    uncleaned
      .keys.toSeq
      .flatMap {
        tt =>
          getByLifespan(tt, condition)._2
      }
  }

  // cannot execute concurrent
  def cleanSweep(tt: Lifespan#ID, condition: Cleanable => Boolean = _ => true) = {
    val (set: ConcurrentSet[Cleanable], filtered: List[Cleanable]) = getByLifespan(tt, condition)
    filtered
      .foreach {
        instance =>
          instance.tryClean()
      }
    set --= filtered
    if (set.isEmpty) uncleaned.remove(tt)
  }

  def cleanSweepAll(
                     condition: Cleanable => Boolean = _ => true
                   ) = {

    uncleaned
      .keys
      .foreach {
        tt =>
          cleanSweep(tt, condition)
      }
  }
}

trait LocalCleanable extends Cleanable with NOTSerializable
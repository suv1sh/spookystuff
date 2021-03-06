package com.tribbloids.spookystuff

import com.tribbloids.spookystuff.conf._
import com.tribbloids.spookystuff.doc.{Doc, Unstructured}
import com.tribbloids.spookystuff.dsl.DriverFactory
import com.tribbloids.spookystuff.execution.SpookyExecutionContext
import com.tribbloids.spookystuff.extractors.{Alias, GenExtractor, GenResolved}
import com.tribbloids.spookystuff.row.{SpookySchema, SquashedFetchedRow, TypedField}
import com.tribbloids.spookystuff.session.CleanWebDriver
import com.tribbloids.spookystuff.testutils.{FunSpecx, RemoteDocsFixture, TestHelper}
import com.tribbloids.spookystuff.utils.lifespan.{Cleanable, Lifespan}
import com.tribbloids.spookystuff.utils.{CommonConst, CommonUtils, RetryFixedInterval}
import org.apache.spark.SparkContext
import org.apache.spark.sql.SQLContext
import org.jutils.jprocesses.JProcesses
import org.jutils.jprocesses.model.ProcessInfo
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, Retries}

import scala.language.implicitConversions

object SpookyEnvFixture {
  //  def cleanDriverInstances(): Unit = {
  //    CleanMixin.unclean.foreach {
  //      tuple =>
  //        tuple._2.foreach (_.finalize())
  //        assert(tuple._2.isEmpty)
  //    }
  //  }

  @volatile var firstRun: Boolean = true

  def shouldBeClean(
                     spooky: SpookyContext,
                     pNames: Seq[String]
                   ): Unit = {

    instancesShouldBeClean(spooky)
    processShouldBeClean(pNames)
  }

  def instancesShouldBeClean(spooky: SpookyContext): Unit = {

    Cleanable
      .uncleaned
      .foreach {
        tuple =>
          val nonLocalDrivers = tuple._2.values
            .filter {
              v =>
                v.lifespan.tpe == Lifespan.Task
            }
          assert(
            nonLocalDrivers.isEmpty,
            s": ${tuple._1} is unclean! ${nonLocalDrivers.size} left:\n" + nonLocalDrivers.mkString("\n")
          )
      }
  }

  /**
    * slow
    */
  def processShouldBeClean(
                            names: Seq[String] = Nil,
                            keywords: Seq[String] = Nil,
                            cleanSweepNotInTask: Boolean = true
                          ): Unit = {

    if (cleanSweepNotInTask) {
      //this is necessary as each suite won't automatically cleanup drivers NOT in task when finished
      Cleanable.cleanSweepAll (
        condition = {
          case v if !v.lifespan.isTask => true
          case _ => false
        }
      )
    }


    import scala.collection.JavaConverters._

    val processes: Seq[ProcessInfo] = RetryFixedInterval(5, 1000) {
      JProcesses.getProcessList()
        .asScala
    }

    names.foreach {
      name =>
        val matchedProcess = processes.filter(v => v.getName == name)
        assert(
          matchedProcess.isEmpty,
          s"${matchedProcess.size} $name process(es) left:\n" + matchedProcess.mkString("\n")
        )
    }

    keywords.foreach {
      keyword =>
        val matchedProcess = processes.filter(v => v.getCommand.contains(keyword))
        assert(
          matchedProcess.isEmpty,
          s"${matchedProcess.size} $keyword process(es) left:\n" + matchedProcess.mkString("\n")
        )
    }
  }
}

trait SpookyEnv {

  def sc: SparkContext = TestHelper.TestSC
  def sql: SQLContext = TestHelper.TestSQL

  lazy val driverFactory: DriverFactory[CleanWebDriver] = SpookyConf.TEST_WEBDRIVER_FACTORY

  @transient lazy val spookyConf = new SpookyConf(
    webDriverFactory = driverFactory
  )
  var _spooky: SpookyContext = _
  def spooky = {
    Option(_spooky)
      .getOrElse {
        val result: SpookyContext = reloadSpooky
        result
      }
  }

  def reloadSpooky: SpookyContext = {
    val sql = this.sql
    val result = new SpookyContext(sql, spookyConf)
    _spooky = result
    result
  }
}

abstract class SpookyEnvFixture
  extends FunSpecx
    with SpookyEnv
    with RemoteDocsFixture
    with BeforeAndAfter
    with BeforeAndAfterAll
    with Retries {

  //  val startTime = System.currentTimeMillis()

  def parallelism: Int = sc.defaultParallelism

  lazy val defaultEC = SpookyExecutionContext(spooky)
  lazy val defaultSchema = SpookySchema(defaultEC)

  def emptySchema = SpookySchema(SpookyExecutionContext(spooky))

  implicit def withSchema(row: SquashedFetchedRow): SquashedFetchedRow#WSchema = row.WSchema(emptySchema)
  implicit def extractor2Resolved[T, R](extractor: Alias[T, R]): GenResolved[T, R] = GenResolved(
    extractor.resolve(emptySchema),
    TypedField(
      extractor.field,
      extractor.resolveType(emptySchema)
    )
  )
  implicit def extractor2Function[T, R](extractor: GenExtractor[T, R]): PartialFunction[T, R] = extractor.resolve(emptySchema)
  implicit def doc2Root(doc: Doc): Unstructured = doc.root

  override def withFixture(test: NoArgTest) = {
    if (isRetryable(test))
      CommonUtils.retry(4) { super.withFixture(test) }
    else
      super.withFixture(test)
  }

  import com.tribbloids.spookystuff.utils.SpookyViews._

  val processNames = Seq("phantomjs", "python")

  override def beforeAll(): Unit = if (SpookyEnvFixture.firstRun){

    val spooky = this.spooky
    val processNames = this.processNames
    super.beforeAll()
    sc.foreachComputer {
      SpookyEnvFixture.shouldBeClean(spooky, processNames)
    }
    SpookyEnvFixture.firstRun = false
  }

  override def afterAll() {

    val spooky = this.spooky
    val processNames = this.processNames
    TestHelper.clearTempDirs()

    //unpersist all RDDs, disabled to better detect memory leak
    //    sc.getPersistentRDDs.values.toList.foreach {
    //      _.unpersist()
    //    }

    sc.foreachComputer {
      SpookyEnvFixture.shouldBeClean(spooky, processNames)
    }
    super.afterAll()
  }

  before{
    // bypass java.lang.NullPointerException at org.apache.spark.broadcast.TorrentBroadcast$.unpersist(TorrentBroadcast.scala:228)
    // TODO: clean up after fix
    CommonUtils.retry(3, 1000) {
      setUp()
    }
  }

  after{
    tearDown()
  }

  def setUp(): Unit = {
    //    SpookyEnvFixture.cleanDriverInstances()
    spooky._configurations = submodules
    spooky.spookyMetrics.zero()
    spooky.rebroadcast()
  }

  def submodules: Submodules[AbstractConf] = {
    Submodules(
      new SpookyConf(
        autoSave = true,
        cacheWrite = false,
        cacheRead = false
      ),
      DirConf(
        root = CommonUtils.\\\(CommonConst.TEMP_DIR, "spooky-unit")
      )
    )
  }

  def tearDown(): Unit = {
    val spooky = this.spooky
    sc.foreachComputer {
      SpookyEnvFixture.instancesShouldBeClean(spooky)
    }
  }
}
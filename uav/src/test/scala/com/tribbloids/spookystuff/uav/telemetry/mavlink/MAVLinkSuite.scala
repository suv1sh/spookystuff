package com.tribbloids.spookystuff.uav.telemetry.mavlink

import com.tribbloids.spookystuff.session.Session
import com.tribbloids.spookystuff.testutils.TestHelper
import com.tribbloids.spookystuff.uav.LinkDepletedException
import com.tribbloids.spookystuff.uav.dsl.{LinkFactories, LinkFactory}
import com.tribbloids.spookystuff.uav.sim.APMQuadFixture
import com.tribbloids.spookystuff.uav.system.UAV
import com.tribbloids.spookystuff.uav.telemetry.{Dispatcher, Link, SimLinkSuite}
import com.tribbloids.spookystuff.utils.SpookyUtils
import org.apache.spark.rdd.RDD
import org.scalatest.Ignore

/**
  * Created by peng on 27/01/17.
  */
class MAVLinkSuite extends SimLinkSuite with APMQuadFixture {

  override lazy val factories: Seq[LinkFactory] = Seq(
    LinkFactories.Direct(),
    LinkFactories.ForkToGCS()
  )

  runTests(factories.filter(_.isInstanceOf[LinkFactories.Direct])) {
    spooky =>
      it("should use first drone uri as primary endpoint") {
        val linkRDD = getLinkRDD(spooky).asInstanceOf[RDD[MAVLink]]
        val connStr_URIs = linkRDD.map {
          link =>
            link.Endpoints.direct.uri -> link.Endpoints.primary.uri
        }
          .collect()

        val expectedURIs = (0 until parallelism).map {
          i =>
            val port = i * 10 + 5760
            val uri = s"tcp:localhost:$port"
            uri -> uri
        }

        connStr_URIs.mkString("\n").shouldBe (
          expectedURIs.mkString("\n"),
          sort = true
        )
        assert(connStr_URIs.length == connStr_URIs.distinct.length)
      }
  }

  runTests(factories.filter(_.isInstanceOf[LinkFactories.ForkToGCS])) {
    spooky =>

      it("should use first proxy out as primary endpoint") {

        val linkRDD = getLinkRDD(spooky).asInstanceOf[RDD[MAVLink]]
        val uris = linkRDD.map {
          link =>
            val firstOut = link.proxyOpt.get.outs.head
            val uri = link.Endpoints.primary.uri
            firstOut -> uri
        }
          .collect()
        uris.foreach {
          tuple =>
            assert(tuple._1 == tuple._2)
        }
      }

      it("Proxy should have different output") {

        val linkRDD = getLinkRDD(spooky).asInstanceOf[RDD[MAVLink]]
        val outs = linkRDD.map {
          link =>
            link.proxyOpt.get.outs.mkString(",")
        }
          .collect()

        val expectedOuts = (0 until parallelism).map {
          _ =>
            val uris = List("udp:localhost:......", "udp:localhost:14550").mkString(",")
            uris
        }

        outs.mkString("\n").shouldBeLike(
          expectedOuts.mkString("\n"),
          sort = true
        )
        assert(outs.distinct.length == parallelism, "Duplicated URIs:\n" + outs.mkString("\n"))
      }

      it("connection to non-existing drone should cause Proxy to fail early") {

        val session = new Session(spooky)
        val drone = UAV(Seq("dummy"))
        TestHelper.setLoggerDuring(classOf[Link], classOf[MAVLink], SpookyUtils.getClass) {
          intercept[LinkDepletedException]{
            Dispatcher(
              Seq(drone),
              session
            )
              .get
          }

          val badLink = Link.registered(drone).asInstanceOf[MAVLink]
          //          val driver = badLink.proxyOpt.get.PY.driver
          //          print(driver.historyCodeOpt.get)
          //          assert(badLink.Endpoints.primary._driver == null,
          //            "endpoint should not have driver\n" + Option(badLink.Endpoints.primary._driver).flatMap(_.historyCodeOpt).orNull)
        }
      }
  }
}

//@Ignore
class MAVLinkSuite_Direct extends MAVLinkSuite {
  override lazy val factories: Seq[LinkFactory] = Seq(
    LinkFactories.Direct()
  )
}

//@Ignore
class MAVLinkSuite_GCS extends MAVLinkSuite {
  override lazy val factories: Seq[LinkFactory] = Seq(
    LinkFactories.ForkToGCS()
  )
}

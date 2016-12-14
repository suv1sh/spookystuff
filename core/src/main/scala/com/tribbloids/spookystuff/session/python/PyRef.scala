
package com.tribbloids.spookystuff.session.python

import com.tribbloids.spookystuff.session._
import com.tribbloids.spookystuff.utils.SpookyUtils
import com.tribbloids.spookystuff.{SpookyContext, caching}
import org.apache.spark.ml.dsl.utils._

import scala.language.dynamics

trait PyRef extends Cleanable {

  // the following are only used by non-singleton subclasses
  def className = this.getClass.getCanonicalName

  /**
    * assumes that a Python class is always defined under pyspookystuff.
    */
  lazy val pyClassNameParts: Seq[String] = {
    (
      "py" +
        className
          .split('.')
          .slice(2, Int.MaxValue)
          .filter(_.nonEmpty)
          .mkString(".")
      )
      .split('.')
  }

  @transient lazy val _driverToBindings: caching.ConcurrentMap[PythonDriver, PyBinding] = {
    caching.ConcurrentMap()
  }

  def driverToBindings = {
    val deadDrivers = _driverToBindings.keys.filter(_.isCleaned)
    _driverToBindings --= deadDrivers
    _driverToBindings
  }

  def bindings = driverToBindings.values

  def imports: Seq[String] = Seq(
    "import simplejson as json"
  )

  def createOpt: Option[String] = None
  def referenceOpt: Option[String] = None

  // run on each driver
  // TODO: DO NOT override this, use __del__() in python implementation as much as you can so it will be called by python interpreter shutdown hook
  def delOpt: Option[String] = if (createOpt.nonEmpty) {
    referenceOpt.map(v => s"del($v)")
  }
  else {
    None
  }

  def dependencies: Seq[PyRef] = Nil // has to be initialized before calling the constructor

  def lzy: Boolean = true //set to false to enable immediate PyBinding initialization

  def converter: PyConverter = PyConverter.JSON

  def pyClassName: String = pyClassNameParts.mkString(".")
  def simpleClassName = pyClassNameParts.last
  def varNamePrefix = FlowUtils.toCamelCase(simpleClassName)
  def packageName = pyClassNameParts.slice(0, pyClassNameParts.length - 1).mkString(".")

  protected def cleanImpl() = {
    bindings.foreach {
      _.tryClean()
    }
  }

  def _Py(
           driver: PythonDriver,
           spookyOpt: Option[SpookyContext] = None
         ): PyBinding = {

    driverToBindings.getOrElse(
      driver,
      new PyBinding(this, driver, spookyOpt)
    )
  }

  def Py(session: Session): PyBinding = {
    _Py(session.pythonDriver, Some(session.spooky))
  }

  def scratchDriver[T](fn: PythonDriver => T): T = {
    bindings
      .headOption
      .map {
        binding =>
          fn(binding.driver)
      }
      .getOrElse {
        val driver = new PythonDriver()
        val result = fn(driver)
        driver.tryClean()
        result
      }
  }

  def bindingCleaningHook(pyBinding: PyBinding): Unit = {}
}

/**
  * bind to a session
  * may be necessary to register with PythonDriver shutdown listener
  */
class PyBinding (
                  val ref: PyRef,
                  val driver: PythonDriver,
                  val spookyOpt: Option[SpookyContext]
                ) extends Dynamic with LocalCleanable {

  import ref._

  {
    dependencies.foreach {
      dep =>
        dep._Py(driver, spookyOpt)
    }

    driver.batchImport(imports)

    val initOpt = createOpt.map {
      create =>
        (referenceOpt.toSeq ++ Seq(create)).mkString("=")
    }

    initOpt.foreach {
      code =>
        if (lzy) driver.lazyInterpret(code)
        else driver.interpret(code)
    }

    ref.driverToBindings += driver -> this
  }

  def strOpt: Option[String] = {
    referenceOpt.flatMap {
      ref =>
        val tempName = "_temp" + SpookyUtils.randomSuffix
        val result = driver.eval(
          s"""
             |$tempName=$ref
            """.trim.stripMargin,
          Some(tempName),
          spookyOpt
        )
        result._2
    }
  }

  def pyCallMethod(methodName: String)(py: (Seq[PyRef], String)): PyBinding = {

    val refName = methodName + SpookyUtils.randomSuffix
    val callPrefix: String = referenceOpt.map(v => v + ".").getOrElse("")

    val result = DetachedRef(
      createOpt = Some(s"$callPrefix$methodName${py._2}"),
      referenceOpt = Some(refName),
      dependencies = py._1,
      converter = converter
    )
      ._Py(
        driver,
        spookyOpt
      )

    result
  }

  def selectDynamic(fieldName: String) = {
    pyCallMethod(fieldName)(Nil -> "")
  }
  def applyDynamic(methodName: String)(args: Any*) = {
    pyCallMethod(methodName)(converter.args2Ref(args))
  }
  def applyDynamicNamed(methodName: String)(kwargs: (String, Any)*) = {
    pyCallMethod(methodName)(converter.kwargs2Ref(kwargs))
  }

  /**
    * chain to all bindings with active drivers
    */
  override protected def cleanImpl(): Unit = {
    if (!driver.isCleaned) {
      delOpt.foreach {
        code =>
          driver.interpret(code, spookyOpt)
      }
    }

    driverToBindings.remove(this.driver)

    bindingCleaningHook(this)
  }
}

object ROOTRef extends PyRef

case class DetachedRef(
                        override val createOpt: Option[String],
                        override val referenceOpt: Option[String],
                        override val dependencies: Seq[PyRef],
                        override val converter: PyConverter
                      ) extends PyRef {

  override def lzy = false
}

trait ClassRef extends PyRef {

  override def imports = super.imports ++ Seq(s"import $packageName")

  override lazy val referenceOpt = Some(varNamePrefix + SpookyUtils.randomSuffix)
}

trait StaticRef extends ClassRef {

  assert(
    className.endsWith("$"),
    s"$className is not an object, only object can implement PyStatic"
  )

  override lazy val pyClassName: String = super.pyClassName.stripSuffix("$")

  override lazy val createOpt = None

  override lazy val referenceOpt = Some(pyClassName)

  override lazy val delOpt = None
}

/**
  * NOT thread safe!
  */
trait InstanceRef extends ClassRef {

  assert(
    !className.contains("$"),
    s"$className is an object/anonymous class, it cannot implement PyInstance"
  )
  assert(
    !className.contains("#"),
    s"$className is a nested class, it cannot implement PyInstance"
  )

  def pyConstructorArgs: String

  override def createOpt = Some(
    s"""
       |$pyClassName$pyConstructorArgs
      """.trim.stripMargin
  )
}

@Deprecated
trait JSONInstanceRef extends InstanceRef with HasMessage {

  override def pyConstructorArgs: String = {
    val converted = this.converter.scala2py(this.toMessage)._2
    val code =
      s"""
         |(**($converted))
      """.trim.stripMargin
    code
  }
}

trait CaseInstanceRef extends InstanceRef with Product {

  def attrMap = SpookyUtils.Reflection.getCaseAccessorMap(this)

  override def dependencies = {
    this.converter.kwargs2Ref(attrMap)._1
  }

  override def pyConstructorArgs = {
    this.converter.kwargs2Ref(attrMap)._2
  }
}
package com.tribbloids.spookystuff.expressions

/**
 * Created by peng on 11/28/14.
 */
object NamedFunction1 {

  def apply[T, R](f: T => R, _name: String): NamedFunction1[T, R] =
    new NamedFunction1[T, R] {

      override val name = _name

      override def apply(v1: T): R = f(v1)
    }

  def apply[T, R](f: T => R): NamedFunction1[T, R] = this.apply(f, ""+f.hashCode())

  //  def apply[T, R](f: T => R): NamedFunction1[T, R] = apply(f.toString(), f)
}

trait NamedFunction1[-T, +R] extends (T => R) with Serializable {

  val name: String

  final def as(name: Symbol): Alias[T, R] = Alias(this, Option(name).map(_.name).orNull)

  final def as_!(name: Symbol): Alias[T, R] = Alias.forced(this, Option(name).map(_.name).orNull)

  //will not rename an already-named Alias.
  def defaultAs(name: Symbol): Alias[T, R] = as(name)

  final def ~(name: Symbol) = as(name)

  final def ~!(name: Symbol) = as_!(name)

  @annotation.unspecialized override def compose[A](g: A => T): NamedFunction1[A, R] =
    NamedFunction1(
      v1 => NamedFunction1.this.apply(g(v1)),
      g.toString() + "." + NamedFunction1.this.name
    )

  @annotation.unspecialized override def andThen[A](g: R => A): NamedFunction1[T, A] =
    NamedFunction1(
      v1 => g(NamedFunction1.this.apply(v1)),
      NamedFunction1.this.name + "." + g.toString()
    )

  override def toString(): String = name
}

object Alias {

  def apply[T, R](src: NamedFunction1[T, R], name: String): Alias[T, R] = {

    val self: NamedFunction1[T, R] = getSelf(src)

    new Alias(self, name)
  }

  def getSelf[R, T](src: NamedFunction1[T, R]): NamedFunction1[T, R] = {
    val self: NamedFunction1[T, R] = src match {
      case a: Alias[T, R] => a.self
      case _ => src
    }
    self
  }

  def forced[T, R](src: NamedFunction1[T, R], name: String): Alias[T, R] = {

    val self: NamedFunction1[T, R] = getSelf(src)

    new Alias(self, name) with ForceNamedFunction1[T, R]
  }
}

class Alias[-T, +R] private(val self: NamedFunction1[T, R], override val name: String) extends NamedFunction1[T, R] {

  override def apply(v1: T): R = self(v1)

  override def defaultAs(name: Symbol): Alias[T, R] = this

  override def toString() = self.toString + " ~ '" + name
}

//subclasses bypass "already exist" check
trait ForceNamedFunction1[-T, +R] extends NamedFunction1[T, R]
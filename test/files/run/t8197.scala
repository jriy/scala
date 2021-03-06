// scala/bug#8197, see also scala/bug#4592 and scala/bug#4728
class A
class B

class Foo(val x: A = null) {
  def this(bla: B*) {
    this(new A)
  }
}

object Test extends App {
  // both constructors of `Foo` are applicable. Overloading resolution
  // will eliminate the alternative that uses a default argument, therefore
  // the vararg constructor is chosen.
  assert((new Foo).x != null)
}

# unroll


Unroll provides the `@unroll.Unroll(from: String)` annotation that can be applied
to methods, classes, and constructors. `@Unroll` generates unrolled/telescoping
versions of the method, starting from the parameter specified by `from`, which
are simple forwarders to the primary method or constructor implementation. 

This makes it easy to preserve binary compatibility when adding default parameters
to methods, classes, and constructors. Downstream code compiled against an old
version of your library with fewer parameters would continue to work, calling the
generated forwarders.

### Methods

```scala
object Unrolled{
   @unroll.Unroll("b")
   def foo(s: String, n: Int = 1, b: Boolean = true, l: Long = 0) = s + n + b + l
}
```

Unrolls to:

```scala
object Unrolled{
   @unroll.Unroll("b")
   def foo(s: String, n: Int = 1, b: Boolean = true, l: Long = 0) = s + n + b + l

   def foo(s: String, n: Int, b: Boolean) = foo(s, n, b, 0)
   def foo(s: String, n: Int) = foo(s, n, true, 0)
}
````
### Classes

```scala
@unroll.Unroll("b")
class Unrolled(s: String, n: Int = 1, b: Boolean = true, l: Long = 0){
   def foo = s + n + b + l
}
```

Unrolls to:

```scala
@unroll.Unroll("b")
class Unrolled(s: String, n: Int = 1, b: Boolean = true, l: Long = 0){
   def foo = s + n + b + l

   def this(s: String, n: Int, b: Boolean) = this(s, n, b, 0)
   def this(s: String, n: Int) = this(s, n, true, 0)
}
```

### Constructors

```scala
class Unrolled() {
   var foo = ""

   @unroll.Unroll("b")
   def this(s: String, n: Int = 1, b: Boolean = true, l: Long = 0) = {
      this()
      foo = s + n + b + l
   }
}
```

Unrolls to:

```scala
class Unrolled() {
   var foo = ""

   @unroll.Unroll("b")
   def this(s: String, n: Int = 1, b: Boolean = true, l: Long = 0) = {
      this()
      foo = s + n + b + l
   }

   def this(s: String, n: Int, b: Boolean) = this(s, n, b, 0)
   def this(s: String, n: Int) = this(s, n, true, 0)
}
```

## Testing

Unroll is tested via the following test-cases

1. `cls` (Class Method)
2. `obj` (Object Method)
3. `trt` (Trait Method)
4. `pri` (Primary Constructor

Each of these cases has three versions, `v1` `v2` `v3`, each of which has 
different numbers of default parameters

For each test-case, we have the following tests:

1. `unroll[<scala-version>].tests[<test-case>]`: Using java reflection to make
   sure the correct methods are generated in version `v3` and are callable with the
   correct output

2. `unroll[<scala-version>].tests[<test-case>].{v1,v2,v3}.test`: Tests compiled against
   the respective version of a test-case and running against the same version

3. `unroll[<scala-version>].tests[<test-case>].{v1v2,v2v3,v1v3}.test`: Tests compiled
   an *older* version of a test-case but running against *newer* version. This simulates
   a downstream library compiled against an older version of an upstream library but
   running against a newer version, ensuring there is backwards binary compatibility

4. `unroll[<scala-version>].tests[<test-case>].{v1,v2,v3}.mimaReportBinaryIssues`: Running
   the Scala [MiMa Migration Manager](https://github.com/lightbend/mima) to check a newer
   version of test-case against an older version for binary compatibility

You can also run the following command to run all tests:

```bash
./mill -i -w "unroll[_].tests.__.run"         
```

This can be useful as a final sanity check, even though you usually want to run
a subset of the tests specific to the `scala-version` and `test-case` you are 
interested in.

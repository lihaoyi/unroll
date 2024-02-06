package unroll

import unroll.TestUtils.logAssertStartsWith

object UnrollTestMain{
  def main(args: Array[String]): Unit = {
    logAssertStartsWith(new Unrolled("cow").foo, "cow1true")
    logAssertStartsWith(new Unrolled("cow", 2).foo, "cow2true")
    logAssertStartsWith(new Unrolled("cow", 2, false).foo, "cow2false")
  }
}















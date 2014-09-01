package org.ensime.test.intg

import org.ensime.test.TestUtil
import org.ensime.util._
import org.scalatest.{ FunSpec, Matchers }
import org.slf4j.LoggerFactory
import scala.concurrent.duration._

class BasicWorkflow extends FunSpec with Matchers {

  val log = LoggerFactory.getLogger(this.getClass)

  describe("Server") {
    it("should open the test project") {

      IntgUtil.withTestProject("src/test/resources/intg/simple", "simple") { (projectBase, interactor) =>

        val fooFile = TestUtil.fileToWireString(CanonFile(projectBase + "/src/main/scala/org/example/Foo.scala"))
        // typecheck
        interactor.expectRPC(20 seconds, s"""(swank:typecheck-file $fooFile)""",
          """(:ok t)""")
        interactor.expectAsync(10 seconds, "(:clear-all-scala-notes)")

        // semantic highlighting
        interactor.expectRPC(20 seconds, s"""(swank:symbol-designations $fooFile -1 299 (var val varField valField functionCall operator param class trait object package))""",
          s"""(:ok (:file $fooFile :syms ((package 12 19) (package 8 11) (trait 40 43) (valField 69 70) (class 100 103) (param 125 126) (class 128 131) (param 133 134) (class 136 142) (operator 156 157) (param 154 155) (functionCall 160 166) (param 158 159) (valField 183 186) (class 193 199) (class 201 204) (valField 214 217) (class 224 227) (functionCall 232 239) (operator 250 251) (valField 256 257) (valField 252 255) (functionCall 261 268) (functionCall 273 283) (valField 269 272))))""")

        // inspect Int symbol
        // expected form(:ok (:name "scala.Int" :local-name "Int" :type (:name "Int" :type-id 1 :full-name "scala.Int" :decl-as class) :owner-type-id 2))
        // n.b. this code is ugly - but will become neater after protocol refactor
        // id changes between platforms/invocations so we need to extract it and use it
        val inspectResp = interactor.sendRPCExp(20 seconds, SExpParser.read(s"""(swank:symbol-at-point $fooFile 128)"""))
        val intTypeId = inspectResp match {
          case SExpList(KeywordAtom(":ok") :: (res: SExpList) :: Nil) =>
            res.toKeywordMap(KeywordAtom(":type")).asInstanceOf[SExpList].toKeywordMap(KeywordAtom(":type-id")).asInstanceOf[IntAtom].value
          case _ =>
            fail("Failed to understand inspect symbol response: " + inspectResp)
        }

        //use the type ID to get the type information
        // compared with a baseline with the type ids removed (
        //        // type info for secondary buffer
        val typeInfoResp = interactor.sendRPCExp(30 seconds, SExpParser.read(s"""(swank:inspect-type-by-id 1)"""))

        typeInfoResp match {
          case SExpList(KeywordAtom(":ok") :: (typeInfo: SExpList) :: Nil) =>
            val typeMap = typeInfo.toKeywordMap(KeywordAtom(":type")).asInstanceOf[SExpList].toKeywordMap

            assert(typeMap(KeywordAtom(":name")) == StringAtom("Int"))
            assert(typeMap(KeywordAtom(":full-name")) == StringAtom("scala.Int"))
            assert(typeMap(KeywordAtom(":decl-as")) == SymbolAtom("class"))
            // short cut - decoding the structure is painful right now - lets just look for bits that must be there
            // I (@rorygraves) promise to clean this up with the protocol refactor
            val str = typeInfo.toWireString
            assert(str.contains("""(:name "!=" :type (:name "(x: Float)Boolean" """))
            assert(str.contains("""(:name "!=" :type (:name "(x: Double)Boolean" """))
            assert(str.contains(""":arrow-type t :result-type (:name "Boolean""""))

          case _ =>
            fail("Failed to understand inspect symbol response: " + inspectResp)
        }

        // uses of symbol
        interactor.expectRPC(30 seconds, s"""(swank:uses-of-symbol-at-point $fooFile 121)""",
          s"""(:ok ((:file $fooFile :offset 114 :start 110 :end 172) (:file $fooFile :offset 273 :start 269 :end 283)))"""
        )
      }
    }
  }
}

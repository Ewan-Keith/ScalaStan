package com.cibo.scalastan.transform

import com.cibo.scalastan.{ScalaStan, ScalaStanBaseSpec, StanInt}
import com.cibo.scalastan.ast._

class AstSimplifierSpec extends ScalaStanBaseSpec {
  describe("AstSimplifier") {
    it("simplifies nested blocks") {
      new ScalaStan {
        val returnStatement = StanReturnStatement(StanLocalDeclaration(StanInt()))
        val code = StanProgram(model = StanBlock(Seq(StanBlock(Seq(returnStatement)))))
        val simplifier = AstSimplifier()
        val simplified = simplifier.run(code)
        simplified.model shouldBe returnStatement
      }
    }

    it("removes empty conditionals") {
      new ScalaStan {
        val code = StanProgram(
          model = StanIfStatement(Seq((StanLocalDeclaration(StanInt()), StanBlock(Seq.empty))), None)
        )
        val simplifier = AstSimplifier()
        val simplified = simplifier.run(code)
        simplified.model shouldBe StanBlock(Seq.empty)
      }
    }
  }
}

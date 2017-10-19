package com.cibo.scalastan

class ScalaStanSpec extends ScalaStanBaseSpec {
  describe("data") {
    it("causes a data declaration to be generated") {
      new ScalaStan {
        data(int())
        val model = new Model { }
        checkCode(model, "data { int v#; }")
      }
    }
  }

  describe("parameter") {
    it("causes a parameter declaration to be generated") {
      new ScalaStan {
        parameter(int())
        val model = new Model { }
        checkCode(model, "parameters { int v#; }")
      }
    }
  }

  describe("model") {
    describe("when") {
      it("generates an if statement") {
        new ScalaStan {
          val model = new Model {
            when(1) {
            }
          }
          checkCode(model, "model { if(1) { } }")
        }
      }
    }

    describe(":=") {
      it("generates an assignment") {
        new ScalaStan {
          val model = new Model {
            val a = local(int())
            a := 1
          }
          checkCode(model, "model { int v#; v# = 1; }")
        }
      }
    }

    describe("~") {
      it("generates a ~ statement") {
        new ScalaStan {
          val model = new Model {
            val a = local(real())
            a ~ Normal(0.0, 1.0)
          }
          checkCode(model, "model { real v#; v# ~ normal(0,1); }")
        }
      }
    }
  }
}

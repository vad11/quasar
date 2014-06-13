package slamdata.engine.physical.mongodb

import slamdata.engine._
import slamdata.engine.DisjunctionMatchers 

import scalaz._
import Scalaz._

import org.specs2.mutable._
import org.specs2.ScalaCheck

import org.scalacheck._
import Gen._

class PipelineSpec extends Specification with ScalaCheck with DisjunctionMatchers {
  def p(ops: PipelineOp*) = Pipeline(ops.toList)

  val empty = p()

  import PipelineOp._
  import ExprOp._

  implicit def arbitraryOp: Arbitrary[PipelineOp] = Arbitrary {
    // Note: Gen.oneOf is overridden and this variant requires two explicit args
    Gen.oneOf(opGens(0), opGens(1), opGens.drop(2): _*)
  }
  
  def opGens = {
    def projectGen: Gen[PipelineOp] = for {
      c <- Gen.alphaChar
    } yield Project(Reshape(Map(c.toString -> -\/(Literal(Bson.Int32(1))))))

    def redactGen = for {
      value <- Gen.oneOf("$$DESCEND", "$$KEEP", "$$PRUNE")
    } yield Redact(Literal(Bson.Text(value)))

    def unwindGen = for {
      c <- Gen.alphaChar
    } yield Unwind(BsonField.Name(c.toString))
    
    def groupGen = for {
      i <- Gen.chooseNum(1, 10)
    } yield Group(Grouped(Map("docsByAuthor" + i -> Sum(Literal(Bson.Int32(1))))), DocVar(BsonField.Name("author" + i)))
    
    def geoNearGen = for {
      i <- Gen.chooseNum(1, 10)
    } yield GeoNear((40.0, -105.0), BsonField.Name("distance" + i), None, None, None, None, None, None, None)
    
    def outGen = for {
      i <- Gen.chooseNum(1, 10)
    } yield Out(Collection("result" + i))

    projectGen ::
      redactGen ::
      unwindGen ::
      groupGen ::
      geoNearGen ::
      outGen ::
      arbitraryShapePreservingOpGens.map(g => for { sp <- g } yield sp.op)
  }
  
  case class ShapePreservingPipelineOp(op: PipelineOp)
  
  implicit def arbitraryShapePreservingOp: Arbitrary[ShapePreservingPipelineOp] = Arbitrary { 
    // Note: Gen.oneOf is overridden and this variant requires two explicit args
    val gens = arbitraryShapePreservingOpGens
    Gen.oneOf(gens(0), gens(1), gens.drop(2): _*) 
  }
    
  def arbitraryShapePreservingOpGens = {
    def matchGen = for {
      c <- Gen.alphaChar
    } yield ShapePreservingPipelineOp(Match(Selector.Doc(Map(BsonField.Name(c.toString) -> Selector.Eq(Bson.Int32(-1))))))

    def skipGen = for {
      i <- Gen.chooseNum(1, 10)
    } yield ShapePreservingPipelineOp(Skip(i))

    def limitGen = for {
      i <- Gen.chooseNum(1, 10)
    } yield ShapePreservingPipelineOp(Limit(i))

    def sortGen = for {
      c <- Gen.alphaChar
    } yield ShapePreservingPipelineOp(Sort(Map("name1" -> Ascending)))
 
    List(matchGen, limitGen, skipGen, sortGen)
  }
  
  case class PairOfOpsWithSameType(op1: PipelineOp, op2: PipelineOp)
  
  implicit def arbitraryPair: Arbitrary[PairOfOpsWithSameType] = Arbitrary {  
    for {
      gen <- Gen.oneOf(opGens)
      op1 <- gen
      op2 <- gen
    } yield PairOfOpsWithSameType(op1, op2)
  }
      
  "MergePatch.Id" should {
    "do nothing with pipeline op" in {
      MergePatch.Id(Skip(10))._1 must_== Skip(10)
    }

    "return Id for successor patch" in {
      MergePatch.Id(Skip(10))._2 must_== MergePatch.Id
    }
  }

  "MergePatch.Nest" should {
    "nest with project op" in {
      val init = Project(Reshape(Map(
        "bar" -> -\/(DocField(BsonField.Name("baz")))
      )))

      val expect = Project(Reshape(Map(
        "bar" -> -\/(DocField(BsonField.Name("foo") :+ BsonField.Name("baz")))
      )))

      MergePatch.Nest(BsonField.Name("foo"))(init)._1 must_== expect
    }
  }

  "Pipeline.merge" should {
    "return left when right is empty" ! prop { (p1: PipelineOp, p2: PipelineOp) =>
      val l = p(p1, p2)

      l.merge(empty) must (beRightDisj(l))
    }

    "return right when left is empty" ! prop { (p1: PipelineOp, p2: PipelineOp) =>
      val r = p(p1, p2)

      empty.merge(r) must (beRightDisj(r))
    }

    "return empty when both empty" in {
      empty.merge(empty) must (beRightDisj(empty))
    }

    "return left when left and right are equal" ! prop { (p1: PipelineOp, p2: PipelineOp) =>
      val v = p(p1, p2)

      v.merge(v) must (beRightDisj(v))
    }.pendingUntilFixed

    // "merge any two shape-preserving ops from the left" ! prop { (sp1: ShapePreservingPipelineOp, sp2: ShapePreservingPipelineOp) =>
 //      val (p1, p2) = (sp1.op, sp2.op)
 //      
 //      if (p1 == p2) {
 //        p(p1).merge(p(p2)) must beRightDisj(p(p1))
 //      }
 //      else {
 //        p(p1).merge(p(p2)) must beRightDisj(p(p1, p2))
 //        p(p2).merge(p(p1)) must beRightDisj(p(p2, p1))
 //      }
 //    }
    
    "be deterministic regardless of parameter order" ! prop { (p1: PipelineOp, p2: PipelineOp) =>
      val pl1 = p(p1)
      val pl2 = p(p2)
  
      pl1.merge(pl2) must_== pl2.merge(pl1)
    }.pendingUntilFixed

    "merge two ops of same type, unless an error" ! prop { (ps: PairOfOpsWithSameType) =>
      val pl1 = p(ps.op1)
      val pl2 = p(ps.op2)

      val mergedOps = pl1.merge(pl2).map(_.ops)
      mergedOps.fold(
        e => 1 must_== 1,  // HACK: ok, nothing to check if an error
        ops => ops must have length(1)  // TODO: ... and should have the same type as both ops
      )
    }.pendingUntilFixed

    "merge two simple projections" in {
      val p1 = Project(Reshape(Map(
        "foo" -> -\/ (Literal(Bson.Int32(1)))
      )))

      val p2 = Project(Reshape(Map(
        "bar" -> -\/ (Literal(Bson.Int32(2)))
      )))   

      val r = Project(Reshape(Map(
        "foo" -> -\/ (Literal(Bson.Int32(1))),
        "bar" -> -\/ (Literal(Bson.Int32(2)))
      ))) 

      p(p1).merge(p(p2)) must (beRightDisj(p(r)))
    }

     "merge two simple nested projections sharing top-level field name" in {
      val p1 = Project(Reshape(Map(
        "foo" -> \/- (Reshape(Map(
          "bar" -> -\/ (Literal(Bson.Int32(9)))
        )))
      )))

      val p2 = Project(Reshape(Map(
        "foo" -> \/- (Reshape(Map(
          "baz" -> -\/ (Literal(Bson.Int32(2)))
        )))
      )))

      val r = Project(Reshape(Map(
        "foo" -> \/- (Reshape(Map(
          "bar" -> -\/ (Literal(Bson.Int32(9))),
          "baz" -> -\/ (Literal(Bson.Int32(2)))
        )))
      )))

      p(p1).merge(p(p2)) must (beRightDisj(p(r)))
    }

    "put redact before project" in {
      val p1 = Project(Reshape(Map(
        "foo" -> \/- (Reshape(Map(
          "bar" -> -\/ (Literal(Bson.Int32(9)))
        )))
      )))

      val p2 = Redact(DocVar(BsonField.Name("KEEP")))

      p(p1).merge(p(p2)) must (beRightDisj(p(p2, p1)))
      p(p2).merge(p(p1)) must (beRightDisj(p(p2, p1)))
    }

    "put any shape-preserving op before project" ! prop { (sp: ShapePreservingPipelineOp) =>
      val p1 = Project(Reshape(Map(
        "foo" -> \/- (Reshape(Map(
          "bar" -> -\/ (Literal(Bson.Int32(9)))
        )))
      )))
      val p2 = sp.op
      
      p(p1).merge(p(p2)) must (beRightDisj(p(p2, p1)))
      p(p2).merge(p(p1)) must (beRightDisj(p(p2, p1)))
    }

    "put unwind before project" in {
      val p1 = Project(Reshape(Map(
        "foo" -> \/- (Reshape(Map(
          "bar" -> -\/ (Literal(Bson.Int32(9)))
        )))
      )))

      val p2 = Unwind(BsonField.Name("foo"))

      p(p1).merge(p(p2)) must (beRightDisj(p(p2, p1)))
      p(p2).merge(p(p1)) must (beRightDisj(p(p2, p1)))
    }
    
    "put any shape-preserving op before unwind" ! prop { (sp: ShapePreservingPipelineOp) =>
      val p1 = Unwind(BsonField.Name("foo"))
      val p2 = sp.op
      
      p(p1).merge(p(p2)) must (beRightDisj(p(p2, p1)))
      p(p2).merge(p(p1)) must (beRightDisj(p(p2, p1)))
    }

    "put any shape-preserving op before redact" ! prop { (sp: ShapePreservingPipelineOp) =>
      val p1 = Redact(DocVar(BsonField.Name("KEEP")))
      val p2 = sp.op
      
      p(p1).merge(p(p2)) must (beRightDisj(p(p2, p1)))
      p(p2).merge(p(p1)) must (beRightDisj(p(p2, p1)))
    }

    "put $geoNear before any other (except another $geoNear)" ! prop { (p2: PipelineOp) =>
      val p1 = GeoNear((40.0, -105.0), BsonField.Name("distance"), None, None, None, None, None, None, None)

      p2 match {
        case GeoNear(_, _, _, _, _, _, _, _, _) if p1 == p2 => {
          p(p1).merge(p(p2)) must beRightDisj(p(p1))
          p(p2).merge(p(p1)) must beRightDisj(p(p1))
        }
        case GeoNear(_, _, _, _, _, _, _, _, _) => {
          p(p1).merge(p(p2)) must beAnyLeftDisj
          p(p2).merge(p(p1)) must beAnyLeftDisj
        }
        case _ => {
          p(p1).merge(p(p2)) must beRightDisj(p(p1, p2))
          p(p2).merge(p(p1)) must beRightDisj(p(p1, p2))
        }
      }
    }
    
    "put $out after any other (except another $out)" ! prop { (p2: PipelineOp) =>
      val p1 = Out(Collection("result"))

      p2 match {
        case Out(_) if p1 == p2 => {
          p(p1).merge(p(p2)) must beRightDisj(p(p1))
          p(p2).merge(p(p1)) must beRightDisj(p(p1))
        }
        case Out(_) => {
          p(p1).merge(p(p2)) must beAnyLeftDisj
          p(p2).merge(p(p1)) must beAnyLeftDisj
        }
        case _ => {
          p(p1).merge(p(p2)) must beRightDisj(p(p2, p1))
          p(p2).merge(p(p1)) must beRightDisj(p(p2, p1))
        }
      }
    }
    
    "merge any op with itself" ! prop { (op: PipelineOp) =>
      p(op).merge(p(op)) must beRightDisj(p(op))
    }.pendingUntilFixed
    
    "merge skips with min" in {
      val p1 = p(Skip(5))
      val p2 = p(Skip(10))
      
      p1.merge(p2) must beRightDisj(p(Skip(5)))
      p2.merge(p1) must beRightDisj(p(Skip(5)))
    }
    
    "merge limits with max" in {
      val p1 = p(Limit(5))
      val p2 = p(Limit(10))
      
      p1.merge(p2) must beRightDisj(p(Limit(10)))
      p2.merge(p1) must beRightDisj(p(Limit(10)))
    }
    
    "merge skip and limit" in {
      val p1 = p(Skip(5))
      val p2 = p(Limit(10))
      
      // We choose to do the skip first, then the limit, but don't think it matters much either way
      val exp = p(Skip(5), Limit(5))
      
      p1.merge(p2) must beRightDisj(exp)
      p2.merge(p1) must beRightDisj(exp)
    }
    
    "merge skip and limit (empty)" in {
      val p1 = p(Skip(15))
      val p2 = p(Limit(10))
      
      // Just Limit(0) would work as well; anyway nothing's coming back
      val exp = p(Skip(15), Limit(0))
      
      p1.merge(p2) must beRightDisj(exp)
      p2.merge(p1) must beRightDisj(exp)
    }
    
    "merge skip/limit before match" in {
      val p1 = p(Skip(5), Limit(10))
      val p2 = p(Match(Selector.Doc(Map(BsonField.Name("foo") -> Selector.Eq(Bson.Int32(-1))))))
      
      p1.merge(p2) must beRightDisj(Pipeline(p1.ops ++ p2.ops))
      p2.merge(p1) must beRightDisj(Pipeline(p1.ops ++ p2.ops))
    }
    
    "merge match before sort" in {
      val p1 = p(Match(Selector.Doc(Map(BsonField.Name("foo") -> Selector.Eq(Bson.Int32(-1))))))
      val p2 = p(Sort(Map("bar" -> Ascending)))
      
      p1.merge(p2) must beRightDisj(Pipeline(p1.ops ++ p2.ops))
      p2.merge(p1) must beRightDisj(Pipeline(p1.ops ++ p2.ops))
    }
    
    "merge matches with different keys" in {
      val sel1 = BsonField.Name("foo") -> Selector.Eq(Bson.Int32(1))
      val sel2 = BsonField.Name("bar") -> Selector.Eq(Bson.Int32(2))
      val p1 = p(Match(Selector.Doc(Map(sel1))))
      val p2 = p(Match(Selector.Doc(Map(sel1))))
      val exp = p(Match(Selector.Doc(Map(sel1, sel2))))
      
      p1.merge(p2) must beRightDisj(exp)
      p2.merge(p1) must beRightDisj(exp)
    }.pendingUntilFixed
    
    "merge matches with same key" in {
      val sel1 = Selector.Gt(Bson.Int32(5))
      val sel2 = Selector.Lt(Bson.Int32(10))
      val p1 = p(Match(Selector.Doc(Map(BsonField.Name("foo") -> sel1))))
      val p2 = p(Match(Selector.Doc(Map(BsonField.Name("foo") -> sel2))))
      val exp = p(Match(Selector.Doc(Map(BsonField.Name("foo") -> Selector.And(NonEmptyList(sel1, sel2))))))
      
      p1.merge(p2) must beRightDisj(exp)
      p2.merge(p1) must beRightDisj(exp)
    }.pendingUntilFixed
  }
}
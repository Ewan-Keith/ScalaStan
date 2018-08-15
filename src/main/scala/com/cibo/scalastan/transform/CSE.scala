/*
 * Copyright (c) 2017 - 2018 CiBO Technologies - All Rights Reserved
 * You may use, distribute, and modify this code under the
 * terms of the BSD 3-Clause license.
 *
 * A copy of the license can be found on the root of this repository,
 * at https://github.com/cibotech/ScalaStan/blob/master/LICENSE,
 * or at https://opensource.org/licenses/BSD-3-Clause
 */

package com.cibo.scalastan.transform

import com.cibo.scalastan.analysis.AvailableExpressions
import com.cibo.scalastan.{ScalaStan, StanType}
import com.cibo.scalastan.ast._

case class CSEState(
  root: Option[StanStatement] = None,
  mapping: Map[Int, StanStatement] = Map.empty,
  eliminated: Set[Int] = Set.empty
)

// Common subexpression elimination.
case class CSE()(implicit val ss: ScalaStan) extends StanTransform[CSEState] {

  def initialState: CSEState = CSEState()

  private def commutative(op: StanBinaryOperator.Operator): Boolean = op match {
    case StanBinaryOperator.NotEqualTo => true
    case StanBinaryOperator.EqualTo    => true
    case StanBinaryOperator.Add        => true
    case StanBinaryOperator.Multiply   => true
    case StanBinaryOperator.LogicalAnd => true
    case StanBinaryOperator.LogicalOr  => true
    case _                             => false
  }

  private def sameValue[A <: StanType, B <: StanType](a: StanValue[A], b: StanValue[B]): Boolean = (a, b) match {
    case (b1: StanBinaryOperator[_, _, _], b2: StanBinaryOperator[_, _, _]) if b1.op == b2.op                       =>
      (sameValue(b1.left, b2.left) && sameValue(b1.right, b2.right)) ||
        (commutative(b1.op) && sameValue(b1.left, b2.right) && sameValue(b1.right, b2.left))
    case (u1: StanUnaryOperator[_, _], u2: StanUnaryOperator[_, _]) if u1.op == u2.op                               =>
      sameValue(u1.right, u2.right)
    case (i1: StanIndexOperator[_, _, _], i2: StanIndexOperator[_, _, _]) if i1.indices.length == i2.indices.length =>
      sameValue(i1.value, i2.value) && i1.indices.zip(i2.indices).forall(i => sameValue(i._1, i._2))
    case (s1: StanSliceOperator[_, _], s2: StanSliceOperator[_, _])                                                 =>
      sameValue(s1.value, s2.value) && sameValue(s1.slice.start, s2.slice.start) && sameValue(s1.slice.end, s2.slice.end)
    case (d1: StanDeclaration[_], d2: StanDeclaration[_])                                                           =>
      d1.name == d2.name
    case (c1: StanConstant[_], c2: StanConstant[_])                                                                 =>
      c1.value == c2.value
    case _                                                                                                          =>
      false
  }

  // Return true if the two statements are both compute the same expression.
  private def sameAssignment(a: StanStatement, b: StanStatement): Boolean = (a, b) match {
    case (a1: StanAssignment, a2: StanAssignment) if a1.op == StanAssignment.Assign && a2.op == a1.op =>
      sameValue(a1.rhs, a2.rhs)
    case _ => false
  }

  override def handleRoot(statement: StanStatement): State[StanStatement] = for {
    _ <- State.update(_.copy(root = Some(statement)))
    _ <- State.update(_.copy(mapping = StanProgram.getStatements(statement).map(s => s.id -> s).toMap))
    _ <- State.update(_.copy(eliminated = Set.empty))
    newRoot <- dispatch(statement)
  } yield newRoot

  private def updateAssignment(a: StanAssignment, otherOpt: Option[StanAssignment]): State[StanAssignment] = {
    otherOpt match {
      case Some(oa) =>
        for {
          _ <- State.update(old => old.copy(eliminated = old.eliminated + a.id))
        } yield StanAssignment(a.lhs, oa.lhs, a.op)
      case None => State.pure(a)
    }
  }

  override def handleAssignment(a: StanAssignment): State[StanStatement] = for {
    state <- State.get
    ae = AvailableExpressions(state.root.get).solve.lookup(a) -- state.eliminated
    mapping = state.mapping
    otherOpt = ae.map(mapping.apply).find(s => sameAssignment(a, s)).map(_.asInstanceOf[StanAssignment])
    newAssignment <- updateAssignment(a, otherOpt)
  } yield newAssignment
}

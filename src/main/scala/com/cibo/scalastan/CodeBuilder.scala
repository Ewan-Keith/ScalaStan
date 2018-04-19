/*
 * Copyright (c) 2017 - 2018 CiBO Technologies - All Rights Reserved
 * You may use, distribute, and modify this code under the
 * terms of the BSD 3-Clause license.
 *
 * A copy of the license can be found on the root of this repository,
 * at https://github.com/cibotech/ScalaStan/blob/master/LICENSE,
 * or at https://opensource.org/licenses/BSD-3-Clause
 */

package com.cibo.scalastan

import com.cibo.scalastan.ast._

import scala.collection.mutable.ArrayBuffer

protected class CodeBuilder {

  // Stack of statements.
  // New statements are appended to the last buffer.
  // Entering a new scope extends the number of buffers.
  private val stack = new ArrayBuffer[ArrayBuffer[StanStatement]]()

  private val dataValues = ArrayBuffer[StanDataDeclaration[_]]()
  private val parameterValues = ArrayBuffer[StanParameterDeclaration[_]]()
  private val functions = ArrayBuffer[ScalaStan#Function[_]]()
  private val transformedData = ArrayBuffer[ScalaStan#TransformedData[_]]()
  private val transformedParameters = ArrayBuffer[ScalaStan#TransformedParameter[_]]()
  private val generatedQuantities = ArrayBuffer[ScalaStan#GeneratedQuantity[_]]()

  // Create the top-level scope.
  stack += ArrayBuffer()

  // Enter a new scope.
  def enter(): Unit = {
    stack += ArrayBuffer()
  }

  // Leave the current scope (collapsing the statements collected using the specified function).
  def leave(f: Seq[StanStatement] => StanStatement): Unit = {
    require(stack.size > 1)
    val result = f(stack.remove(stack.size - 1))
    stack.last += result
  }

  // Special handling for "else if" statements.
  def handleElseIf(cond: StanValue[StanInt]): Unit = {
    val inside = stack.remove(stack.size - 1)
    val ifStatement = stack.last.remove(stack.last.size - 1).asInstanceOf[StanIfStatement]
    stack.last += ifStatement.copy(ifStatement.conds :+ (cond, StanBlock(inside)))
  }

  // Special handling for "else" statements.
  def handleElse(): Unit = {
    val inside = stack.remove(stack.size - 1)
    val ifStatement = stack.last.remove(stack.last.size - 1).asInstanceOf[StanIfStatement]
    stack.last += ifStatement.copy(otherwise = Some(StanBlock(inside)))
  }

  def insert(s: StanStatement): Unit = {
    require(stack.nonEmpty)
    s.export(this)
    stack.last.insert(0, s)
  }

  def append(other: CodeBuilder): Unit = {
    other.dataValues.foreach(append)
    other.parameterValues.foreach(append)
    other.functions.foreach(append)
    other.transformedParameters.foreach(append)
    other.transformedData.foreach(append)
    other.generatedQuantities.foreach(append)
  }

  def append(d: StanDataDeclaration[_]): Unit = {
    if (!dataValues.exists(_.id == d.id)) {
      dataValues += d
    }
  }

  def append(d: StanParameterDeclaration[_]): Unit = {
    if (!parameterValues.exists(_.id == d.id)) {
      parameterValues += d
    }
  }

  def append(f: ScalaStan#Function[_]): Unit = {
    if (!functions.exists(_.name == f.name)) {
      append(f._code)
      functions += f
    }
  }

  def append(t: ScalaStan#TransformedData[_]): Unit = {
    if (!transformedData.exists(_.name == t.name)) {
      append(t._code)
      transformedData += t
    }
  }

  def append(t: ScalaStan#TransformedParameter[_]): Unit = {
    if (!transformedParameters.exists(_.name == t.name)) {
      append(t._code)
      transformedParameters += t
    }
  }

  def append(g: ScalaStan#GeneratedQuantity[_]): Unit = {
    if (!generatedQuantities.exists(_.name == g.name)) {
      append(g._code)
      generatedQuantities += g
    }
  }

  def append(s: StanStatement): Unit = {
    require(stack.nonEmpty)
    s.export(this)
    stack.last += s
  }

  lazy val results: StanBlock = {
    require(stack.size == 1)
    StanBlock(stack.last)
  }

  lazy val program: StanProgram = StanProgram(
    dataValues,
    parameterValues,
    functions.map(_.generate),
    transformedData.map(_.generate),
    transformedParameters.map(_.generate),
    generatedQuantities.map(_.generate),
    results
  )

}

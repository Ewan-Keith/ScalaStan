/*
 * Copyright (c) 2017 - 2018 CiBO Technologies - All Rights Reserved
 * You may use, distribute, and modify this code under the
 * terms of the BSD 3-Clause license.
 *
 * A copy of the license can be found on the root of this repository,
 * at https://github.com/cibotech/ScalaStan/blob/master/LICENSE,
 * or at https://opensource.org/licenses/BSD-3-Clause
 */

package com.cibo.scalastan.run

import java.io._
import java.nio.file.{Files, Paths, StandardCopyOption}

import com.cibo.scalastan._
import com.typesafe.scalalogging.LazyLogging

import scala.collection.JavaConverters._

case class CmdStanChainContext(
  compiledModel: CompiledModel,
  method: RunMethod.Method,
  modelDir: File,
  outputFile: File,
  modelExecutable: File,
  dataFile: File,
  initialValueFile: Option[File],
  chainSeed: Int,
  runHash: String
) extends LazyLogging {

  def initialValueArguments: Vector[String] = compiledModel.initialValue match {
    case DefaultInitialValue => Vector.empty
    case InitialValueMapping(_) => Vector(s"init=${initialValueFile.get.getName}")
    case InitialValueDouble(d) => Vector(s"init=$d")
  }

  def run(): Int = {
    val command = Vector(
      s"./$modelExecutable",
      "data", s"file=${dataFile.getName}",
      "output", s"file=${outputFile.getName}",
      "random", s"seed=$chainSeed"
    ) ++ initialValueArguments ++ method.arguments
    logger.info("Running " + command.mkString(" "))
    CommandRunner.runCommand(modelDir, command)
  }

}

class CmdStanRunner(
  val modelDir: File,
  val modelHash: String
) extends StanRunner with LazyLogging {

  private val modelExecutable: String = CmdStanCompiler.modelExecutable

  private val initialValuePrefix: String = "initial"
  private val dataPrefix: String = "input"

  def initialValueFileName(hash: String): String = s"$modelDir/$initialValuePrefix-$hash.R"

  def dataFileName(hash: String): String = s"$modelDir/$dataPrefix-$hash.R"

  /** Write to a file whose name contains the hash. */
  def writeToHashedFileName(prefix: String, emit: Writer => Unit): String = {

    // Write the data with a temporary file name to get the hash.
    val temp = Files.createTempFile(prefix, ".R").toFile
    val writer = ShaWriter(new FileWriter(temp))
    emit(writer)
    writer.close()

    // Move the temp file to the expected name.
    val hash = writer.sha.digest
    val fileName = s"$modelDir/$prefix-$hash.R"
    logger.info(s"writing $prefix to $fileName")
    Files.move(temp.toPath, Paths.get(fileName), StandardCopyOption.REPLACE_EXISTING)

    hash
  }

  /** Write the data file. Returns the data hash. */
  def writeData(model: CompiledModel): String = writeToHashedFileName(dataPrefix, model.emitData)

  /** Write the initial value file. Returns the hash of the initial value (if any). */
  def writeInitialValue(model: CompiledModel): Option[String] = {
    model.initialValue match {
      case InitialValueMapping(_) =>
        Some(writeToHashedFileName(initialValuePrefix, model.emitInitialValues))
      case InitialValueDouble(d) => Some(SHA.hash(d.toString))
      case _ => None
    }
  }

  /** Compute the run hash (used for the output file name). */
  def computeRunHash(dataHash: String, initialValueHash: Option[String], method: RunMethod.Method): String = {
    val sha = SHA()
    sha.update(dataHash)
    initialValueHash.foreach(sha.update)
    method.arguments.foreach(sha.update)
    sha.digest
  }

  def initialValueFile(
    model: CompiledModel,
    initialValueHash: Option[String]
  ): Option[File] = model.initialValue match {
    case InitialValueMapping(_) => Some(new File(initialValueFileName(initialValueHash.get)))
    case _                      => None
  }

  def readIterations(file: File): Map[String, Vector[String]] = {
    val reader = new BufferedReader(new FileReader(file))
    try {
      val lines = reader.lines.iterator.asScala.filterNot(_.startsWith("#")).toVector
      if (lines.nonEmpty) {
        val header = lines.head.split(',').toVector
        val columns = lines.tail.map(_.split(',').toVector).transpose
        header.zip(columns).toMap
      } else {
        Map.empty
      }
    } finally {
      reader.close()
    }
  }

  def outputFileName(runHash: String, seed: Int, chainIndex: Int): String = s"$runHash-$seed-$chainIndex.csv"

  def loadFromCache(file: File): Option[Map[String, Vector[String]]] = {
    if (file.exists) Some(readIterations(file)) else None
  }

  def runChain(context: CmdStanChainContext): Option[Map[String, Vector[String]]] = {
    val rc = context.run()
    if (rc != 0) {
      logger.error(s"model returned $rc")
      None
    } else {
      Some(readIterations(context.outputFile))
    }
  }

  def run(
    compiledModel: CompiledModel,
    chains: Int,
    seed: Int,
    cache: Boolean,
    method: RunMethod.Method
  ): StanResults = {

    // Only allow one instance of a model to run at a time.
    compiledModel.model.synchronized {

      val dataHash = writeData(compiledModel)
      val initialValueHash = writeInitialValue(compiledModel)
      val runHash = computeRunHash(dataHash, initialValueHash, method)
      val baseSeed = if (seed < 0) (System.currentTimeMillis % Int.MaxValue).toInt else seed
      val results = Vector.range(0, chains).par.flatMap { chainIndex =>
        val chainSeed = ((baseSeed.toLong + chainIndex) % Int.MaxValue).toInt
        val outputName = outputFileName(runHash, seed, chainIndex)
        val outputFile = new File(s"$modelDir/$outputName")
        val context = CmdStanChainContext(
          compiledModel = compiledModel,
          method = method,
          modelDir = modelDir,
          outputFile = outputFile,
          modelExecutable = new File(s"./$modelExecutable"),
          dataFile = new File(dataFileName(dataHash)),
          initialValueFile = initialValueFile(compiledModel, initialValueHash),
          chainSeed = chainSeed,
          runHash = runHash
        )
        val cachedResults = if (cache) loadFromCache(outputFile) else None
        cachedResults match {
          case Some(r) if r.nonEmpty =>
            logger.info(s"Found cached results: $outputName")
            Some(r)
          case _ => runChain(context)
        }
      }.seq

      val parameterChains: Map[String, Vector[Vector[String]]] = results.flatten.groupBy(_._1).mapValues(_.map(_._2))
      StanResults(parameterChains, compiledModel)
    }
  }
}


package midas

import java.io.{File, FileWriter, Writer}

import scala.collection.immutable.ListMap

import freechips.rocketchip.config.Parameters
import chisel3.{Data, Bundle, Record, Clock, Bool}
import chisel3.internal.firrtl.Port
import firrtl.ir.Circuit
import firrtl.AnnotationMap
import firrtl.annotations.Annotation
import firrtl.CompilerUtils.getLoweringTransforms
import firrtl.passes.memlib._
import barstools.macros._

// Compiler for Midas Transforms
private class MidasCompiler(dir: File, io: Seq[Data])(implicit param: Parameters) 
    extends firrtl.Compiler {
  def emitter = new firrtl.LowFirrtlEmitter
  def transforms =
    getLoweringTransforms(firrtl.ChirrtlForm, firrtl.MidForm) ++
    Seq(new InferReadWrite,
        new ReplSeqMem) ++
    getLoweringTransforms(firrtl.MidForm, firrtl.LowForm) ++
    Seq(new passes.MidasTransforms(dir, io))
}

// Compilers to emit proper verilog
private class VerilogCompiler extends firrtl.Compiler {
  def emitter = new firrtl.VerilogEmitter
  def transforms =
    Seq(new firrtl.IRToWorkingIR,
        new firrtl.ResolveAndCheck,
        new firrtl.HighFirrtlToMiddleFirrtl) ++
    getLoweringTransforms(firrtl.MidForm, firrtl.LowForm) ++
    Seq(new firrtl.LowFirrtlOptimization)
}

object MidasCompiler {
  // Generates the verilog and memory map for a MIDAS simulation
  // Accepts: An elaborated chisel circuit, record that mirrors its I/O,
  // an output directory, and technology library
  def apply(
      chirrtl: Circuit,
      targetAnnos: Seq[Annotation],
      io: Seq[Data],
      dir: File,
      lib: Option[File])
     (implicit p: Parameters): Circuit = {
    val conf = new File(dir, s"${chirrtl.main}.conf")
    val json = new File(dir, s"${chirrtl.main}.macros.json")
    val midasAnnos = Seq(
      InferReadWriteAnnotation(chirrtl.main),
      ReplSeqMemAnnotation(s"-c:${chirrtl.main}:-o:$conf"),
      passes.MidasAnnotation(chirrtl.main, conf, json, lib),
      MacroCompilerAnnotation(chirrtl.main, MacroCompilerAnnotation.Params(
        json.toString, lib map (_.toString), CostMetric.default, MacroCompilerAnnotation.Synflops)))
    val writer = new java.io.StringWriter
    val midas = new MidasCompiler(dir, io) compile (firrtl.CircuitState(
      chirrtl, firrtl.ChirrtlForm, Some(new AnnotationMap(targetAnnos ++ midasAnnos))), writer)
    val verilog = new FileWriter(new File(dir, s"FPGATop.v"))
    val result = new VerilogCompiler compile (firrtl.CircuitState(
      midas.circuit, firrtl.HighForm, Some(new AnnotationMap(midasAnnos))), verilog)
    verilog.close
    result.circuit
  }

  // Unlike above, elaborates the target locally, before constructing the target IO Record.
  def apply[T <: chisel3.core.UserModule](
      w: => T,
      dir: File,
      libFile: Option[File] = None)
     (implicit p: Parameters): Circuit = {
    dir.mkdirs
    lazy val target = w
    val circuit = chisel3.Driver.elaborate(() => target)
    val chirrtl = firrtl.Parser.parse(chisel3.Driver.emit(circuit))
    val io = target.getPorts map (_.id)
    apply(chirrtl, circuit.annotations.toSeq, io, dir, libFile)
  }
}


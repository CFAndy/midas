package midas
package platform

import freechips.rocketchip.util.ParameterizedBundle // from rocketchip

import chisel3._
import chisel3.util._
import freechips.rocektchip.amba.axi4._
import freechips.rocketchip.config.{Parameters, Field}

abstract class PlatformShim extends Module {
  def top: midas.core.FPGATop
  def headerConsts: Seq[(String, Int)]
  def genHeader(sb: StringBuilder, target: String) {
    import widgets.CppGenerationUtils._
    sb.append(genStatic("TARGET_NAME", widgets.CStrLit(target)))
    sb.append(genMacro("PLATFORM_TYPE", s"V${this.getClass.getSimpleName}"))
    if (top.sim.enableSnapshot) sb append(genMacro("ENABLE_SNAPSHOT"))
    sb.append(genMacro("data_t", "uint%d_t".format(top.sim.channelWidth)))
    top.genHeader(sb)(top.sim.channelWidth)
    sb.append("\n// Simulation Constants\n")
    headerConsts map { case (name, value) =>
      genMacro(name, widgets.UInt32(value)) } addString sb
  }
}

case object MasterAXIKey extends Field[AXI4BundleParameters]
case object SlaveAXIKey extends Field[AXI4BundleParameters]

class ZynqShimIO(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val master = Flipped(new AXI4Bundle(p(MasterAXIKey))
  val slave  = new AXI4Bundle(SlaveAXIKey)
}

class ZynqShim(simIo: midas.core.SimWrapperIO)
              (implicit p: Parameters) extends PlatformShim {
  val io = IO(new ZynqShimIO)
  val top = Module(new midas.core.FPGATop(simIo))
  val headerConsts = List(
    "MMIO_WIDTH" -> p(MasterAXIKey).dataBits / 8,
    "MEM_WIDTH"  -> p(SlaveAXIKey).dataBits / 8
  ) ++ top.headerConsts

  top.io.ctrl <> io.master
  io.slave <> top.io.mem
}

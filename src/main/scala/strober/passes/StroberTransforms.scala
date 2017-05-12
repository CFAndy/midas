package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import core.ChainType
import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet, LinkedHashSet}

private object StroberMetaData {
  private def collectChildren(
      mname: String,
      meta: StroberMetaData,
      blackboxes: Set[String])
     (s: Statement): Statement = {
    s match {
      case s: WDefInstance if !blackboxes(s.module) =>
        meta.childMods(mname) += s.module
        meta.childInsts(mname) += s.name
        meta.instModMap(s.name -> mname) = s.module
      case _ =>
    }
    s map collectChildren(mname, meta, blackboxes)
  }

  private def collectChildrenMod(
      meta: StroberMetaData,
      blackboxes: Set[String])
     (m: DefModule) = {
    meta.childInsts(m.name) = ArrayBuffer[String]()
    meta.childMods(m.name) = LinkedHashSet[String]()
    m map collectChildren(m.name, meta, blackboxes)
  }

  def apply(c: Circuit) = {
    val meta = new StroberMetaData
    val blackboxes = (c.modules collect { case m: ExtModule => m.name }).toSet
    c.modules map collectChildrenMod(meta, blackboxes)
    meta
  }
}

private class StroberMetaData {
  type ChainMap = HashMap[String, ArrayBuffer[ir.Statement]]
  type ChildMods = HashMap[String, LinkedHashSet[String]]
  type ChildInsts = HashMap[String, ArrayBuffer[String]]
  type InstModMap = HashMap[(String, String), String]

  val childMods = new ChildMods
  val childInsts = new ChildInsts
  val instModMap = new InstModMap
  val chains = (ChainType.values.toList map (_ -> new ChainMap)).toMap
}

private object preorder {
  def aplly(c: Circuit,
            meta: StroberMetaData)
           (visit: DefModule => DefModule): Seq[DefModule] = {
    val head = (c.modules find (_.name == c.main)).get
    val visited = HashSet[String]()
    def loop(m: DefModule): Seq[DefModule] = {
      visited += m.name
      visit(m) +: (c.modules filter (x =>
        meta.childMods(m.name)(x.name) && !visited(x.name)) flatMap loop)
    }
    loop(head) ++ (c.modules collect { case m: ExtModule => m })
  }
}

private object postorder {
  def apply(c: Circuit,
            meta: StroberMetaData)
           (visit: DefModule => DefModule): Seq[DefModule] = {
    val head = (c.modules find (_.name == c.main)).get
    val visited = HashSet[String]()
    def loop(m: DefModule): Seq[DefModule] = {
      val res = (c.modules filter (x =>
        meta.childMods(m.name)(x.name)) flatMap loop)
      if (visited(m.name)) {
        res
      } else {
        visited += m.name
        res :+ visit(m)
      }
    }
    loop(head) ++ (c.modules collect { case m: ExtModule => m })
  }
}

class StroberTransforms(
    dir: java.io.File,
    seqMems: Map[String, midas.passes.MemConf])
   (implicit param: config.Parameters) extends Transform {
  def inputForm = MidForm
  def outputForm = MidForm
  def execute(state: CircuitState) = {
    val meta = StroberMetaData(state.circuit)
    val transforms = Seq(
      new AddDaisyChains(meta, seqMems),
      new DumpChains(dir, meta, seqMems)
    )
    (transforms foldLeft state)((in, xform) => xform runTransform in).copy(form=outputForm)
  }
}
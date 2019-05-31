package is.hail.backend

import is.hail.expr.ir._
import is.hail.expr.types._
import is.hail.expr.types.virtual._
import is.hail.rvd.{RVDPartitioner, RVDType}
import is.hail.utils._
import org.apache.spark.sql.Row

class LowererUnsupportedOperation(msg: String = null) extends Exception(msg)

case class ShuffledStage(child: TableStage)

case class Binding(name: String, value: IR)

case class TableStage(
  broadcastVals: IR,
  globalsField: String,
  rvdType: RVDType,
  partitioner: RVDPartitioner,
  contextType: Type,
  contexts: IR,
  body: IR) {

  def toIR(bodyTransform: IR => IR): CollectDistributedArray =
    CollectDistributedArray(contexts, broadcastVals, "context", "global", bodyTransform(body))

  def broadcastRef: IR = Ref("global", broadcastVals.typ)
  def contextRef: IR = Ref("context", contextType)
  def globals: IR = GetField(broadcastRef, globalsField)
}

object LowerTableIR {

  def apply(ir0: IR, timer: Option[ExecutionTimer], optimize: Boolean = true): IR = {

    def opt(context: String, ir: IR): IR =
      Optimize(ir, noisy = true, canGenerateLiterals = true,
        Some(timer.map(t => s"${ t.context }: $context")
          .getOrElse(context)))

    def time(context: String, ir: (String) => IR): IR =
      timer.map(t => t.time(ir(context), context))
        .getOrElse(ir(context))

    var ir = ir0

    ir = ir.unwrap
    if (optimize) { ir = time( "first pass", opt(_, ir)) }

    ir = time("lowering MatrixIR", _ => LowerMatrixIR(ir))

    if (optimize) { ir = time("after MatrixIR lowering", opt(_, ir)) }

    ir = time("lowering TableIR", _ => LowerTableIR.lower(ir))

    if (optimize) { ir = time("after TableIR lowering", opt(_, ir)) }
    ir
  }

  def lower(ir: IR): IR = ir match {

    case TableCount(tableIR) =>
      val stage = lower(tableIR)
      invoke("sum", stage.toIR(node => Cast(ArrayLen(node), TInt64())))

    case TableGetGlobals(child) =>
      val stage = lower(child)
      GetField(stage.broadcastVals, stage.globalsField)

    case TableCollect(child) =>
      val lowered = lower(child)
      assert(lowered.body.typ.isInstanceOf[TContainer])
      val elt = genUID()
      MakeStruct(FastIndexedSeq(
        "rows" -> ArrayFlatMap(lowered.toIR(x => x), elt, Ref(elt, lowered.body.typ)),
        "global" -> GetField(lowered.broadcastVals, lowered.globalsField)))

    case node if node.children.exists( _.isInstanceOf[TableIR] ) =>
      throw new LowererUnsupportedOperation(s"IR nodes with TableIR children must be defined explicitly: \n${ Pretty(node) }")

    case node if node.children.exists( _.isInstanceOf[MatrixIR] ) =>
      throw new LowererUnsupportedOperation(s"MatrixIR nodes must be lowered to TableIR nodes separately: \n${ Pretty(node) }")
      
    case node =>
      Copy(node, ir.children.map { case c: IR => lower(c) })
  }

  // table globals should be stored in the first element of `globals` in TableStage;
  // globals in TableStage should have unique identifiers.
  def lower(tir: TableIR): TableStage = tir match {
    case TableRange(n, nPartitions) =>
      val nPartitionsAdj = math.max(math.min(n, nPartitions), 1)
      val partCounts = partition(n, nPartitionsAdj)
      val partStarts = partCounts.scanLeft(0)(_ + _)

      val rvdType = RVDType(tir.typ.rowType.physicalType, Array("idx"))

      val contextType = TStruct(
        "start" -> TInt32(),
        "end" -> TInt32())

      val i = Ref(genUID(), TInt32())
      val ranges = Array.tabulate(nPartitionsAdj) { i => partStarts(i) -> partStarts(i + 1) }
      val globalRef = genUID()

      TableStage(
        MakeStruct(FastIndexedSeq(globalRef -> MakeStruct(Seq()))),
        globalRef,
        rvdType,
        new RVDPartitioner(Array("idx"), tir.typ.rowType,
          ranges.map { case (start, end) =>
            Interval(Row(start), Row(end), includesStart = true, includesEnd = false)
          }),
        contextType,
        MakeArray(ranges.map { case (start, end) =>
          MakeStruct(FastIndexedSeq("start" -> start, "end" -> end)) },
          TArray(contextType)
        ),
        ArrayMap(ArrayRange(
          GetField(Ref("context", contextType), "start"),
          GetField(Ref("context", contextType), "end"),
          I32(1)), i.name, MakeStruct(FastSeq("idx" -> i))))

    case TableMapGlobals(child, newGlobals) =>
      val loweredChild = lower(child)
      val oldbroadcast = Ref(genUID(), loweredChild.broadcastVals.typ)
      val newGlobRef = genUID()
      val newBroadvastVals =
        Let(
          oldbroadcast.name,
          loweredChild.broadcastVals,
          InsertFields(oldbroadcast,
            FastIndexedSeq(newGlobRef ->
              Subst(lower(newGlobals),
                BindingEnv.eval("global" -> GetField(oldbroadcast, loweredChild.globalsField))))))

      loweredChild.copy(broadcastVals = newBroadvastVals, globalsField = newGlobRef)

    case TableFilter(child, cond) =>
      val loweredChild = lower(child)
      val row = Ref(genUID(), child.typ.rowType)
      val global = loweredChild.globals
      val env: Env[IR] = Env("row" -> row, "global" -> loweredChild.globals)
      loweredChild.copy(body = ArrayFilter(loweredChild.body, row.name, Subst(cond, BindingEnv(env))))

    case TableMapRows(child, newRow) =>
      val loweredChild = lower(child)
      val row = Ref(genUID(), child.typ.rowType)
      val env: Env[IR] = Env("row" -> row, "global" -> loweredChild.globals)
      loweredChild.copy(body = ArrayMap(loweredChild.body, row.name, Subst(newRow, BindingEnv(env, scan = Some(env)))))

    case TableExplode(child, path) =>
      val loweredChild = lower(child)
      val row = Ref(genUID(), child.typ.rowType)

      val fieldRef = path.foldLeft[IR](row) { case (expr, field) => GetField(expr, field) }
      val elt = Ref(genUID(), coerce[TContainer](fieldRef.typ).elementType)

      val refs = path.scanLeft(row)((struct, name) =>
        Ref(genUID(), coerce[TStruct](struct.typ).field(name).typ))
      val newRow = path.zip(refs).zipWithIndex.foldRight[IR](elt) {
        case (((field, ref), i), arg) =>
          InsertFields(ref, FastIndexedSeq(field ->
              Let(refs(i + 1).name, GetField(ref, field), arg)))
      }.asInstanceOf[InsertFields]

      loweredChild.copy(body = ArrayFlatMap(loweredChild.body, row.name, ArrayMap(fieldRef, elt.name, newRow)))

    case node =>
      throw new LowererUnsupportedOperation(s"undefined: \n${ Pretty(node) }")
  }
}
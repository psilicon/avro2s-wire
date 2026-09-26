package avro2s.wire.resolution

import avro2s.wire.runtime.*
import avro2s.wire.runtime.codegen.{StackSafe, Step}
import com.fasterxml.jackson.databind.JsonNode
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * Compiles a writer/reader schema pair once and constructs reader models directly.
 * Retain and reuse this instance. Schema JSON is preserved exactly, including
 * defaults and aliases; parsing-canonical fingerprints are not resolution keys.
 * Reading does not create GenericRecords or re-encode a datum into another buffer.
 * The compiled plan is immutable after construction; each read owns its slots.
 * The supplied codec selects direct or stack-safe execution once at construction,
 * including skipped writer fields and materialization of reader defaults.
 * Reader unions prefer an exact type/name (including reader aliases) before
 * considering promotions, matching Java Avro 1.12.1's branch-selection policy.
 * This deliberately differs from a literal first-promotable-branch reading of
 * the specification and keeps metadata changes from changing Scala branch types.
 * Decode limits bound wire reads; reader-schema defaults are trusted configuration
 * and are not included in those wire-byte/item budgets.
 */
final class ResolvingReader[A](val writerSchemaJson: String, val readerCodec: AvroCodec[A]):
  private val plan: AvroInput => A =
    if writerSchemaJson == readerCodec.schemaJson then readerCodec.read
    else
      val writer = SchemaModel.parse(writerSchemaJson)
      val reader = SchemaModel.parse(readerCodec.schemaJson)
      val execution = readerCodec.execution
      val compiled = new ResolutionCompiler(reader, readerCodec, execution).compile(writer, reader, "root")
      execution match
        case CodecExecution.Direct => in => compiled.read(in).asInstanceOf[A]
        case CodecExecution.StackSafe => in => Step.run(compiled.readStep(in)).asInstanceOf[A]

  /** Uses the supplied input's validation and resource-limit policy. */
  def read(in: AvroInput): A = plan(in)

  /** Reads exactly one datum with the native input's bounds, UTF-8, and depth checks. */
  def decode(bytes: Array[Byte], limits: DecodeLimits = DecodeLimits.default): A =
    val in = new BinaryInput(bytes, limits)
    val result = read(in)
    in.requireEnd()
    result

object ResolvingReader:
  def apply[A](writerSchemaJson: String, readerCodec: AvroCodec[A]): ResolvingReader[A] =
    new ResolvingReader(writerSchemaJson, readerCodec)

private[resolution] trait ReadPlan:
  /** A parent must yield before invoking a structural child. */
  def canSuspend: Boolean = false
  /** Scalar operations contain no record or collection traversal. */
  def isScalar: Boolean = true
  def read(in: AvroInput): Any
  def readStep(in: AvroInput): Step[Any] = Step.delay(read(in))

private[resolution] trait DefaultPlan extends (() => Any):
  def canSuspend: Boolean = false
  def isScalar: Boolean = true
  def materialize: Step[Any] = Step.delay(apply())

private[resolution] final class ResolutionCompiler(
    root: SchemaModel.Node, rootCodec: AvroCodec[?], execution: CodecExecution
):
  import SchemaModel.*

  private final class Deferred extends ReadPlan:
    var target: ReadPlan = null
    // A back edge encountered while compiling is always a suspension boundary.
    override def canSuspend: Boolean = target == null || target.canSuspend
    override def isScalar: Boolean = target != null && target.isScalar
    override def read(in: AvroInput): Any = target.read(in)
    override def readStep(in: AvroInput): Step[Any] = target.readStep(in)

  private val pairs = mutable.HashMap.empty[(Node, Node), Deferred]
  private val skips = mutable.HashMap.empty[Node, Deferred]
  private val codecs = mutable.HashMap.empty[String, AvroCodec[?]]
  private val defaultStack = mutable.HashSet.empty[(Node, JsonNode)]

  private def action(f: AvroInput => Any): ReadPlan = new ReadPlan:
    override def read(in: AvroInput): Any = f(in)

  private def structural(f: AvroInput => Any)(step: AvroInput => Step[Any]): ReadPlan =
    structural(suspends = true, scalar = false)(f)(step)

  private def structural(suspends: Boolean, scalar: Boolean)(f: AvroInput => Any)
      (step: AvroInput => Step[Any]): ReadPlan =
    // Keep direct plan dispatch and retained node shape on the original action
    // implementation. Execution is selected only while compiling the plan.
    if execution == CodecExecution.Direct then action(f)
    else new ReadPlan:
      override val canSuspend: Boolean = suspends
      override val isScalar: Boolean = scalar
      override def read(in: AvroInput): Any = f(in)
      // Providers construct a suspended operation; they never traverse values
      // here. Effectful dispatch, such as reading a union tag, suspends explicitly.
      override def readStep(in: AvroInput): Step[Any] =
        if suspends then step(in) else Step.delay(f(in))

  private def defaultAction(suspends: Boolean = true, scalar: Boolean = false)(f: () => Any)
      (step: => Step[Any]): DefaultPlan = new DefaultPlan:
    override val canSuspend: Boolean = suspends
    override val isScalar: Boolean = scalar
    override def apply(): Any = f()
    override def materialize: Step[Any] =
      if suspends then Step.defer(step) else Step.delay(f())

  /** Batch scalar defaults; every structural child returns through the driver. */
  private def fillDefaults(plans: IndexedSeq[DefaultPlan])(store: (Int, Any) => Unit)
      (finish: => Any): Step[Any] = new Step.Frame[Any]:
    private var index = 0
    private var pendingSlot = -1
    private var constructed: Any = null

    override def advance(completed: Any): Step[?] =
      if pendingSlot >= 0 then store(pendingSlot, completed)
      pendingSlot = -1
      while index < plans.size do
        val slot = index
        index += 1
        if plans(slot).canSuspend then
          pendingSlot = slot
          return plans(slot).materialize
        store(slot, plans(slot)())
      constructed = finish
      null

    override def result: Any = constructed

  private def skipCollection(in: AvroInput, isMap: Boolean, element: ReadPlan, path: String): Step[Unit] =
    def block(count: Long): Step[Unit] =
      if count < 0 then malformed(s"$path: negative skipped collection count")
      else if count == 0 then Step.done(())
      else items(count)
    def items(left: Long): Step[Unit] = Step.defer {
      if left == 0 then block(if isMap then in.mapNext() else in.arrayNext())
      else
        if isMap then in.skipString()
        element.readStep(in).flatMap(_ => items(left - 1))
    }
    Step.defer(block(if isMap then in.readMapStart() else in.readArrayStart()))

  private def mismatch(writer: Node, reader: Node, path: String, detail: String = ""): ReadPlan =
    action(_ => throw SchemaResolutionException(
      s"$path: cannot resolve ${writer.label} to ${reader.label}${if detail.isEmpty then "" else s": $detail"}"
    ))

  private def malformed(message: String): Nothing = throw AvroDecodingException(message)
  private def invalid(message: String): Nothing = throw SchemaResolutionException(message)

  private def codec(schema: Node): AvroCodec[?] =
    codecs.getOrElseUpdate(schema.name,
      if schema eq root then rootCodec else rootCodec.namedCodec(schema.name))

  // Index ownership without loading codecs for unused union alternatives.
  // Separately generated named models own the representation settings for their fields.
  private lazy val logicalOwners: Map[Node, Node] =
    val result = mutable.HashMap.empty[Node, Node]
    def visit(node: Node, inherited: Node): Unit =
      if !result.contains(node) then
        val owner = if node.name.nonEmpty then node else inherited
        result(node) = owner
        node.fields.foreach(field => visit(field.schema, owner))
        node.branches.foreach(visit(_, owner))
        if node.element != null then visit(node.element, owner)
    visit(root, root)
    result.toMap

  private def decimalRepresentation(schema: Node): DecimalRepresentation =
    codec(logicalOwners(schema)).decimalRepresentation

  private def rawLogicalType(schema: Node, name: String): Boolean =
    codec(logicalOwners(schema)).rawLogicalTypes(name)

  private def sameName(writer: Node, reader: Node): Boolean =
    writer.name == reader.name || reader.aliases(writer.name)

  private def decimalCompatible(writer: Node, reader: Node): Boolean =
    (writer.logical, reader.logical) match
      case (Some(a), Some(b)) if a.name == "decimal" && b.name == "decimal" =>
        a.precision == b.precision && a.scale == b.scale
      case _ => true

  private def matches(writer: Node, reader: Node): Boolean =
    if !decimalCompatible(writer, reader) then false
    else if writer.kind == reader.kind then
      writer.kind match
        case "record" | "enum" => sameName(writer, reader)
        case "fixed" => sameName(writer, reader) && writer.size == reader.size
        case _ => true
    else (writer.kind, reader.kind) match
      case ("int", "long" | "float" | "double") => true
      case ("long", "float" | "double") => true
      case ("float", "double") => true
      case ("string", "bytes") | ("bytes", "string") => true
      case _ => false

  def compile(writer: Node, reader: Node, path: String): ReadPlan =
    pairs.get((writer, reader)) match
      case Some(existing) => existing
      case None =>
        val deferred = new Deferred()
        pairs((writer, reader)) = deferred
        deferred.target = build(writer, reader, path)
        deferred

  private def build(writer: Node, reader: Node, path: String): ReadPlan =
    if writer.kind == "union" then
      val branches = writer.branches.zipWithIndex.map { (branch, index) =>
        compile(branch, reader, s"$path.writerUnion[$index]")
      }.toArray
      structural(branches.exists(_.canSuspend), branches.forall(_.isScalar)) { in =>
        val index = in.readIndex()
        if index < 0 || index >= branches.length then malformed(s"$path: invalid writer union index $index")
        branches(index).read(in)
      } { in =>
        Step.defer {
          val index = in.readIndex()
          if index < 0 || index >= branches.length then malformed(s"$path: invalid writer union index $index")
          branches(index).readStep(in)
        }
      }
    else if reader.kind == "union" then
      // Preserve the exact writer branch where available. Java Avro uses this
      // policy rather than selecting an earlier numeric/string promotion.
      val exact = reader.branches.indexWhere(branch => writer.kind == branch.kind && matches(writer, branch))
      val index = if exact >= 0 then exact else reader.branches.indexWhere(matches(writer, _))
      if index < 0 then mismatch(writer, reader, path, "no matching reader union branch")
      else
        val branch = reader.branches(index)
        val inner = compile(writer, branch, s"$path.readerUnion[$index]")
        val wrap = unionWrapper(reader, branch)
        structural(inner.canSuspend, inner.isScalar)(in => wrap(inner.read(in)))(in => inner.readStep(in).map(wrap))
    else if !matches(writer, reader) then mismatch(writer, reader, path)
    else reader.kind match
      case "record" => record(writer, reader, path)
      case "enum" =>
        val target = codec(reader)
        val fallback = reader.enumDefault.map(reader.symbols.indexOf).getOrElse(-1)
        val ordinals = writer.symbols.map { symbol =>
          val index = reader.symbols.indexOf(symbol)
          if index >= 0 then index else fallback
        }.toArray
        action { in =>
          val index = in.readEnum()
          if index < 0 || index >= ordinals.length then malformed(s"$path: invalid writer enum ordinal $index")
          val mapped = ordinals(index)
          if mapped < 0 then invalid(s"$path: reader enum ${reader.name} has no symbol '${writer.symbols(index)}' or default")
          target.construct(Array[Any](mapped))
        }
      case "fixed" =>
        val target = codec(reader)
        val convert = logicalConversion(reader)
        action(in => target.construct(Array[Any](convert(in.readFixed(writer.size)))))
      case "array" =>
        val element = compile(writer.element, reader.element, s"$path[]")
        structural(suspends = !element.isScalar, scalar = false) { in =>
          val result = Vector.newBuilder[Any]
          var count = in.readArrayStart()
          while count != 0 do
            if count < 0 then malformed(s"$path: negative array block count")
            var left = count
            while left > 0 do
              result += element.read(in)
              left -= 1
            count = in.arrayNext()
          result.result()
        }(in => StackSafe.readArray(in)(element.readStep(in)))
      case "map" =>
        val element = compile(writer.element, reader.element, s"$path{}")
        structural(suspends = !element.isScalar, scalar = false) { in =>
          val result = Map.newBuilder[String, Any]
          var count = in.readMapStart()
          while count != 0 do
            if count < 0 then malformed(s"$path: negative map block count")
            var left = count
            while left > 0 do
              val key = in.readString()
              result += key -> element.read(in)
              left -= 1
            count = in.mapNext()
          result.result()
        }(in => StackSafe.readMap(in)(element.readStep(in)))
      case _ =>
        val raw = primitive(writer.kind, reader.kind)
        val convert = logicalConversion(reader)
        action(in => convert(raw(in)))

  private def record(writer: Node, reader: Node, path: String): ReadPlan =
    val assigned = mutable.HashSet.empty[Int]
    val fields = writer.fields.map { written =>
      val candidates = reader.fields.zipWithIndex.filter { (expected, _) =>
        expected.name == written.name || expected.aliases(written.name)
      }
      if candidates.size > 1 then invalid(s"$path: ambiguous reader field aliases for '${written.name}'")
      candidates.headOption match
        case Some((expected, index)) =>
          if !assigned.add(index) then invalid(s"$path: multiple writer fields map to '${expected.name}'")
          index -> compile(written.schema, expected.schema, s"$path.${expected.name}")
        case None => -1 -> skip(written.schema, s"$path.${written.name}")
    }.toArray
    val missing = reader.fields.zipWithIndex.filterNot { (_, index) => assigned(index) }
    val required = missing.find(_._1.default.isEmpty)
    if required.nonEmpty then return mismatch(writer, reader, path, s"missing required reader field '${required.get._1.name}'")
    val defaults = missing.map { (field, index) =>
      index -> defaultValue(field.schema, field.default.get, s"$path.${field.name}.default")
    }.toArray
    val fieldSuspends = fields.map(_._2.canSuspend)
    val defaultsInline = !defaults.exists(_._2.canSuspend)
    val allInline = !fieldSuspends.contains(true) && defaultsInline
    val target = codec(reader)
    val direct: AvroInput => Any = in =>
      in.enterRecord()
      try
        val values = new Array[Any](reader.fields.size)
        var index = 0
        while index < fields.length do
          val (slot, field) = fields(index)
          val value = field.read(in)
          if slot >= 0 then values(slot) = value
          index += 1
        index = 0
        while index < defaults.length do
          val (slot, value) = defaults(index)
          values(slot) = value()
          index += 1
        target.construct(values)
      finally in.leaveRecord()
    structural(direct) { in =>
      // This record remains a suspension boundary to its parent. Only its own
      // scalar fields and scalar-element collections run in a single operation.
      if allInline then Step.delay(direct(in))
      else new Step.Frame[Any]:
        private val values = new Array[Any](reader.fields.size)
        private var fieldIndex = 0
        private var defaultIndex = 0
        private var pendingSlot = -1
        private var entered = false
        private var constructed: Any = null

        override def advance(completed: Any): Step[?] =
          if !entered then
            in.enterRecord()
            entered = true
          else if pendingSlot >= 0 then values(pendingSlot) = completed
          pendingSlot = -1
          while fieldIndex < fields.length do
            val index = fieldIndex
            fieldIndex += 1
            val (slot, field) = fields(index)
            if fieldSuspends(index) then
              pendingSlot = slot
              return field.readStep(in)
            val value = field.read(in)
            if slot >= 0 then values(slot) = value
          while defaultIndex < defaults.length do
            val (slot, value) = defaults(defaultIndex)
            defaultIndex += 1
            if value.canSuspend then
              pendingSlot = slot
              return value.materialize
            values(slot) = value()
          constructed = target.construct(values)
          null

        override def result: Any = constructed

        override def cleanup(): Unit =
          if entered then
            entered = false
            in.leaveRecord()
    }

  private def primitive(writer: String, reader: String): AvroInput => Any =
    (writer, reader) match
      case ("null", "null") => in => { in.readNull(); null }
      case ("boolean", "boolean") => _.readBoolean()
      case ("int", "int") => _.readInt()
      case ("int", "long") => in => in.readInt().toLong
      case ("int", "float") => in => in.readInt().toFloat
      case ("int", "double") => in => in.readInt().toDouble
      case ("long", "long") => _.readLong()
      case ("long", "float") => in => in.readLong().toFloat
      case ("long", "double") => in => in.readLong().toDouble
      case ("float", "float") => _.readFloat()
      case ("float", "double") => in => in.readFloat().toDouble
      case ("double", "double") => _.readDouble()
      case ("string", "string") => _.readString()
      case ("bytes", "bytes") => _.readBytes()
      case ("string", "bytes") => _.readStringAsBytes()
      case ("bytes", "string") => _.readBytesAsString()
      case _ => invalid(s"Unsupported primitive promotion $writer -> $reader")

  private def logicalConversion(schema: Node): Any => Any = schema.logical match
    case None => identity
    case Some(logical) if rawLogicalType(schema, logical.name) => identity
    case Some(logical) => logical.name match
      case "date" => value => LogicalValues.dateFromDays(value.asInstanceOf[Int])
      case "time-millis" => value => LogicalValues.timeFromMillis(value.asInstanceOf[Int])
      case "time-micros" => value => LogicalValues.timeFromMicros(value.asInstanceOf[Long])
      case "timestamp-millis" => value => LogicalValues.instantFromMillis(value.asInstanceOf[Long])
      case "timestamp-micros" => value => LogicalValues.instantFromMicros(value.asInstanceOf[Long])
      case "timestamp-nanos" => value => LogicalValues.instantFromNanos(value.asInstanceOf[Long])
      case "local-timestamp-millis" => value => LogicalValues.localDateTimeFromMillis(value.asInstanceOf[Long])
      case "local-timestamp-micros" => value => LogicalValues.localDateTimeFromMicros(value.asInstanceOf[Long])
      case "local-timestamp-nanos" => value => LogicalValues.localDateTimeFromNanos(value.asInstanceOf[Long])
      case "uuid" if schema.kind == "fixed" => value => LogicalValues.uuidFromFixed(value.asInstanceOf[Bytes])
      case "uuid" => value => LogicalValues.uuidFromString(value.asInstanceOf[String])
      case "decimal" =>
        if decimalRepresentation(schema) == DecimalRepresentation.Java then
          value => LogicalValues.javaDecimalFromBytes(value.asInstanceOf[Bytes], logical.precision, logical.scale)
        else value => LogicalValues.decimalFromBytes(value.asInstanceOf[Bytes], logical.precision, logical.scale)
      case "big-decimal" =>
        if decimalRepresentation(schema) == DecimalRepresentation.Java then
          value => LogicalValues.javaBigDecimalFromBytes(value.asInstanceOf[Bytes])
        else value => LogicalValues.bigDecimalFromBytes(value.asInstanceOf[Bytes])
      case "duration" => value => LogicalValues.durationFromBytes(value.asInstanceOf[Bytes])
      case other => invalid(s"Unsupported logical type '$other'")

  private def unionWrapper(union: Node, branch: Node): Any => Any =
    def convertedTime(name: String): Boolean =
      union.branches.exists(branch => branch.logical.exists(_.name == name) && !rawLogicalType(branch, name))
    val tagTime = convertedTime("time-millis") && convertedTime("time-micros")
    val wrapValue: Any => Any =
      if tagTime && branch.logical.exists(_.name == "time-millis") then
        value => TimeMillis(value.asInstanceOf[java.time.LocalTime])
      else if tagTime && branch.logical.exists(_.name == "time-micros") then
        value => TimeMicros(value.asInstanceOf[java.time.LocalTime])
      else identity
    if union.branches.size > 1 && union.branches.exists(_.kind == "null") then
      if branch.kind == "null" then _ => None else value => Some(wrapValue(value))
    else wrapValue

  private def skip(schema: Node, path: String): ReadPlan =
    skips.get(schema) match
      case Some(existing) => existing
      case None =>
        val deferred = new Deferred()
        skips(schema) = deferred
        deferred.target = schema.kind match
          case "record" =>
            val fields = schema.fields.map(field => skip(field.schema, s"$path.${field.name}")).toArray
            val suspends = fields.map(_.canSuspend)
            val allInline = !suspends.contains(true)
            val direct: AvroInput => Any = in =>
              in.enterRecord()
              try
                fields.foreach(_.read(in))
                ()
              finally in.leaveRecord()
            structural(direct) { in =>
              if allInline then Step.delay(direct(in))
              else new Step.Frame[Unit]:
                private var index = 0
                private var entered = false

                override def advance(completed: Any): Step[?] =
                  if !entered then
                    in.enterRecord()
                    entered = true
                  while index < fields.length do
                    val slot = index
                    index += 1
                    if suspends(slot) then return fields(slot).readStep(in)
                    fields(slot).read(in)
                  null

                override def result: Unit = ()

                override def cleanup(): Unit =
                  if entered then
                    entered = false
                    in.leaveRecord()
            }
          case "union" =>
            val branches = schema.branches.map(skip(_, path)).toArray
            structural(branches.exists(_.canSuspend), branches.forall(_.isScalar)) { in =>
              val index = in.readIndex()
              if index < 0 || index >= branches.length then malformed(s"$path: invalid skipped union index $index")
              branches(index).read(in)
              ()
            } { in =>
              Step.defer {
                val index = in.readIndex()
                if index < 0 || index >= branches.length then malformed(s"$path: invalid skipped union index $index")
                branches(index).readStep(in).map(_ => ())
              }
            }
          case "array" | "map" =>
            val element = skip(schema.element, path)
            val isMap = schema.kind == "map"
            structural(suspends = !element.isScalar, scalar = false) { in =>
              var count = if isMap then in.readMapStart() else in.readArrayStart()
              while count != 0 do
                if count < 0 then malformed(s"$path: negative skipped collection count")
                var left = count
                while left > 0 do
                  if isMap then in.skipString()
                  element.read(in)
                  left -= 1
                count = if isMap then in.mapNext() else in.arrayNext()
              ()
            }(in => skipCollection(in, isMap, element, path))
          case "string" => action(in => in.skipString())
          case "bytes" => action(in => in.skipBytes())
          case "fixed" => action(in => in.skipFixed(schema.size))
          case "enum" => action { in =>
            val ordinal = in.readEnum()
            if ordinal < 0 || ordinal >= schema.symbols.size then malformed(s"$path: invalid skipped enum ordinal $ordinal")
            ()
          }
          case primitiveKind => action(primitive(primitiveKind, primitiveKind))
        deferred

  private def defaultValue(schema: Node, json: JsonNode, path: String): DefaultPlan =
    val key = (schema, json)
    if defaultStack.size >= 256 then invalid(s"$path: default nesting exceeds 256 levels")
    if !defaultStack.add(key) then invalid(s"$path: recursive default does not terminate")
    try compileDefault(schema, json, path)
    finally defaultStack.remove(key)

  private def compileDefault(schema: Node, json: JsonNode, path: String): DefaultPlan =
    def bad(): Nothing = invalid(s"$path: invalid default for ${schema.label}: $json")
    def constant(value: Any): DefaultPlan = new DefaultPlan:
      override def apply(): Any = value
    def bytes(): Bytes =
      if !json.isTextual then bad()
      val value = json.textValue()
      if value.exists(_.toInt > 255) then bad()
      Bytes.fromArray(value.map(_.toByte).toArray)
    val raw: DefaultPlan = schema.kind match
      case "null" => if json.isNull then constant(null) else bad()
      case "boolean" => if json.isBoolean then constant(json.booleanValue()) else bad()
      case "int" => if json.isIntegralNumber && json.canConvertToInt then constant(json.intValue()) else bad()
      case "long" => if json.isIntegralNumber && json.canConvertToLong then constant(json.longValue()) else bad()
      case "float" => if json.isNumber then constant(json.floatValue()) else bad()
      case "double" => if json.isNumber then constant(json.doubleValue()) else bad()
      case "string" => if json.isTextual then constant(json.textValue()) else bad()
      case "bytes" => constant(bytes())
      case "fixed" =>
        val value = bytes()
        if value.size != schema.size then bad()
        val underlying = logicalConversion(schema)(value)
        val target = codec(schema)
        return defaultAction(suspends = false, scalar = true)(() => target.construct(Array[Any](underlying))) {
          Step.delay(target.construct(Array[Any](underlying)))
        }
      case "enum" =>
        if !json.isTextual then bad()
        val ordinal = schema.symbols.indexOf(json.textValue())
        if ordinal < 0 then bad()
        val target = codec(schema)
        return defaultAction(suspends = false, scalar = true)(() => target.construct(Array[Any](ordinal))) {
          Step.delay(target.construct(Array[Any](ordinal)))
        }
      case "record" =>
        if !json.isObject then bad()
        val fields = schema.fields.map { field =>
          val value = Option(json.get(field.name)).orElse(field.default)
            .getOrElse(invalid(s"$path: missing default record field '${field.name}'"))
          defaultValue(field.schema, value, s"$path.${field.name}")
        }.toArray
        val target = codec(schema)
        val plans = fields.toIndexedSeq
        return defaultAction()(() => target.construct(fields.map(_()))) {
          val values = new Array[Any](fields.length)
          fillDefaults(plans)((index, value) => values(index) = value)(target.construct(values))
        }
      case "array" =>
        if !json.isArray then bad()
        val values = json.elements().asScala.map(defaultValue(schema.element, _, s"$path[]")).toVector
        return defaultAction(suspends = !values.forall(_.isScalar))(() => values.map(_())) {
          val result = Vector.newBuilder[Any]
          fillDefaults(values)((_, value) => { result += value; () })(result.result())
        }
      case "map" =>
        if !json.isObject then bad()
        val values = json.properties().iterator().asScala.map { entry =>
          entry.getKey -> defaultValue(schema.element, entry.getValue, s"$path.${entry.getKey}")
        }.toVector
        val plans = values.map(_._2)
        return defaultAction(suspends = !plans.forall(_.isScalar))(() => values.iterator.map { (key, value) => key -> value() }.toMap) {
          val result = Map.newBuilder[String, Any]
          fillDefaults(plans) { (index, value) =>
            result += values(index)._1 -> value
            ()
          }(result.result())
        }
      case "union" =>
        val iterator = schema.branches.iterator
        while iterator.hasNext do
          val branch = iterator.next()
          val candidate = try Some(defaultValue(branch, json, path))
          catch case _: SchemaResolutionException => None
          candidate match
            case Some(value) =>
              val wrap = unionWrapper(schema, branch)
              return defaultAction(value.canSuspend, value.isScalar)(() => wrap(value()))(value.materialize.map(wrap))
            case None => ()
        bad()
      case _ => bad()
    val converted = logicalConversion(schema)(raw())
    constant(converted)

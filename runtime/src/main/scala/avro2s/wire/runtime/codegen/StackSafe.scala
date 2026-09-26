package avro2s.wire.runtime.codegen

import avro2s.wire.runtime.*

/** Collection and record operations shared by generated codecs and resolution.
  * Each collection schedules one item at a time, keeping pending work proportional
  * to nesting depth rather than the number of sibling elements.
  */
object StackSafe:
  def readRecord[A](in: AvroInput)(fields: => Step[A]): Step[A] = Step.record(in)(fields)

  def readArray[A](in: AvroInput)(element: => Step[A]): Step[Vector[A]] = new Step.Frame[Vector[A]]:
    private var started = false
    private var remaining = 0L
    private var builder: scala.collection.mutable.Builder[A, Vector[A]] = null

    override def advance(completed: Any): Step[?] =
      if !started then
        started = true
        remaining = in.readArrayStart()
        if remaining == 0 then return null
        builder = Vector.newBuilder[A]
      else
        builder += completed.asInstanceOf[A]
        remaining -= 1
        if remaining == 0 then remaining = in.arrayNext()
      if remaining < 0 then throw new AvroDecodingException("Negative array block count")
      if remaining == 0 then null else element

    override def result: Vector[A] = if builder == null then Vector.empty[A] else builder.result()

  def readMap[A](in: AvroInput)(element: => Step[A]): Step[Map[String, A]] = new Step.Frame[Map[String, A]]:
    private var started = false
    private var remaining = 0L
    private var key: String = null
    private var builder: scala.collection.mutable.Builder[(String, A), Map[String, A]] = null

    override def advance(completed: Any): Step[?] =
      if !started then
        started = true
        remaining = in.readMapStart()
        if remaining == 0 then return null
        builder =
          if remaining > 4L then scala.collection.immutable.HashMap.newBuilder[String, A]
          else Map.newBuilder[String, A]
      else
        builder += ((key, completed.asInstanceOf[A]))
        remaining -= 1
        if remaining == 0 then remaining = in.mapNext()
      if remaining < 0 then throw new AvroDecodingException("Negative map block count")
      if remaining == 0 then null
      else
        key = in.readString()
        element

    override def result: Map[String, A] = if builder == null then Map.empty[String, A] else builder.result()

  def writeArray[A](out: AvroOutput, values: Vector[A])(element: A => Step[Unit]): Step[Unit] = new Step.Frame[Unit]:
    private var iterator: Iterator[A] = null

    override def advance(completed: Any): Step[?] =
      if iterator == null then
        out.writeArrayStart(values.size)
        iterator = values.iterator
      if iterator.hasNext then
        out.startItem()
        element(iterator.next())
      else
        out.writeArrayEnd()
        null

    override def result: Unit = ()

  def writeMap[A](out: AvroOutput, values: Map[String, A])(element: A => Step[Unit]): Step[Unit] = new Step.Frame[Unit]:
    private var iterator: Iterator[(String, A)] = null

    override def advance(completed: Any): Step[?] =
      if iterator == null then
        out.writeMapStart(values.size)
        iterator = values.iterator
      if iterator.hasNext then
        out.startItem()
        val (key, item) = iterator.next()
        out.writeString(key)
        element(item)
      else
        out.writeMapEnd()
        null

    override def result: Unit = ()

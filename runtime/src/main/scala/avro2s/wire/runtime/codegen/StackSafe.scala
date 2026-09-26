package avro2s.wire.runtime.codegen

import avro2s.wire.runtime.*

/** Collection and record operations shared by generated codecs and resolution.
  * Each collection schedules one item at a time, keeping pending work proportional
  * to nesting depth rather than the number of sibling elements.
  */
object StackSafe:
  def readRecord[A](in: AvroInput)(fields: => Step[A]): Step[A] = Step.defer {
    in.enterRecord()
    Step.guarantee(fields)(in.leaveRecord())
  }

  def readArray[A](in: AvroInput)(element: => Step[A]): Step[Vector[A]] = Step.defer {
    var remaining = in.readArrayStart()
    if remaining == 0 then Step.done(Vector.empty[A])
    else
      val builder = Vector.newBuilder[A]
      def loop(): Step[Vector[A]] =
        if remaining < 0 then throw new AvroDecodingException("Negative array block count")
        else if remaining == 0 then Step.done(builder.result())
        else Step.defer(element).flatMap { item =>
          builder += item
          remaining -= 1
          if remaining == 0 then remaining = in.arrayNext()
          Step.defer(loop())
        }
      loop()
  }

  def readMap[A](in: AvroInput)(element: => Step[A]): Step[Map[String, A]] = Step.defer {
    var remaining = in.readMapStart()
    if remaining == 0 then Step.done(Map.empty[String, A])
    else
      val builder =
        if remaining > 4L then scala.collection.immutable.HashMap.newBuilder[String, A]
        else Map.newBuilder[String, A]
      def loop(): Step[Map[String, A]] =
        if remaining < 0 then throw new AvroDecodingException("Negative map block count")
        else if remaining == 0 then Step.done(builder.result())
        else
          val key = in.readString()
          Step.defer(element).flatMap { item =>
            builder += ((key, item))
            remaining -= 1
            if remaining == 0 then remaining = in.mapNext()
            Step.defer(loop())
          }
      loop()
  }

  def writeArray[A](out: AvroOutput, values: Vector[A])(element: A => Step[Unit]): Step[Unit] = Step.defer {
    out.writeArrayStart(values.size)
    val iterator = values.iterator
    def loop(): Step[Unit] =
      if !iterator.hasNext then Step.delay(out.writeArrayEnd())
      else
        out.startItem()
        val item = iterator.next()
        Step.defer(element(item)).flatMap(_ => Step.defer(loop()))
    loop()
  }

  def writeMap[A](out: AvroOutput, values: Map[String, A])(element: A => Step[Unit]): Step[Unit] = Step.defer {
    out.writeMapStart(values.size)
    val iterator = values.iterator
    def loop(): Step[Unit] =
      if !iterator.hasNext then Step.delay(out.writeMapEnd())
      else
        out.startItem()
        val (key, item) = iterator.next()
        out.writeString(key)
        Step.defer(element(item)).flatMap(_ => Step.defer(loop()))
    loop()
  }

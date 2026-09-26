package avro2s.wire.runtime.codegen

import avro2s.wire.runtime.*

/** Support protocol for generated stack-safe codecs. Applications normally use
  * AvroCodec's read/write methods; child generated operations use the step methods.
  */
trait StackSafeCodec[A] extends AvroCodec[A]:
  final override def execution: CodecExecution = CodecExecution.StackSafe

  def readStep(in: AvroInput): Step[A]
  def writeStep(value: A, out: AvroOutput): Step[Unit]

  final override def read(in: AvroInput): A = Step.run(Step.defer(readStep(in)))
  final override def write(value: A, out: AvroOutput): Unit =
    Step.run(Step.defer(writeStep(value, out)))

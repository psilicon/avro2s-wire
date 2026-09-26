package example.stack

import avro2s.wire.resolution.ResolvingReader
import avro2s.wire.runtime.{AvroCodec, BinaryInput, BinaryOutput, CodecExecution, DecodeLimits}

object Usage:
  def main(args: Array[String]): Unit =
    // Node and NodeV2 are generated from the schemas alongside this file.
    val direct: AvroCodec[Node] = Node.codec
    val safe: AvroCodec[Node] = Node.stackSafeCodec
    assert(direct.execution == CodecExecution.Direct)
    assert(safe.execution == CodecExecution.StackSafe)
    assert(summon[AvroCodec[Node]] eq direct) // The existing given stays direct.

    val small = Node(Some(Node(None, 1)), 2)
    assert(java.util.Arrays.equals(direct.encode(small), safe.encode(small)))
    assert(direct.decode(safe.encode(small)) == small)
    assert(safe.decode(direct.encode(small)) == small)

    val depth = 100000
    val deep = (1 until depth).foldLeft(Node(None, 0)) { (tail, value) =>
      Node(Some(tail), value)
    }
    val limits = DecodeLimits(
      maxInputBytes = None,
      maxStringBytes = None,
      maxBytesLength = None,
      maxCollectionItems = None,
      maxNestingDepth = None
    )
    val bytes = safe.encode(deep)
    val restored: Node = safe.decode(bytes, limits)
    // Inspect deep values iteratively: generated case-class equality is recursive.
    val restoredCount = Iterator.iterate(Option(restored))(_.flatMap(_.next))
      .takeWhile(_.nonEmpty).size
    assert(restoredCount == depth)

    // The same codec works with caller-owned input/output instances.
    val output = new BinaryOutput(1024)
    safe.write(deep, output)
    val input = new BinaryInput(output.toByteArray, limits)
    assert(safe.read(input).value == depth - 1)
    input.requireEnd()

    // Reader selection also makes evolved-schema traversal stack safe.
    // This renames the record, reorders fields, promotes int to long and adds a default.
    val reader = ResolvingReader(Node.schemaJson, NodeV2.stackSafeCodec)
    val evolved: NodeV2 = reader.decode(bytes, limits)
    val evolvedNodes = Iterator.iterate(Option(evolved))(_.flatMap(_.next)).takeWhile(_.nonEmpty)
    assert(evolvedNodes.zipWithIndex.forall { (node, index) =>
      node.get.value == (depth - index - 1).toLong && node.get.added
    })
    println(s"Read, wrote and resolved $restoredCount nested immutable records.")

package avro2s.wire.registry

import avro2s.wire.fixtures.stacks.{StackHead, StackNode, StackNodeV2}
import avro2s.wire.runtime.DecodeLimits
import io.confluent.kafka.schemaregistry.avro.AvroSchema
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient
import java.util.concurrent.atomic.AtomicReference
import org.apache.kafka.common.errors.SerializationException
import scala.util.Using

final class RegistryStackSafetySuite extends munit.FunSuite:
  test("selecting a stack-safe codec covers framing, identical schemas, resolution, and skipped fields") {
    val depth = 20000
    val value = (1 until depth).foldLeft(StackNode(None, 0))((tail, i) => StackNode(Some(tail), i))
    val client = new MockSchemaRegistryClient()
    client.register("stack-value", new AvroSchema(StackNode.schemaJson))
    Using.Manager { use =>
      val serializer = use(RegistrySerializer.forValue(StackNode.stackSafeCodec, client))
      val same = use(RegistryDeserializer.forValue(StackNode.stackSafeCodec, client))
      val evolved = use(RegistryDeserializer.forValue(StackNodeV2.stackSafeCodec, client))
      val skipped = use(RegistryDeserializer.forValue(StackHead.stackSafeCodec, client))
      val limited = use(RegistryDeserializer.forValue(StackNodeV2.stackSafeCodec, client,
        DeserializerSettings(decodeLimits = DecodeLimits(maxNestingDepth = Some(32)))))
      val failure = new AtomicReference[Throwable]()
      val runnable = new Runnable:
        override def run(): Unit =
          try
            val bytes = serializer.serialize("stack", value)
            var original = Option(same.deserialize("stack", bytes))
            var converted = Option(evolved.deserialize("stack", bytes))
            var expected = depth - 1
            while original.nonEmpty && converted.nonEmpty do
              assertEquals(original.get.value, expected)
              assertEquals(converted.get.value, expected.toLong)
              assert(converted.get.added)
              original = original.get.next
              converted = converted.get.next
              expected -= 1
            assert(original.isEmpty && converted.isEmpty)
            assertEquals(expected, -1)
            assertEquals(skipped.deserialize("stack", bytes).value, (depth - 1).toLong)
            val error = intercept[SerializationException](limited.deserialize("stack", bytes))
            assert(error.getCause.getMessage.contains("maxNestingDepth"))
            // Reusing cached readers after either a successful or failed read
            // must not retain the preceding datum's continuation state.
            val small = serializer.serialize("stack", StackNode(None, 7))
            assertEquals(evolved.deserialize("stack", small), StackNodeV2(7L, None, true))
            assertEquals(limited.deserialize("stack", small), StackNodeV2(7L, None, true))
          catch case error: Throwable => failure.set(error)
      val thread = new Thread(null, runnable, "registry-stack-safety", 256 * 1024L)
      thread.setDaemon(true)
      thread.start()
      thread.join(60000L)
      assert(!thread.isAlive, "Registry stack-safety test did not finish within 60 seconds")
      Option(failure.get()).foreach(error => throw error)
    }.get
    client.close()
  }

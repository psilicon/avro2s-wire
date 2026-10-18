package avro2s.wire.registry

final class SchemaLookupSuite extends munit.FunSuite:
  test("topic lookup distinguishes key and value subjects") {
    assertEquals(SchemaLookup.TopicName().subject("orders", RegistryRole.Key), Some("orders-key"))
    assertEquals(SchemaLookup.TopicName().subject("orders", RegistryRole.Value), Some("orders-value"))
  }

  test("context names use Confluent qualification without changing explicit subjects") {
    for context <- Seq("orders", ".orders", ":.orders:") do
      assertEquals(SchemaLookup.TopicName(Some(context)).subject("events", RegistryRole.Value),
        Some(":.orders:events-value"))
      assertEquals(SchemaLookup.Context(context).subject(null, RegistryRole.Key), Some(":.orders:"))
    assertEquals(SchemaLookup.Subject(":.orders:chosen").subject(null, RegistryRole.Value),
      Some(":.orders:chosen"))
    assertEquals(SchemaLookup.ById.subject(null, RegistryRole.Value), None)
    assertEquals(SchemaLookup.Context(".").subject(null, RegistryRole.Value), Some(""))
  }

  test("invalid hints fail during settings validation") {
    for policy <- Seq(SchemaLookup.Subject(""), SchemaLookup.Subject(null), SchemaLookup.Context(""),
        SchemaLookup.Context(null), SchemaLookup.Context("a:b"), SchemaLookup.Context("*"),
        SchemaLookup.TopicName(Some(" ")), SchemaLookup.TopicName(null)) do
      intercept[IllegalArgumentException](policy.validate())
    intercept[IllegalArgumentException](SchemaLookup.TopicName().subject("", RegistryRole.Key))
  }

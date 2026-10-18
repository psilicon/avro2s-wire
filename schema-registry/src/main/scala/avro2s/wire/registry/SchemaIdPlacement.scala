package avro2s.wire.registry

/** Where a writer puts the registry's schema identity. */
enum SchemaIdPlacement:
  /** Classic version-zero numeric ID followed by the Avro datum. */
  case PayloadPrefix
  /** Version-one GUID in the role's Kafka header, leaving only Avro in the payload. */
  case HeaderGuid

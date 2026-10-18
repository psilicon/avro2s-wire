package avro2s.wire.registry

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient

/** A registry serializer or deserializer participating in a coordinated cache refresh.
  * Pass all adapters sharing a client to [[RegistryCaches.refresh]].
  */
trait RegistryAdapter:
  private[registry] def registryClient: SchemaRegistryClient
  private[registry] def registryLifecycle: RegistryLifecycle
  private[registry] def clearRegistryCache(): Unit

package avro2s.wire.registry

/** Connection settings for an adapter-owned registry client.
  *
  * Properties configure networking, authentication and TLS. Put serializer
  * behavior in [[SerializerSettings]]. Reusing these settings creates independent
  * clients; inject a shared SchemaRegistryClient when sharing client ownership.
  */
final case class RegistryConnection(
    urls: List[String],
    cacheCapacity: Int = 1024,
    properties: Map[String, AnyRef] = Map.empty
):
  require(urls != null && urls.nonEmpty && urls.forall(url => url != null && url.trim.nonEmpty),
    "At least one Schema Registry URL is required")
  require(cacheCapacity > 0, "Registry client cacheCapacity must be positive")
  require(properties != null, "Registry client properties must be non-null")
  private[registry] val clientProperties = RegistrySupport.clientProperties(properties)

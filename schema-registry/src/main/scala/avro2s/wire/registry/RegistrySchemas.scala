package avro2s.wire.registry

import io.confluent.kafka.schemaregistry.SchemaProvider
import io.confluent.kafka.schemaregistry.avro.{AvroSchema, AvroSchemaProvider}
import io.confluent.kafka.schemaregistry.client.{SchemaRegistryClient, SchemaVersionFetcher}
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException
import io.confluent.kafka.schemaregistry.utils.QualifiedSubject
import java.io.IOException
import org.apache.kafka.common.KafkaException
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Resolve references before compiling a reader, without using Confluent's
  * context-insensitive parsed-schema cache. The client's raw entity/version
  * caches remain useful and are invalidated by its public reset operation.
  */
private[registry] object RegistrySchemas:
  def byId(client: SchemaRegistryClient, subject: Option[String], id: Int): String =
    val entity = try client.getSchemaEntityBySubjectAndId(subject.orNull, id)
      catch case error: UnsupportedOperationException =>
        throw new IllegalArgumentException(
          "Numeric schema reads require SchemaRegistryClient.getSchemaEntityBySubjectAndId", error)
    require(entity != null, "Registry returned no schema")
    val copy = entity.copy()
    if copy.getSubject == null || copy.getSubject.isEmpty then copy.setSubject(subject.orNull)
    resolve(client, copy)

  def byGuid(client: SchemaRegistryClient, guid: String): String = preserveRegistryFailures {
    client.getSchemaByGuid(guid, null) match
      case schema: AvroSchema =>
        // The GUID response has no parent context. Even explicitly spelling the
        // default context (:.:child) is relative under Confluent's qualification
        // rules, so only a named context anchors a root reference unambiguously.
        require(schema.references().asScala.forall { reference =>
          val subject = QualifiedSubject.create(QualifiedSubject.DEFAULT_TENANT, reference.getSubject)
          subject != null && subject.getContext != QualifiedSubject.DEFAULT_CONTEXT &&
            subject.getContext != QualifiedSubject.WILDCARD && subject.getSubject.nonEmpty
        }, "GUID schemas with relative references are unsupported; use references fully qualified " +
          "with a named context (for example :.sales:child), or numeric-ID framing")
        if schema.references().isEmpty then RegistrySupport.writerSchema(schema)
        else
          // canonicalString retains external reference names. Re-resolve them
          // from raw versions rather than trusting a previously expanded schema.
          resolve(client, new Schema(null, null, null, schema).copy())
      case _ => throw new IllegalArgumentException("Registry schema is not Avro")
  }

  private def resolve(client: SchemaRegistryClient, entity: Schema): String =
    require(entity.getSchemaType == null || entity.getSchemaType == AvroSchema.TYPE,
      "Registry schema is not Avro")
    val fetcher = new SchemaVersionFetcher:
      override def tenant(): String = client.tenant()
      override def getByVersion(subject: String, version: Int, deleted: Boolean): Schema =
        val schema = client.getByVersion(subject, version, deleted)
        // The provider resolves version -1 by modifying its descriptor. Never
        // let that mutation reach an entity shared in the client's cache.
        if schema == null then null else schema.copy()
    val provider = new AvroSchemaProvider()
    provider.configure(java.util.Map.of(SchemaProvider.SCHEMA_VERSION_FETCHER_CONFIG, fetcher))
    preserveRegistryFailures {
      RegistrySupport.writerSchema(provider.parseSchemaOrElseThrow(entity, false, false))
    }

  private def preserveRegistryFailures[A](body: => A): A =
    try body
    catch
      case NonFatal(error) => throw registryCause(error).getOrElse(error)

  /** The client and schema provider wrap reference-fetch failures. Recover only
    * operational causes here so the adapter preserves retry/auth/interrupt semantics.
    */
  @tailrec private def registryCause(error: Throwable): Option[Throwable] = error match
    case _: RestClientException | _: IOException | _: InterruptedException | _: KafkaException => Some(error)
    case _ =>
      val cause = error.getCause
      if cause == null || (cause eq error) then None else registryCause(cause)

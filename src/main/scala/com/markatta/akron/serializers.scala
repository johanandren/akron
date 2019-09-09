/**
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package com.markatta.akron

import java.util.UUID

import akka.actor.typed.internal.receptionist.DefaultServiceKey
import akka.actor.typed.receptionist.ServiceKey
import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.{DeserializationContext, JavaType, JsonNode, SerializerProvider}
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.jsontype.TypeSerializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer

import scala.reflect.ClassTag

class ServiceKeySerializer extends StdSerializer[ServiceKey[_]](classOf[ServiceKey[_]]) {

  def serialize(value: ServiceKey[_], gen: JsonGenerator, provider: SerializerProvider): Unit = {
    gen.writeStartObject()
    gen.writeStringField("id", value.id)
    gen.writeStringField("class", value.asInstanceOf[DefaultServiceKey[_]].typeName)
    gen.writeEndObject()
  }
}


class ServiceKeyDeserializer extends StdDeserializer[ServiceKey[_]](classOf[ServiceKey[_]]) {
  def deserialize(p: JsonParser, ctxt: DeserializationContext): ServiceKey[_] = {
    val tree: JsonNode = p.getCodec.readTree(p)
    val id = tree.get("id").asText()
    val clazz = getClass.getClassLoader.loadClass(tree.get("class").asText())
    ServiceKey[AnyRef](id)(ClassTag(clazz))
  }
}


class CronExpressionSerializer extends StdSerializer[CronExpression](classOf[CronExpression]) {

  override def serializeWithType(value: CronExpression, gen: JsonGenerator, serializers: SerializerProvider, typeSer: TypeSerializer): Unit = {
    gen.writeStartObject()
    gen.writeFieldName(typeSer.getPropertyName)
    gen.writeString(value.getClass.getName)
    gen.writeFieldName("expr")
    serialize(value, gen, serializers)

    gen.writeEndObject()
  }

  def serialize(value: CronExpression, gen: JsonGenerator, provider: SerializerProvider): Unit = {
    gen.writeString(value.toString)
  }
}

class CronExpressionDeserializer extends StdDeserializer[CronExpression](classOf[CronExpression]) {
  def deserialize(p: JsonParser, ctxt: DeserializationContext): CronExpression = {
    val tree:JsonNode = p.readValueAsTree()
    CronExpression(tree.get("expr").asText())
  }
}
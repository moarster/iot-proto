package ru.iteco.opcua.config

import org.bson.BsonReader
import org.bson.BsonWriter
import org.bson.codecs.Codec
import org.bson.codecs.DecoderContext
import org.bson.codecs.EncoderContext
import org.eclipse.milo.opcua.stack.core.types.builtin.*
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.ULong
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort

// UByte codec
class UByteCodec : Codec<UByte> {
    override fun encode(writer: BsonWriter, value: UByte, encoderContext: EncoderContext) {
        writer.writeInt32(value.toInt())
    }

    override fun decode(reader: BsonReader, decoderContext: DecoderContext): UByte {
        return UByte.valueOf(reader.readInt32().toShort())
    }

    override fun getEncoderClass(): Class<UByte> = UByte::class.java
}

// UShort codec
class UShortCodec : Codec<UShort> {
    override fun encode(writer: BsonWriter, value: UShort, encoderContext: EncoderContext) {
        writer.writeInt32(value.toInt())
    }

    override fun decode(reader: BsonReader, decoderContext: DecoderContext): UShort {
        return UShort.valueOf(reader.readInt32())
    }

    override fun getEncoderClass(): Class<UShort> = UShort::class.java
}

// UInteger codec (you probably already have this)
class UIntegerCodec : Codec<UInteger> {
    override fun encode(writer: BsonWriter, value: UInteger, encoderContext: EncoderContext) {
        writer.writeInt64(value.toLong())
    }

    override fun decode(reader: BsonReader, decoderContext: DecoderContext): UInteger {
        return UInteger.valueOf(reader.readInt64())
    }

    override fun getEncoderClass(): Class<UInteger> = UInteger::class.java
}

// ULong codec
class ULongCodec : Codec<ULong> {
    override fun encode(writer: BsonWriter, value: ULong, encoderContext: EncoderContext) {
        // Store as string since BSON doesn't support unsigned 64-bit
        writer.writeString(value.toString())
    }

    override fun decode(reader: BsonReader, decoderContext: DecoderContext): ULong {
        return ULong.valueOf(reader.readString())
    }

    override fun getEncoderClass(): Class<ULong> = ULong::class.java
}

// DateTime codec
class DateTimeCodec : Codec<DateTime> {
    override fun encode(writer: BsonWriter, value: DateTime, encoderContext: EncoderContext) {
        writer.writeDateTime(value.javaTime)
    }

    override fun decode(reader: BsonReader, decoderContext: DecoderContext): DateTime {
        return DateTime(reader.readDateTime())
    }

    override fun getEncoderClass(): Class<DateTime> = DateTime::class.java
}

// NodeId codec
class NodeIdCodec : Codec<NodeId> {
    override fun encode(writer: BsonWriter, value: NodeId, encoderContext: EncoderContext) {
        writer.writeStartDocument()
        writer.writeInt32("namespaceIndex", value.namespaceIndex.toInt())
        writer.writeName("identifier")

        when (val identifier = value.identifier) {
            is UInteger -> writer.writeInt64(identifier.toLong())
            is String -> writer.writeString(identifier)
            is ByteString -> writer.writeBinaryData(org.bson.BsonBinary(identifier.bytes()))
            is java.util.UUID -> writer.writeString(identifier.toString())
            else -> writer.writeString(identifier.toString())
        }

        writer.writeString("identifierType", value.type.name)
        writer.writeEndDocument()
    }

    override fun decode(reader: BsonReader, decoderContext: DecoderContext): NodeId {
        reader.readStartDocument()
        val namespaceIndex = UShort.valueOf(reader.readInt32("namespaceIndex"))
        val identifierValue = reader.readString("identifier")
        val identifierType = reader.readString("identifierType")
        reader.readEndDocument()

        return when (identifierType) {
            "Numeric" -> NodeId(namespaceIndex, UInteger.valueOf(identifierValue.toLong()))
            "String" -> NodeId(namespaceIndex, identifierValue)
            "Guid" -> NodeId(namespaceIndex, java.util.UUID.fromString(identifierValue))
            "Opaque" -> NodeId(namespaceIndex, ByteString.of(identifierValue.toByteArray()))
            else -> NodeId(namespaceIndex, identifierValue)
        }
    }

    override fun getEncoderClass(): Class<NodeId> = NodeId::class.java
}

// ExpandedNodeId codec
class ExpandedNodeIdCodec : Codec<ExpandedNodeId> {
    override fun encode(writer: BsonWriter, value: ExpandedNodeId, encoderContext: EncoderContext) {
        writer.writeStartDocument()
        writer.writeString("nodeId", value.toParseableString())
        value.namespaceUri?.let { writer.writeString("namespaceUri", it) }
        value.serverIndex?.let { writer.writeInt32("serverIndex", it.toInt()) }
        writer.writeEndDocument()
    }

    override fun decode(reader: BsonReader, decoderContext: DecoderContext): ExpandedNodeId {
        reader.readStartDocument()
        val nodeIdString = reader.readString("nodeId")
        val namespaceUri = if (reader.readBsonType() != org.bson.BsonType.NULL) reader.readString("namespaceUri") else null
        val serverIndex = if (reader.readBsonType() != org.bson.BsonType.NULL) UInteger.valueOf(reader.readInt32("serverIndex")) else null
        reader.readEndDocument()

        return ExpandedNodeId.newBuilder()
                .setIdentifier(nodeIdString)
                .setNamespaceUri(namespaceUri)
                .setServerIndex(serverIndex)
                .build()
    }

    override fun getEncoderClass(): Class<ExpandedNodeId> = ExpandedNodeId::class.java
}

// QualifiedName codec
class QualifiedNameCodec : Codec<QualifiedName> {
    override fun encode(writer: BsonWriter, value: QualifiedName, encoderContext: EncoderContext) {
        writer.writeStartDocument()
        writer.writeInt32("namespaceIndex", value.namespaceIndex.toInt())
        writer.writeString("name", value.name)
        writer.writeEndDocument()
    }

    override fun decode(reader: BsonReader, decoderContext: DecoderContext): QualifiedName {
        reader.readStartDocument()
        val namespaceIndex = UShort.valueOf(reader.readInt32("namespaceIndex"))
        val name = reader.readString("name")
        reader.readEndDocument()
        return QualifiedName(namespaceIndex, name)
    }

    override fun getEncoderClass(): Class<QualifiedName> = QualifiedName::class.java
}

// LocalizedText codec
class LocalizedTextCodec : Codec<LocalizedText> {
    override fun encode(writer: BsonWriter, value: LocalizedText, encoderContext: EncoderContext) {
        writer.writeStartDocument()
        value.locale?.let { writer.writeString("locale", it) }
        value.text?.let { writer.writeString("text", it) }
        writer.writeEndDocument()
    }

    override fun decode(reader: BsonReader, decoderContext: DecoderContext): LocalizedText {
        reader.readStartDocument()
        val locale = if (reader.readBsonType() != org.bson.BsonType.NULL) reader.readString("locale") else null
        val text = if (reader.readBsonType() != org.bson.BsonType.NULL) reader.readString("text") else null
        reader.readEndDocument()
        return LocalizedText(locale, text)
    }

    override fun getEncoderClass(): Class<LocalizedText> = LocalizedText::class.java
}

// ByteString codec
class ByteStringCodec : Codec<ByteString> {
    override fun encode(writer: BsonWriter, value: ByteString, encoderContext: EncoderContext) {
        writer.writeBinaryData(org.bson.BsonBinary(value.bytes()))
    }

    override fun decode(reader: BsonReader, decoderContext: DecoderContext): ByteString {
        return ByteString.of(reader.readBinaryData().data)
    }

    override fun getEncoderClass(): Class<ByteString> = ByteString::class.java
}

// XmlElement codec
class XmlElementCodec : Codec<XmlElement> {
    override fun encode(writer: BsonWriter, value: XmlElement, encoderContext: EncoderContext) {
        writer.writeString(value.fragment)
    }

    override fun decode(reader: BsonReader, decoderContext: DecoderContext): XmlElement {
        return XmlElement(reader.readString())
    }

    override fun getEncoderClass(): Class<XmlElement> = XmlElement::class.java
}

// Variant codec - this one's tricky because it can contain any type
class VariantCodec : Codec<Variant> {
    override fun encode(writer: BsonWriter, value: Variant, encoderContext: EncoderContext) {
        writer.writeStartDocument()
        writer.writeString("dataType", value.dataType.get().toString())

        // Store the actual value based on its type
        writer.writeName("value")
        when (val v = value.value) {
            is String -> writer.writeString(v)
            is Boolean -> writer.writeBoolean(v)
            is Int -> writer.writeInt32(v)
            is Long -> writer.writeInt64(v)
            is Double -> writer.writeDouble(v)
            is Float -> writer.writeDouble(v.toDouble())
            is UByte -> writer.writeInt32(v.toInt())
            is UShort -> writer.writeInt32(v.toInt())
            is UInteger -> writer.writeInt64(v.toLong())
            is ULong -> writer.writeString(v.toString())
            is ByteString -> writer.writeBinaryData(org.bson.BsonBinary(v.bytes()))
            else -> writer.writeString(v.toString()) // fallback
        }

        writer.writeEndDocument()
    }

    override fun decode(reader: BsonReader, decoderContext: DecoderContext): Variant {
        reader.readStartDocument()
        val dataType = reader.readString("dataType")
        val value = reader.readString("value") // simplified - you might want more sophisticated handling
        reader.readEndDocument()

        // This is simplified - you'd want to properly reconstruct based on dataType
        return Variant(value)
    }

    override fun getEncoderClass(): Class<Variant> = Variant::class.java
}
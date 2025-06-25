package ru.iteco.opcua.config.mongo

import org.bson.BsonReader
import org.bson.BsonWriter
import org.bson.codecs.Codec
import org.bson.codecs.DecoderContext
import org.bson.codecs.EncoderContext
import org.bson.codecs.configuration.CodecRegistry
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned

class UIntegerCodec : Codec<UInteger> {
    override fun getEncoderClass(): Class<UInteger> = UInteger::class.java

    override fun encode(writer: BsonWriter, value: UInteger, encoderContext: EncoderContext) {
        writer.writeInt64(value.toLong())
    }

    
    override fun decode(reader: BsonReader, decoderContext: DecoderContext): UInteger {
        return Unsigned.uint(reader.readInt64())
    }
}

/**
 * Extension function to register the UInteger codec with a CodecRegistry
 */
fun CodecRegistry.registerUIntegerCodec(): CodecRegistry {
    return org.bson.codecs.configuration.CodecRegistries.fromRegistries(
        org.bson.codecs.configuration.CodecRegistries.fromCodecs(UIntegerCodec()),
        this
    )
}

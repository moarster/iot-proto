package ru.iteco.opcua.config

import com.mongodb.MongoClientSettings
import org.bson.codecs.configuration.CodecRegistries
import org.bson.codecs.pojo.PojoCodecProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MongoConfig {

    @Bean
    fun mongoClientSettings(): MongoClientSettings {
        val pojoCodecRegistry = CodecRegistries.fromProviders(
            PojoCodecProvider.builder().automatic(true).build()
        )

        // Регистрируем OPC UA кодеки
        val opcUaCodecRegistry = CodecRegistries.fromCodecs(
            UByteCodec(),
            UShortCodec(),
            UIntegerCodec(),
            ULongCodec(),
            DateTimeCodec(),
            NodeIdCodec(),
            ExpandedNodeIdCodec(),
            QualifiedNameCodec(),
            LocalizedTextCodec(),
            ByteStringCodec(),
            XmlElementCodec(),
            VariantCodec()
        )

        return MongoClientSettings.builder()
            .codecRegistry(
                CodecRegistries.fromRegistries(
                    MongoClientSettings.getDefaultCodecRegistry(),
                    pojoCodecRegistry,
                    opcUaCodecRegistry
                )
            )
            .build()
    }
}
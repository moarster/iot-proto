package ru.iteco.opcua.config

import com.mongodb.MongoClientSettings
import org.bson.codecs.configuration.CodecRegistries
import org.bson.codecs.pojo.PojoCodecProvider
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.iteco.opcua.config.mongo.registerUIntegerCodec
import ru.iteco.opcua.config.mongo.UIntegerCodec

@Configuration
class MongoConfig {

    @Bean
    fun mongoClientSettings(): MongoClientSettings {
        // Get default codec registry
        val pojoCodecRegistry = CodecRegistries.fromProviders(
            PojoCodecProvider.builder().automatic(true).build()
        )
        
        // Register our custom codecs
        return MongoClientSettings.builder()
            .codecRegistry(
                CodecRegistries.fromRegistries(
                    MongoClientSettings.getDefaultCodecRegistry(),
                    pojoCodecRegistry,
                    CodecRegistries.fromCodecs(UIntegerCodec())
                )
            )
            .build()
    }
}

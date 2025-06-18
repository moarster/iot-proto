package ru.iteco.opcua.config

import io.r2dbc.spi.ConnectionFactory
import org.springframework.boot.autoconfigure.r2dbc.R2dbcProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.io.ClassPathResource
import org.springframework.r2dbc.connection.init.CompositeDatabasePopulator
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator

@Configuration
@EnableConfigurationProperties(R2dbcProperties::class)
class DatabaseInitializationConfig(
    private val r2dbcProperties: R2dbcProperties
) {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun databaseInitializer(connectionFactory: ConnectionFactory): ConnectionFactoryInitializer {
        val initializer = ConnectionFactoryInitializer()
        initializer.setConnectionFactory(connectionFactory)

        // Настройка инициализации схемы
        val databasePopulator = CompositeDatabasePopulator()
        databasePopulator.setPopulators(
            ResourceDatabasePopulator(ClassPathResource("schema.sql"))
        )

        initializer.setDatabasePopulator(databasePopulator)

        return initializer
    }
}
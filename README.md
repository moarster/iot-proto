# OPC UA Сервис сбора данных с приборов учета

## Описание

Микросервис для сбора и обработки данных с приборов учета через протокол OPC UA. Сервис обеспечивает:
- Подключение к OPC UA серверам
- Сбор данных в реальном времени
- Обогащение и валидацию данных
- Параллельную запись в несколько хранилищ
- Интеграцию с внешними системами через Kafka

## Архитектура

```
OPC UA Server → OPC UA Client → DataCollectionOrchestrator → MeterDataOrchestrationService
                                      ↓
                +---------------------+---------------------+
                ↓                     ↓                     ↓
        MongoDB (сырые)     PostgreSQL (временные ряды)    Kafka (сообщения)
```

## Функциональность

### 1. Сбор данных (DataCollectionOrchestrator)
- Инициализация и управление OPC UA клиентом
- Подписка на обновления узлов
- Потоковая обработка входящих данных
- Обработка ошибок и повторные попытки подключения
- Мониторинг состояния сбора данных

### 2. Обработка данных (MeterDataOrchestrationService)
- Координация процесса обработки данных
- Параллельная запись в несколько хранилищ
- Обработка ошибок и логирование
- Управление корутинами для асинхронной обработки

### 3. Обогащение данных (DataEnrichmentService)
- Преобразование сырых данных в структурированный формат
- Извлечение метаданных из идентификаторов узлов
- Валидация и нормализация значений
- Подготовка данных для различных хранилищ

## Запуск

### 1. Запуск инфраструктуры
```bash
# Запуск MongoDB, PostgreSQL, Kafka, Redis через Docker Compose
docker-compose up -d
```

### 2. Инициализация базы данных
```bash
# PostgreSQL схема создается автоматически при запуске
# Файл: src/main/resources/schema.sql
```

### 3. Запуск приложения
```bash
# Сборка проекта
./gradlew build

# Запуск
./gradlew bootRun

# Или через JAR
java -jar build/libs/opcua-proto-0.0.1-SNAPSHOT.jar
```

## Структура проекта

```
src/main/kotlin/ru/iteco/opcua/
├── api/                                # API контроллеры
│   └── MeterDataController.kt          # REST API для проверки работы сервиса
│
├── client/                            # OPC UA клиент
│   ├── OpcUaClientManager.kt          # Управление клиентским подключением
│   └── OpcUaDataCollector.kt          # Сбор данных с OPC UA сервера
│
├── config/                            # Конфигурационные классы
│   ├── OpcUaConfig.kt                 # Конфигурация OPC UA клиента
│   ├── MongoConfig.kt                 # Конфигурация MongoDB
│   ├── MongoCodecs.kt                 # BSON кодеки
│   └── NodeIdsConfig.kt               # Схема идентификаторов OPC UA узлов
│
├── metadata/                          # Работа с метаданными
│
├── model/                             # Модели данных
│   ├── MeterData.kt                   # Основные модели данных
│   ├── Metadata.kt                    # Модель метаданных
│   └── MeterDataEvent.kt              # Мультиформатный контейнер 
│
├── repository/                        # Репозитории для работы с БД
│
├── service/                           # Бизнес-логика
│   ├── DataCollectionOrchestrator.kt  # Оркестратор сбора данных
│   ├── MeterDataOrchestrationService.kt # Основной сервис обработки
│   │
│   ├── enrichment/                    # Обогащение данных
│   │   └── DataEnrichmentService.kt   # Сервис обогащения
│   │
│   ├── messaging/                     # Работа с сообщениями
│   │   └── KafkaMessagingService.kt   # Отправка в Kafka
│   │
│   └── persistence/                   # Работа с хранилищами
│       ├── MongoDataPersistenceService.kt  # Сохранение в MongoDB
│       └── PostgresDataPersistenceService.kt # Сохранение в PostgreSQL
│
└── OpcuaProtoApplication.kt           # Точка входа в приложение

```

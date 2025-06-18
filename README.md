# OPC UA Сервис сбора данных с приборов учета

## Описание

Микросервис для сбора данных с приборов учета горячей воды, холодной воды и тепловой энергии через протокол OPC UA. Сервис реализует подписочную модель для получения данных в реальном времени и обеспечивает их параллельную обработку в три независимых потока данных.

## Архитектура

```
OPC UA Server → OPC UA Client → Параллельная обработка:
                                  ├─ MongoDB (сырые данные)
                                  ├─ PostgreSQL (временные ряды)
                                  └─ Kafka (трансформированные сообщения)
```

## Функциональность

### 1. OPC UA Client
- Подключение к OPC UA серверу по конфигурируемому endpoint
- Создание подписки на узлы приборов учета
- Получение данных в режиме реального времени
- Поддержка мониторинга качества данных

### 2. Параллельная обработка данных

#### 2.1 Сохранение сырых данных в MongoDB
Сырые данные от OPC UA сервера сохраняются "как есть" в MongoDB для архивирования:
```json
{
  "nodeId": "ns=2;s=HotWaterMeter.Flow",
  "value": 15.7,
  "dataType": "Double",
  "quality": "good",
  "timestamp": "2025-06-17T10:30:00",
  "serverTimestamp": "2025-06-17T10:30:00.123"
}
```

#### 2.2 Трансформация в PostgreSQL TimeSeries
Данные преобразуются в структурированный формат временных рядов:
```sql
CREATE TABLE meter_timeseries (
    id BIGSERIAL PRIMARY KEY,
    meter_id VARCHAR(50),      -- HWM_001, CWM_001, HM_001
    meter_type VARCHAR(50),    -- HOT_WATER_FLOW, COLD_WATER_TEMPERATURE, etc.
    value DOUBLE PRECISION,   -- числовое значение
    unit VARCHAR(20),         -- L/min, °C, kWh, kW
    timestamp TIMESTAMP
);
```

#### 2.3 Отправка в Kafka
Трансформированные данные отправляются в топик Kafka в формате IoT сообщений:
```json
{
  "deviceId": "DEVICE_HWM_001",
  "sensorType": "flow_sensor",
  "measurement": 15.7,
  "unit": "L/min",
  "location": "Building_A",
  "recordedAt": "2025-06-17T10:30:00.000Z",
  "metadata": {
    "nodeId": "ns=2;s=HotWaterMeter.Flow",
    "dataType": "Double",
    "quality": "good"
  }
}
```

## Технологический стек

- **Kotlin** + **Spring Boot 3.5**
- **Spring WebFlux** (реактивный стек)
- **Kotlin Coroutines** (асинхронная обработка)
- **Eclipse Milo** (OPC UA клиент)
- **MongoDB Reactive** (хранение сырых данных)
- **R2DBC PostgreSQL** (временные ряды)
- **Spring Kafka** (отправка сообщений)

## Конфигурация

### application.yml
```yaml
opcua:
  endpoint-url: "opc.tcp://localhost:4840"
  username: # опционально
  password: # опционально
  application-name: "Spring Boot OPC UA Meter Client"
  subscription-interval: 5000  # интервал подписки в мс
  node-ids:
    - "ns=2;s=HotWaterMeter.Flow"
    - "ns=2;s=HotWaterMeter.Temperature"
    - "ns=2;s=ColdWaterMeter.Flow"
    - "ns=2;s=ColdWaterMeter.Temperature"
    - "ns=2;s=HeatMeter.Energy"
    - "ns=2;s=HeatMeter.Power"

spring:
  data:
    mongodb:
      host: localhost
      port: 27017
      database: meter_data
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/timeseries_db
    username: postgres
    password: password
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      acks: all
      retries: 3
```

## Запуск

### 1. Запуск инфраструктуры
```bash
# Запуск MongoDB, PostgreSQL, Kafka через Docker Compose
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

## Мониторинг узлов

Сервис автоматически подписывается на следующие узлы приборов учета:

| Тип прибора | Узел OPC UA | Параметр | Единица измерения |
|-------------|-------------|----------|-------------------|
| Горячая вода | `ns=2;s=HotWaterMeter.Flow` | Расход | L/min |
| Горячая вода | `ns=2;s=HotWaterMeter.Temperature` | Температура | °C |
| Холодная вода | `ns=2;s=ColdWaterMeter.Flow` | Расход | L/min |
| Холодная вода | `ns=2;s=ColdWaterMeter.Temperature` | Температура | °C |
| Тепловая энергия | `ns=2;s=HeatMeter.Energy` | Энергия | kWh |
| Тепловая энергия | `ns=2;s=HeatMeter.Power` | Мощность | kW |

## Структура проекта

```
src/main/kotlin/ru/iteco/opcua/
├── client/
│   └── OpcUaClientService.kt          # OPC UA клиент
├── config/
│   └── OpcUaConfig.kt                 # Конфигурация
├── model/
│   └── MeterData.kt                   # Модели данных
├── repository/
│   ├── MongoRepository.kt             # MongoDB репозиторий
│   └── PostgresRepository.kt          # PostgreSQL репозиторий
├── service/
│   ├── DataProcessingService.kt       # Обработка данных
│   ├── DataTransformationService.kt   # Трансформация данных
│   └── DataCollectionOrchestrator.kt  # Оркестратор сбора
└── OpcuaProtoApplication.kt          # Главный класс
```

## Логирование

Настроены уровни логирования:
- `ru.iteco.opcua: DEBUG` - детальное логирование работы сервиса
- `org.eclipse.milo: INFO` - информация об OPC UA соединении

## Health Check

Доступны Spring Boot Actuator endpoints:
- `http://localhost:8080/actuator/health`
- `http://localhost:8080/actuator/info`
- `http://localhost:8080/actuator/metrics`

## Обработка ошибок

- Автоматическое переподключение к OPC UA серверу
- Retry механизм для потока данных (3 попытки)
- Независимая обработка ошибок в каждом потоке данных
- Продолжение работы при сбое одного из компонентов хранения

## Масштабирование

Сервис спроектирован с учетом:
- Асинхронной обработки данных
- Параллельного выполнения операций записи
- Использования реактивных драйверов базы данных
- Возможности горизонтального масштабирования

## Требования к системе

- **Java 21+**
- **MongoDB 8.0+**
- **PostgreSQL 17+**
- **Apache Kafka 2.8+**
- **OPC UA сервер** с поддерживаемыми узлами

## Зависимости

Основные зависимости указаны в `build.gradle.kts`:
- `spring-boot-starter-webflux`
- `spring-boot-starter-data-mongodb-reactive`
- `spring-boot-starter-data-r2dbc`
- `spring-kafka`
- `sdk-client` (Eclipse Milo)
- `kotlinx-coroutines-reactor`
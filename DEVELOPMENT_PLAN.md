# План разработки URL Shortener

## 1. Назначение документа

Этот документ превращает README проекта в пошаговый план реализации. Он рассчитан на разработку production-oriented URL Shortener на:

- [ ] Java 25
- [ ] Spring Boot 4.1.1
- [ ] Spring Web
- [ ] Spring Data JPA
- [ ] PostgreSQL 17
- [ ] Flyway
- [ ] Redis 7
- [ ] Spring Data Redis
- [ ] Gradle Kotlin DSL
- [ ] JUnit 5
- [ ] Testcontainers
- [ ] Awaitility
- [ ] AssertJ
- [ ] Docker Compose
- [ ] GitHub Actions

Главный принцип разработки: сначала создаётся минимальный полностью работающий вертикальный срез, затем к нему последовательно добавляются Redis cache-aside, буферизация click counter, атомарный flush, наблюдаемость, нагрузочные тесты и production hardening.

---

# 2. Целевой результат

К концу разработки сервис должен поддерживать:

- [ ] Создание коротких ссылок.
- [ ] Генерацию уникального Base62-кода длиной 7 символов.
- [ ] Создание пользовательских alias.
- [ ] TTL/expiration ссылки.
- [ ] Redirect через HTTP 302.
- [ ] Cache-aside через Redis.
- [ ] Увеличение счётчика переходов в Redis.
- [ ] Периодический атомарный flush счётчиков в PostgreSQL.
- [ ] Получение статистики.
- [ ] Удаление ссылки.
- [ ] Корректную invalidation Redis.
- [ ] RFC 9457 ProblemDetail.
- [ ] OpenAPI/Swagger UI.
- [ ] Actuator и базовые operational metrics.
- [ ] Java virtual threads.
- [ ] Полноценные integration tests на PostgreSQL + Redis через Testcontainers.
- [ ] Проверку конкурентных переходов.
- [ ] Запуск всего окружения через Docker Compose.
- [ ] CI pipeline.
- [ ] Документированный API и архитектуру.

---

# 3. Правила выполнения

## 3.1. Принцип вертикальных срезов

Не следует сначала полностью реализовывать все domain-классы, потом все repositories, затем все controllers.

Каждый спринт должен заканчиваться работающей частью системы.

Правильная последовательность:

```text
Infrastructure
    ↓
Persistence
    ↓
Create Link
    ↓
Redirect
    ↓
Redis Cache
    ↓
Click Counter
    ↓
Atomic Flush
    ↓
Stats / Delete
    ↓
Errors / Docs / Observability
    ↓
Performance / CI / Hardening
```

## 3.2. Definition of Done для любой задачи

Задача считается завершённой, если:

- [ ] Код компилируется.
- [ ] Есть автоматическая проверка там, где она нужна.
- [ ] Нет новых compiler warnings без объяснения.
- [ ] Конфигурация вынесена из кода.
- [ ] Поведение покрыто тестом на соответствующем уровне.
- [ ] Логирование не содержит секретов.
- [ ] Изменение отражено в документации при необходимости.
- [ ] Приложение можно запустить штатной командой проекта.

---

# 4. План по спринтам

---

# Sprint 0 — Подготовка проекта и рабочее окружение

## Цель

Получить пустой, но полностью собираемый Spring Boot проект с Java 25, Gradle и Docker Compose.

- [ ] **Task 0.1 — Зафиксировать технологические требования**

Шаги:

- [ ] Зафиксировать Java 25 как единственную версию JDK проекта.
- [ ] Зафиксировать Spring Boot 4.1.1.
- [ ] Зафиксировать PostgreSQL 17.
- [ ] Зафиксировать Redis 7.
- [ ] Зафиксировать Gradle wrapper.
- [ ] Определить минимальную ОС/окружение для локальной разработки.
- [ ] Определить стандартные команды:
   - `./gradlew build`
   - `./gradlew test`
   - `./gradlew bootRun`
- [ ] Добавить эти требования в README.

Результат:

```text
Разработчик клонирует проект,
устанавливает JDK 25 и Docker
и понимает, какими командами пользоваться.
```

- [x] **Task 0.2 — Создать Gradle-проект**

Шаги:

- [x] Создать `settings.gradle.kts`.
- [x] Создать корневой `build.gradle.kts`.
- [x] Настроить Java Toolchain 25.
- [x] Подключить Spring Boot plugin.
- [x] Подключить dependency management.
- [x] Настроить UTF-8.
- [x] Настроить test task.
- [x] Включить JUnit Platform.
- [x] Добавить базовые quality checks:
   - [x] compilation
   - [x] tests
- [x] Добавить Gradle wrapper.

Критерий готовности:

```bash
./gradlew clean build
```

проходит успешно.

- [x] **Task 0.3 — Создать минимальное приложение**

Шаги:

- [x] Создать main application class.
- [x] Проверить component scanning.
- [x] Запустить приложение.
- [x] Убедиться, что Spring context поднимается.
- [x] Добавить простой smoke test на application context.

- [x] **Task 0.4 — Создать структуру пакетов**

Создать:

```text
api
domain
infra
config
```

Внутри оставить минимальные подпакеты:

```text
api/controller
api/dto
api/error

domain/model
domain/repository
domain/service

infra/redis/cache
infra/redis/counter
infra/redis/flush

config
```

На этом этапе не создавать пустые классы без необходимости.

- [x] **Task 0.5 — Настроить базовый application configuration**

Создать:

```text
application.yml
application-local.yml
application-test.yml
```

Определить:

- [x] port
- [x] datasource configuration
- [x] Redis configuration
- [x] cache TTL
- [x] counter flush interval
- [x] Base62 code length

Критерий:

- [x] конфигурация параметризуется через environment variables.

---

# Sprint 1 — Локальная инфраструктура

## Цель

Запустить PostgreSQL и Redis без приложения.

- [x] **Task 1.1 — Docker Compose для production-like local stack**

Шаги:

- [x] Создать `docker-compose.yml`.
- [x] Добавить PostgreSQL.
- [x] Добавить Redis.
- [x] Создать named volumes.
- [x] Создать private network.
- [x] Задать health checks.
- [x] Проверить корректность environment variables.
- [x] Проверить restart policy.
- [x] Проверить ports.
- [x] Не использовать secrets напрямую в Git.

Критерий:

```bash
docker compose up -d
```

запускает оба сервиса.

- [x] **Task 1.2 — Docker Compose для разработки**

Создать:

```text
docker-compose.dev.yml
```

Он должен запускать только PostgreSQL и Redis.

Проверить:

```bash
docker compose -f docker-compose.dev.yml up -d
```

- [x] **Task 1.3 — Проверить connectivity из приложения**

Шаги:

- [x] Подключить PostgreSQL driver.
- [x] Подключить Redis.
- [x] Запустить приложение.
- [x] Проверить подключение к PostgreSQL.
- [x] Проверить подключение к Redis.
- [x] Добавить startup diagnostics только для dev/test.
- [x] Убедиться, что credentials не печатаются в лог.

---

# Sprint 2 — PostgreSQL и модель данных

## Цель

Создать надёжную persistence model.

- [x] **Task 2.1 — Спроектировать таблицу links**

Определить:

```text
links
-----
code
original_url
expires_at
click_count
created_at
updated_at
```

Решения:

- [x] `code` — primary key.
- [x] `original_url` — обязательное поле.
- [x] `expires_at` — nullable.
- [x] `click_count` — non-negative.
- [x] `created_at` — обязательное.
- [x] `updated_at` — при необходимости.
- [x] Добавить уникальность code.

- [x] **Task 2.2 — Создать Flyway baseline migration**

Шаги:

- [x] Создать `V1__create_links.sql`.
- [x] Добавить таблицу.
- [x] Добавить primary key.
- [x] Добавить constraints.
- [x] Добавить индексы только там, где они реально нужны.
- [x] Проверить migration на чистой базе.
- [x] Проверить повторный startup приложения.

- [x] **Task 2.3 — Создать JPA entity**

Шаги:

- [x] Создать `LinkEntity`.
- [x] Замапить все колонки.
- [x] Корректно определить temporal types.
- [x] Проверить nullable поля.
- [x] Определить generated identity strategy только если она нужна.
- [x] Не помещать Redis-specific поля в JPA entity.

- [x] **Task 2.4 — Создать repository**

Добавить операции:

- [x] find by code
- [x] exists by code
- [x] insert/save
- [x] update click count
- [x] delete by code

Отдельно определить, какие операции будут выполняться JPA, а какие bulk SQL.

- [x] **Task 2.5 — Написать persistence tests**

Проверить:

- [x] insert;
- [x] find;
- [x] update clicks;
- [x] delete;
- [x] unique constraint;
- [x] nullable expiresAt.

---

# Sprint 3 — Domain model и Base62

## Цель

Изолировать бизнес-логику от инфраструктуры.

- [x] **Task 3.1 — Определить domain model**

Создать domain representation короткой ссылки.

Она должна содержать только бизнес-значимые данные:

- [x] code
- [x] originalUrl
- [x] expiresAt
- [x] clickCount
- [x] createdAt

- [x] **Task 3.2 — Реализовать Base62 codec**

Шаги:

- [x] Определить alphabet.
- [x] Зафиксировать порядок символов.
- [x] Реализовать encode.
- [x] Реализовать decode при необходимости.
- [x] Добавить проверку длины.
- [x] Не использовать случайную генерацию через небезопасный/предсказуемый источник.
- [x] Сделать компонент thread-safe.

- [x] **Task 3.3 — Тесты Base62**

Минимальные тесты:

- [x] известное число → ожидаемый код;
- [x] `0`;
- [x] максимальное значение для выбранного диапазона;
- [x] round-trip encode/decode;
- [x] запрещённые символы;
- [x] пустая строка;
- [x] неправильная длина.

- [x] **Task 3.4 — Определить CodeGenerator**

Создать abstraction:

```text
CodeGenerator
    ↓
Base62CodeGenerator
```

Это позволит позже отдельно тестировать random source и collision handling.

---

# Sprint 4 — Валидация URL и create API

## Цель

Реализовать первый end-to-end сценарий:

```text
POST /api/links
→ PostgreSQL
→ 201
```

- [x] **Task 4.1 — Создать request DTO**

Поля:

```text
originalUrl
alias
expiresAt
```

Определить:

- [x] required/optional поля;
- [x] максимальную длину;
- [x] формат URL;
- [x] формат expiration;
- [x] ограничения alias.

- [x] **Task 4.2 — Реализовать URL validation**

Шаги:

- [x] Проверить наличие scheme.
- [x] Разрешить только необходимые schemes.
- [x] Проверить host.
- [x] Отклонить некорректные URL.
- [x] Ограничить длину URL.
- [x] Добавить unit tests на valid/invalid cases.

Не следует полагаться только на regex.

- [x] **Task 4.3 — Реализовать alias validation**

Определить допустимый формат:

```text
[a-zA-Z0-9_-]+
```

и максимальную длину.

Отдельно определить:

- [x] запрещённые alias;
- [x] конфликт alias с системными endpoint;
- [x] регистр (`ABC` и `abc`) — должны ли считаться одинаковыми.

- [x] **Task 4.4 — Реализовать LinkService.create**

Алгоритм:

- [x] Валидировать request.
- [x] Если alias задан:
   - проверить допустимость;
   - проверить наличие;
   - создать link с alias;
   - обработать race condition через DB constraint.
- [x] Если alias отсутствует:
   - генерировать code;
   - попытаться сохранить;
   - при collision повторить генерацию;
   - ограничить число retry.
- [x] Сформировать domain object.
- [x] Вернуть response DTO.

- [x] **Task 4.5 — Реализовать POST controller**

Endpoint:

```http
POST /api/links
```

Проверить:

- [x] `201 Created`;
- [x] response body;
- [x] Content-Type;
- [x] validation errors.

- [x] **Task 4.6 — Integration test create flow**

Сценарий:

```text
POST
 ↓
DB insert
 ↓
201
 ↓
GET details
 ↓
проверить все поля
```

---

# Sprint 5 — Redirect без Redis

## Цель

Сделать redirect полностью рабочим только на PostgreSQL.

- [x] **Task 5.1 — Реализовать GET /{code}**

Шаги:

- [x] Получить code из path variable.
- [x] Найти link.
- [x] Если нет → `404`.
- [x] Если expired → `410`.
- [x] Если active → вернуть `302`.
- [x] Установить `Location`.

- [x] **Task 5.2 — Реализовать expiration policy**

Определить:

```text
expiresAt == null
→ never expires

expiresAt > now
→ active

expiresAt <= now
→ expired
```

Зафиксировать timezone policy.

Рекомендуется хранить timestamps в UTC.

- [x] **Task 5.3 — Тесты redirect**

Проверить:

- [x] существующая ссылка;
- [x] неизвестная ссылка;
- [x] expired link;
- [x] ссылка без expiration;
- [x] точное поведение на boundary `expiresAt == now`.

- [x] **Task 5.4 — Разделить lookup и redirect logic**

Не перегружать controller.

Целевой поток:

```text
Controller
  ↓
LinkService
  ↓
Repository
```

---

# Sprint 6 — Redis cache-aside

## Цель

Перенести hot redirect lookup в Redis.

- [x] **Task 6.1 — Определить Redis key model**

Использовать:

```text
link:{code}
```

Определить serialization format.

В cached value хранить минимум:

```text
code
originalUrl
expiresAt
```

Не хранить данные, которые не нужны для redirect.

- [x] **Task 6.2 — Создать Redis cache abstraction**

Например:

```text
LinkCache
```

Операции:

- [x] get
- [x] put
- [x] evict

Service layer не должен знать Redis command details.

- [x] **Task 6.3 — Реализовать cache-aside lookup**

Алгоритм:

```text
GET code
 ↓
Redis GET
 ├─ HIT → deserialize → validate expiration → redirect
 └─ MISS
      ↓
   PostgreSQL
      ↓
   check expiration
      ↓
   Redis SETEX
      ↓
   redirect
```

- [x] **Task 6.4 — TTL calculation**

TTL Redis должен учитывать:

```text
min(configuredCacheTTL, remainingLinkLifetime)
```

Для ссылки без `expiresAt` использовать стандартный cache TTL.

Для уже истёкшей ссылки не писать cache.

- [x] **Task 6.5 — Cache integration tests**

Проверить:

- [x] cache miss → DB query;
- [x] cache hit → DB query не выполняется;
- [x] cache entry имеет TTL;
- [x] expired link не возвращается;
- [x] invalid cached value корректно обрабатывается.

- [x] **Task 6.6 — Cache stampede policy**

Зафиксировать базовую стратегию.

На первом этапе допускается:

```text
несколько concurrent misses
→ несколько одинаковых DB queries
```

После основного MVP при необходимости добавить:

- [x] distributed lock;
- [x] request coalescing;
- [x] short local cache.

Не добавлять сложность раньше подтверждённой потребности.

---

# Sprint 7 — Click counter в Redis

## Цель

Убрать click increment из PostgreSQL на каждый redirect.

- [x] **Task 7.1 — Определить Redis counter key**

Использовать:

```text
clicks:{code}
```

Значение:

```text
integer
```

- [x] **Task 7.2 — Реализовать increment**

На каждом успешном redirect:

```text
INCR clicks:{code}
```

или `HINCRBY`, если выбран hash-based model.

Зафиксировать один формат и не смешивать две модели без причины.

- [x] **Task 7.3 — Определить семантику успешного клика**

Клик увеличивается только после:

- [x] link найден;
- [x] link не expired;
- [x] redirect подготовлен.

Не увеличивать counter для:

- [x] `404`;
- [x] `410`;
- [x] malformed request.

- [x] **Task 7.4 — Проверить concurrent increments**

Запустить параллельные increments.

Ожидание:

```text
N requests
→ counter == N
```

при успешном выполнении всех операций Redis.

---

# Sprint 8 — Atomic flush Redis → PostgreSQL

## Цель

Надёжно переносить накопленные clicks в PostgreSQL.

- [x] **Task 8.1 — Определить flush interval**

Config:

```text
SHORTENER_FLUSH_INTERVAL=PT5S
```

Сделать interval configurable.

- [x] **Task 8.2 — Определить атомарный алгоритм**

Плохой вариант:

```text
GET counter
DEL counter
UPDATE DB
```

Проблема:

```text
GET
   ↓
increment
   ↓
DEL
```

increment может потеряться.

- [x] **Task 8.3 — Создать Lua script**

Скрипт должен атомарно:

- [x] получить текущее значение;
- [x] установить counter в 0 или удалить key;
- [x] вернуть drained value.

Важно:

- [x] операции над одним Redis key выполняются атомарно;
- [x] script должен быть idempotent с точки зрения пустого counter.

- [x] **Task 8.4 — Создать CounterFlushService**

Алгоритм:

- [x] определить набор counters;
- [x] atomically drain;
- [x] преобразовать результат;
- [x] batch update PostgreSQL;
- [x] залогировать результат.

- [x] **Task 8.5 — Batch update**

Не выполнять:

```text
UPDATE ...
UPDATE ...
UPDATE ...
```

для каждого code отдельно без необходимости.

Использовать bulk/batch strategy.

- [x] **Task 8.6 — Обработать ошибки PostgreSQL**

Критический вопрос:

```text
Redis counter уже drained,
а PostgreSQL UPDATE завершился ошибкой.
```

Нельзя просто потерять drained value.

На этом этапе определить recovery strategy.

Варианты:

- [x] вернуть drained value в Redis;
- [x] durable outbox;
- [x] Redis pending buffer;
- [x] transaction-like application protocol.

Для MVP выбрать и явно задокументировать один вариант.

- [x] **Task 8.7 — Flush integration tests**

Проверить:

- [x] 10 clicks → flush → DB +10.
- [x] второй flush → +0.
- [x] concurrent increment во время flush не теряется.
- [x] несколько links flush одновременно.
- [x] DB error не приводит к silent data loss.

---

# Sprint 9 — Statistics API

## Цель

Предоставить актуальную статистику click count.

- [ ] **Task 9.1 — Определить семантику stats**

Важно решить:

```text
clickCount = persisted DB count
+
unflushed Redis count
```

или только persisted count.

Для пользователя предпочтительно возвращать логически актуальное значение:

```text
DB clickCount + pending Redis counter
```

- [ ] **Task 9.2 — Реализовать stats service**

Алгоритм:

- [ ] найти link в PostgreSQL;
- [ ] если нет → `404`;
- [ ] получить pending Redis counter;
- [ ] сложить значения;
- [ ] вернуть response.

- [ ] **Task 9.3 — Реализовать endpoint**

```http
GET /api/links/{code}/stats
```

- [ ] **Task 9.4 — Tests**

Проверить:

- [ ] zero clicks;
- [ ] persisted clicks;
- [ ] pending clicks;
- [ ] persisted + pending;
- [ ] missing link.

---

# Sprint 10 — Delete и cache invalidation

## Цель

Удаление ссылки должно быть согласованным между PostgreSQL и Redis.

- [ ] **Task 10.1 — Реализовать DELETE**

Endpoint:

```http
DELETE /api/links/{code}
```

Flow:

```text
find link
 ↓
delete DB record
 ↓
evict Redis link:{code}
 ↓
return 204
```

- [ ] **Task 10.2 — Определить порядок операций**

Зафиксировать поведение при частичной ошибке.

Критически важно не оставить stale cached link после удаления.

- [ ] **Task 10.3 — Counter cleanup**

Определить, что делать с:

```text
clicks:{code}
```

при delete.

Варианты:

- [ ] flush before delete;
- [ ] archive;
- [ ] discard;
- [ ] persist pending clicks before deletion.

Выбрать стратегию и покрыть её тестом.

- [ ] **Task 10.4 — Integration tests**

Проверить:

- [ ] create;
- [ ] redirect;
- [ ] delete;
- [ ] second redirect → `404`;
- [ ] cached entry отсутствует;
- [ ] counter обработан согласно выбранной политике.

---

# Sprint 11 — Unified error handling

## Цель

Все ошибки API должны иметь одинаковый формат.

- [ ] **Task 11.1 — Определить exception hierarchy**

Например:

```text
ShortenerException
├── LinkNotFoundException
├── LinkExpiredException
├── AliasAlreadyExistsException
└── InvalidUrlException
```

Не создавать exception class для каждой мелочи.

- [ ] **Task 11.2 — Реализовать ProblemDetail handler**

Для каждой категории определить:

- [ ] HTTP status;
- [ ] title;
- [ ] detail;
- [ ] type;
- [ ] instance.

- [ ] **Task 11.3 — Validation errors**

Проверить обработку:

- [ ] missing fields;
- [ ] invalid URL;
- [ ] invalid alias;
- [ ] invalid expiration.

- [ ] **Task 11.4 — Ошибки Redis/Postgres**

Не отдавать клиенту:

- [ ] stack trace;
- [ ] SQL;
- [ ] Redis internals;
- [ ] connection URLs;
- [ ] credentials.

- [ ] **Task 11.5 — Error integration tests**

Проверить контракт JSON для:

- [ ] 400;
- [ ] 404;
- [ ] 409;
- [ ] 410;
- [ ] 500.

---

# Sprint 12 — OpenAPI и API contract

## Цель

API должно быть понятно разработчику без чтения исходников.

- [ ] **Task 12.1 — Подключить springdoc-openapi**

Настроить:

- [ ] OpenAPI JSON;
- [ ] Swagger UI.

- [ ] **Task 12.2 — Описать API models**

Для каждого DTO определить:

- [ ] description;
- [ ] required;
- [ ] examples;
- [ ] constraints;
- [ ] formats.

- [ ] **Task 12.3 — Документировать endpoints**

Добавить документацию для:

```text
POST /api/links
GET /{code}
GET /api/links/{code}
GET /api/links/{code}/stats
DELETE /api/links/{code}
```

- [ ] **Task 12.4 — Документировать errors**

Для каждого endpoint описать:

- [ ] 400;
- [ ] 404;
- [ ] 409;
- [ ] 410.

- [ ] **Task 12.5 — Contract smoke test**

Проверить, что OpenAPI endpoint доступен после запуска приложения.

---

# Sprint 13 — Actuator и observability

## Цель

Сделать сервис наблюдаемым.

- [ ] **Task 13.1 — Health endpoints**

Настроить:

- [ ] liveness;
- [ ] readiness;
- [ ] application health.

Проверить dependency health для:

- [ ] PostgreSQL;
- [ ] Redis.

- [ ] **Task 13.2 — Сформировать бизнес-метрики**

Добавить counters/timers для:

```text
links.created
links.redirected
links.not_found
links.expired
links.deleted

cache.hit
cache.miss

clicks.incremented
clicks.flushed
clicks.flush.failed
```

- [ ] **Task 13.3 — Timer metrics**

Добавить latency:

```text
redirect.duration
postgres.lookup.duration
redis.lookup.duration
```

- [ ] **Task 13.4 — Logging**

Логировать:

- [ ] startup;
- [ ] migrations;
- [ ] flush cycle;
- [ ] flush failures;
- [ ] unexpected exceptions.

Не логировать:

- [ ] credentials;
- [ ] connection strings;
- [ ] полные user-provided secrets;
- [ ] чувствительные headers.

---

# Sprint 14 — Virtual Threads

## Цель

Включить Java 25 virtual threads и проверить поведение приложения.

- [ ] **Task 14.1 — Включить virtual threads**

Configuration:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

- [ ] **Task 14.2 — Проверить blocking operations**

Определить:

- [ ] JPA calls;
- [ ] Redis calls;
- [ ] HTTP request lifecycle;
- [ ] scheduled flush.

Убедиться, что virtual threads не используются как способ скрыть проблемы с connection pool.

- [ ] **Task 14.3 — Connection pool review**

Проверить:

- [ ] PostgreSQL pool size;
- [ ] Redis connection model;
- [ ] maximum concurrent DB access.

Нагрузка на виртуальные потоки не должна автоматически означать увеличение database connection pool.

- [ ] **Task 14.4 — Benchmark comparison**

Сделать локальное сравнение:

```text
virtual threads OFF
vs
virtual threads ON
```

Измерить:

- [ ] latency;
- [ ] throughput;
- [ ] CPU;
- [ ] memory;
- [ ] DB saturation.

---

# Sprint 15 — Полный integration test suite

## Цель

Получить воспроизводимую проверку всей системы на реальных PostgreSQL и Redis.

- [ ] **Task 15.1 — Testcontainers infrastructure**

Создать shared test setup:

```text
PostgreSQLContainer
RedisContainer
```

Определить:

- [ ] lifecycle;
- [ ] dynamic properties;
- [ ] clean state between tests.

- [ ] **Task 15.2 — Test create flow**

Сценарий:

```text
POST
→ DB
→ response
```

Проверить persisted record.

- [ ] **Task 15.3 — Test redirect cache miss**

Сценарий:

```text
create
→ clear Redis
→ redirect
→ DB lookup
→ cache write
```

- [ ] **Task 15.4 — Test redirect cache hit**

Сценарий:

```text
create
→ first redirect
→ second redirect
```

Проверить, что второй request не требует PostgreSQL lookup.

- [ ] **Task 15.5 — Test expiration**

Проверить:

- [ ] active link;
- [ ] expired link;
- [ ] immediate expiry;
- [ ] Redis cached expired representation.

- [ ] **Task 15.6 — Test alias conflict**

Параллельно отправить два create с одинаковым alias.

Expected:

```text
один запрос succeeds
другой → 409
```

- [ ] **Task 15.7 — Test deletion**

Проверить:

```text
create
→ redirect
→ delete
→ redirect == 404
```

- [ ] **Task 15.8 — Test stats**

Проверить:

```text
redirect × N
→ stats == N
```

учитывая pending Redis counter.

---

# Sprint 16 — Concurrency и correctness

## Цель

Проверить систему под конкурентной нагрузкой.

- [ ] **Task 16.1 — Concurrent click test**

Запустить:

```text
10,000 parallel redirects
```

Проверить:

```text
successful redirects == 10,000
```

- [ ] **Task 16.2 — Counter exactness**

После flush проверить:

```text
DB count == 10,000
```

- [ ] **Task 16.3 — Concurrent create test**

Создать большое число links параллельно.

Проверить:

- [ ] отсутствие duplicate codes;
- [ ] отсутствие потерянных successful requests;
- [ ] bounded collision retries.

- [ ] **Task 16.4 — Concurrent alias test**

100 concurrent requests с одним alias.

Ожидание:

```text
1 success
N-1 conflicts
```

- [ ] **Task 16.5 — Delete vs redirect race**

Запустить одновременно:

```text
DELETE
+
GET /{code}
```

Зафиксировать и протестировать допустимую семантику.

Не требуется искусственно гарантировать линейризуемость всего API, если это не часть требований.

---

# Sprint 17 — Redis failure scenarios

## Цель

Определить поведение приложения при частичной недоступности Redis.

- [ ] **Task 17.1 — Redis down during redirect**

Проверить:

```text
Redis unavailable
→ application falls back to PostgreSQL
```

или документировать, почему fallback не используется.

- [ ] **Task 17.2 — Redis down during click increment**

Определить:

- [ ] redirect succeeds or fails;
- [ ] click is lost or persisted elsewhere.

Выбрать policy.

- [ ] **Task 17.3 — Redis down during flush**

Проверить:

- [ ] scheduled task recovery;
- [ ] no application crash;
- [ ] retry behavior.

- [ ] **Task 17.4 — Cache corruption**

Смоделировать:

```text
invalid JSON/value
```

Проверить, что application:

- [ ] не падает;
- [ ] evicts bad cache entry;
- [ ] читает PostgreSQL;
- [ ] восстанавливает cache.

---

# Sprint 18 — PostgreSQL failure scenarios

## Цель

Проверить поведение при временной недоступности базы.

- [ ] **Task 18.1 — DB unavailable during create**

Ожидание:

- [ ] controlled 500/503 policy;
- [ ] понятный log;
- [ ] request не зависает бесконечно.

- [ ] **Task 18.2 — DB unavailable during cache miss**

Определить:

```text
Redis MISS
+
DB DOWN
```

→ controlled error.

- [ ] **Task 18.3 — DB unavailable during flush**

Это наиболее критический сценарий.

Проверить:

- [ ] Redis drained counter;
- [ ] DB update fails;
- [ ] recovery mechanism executes;
- [ ] next flush can persist value;
- [ ] no silent click loss.

---

# Sprint 19 — Cache consistency и edge cases

## Цель

Закрыть неочевидные проблемы cache-aside.

- [ ] **Task 19.1 — Cache TTL меньше business TTL**

Проверить:

```text
cache expires first
→ DB lookup
→ cache repopulate
```

- [ ] **Task 19.2 — Business TTL меньше cache TTL**

Проверить:

```text
link expires
→ Redis still has entry
→ expiration is checked
→ no redirect
```

- [ ] **Task 19.3 — Cache deletion race**

Проверить sequence:

```text
GET cache
DELETE DB
EVICT cache
```

зафиксировать допустимую race semantics.

- [ ] **Task 19.4 — Cache serialization compatibility**

Определить версионирование serialized object.

Не связывать Redis schema с JVM serialization.

Предпочтительно использовать стабильный JSON/string representation.

---

# Sprint 20 — Security hardening

## Цель

Минимизировать очевидные abuse-векторы.

- [ ] **Task 20.1 — Ограничить размеры запросов**

Настроить максимальные:

- [ ] URL length;
- [ ] alias length;
- [ ] JSON payload size.

- [ ] **Task 20.2 — SSRF analysis**

Сам redirect endpoint не должен серверно загружать destination URL.

Проверить, что приложение:

```text
302 Location
```

а не выполняет HTTP request к original URL.

- [ ] **Task 20.3 — Alias abuse**

Проверить:

- [ ] path traversal-like strings;
- [ ] reserved routes;
- [ ] Unicode edge cases;
- [ ] whitespace;
- [ ] control characters.

- [ ] **Task 20.4 — Rate limiting foundation**

Даже если Bucket4j пока не добавляется, подготовить abstraction:

```text
RateLimiter
```

с future Redis-backed implementation.

---

# Sprint 21 — Docker image приложения

## Цель

Собрать reproducible application container.

- [ ] **Task 21.1 — Создать Dockerfile**

Шаги:

- [ ] Собрать приложение Gradle.
- [ ] Использовать минимальный runtime image.
- [ ] Не запускать приложение от root.
- [ ] Expose только application port.
- [ ] Передавать configuration через environment variables.

- [ ] **Task 21.2 — Docker healthcheck**

Добавить healthcheck на readiness/health endpoint.

- [ ] **Task 21.3 — Integrate application into docker-compose**

Сценарий:

```text
docker compose up --build
```

должен запускать:

```text
app
postgres
redis
```

- [ ] **Task 21.4 — Startup order**

Не полагаться только на `depends_on`.

Использовать health conditions там, где это необходимо.

---

# Sprint 22 — CI/CD

## Цель

Каждый push/PR должен автоматически проверять проект.

- [ ] **Task 22.1 — GitHub Actions workflow**

Создать:

```text
.github/workflows/ci.yml
```

- [ ] **Task 22.2 — Build stage**

Проверять:

```bash
./gradlew clean build
```

- [ ] **Task 22.3 — Unit tests**

Запускать отдельно для быстрой диагностики.

- [ ] **Task 22.4 — Integration tests**

CI runner должен иметь Docker capability.

Проверить, что Testcontainers реально запускается.

- [ ] **Task 22.5 — Architecture/security checks**

Добавить при необходимости:

- [ ] dependency vulnerability scan;
- [ ] formatting/checkstyle;
- [ ] static analysis.

Не подключать десятки quality tools одновременно без практической причины.

- [ ] **Task 22.6 — Docker build check**

CI должен собирать production image.

---

# Sprint 23 — Performance baseline

## Цель

Получить измеримые характеристики системы до оптимизации.

- [ ] **Task 23.1 — Определить benchmark scenarios**

Минимум:

```text
redirect cache hit
redirect cache miss
create link
stats
delete
10k concurrent redirects
```

- [ ] **Task 23.2 — Определить метрики**

Собирать:

- [ ] RPS;
- [ ] p50;
- [ ] p95;
- [ ] p99;
- [ ] error rate;
- [ ] CPU;
- [ ] memory;
- [ ] Redis ops;
- [ ] PostgreSQL connections.

- [ ] **Task 23.3 — Load test**

Использовать k6.

Сделать сценарий:

```text
warm-up
→ sustained load
→ spike
→ cool-down
```

- [ ] **Task 23.4 — Cache hit benchmark**

Сравнить:

```text
Redis HIT
vs
PostgreSQL MISS
```

- [ ] **Task 23.5 — Counter flush benchmark**

Измерить влияние:

- [ ] flush every 1s;
- [ ] 5s;
- [ ] 10s.

Не менять default только на основании synthetic benchmark — решение должно соответствовать требованиям точности и latency.

---

# Sprint 24 — Database optimization

## Цель

Убедиться, что PostgreSQL является узким местом только там, где это неизбежно.

- [ ] **Task 24.1 — Проверить query plans**

Для основных запросов:

```sql
find by code
update clicks
delete by code
```

использовать `EXPLAIN`.

- [ ] **Task 24.2 — Проверить индексы**

Убедиться:

```text
PRIMARY KEY(code)
```

покрывает lookup.

Не добавлять лишние индексы.

- [ ] **Task 24.3 — Проверить batch updates**

Измерить:

```text
1 update per click
vs
batch flush
```

- [ ] **Task 24.4 — Connection pool tuning**

Настроить pool на основании нагрузки, а не количества virtual threads.

---

# Sprint 25 — Redis optimization

## Цель

Проверить, что Redis используется эффективно.

- [ ] **Task 25.1 — Key inspection**

Проверить форматы:

```text
link:{code}
clicks:{code}
```

- [ ] **Task 25.2 — TTL audit**

Убедиться, что stale entries удаляются автоматически.

- [ ] **Task 25.3 — Serialization overhead**

Сравнить:

- [ ] JSON;
- [ ] компактный string/hash format.

Выбрать вариант по измерениям.

- [ ] **Task 25.4 — Lua execution**

Проверить:

- [ ] latency;
- [ ] number of keys;
- [ ] script error handling.

---

# Sprint 26 — Refactoring и архитектурная чистка

## Цель

Перед production-ready milestone убрать накопленный технический долг.

- [ ] **Task 26.1 — Controller review**

Controller должен:

- [ ] принимать request;
- [ ] валидировать;
- [ ] вызывать service;
- [ ] формировать HTTP response.

Business logic в controller быть не должно.

- [ ] **Task 26.2 — Service review**

Service должен координировать:

- [ ] domain;
- [ ] repository;
- [ ] cache;
- [ ] counter.

Не смешивать HTTP concepts с persistence logic.

- [ ] **Task 26.3 — Infrastructure boundary review**

Проверить, что domain model не зависит от:

- [ ] Redis classes;
- [ ] Jackson annotations без необходимости;
- [ ] JPA APIs без необходимости.

- [ ] **Task 26.4 — Configuration review**

Проверить, что magic numbers отсутствуют:

```text
7
5s
1h
max URL length
retry count
```

должны быть конфигурируемыми или явно константными бизнес-правилами.

- [ ] **Task 26.5 — Dead code cleanup**

Удалить:

- [ ] unused classes;
- [ ] obsolete configs;
- [ ] experimental code;
- [ ] старые scripts.

---

# Sprint 27 — Документация и developer experience

## Цель

Новый разработчик должен поднять проект без автора.

- [ ] **Task 27.1 — Обновить README**

Проверить:

- [ ] feature list;
- [ ] architecture;
- [ ] local startup;
- [ ] Docker startup;
- [ ] API;
- [ ] testing;
- [ ] configuration.

- [ ] **Task 27.2 — Добавить DEVELOPMENT.md**

Документировать:

```text
local setup
IDE setup
testcontainers
debugging
Redis inspection
PostgreSQL inspection
common failures
```

- [ ] **Task 27.3 — Добавить API examples**

Для каждого endpoint показать:

- [ ] request;
- [ ] response;
- [ ] error case.

- [ ] **Task 27.4 — Добавить architecture decision records**

Зафиксировать минимум:

```text
ADR-001 302 instead of 301
ADR-002 Redis cache-aside
ADR-003 Redis click buffering
ADR-004 Lua atomic flush
ADR-005 Base62 generation strategy
ADR-006 expiration semantics
```

---

# Sprint 28 — Production readiness review

## Цель

Провести финальную проверку системы как единого продукта.

- [ ] **Task 28.1 — Functional checklist**

Проверить весь API:

```text
POST
GET redirect
GET details
GET stats
DELETE
```

- [ ] **Task 28.2 — Consistency checklist**

Проверить:

```text
Postgres ↔ Redis cache
Redis counter ↔ Postgres click_count
expiration
deletion
```

- [ ] **Task 28.3 — Failure checklist**

Проверить:

```text
Redis DOWN
Postgres DOWN
Redis corrupted value
DB flush failure
concurrent traffic
```

- [ ] **Task 28.4 — Performance checklist**

Зафиксировать baseline:

```text
cache-hit p50
cache-hit p95
cache-hit p99
cache-miss p95
create throughput
10k concurrent click result
```

- [ ] **Task 28.5 — Security checklist**

Проверить:

- [ ] dependency vulnerabilities;
- [ ] exposed actuator endpoints;
- [ ] container user;
- [ ] request limits;
- [ ] log sanitization;
- [ ] secrets handling.

- [ ] **Task 28.6 — Release checklist**

Перед релизом:

- [ ] `./gradlew clean build`
- [ ] `./gradlew test`
- [ ] integration tests
- [ ] Docker image build
- [ ] Docker Compose smoke test
- [ ] OpenAPI smoke test
- [ ] health check
- [ ] README update
- [ ] changelog/release notes

---

# 5. Рекомендуемый порядок реализации внутри каждого спринта

Для каждой функциональности использовать одинаковый шаблон:

```text
1. Contract
   ↓
2. Domain model
   ↓
3. Persistence/infrastructure
   ↓
4. Service
   ↓
5. Controller
   ↓
6. Unit tests
   ↓
7. Integration tests
   ↓
8. Observability
   ↓
9. Documentation
```

Например для stats:

```text
Stats response DTO
→ repository lookup
→ Redis pending counter
→ StatsService
→ GET endpoint
→ unit tests
→ Testcontainers test
→ metrics
→ OpenAPI
```

---

# 6. Приоритеты

## P0 — обязательный MVP

До этого этапа нельзя считать проект завершённым:

```text
Sprint 0
Sprint 1
Sprint 2
Sprint 3
Sprint 4
Sprint 5
Sprint 6
Sprint 7
Sprint 8
Sprint 9
Sprint 10
Sprint 11
Sprint 12
```

Результат:

```text
Create
+
Redirect
+
Redis cache
+
Redis counter
+
Atomic flush
+
Stats
+
Delete
+
ProblemDetail
+
OpenAPI
```

## P1 — production-oriented

```text
Sprint 13
Sprint 14
Sprint 15
Sprint 16
Sprint 17
Sprint 18
Sprint 19
Sprint 21
Sprint 22
```

Результат:

```text
Observability
+
Virtual Threads
+
Integration Tests
+
Concurrency
+
Failure Handling
+
Docker
+
CI
```

## P2 — optimization

```text
Sprint 23
Sprint 24
Sprint 25
Sprint 26
```

Результат:

```text
Measured performance
+
DB optimization
+
Redis optimization
+
Refactoring
```

## P3 — project maturity

```text
Sprint 27
Sprint 28
```

Результат:

```text
Documentation
+
ADRs
+
Production readiness
```

---

# 7. Milestones

## Milestone M1 — Buildable project

После Sprint 0–1:

```text
Java 25
Spring Boot 4.1.1
Gradle
Postgres
Redis
Docker Compose
```

## Milestone M2 — Database-backed URL Shortener

После Sprint 2–5:

```text
Create
Redirect
Expiration
Base62
PostgreSQL persistence
```

## Milestone M3 — Redis accelerated URL Shortener

После Sprint 6–8:

```text
Cache-aside
Click counter
Atomic flush
```

## Milestone M4 — Complete API

После Sprint 9–12:

```text
Stats
Delete
ProblemDetail
OpenAPI
```

## Milestone M5 — Production engineering

После Sprint 13–22:

```text
Metrics
Health
Virtual Threads
Integration tests
Concurrency
Failure handling
Docker
CI
```

## Milestone M6 — Performance and release

После Sprint 23–28:

```text
Load tests
DB tuning
Redis tuning
Refactoring
Documentation
Production readiness
```

---

# 8. Recommended task size

Чтобы задачи оставались маленькими и управляемыми:

| Тип задачи | Рекомендуемый размер |
|---|---:|
| Configuration | 30–60 min |
| DTO / model | 30–60 min |
| Simple service method | 45–90 min |
| Repository | 30–60 min |
| Controller endpoint | 30–90 min |
| Unit test group | 30–90 min |
| Integration test | 60–120 min |
| Redis feature | 60–120 min |
| Lua / atomicity | 60–120 min |
| Failure scenario | 60–120 min |
| Performance scenario | 1–3 h |
| Refactoring | 1–2 h |

Если задача выходит за 2–3 часа, её следует дополнительно разделить.

---

# 9. Формат карточки задачи для Jira / GitHub Issues

Каждую задачу можно переносить в issue в таком формате:

```text
Title:
[S08-T04] Implement batch click counter flush

Goal:
Persist accumulated Redis clicks to PostgreSQL.

Prerequisites:
- click counter exists
- Redis key format is fixed
- Link repository supports click update

Steps:
1. Create flush service.
2. Load/drain Redis counter atomically.
3. Convert drained values to batch update commands.
4. Execute PostgreSQL update.
5. Add failure handling.
6. Add structured logging.
7. Add integration tests.

Acceptance Criteria:
- no increments are lost during concurrent flush
- 10 clicks are persisted as +10
- empty counter causes no DB update
- DB failure is handled according to recovery policy
- integration test passes

Tests:
- unit
- integration
- concurrency

Definition of Done:
- code merged
- tests green
- metrics/logging present
- documentation updated
```

---

# 10. Главные технические риски, которые нужно закрыть

- [ ] **Risk 1 — Lost clicks during flush**

Самый важный риск.

Нельзя использовать:

```text
GET + DEL
```

как две независимые операции.

Нужна атомарная drain operation.

- [ ] **Risk 2 — Click loss после Redis drain**

Если Redis counter уже сброшен, а PostgreSQL update упал, counter не должен исчезнуть без recovery path.

Это должно быть решено до production milestone.

- [ ] **Risk 3 — Cache contains expired link**

Expiration нельзя определять только Redis TTL.

Бизнес-время жизни ссылки должно проверяться на чтении.

- [ ] **Risk 4 — Code collision**

`existsByCode` не обеспечивает concurrency safety.

Final guarantee должна предоставляться PostgreSQL unique constraint.

- [ ] **Risk 5 — Excessive database connections**

Virtual threads позволяют обслуживать большое количество concurrent requests, но PostgreSQL connection pool остаётся ограничением.

- [ ] **Risk 6 — Stale cache after delete**

Удаление должно учитывать PostgreSQL и Redis.

- [ ] **Risk 7 — Stats lag**

Если stats читается только из PostgreSQL, значение может быть меньше реального из-за pending Redis clicks.

Нужно заранее определить API semantics.

---

# 11. Финальная архитектура после завершения плана

```text
                         ┌──────────────────────────────┐
                         │        Spring Boot 4.1.1    │
                         │            Java 25          │
                         │                              │
                         │ API → Service → Repository  │
                         └──────────────┬───────────────┘
                                        │
                   ┌────────────────────┼────────────────────┐
                   │                    │                    │
                   ▼                    ▼                    ▼
             ┌───────────┐        ┌───────────┐       ┌─────────────┐
             │ Redis     │        │ Redis     │       │ PostgreSQL  │
             │ link:*    │        │ clicks:*  │       │ links       │
             └─────┬─────┘        └─────┬─────┘       └──────┬──────┘
                   │                    │                     │
                   │                    │                     │
                 cache                counter              source
                 aside               buffering             of truth
                                        │
                                        ▼
                                ┌─────────────────┐
                                │ Lua atomic      │
                                │ drain + reset   │
                                └────────┬────────┘
                                         │
                                         ▼
                                  batch DB update
```

---

# 12. Итоговая последовательность

```text
S00  Project bootstrap
S01  Local infrastructure
S02  PostgreSQL schema
S03  Domain + Base62
S04  Create API
S05  Redirect
S06  Redis cache
S07  Redis click counter
S08  Atomic click flush
S09  Statistics
S10  Delete + invalidation
S11  ProblemDetail
S12  OpenAPI
S13  Observability
S14  Virtual Threads
S15  Integration test suite
S16  Concurrency
S17  Redis failures
S18  PostgreSQL failures
S19  Cache consistency
S20  Security hardening
S21  Docker application
S22  CI/CD
S23  Performance baseline
S24  PostgreSQL optimization
S25  Redis optimization
S26  Refactoring
S27  Documentation
S28  Production readiness
```

Таким образом, разработка идёт от простого работающего CRUD/redirect-сервиса к полноценному production-oriented сервису, при этом наиболее опасные части — Redis counter, atomic flush, consistency и concurrency — получают отдельные спринты и отдельные тестовые сценарии.

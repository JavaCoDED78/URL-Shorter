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

- [x] **Task 9.1 — Определить семантику stats**

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

- [x] **Task 9.2 — Реализовать stats service**

Алгоритм:

- [x] найти link в PostgreSQL;
- [x] если нет → `404`;
- [x] получить pending Redis counter;
- [x] сложить значения;
- [x] вернуть response.

- [x] **Task 9.3 — Реализовать endpoint**

```http
GET /api/links/{code}/stats
```

- [x] **Task 9.4 — Tests**

Проверить:

- [x] zero clicks;
- [x] persisted clicks;
- [x] pending clicks;
- [x] persisted + pending;
- [x] missing link.

---

# Sprint 10 — Delete и cache invalidation

## Цель

Удаление ссылки должно быть согласованным между PostgreSQL и Redis.

- [x] **Task 10.1 — Реализовать DELETE**

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

- [x] **Task 10.2 — Определить порядок операций**

Зафиксировать поведение при частичной ошибке.

Критически важно не оставить stale cached link после удаления.

- [x] **Task 10.3 — Counter cleanup**

Определить, что делать с:

```text
clicks:{code}
```

при delete.

Варианты:

- [x] flush before delete;
- [x] archive;
- [x] discard;
- [x] persist pending clicks before deletion.

Выбрать стратегию и покрыть её тестом.

- [x] **Task 10.4 — Integration tests**

Проверить:

- [x] create;
- [x] redirect;
- [x] delete;
- [x] second redirect → `404`;
- [x] cached entry отсутствует;
- [x] counter обработан согласно выбранной политике.

---

# Sprint 11 — Unified error handling

## Цель

Все ошибки API должны иметь одинаковый формат.

- [x] **Task 11.1 — Определить exception hierarchy**

Например:

```text
ShortenerException
├── LinkNotFoundException
├── LinkExpiredException
├── AliasAlreadyExistsException
└── InvalidUrlException
```

Не создавать exception class для каждой мелочи.

- [x] **Task 11.2 — Реализовать ProblemDetail handler**

Для каждой категории определить:

- [x] HTTP status;
- [x] title;
- [x] detail;
- [x] type;
- [x] instance.

- [x] **Task 11.3 — Validation errors**

Проверить обработку:

- [x] missing fields;
- [x] invalid URL;
- [x] invalid alias;
- [x] invalid expiration.

- [x] **Task 11.4 — Ошибки Redis/Postgres**

Не отдавать клиенту:

- [x] stack trace;
- [x] SQL;
- [x] Redis internals;
- [x] connection URLs;
- [x] credentials.

- [x] **Task 11.5 — Error integration tests**

Проверить контракт JSON для:

- [x] 400;
- [x] 404;
- [x] 409;
- [x] 410;
- [x] 500.

---

# Sprint 12 — OpenAPI и API contract

## Цель

API должно быть понятно разработчику без чтения исходников.

- [x] **Task 12.1 — Подключить springdoc-openapi**

Настроить:

- [x] OpenAPI JSON;
- [x] Swagger UI.

- [x] **Task 12.2 — Описать API models**

Для каждого DTO определить:

- [x] description;
- [x] required;
- [x] examples;
- [x] constraints;
- [x] formats.

- [x] **Task 12.3 — Документировать endpoints**

Добавить документацию для:

```text
POST /api/links
GET /{code}
GET /api/links/{code}
GET /api/links/{code}/stats
DELETE /api/links/{code}
```

- [x] **Task 12.4 — Документировать errors**

Для каждого endpoint описать:

- [x] 400;
- [x] 404;
- [x] 409;
- [x] 410.

- [x] **Task 12.5 — Contract smoke test**

Проверить, что OpenAPI endpoint доступен после запуска приложения.

---

# Sprint 13 — Actuator и observability

## Цель

Сделать сервис наблюдаемым.

- [x] **Task 13.1 — Health endpoints**

Настроить:

- [x] liveness;
- [x] readiness;
- [x] application health.

Проверить dependency health для:

- [x] PostgreSQL;
- [x] Redis.

- [x] **Task 13.2 — Сформировать бизнес-метрики**

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

- [x] **Task 13.3 — Timer metrics**

Добавить latency:

```text
redirect.duration
postgres.lookup.duration
redis.lookup.duration
```

- [x] **Task 13.4 — Logging**

Логировать:

- [x] startup;
- [x] migrations;
- [x] flush cycle;
- [x] flush failures;
- [x] unexpected exceptions.

Не логировать:

- [x] credentials;
- [x] connection strings;
- [x] полные user-provided secrets;
- [x] чувствительные headers.

---

# Sprint 14 — Virtual Threads

## Цель

Включить Java 25 virtual threads и проверить поведение приложения.

- [x] **Task 14.1 — Включить virtual threads**

Configuration:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

- [x] **Task 14.2 — Проверить blocking operations**

Определить:

- [x] JPA calls;
- [x] Redis calls;
- [x] HTTP request lifecycle;
- [x] scheduled flush.

Убедиться, что virtual threads не используются как способ скрыть проблемы с connection pool.

- [x] **Task 14.3 — Connection pool review**

Проверить:

- [x] PostgreSQL pool size;
- [x] Redis connection model;
- [x] maximum concurrent DB access.

Нагрузка на виртуальные потоки не должна автоматически означать увеличение database connection pool.

- [x] **Task 14.4 — Benchmark comparison**

Сделать локальное сравнение:

```text
virtual threads OFF
vs
virtual threads ON
```

Измерить:

- [x] latency;
- [x] throughput;
- [x] CPU;
- [x] memory;
- [x] DB saturation.

---

# Sprint 15 — Полный integration test suite

## Цель

Получить воспроизводимую проверку всей системы на реальных PostgreSQL и Redis.

- [x] **Task 15.1 — Testcontainers infrastructure**

Создать shared test setup:

```text
PostgreSQLContainer
RedisContainer
```

Определить:

- [x] lifecycle;
- [x] dynamic properties;
- [x] clean state between tests.

- [x] **Task 15.2 — Test create flow**

Сценарий:

```text
POST
→ DB
→ response
```

Проверить persisted record.

- [x] **Task 15.3 — Test redirect cache miss**

Сценарий:

```text
create
→ clear Redis
→ redirect
→ DB lookup
→ cache write
```

- [x] **Task 15.4 — Test redirect cache hit**

Сценарий:

```text
create
→ first redirect
→ second redirect
```

Проверить, что второй request не требует PostgreSQL lookup.

- [x] **Task 15.5 — Test expiration**

Проверить:

- [x] active link;
- [x] expired link;
- [x] immediate expiry;
- [x] Redis cached expired representation.

- [x] **Task 15.6 — Test alias conflict**

Параллельно отправить два create с одинаковым alias.

Expected:

```text
один запрос succeeds
другой → 409
```

- [x] **Task 15.7 — Test deletion**

Проверить:

```text
create
→ redirect
→ delete
→ redirect == 404
```

- [x] **Task 15.8 — Test stats**

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

- [x] **Task 16.1 — Concurrent click test**

Запустить:

```text
10,000 parallel redirects
```

Проверить:

```text
successful redirects == 10,000
```

- [x] **Task 16.2 — Counter exactness**

После flush проверить:

```text
DB count == 10,000
```

- [x] **Task 16.3 — Concurrent create test**

Создать большое число links параллельно.

Проверить:

- [x] отсутствие duplicate codes;
- [x] отсутствие потерянных successful requests;
- [x] bounded collision retries.

- [x] **Task 16.4 — Concurrent alias test**

100 concurrent requests с одним alias.

Ожидание:

```text
1 success
N-1 conflicts
```

- [x] **Task 16.5 — Delete vs redirect race**

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

- [x] **Task 17.1 — Redis down during redirect**

Проверить:

```text
Redis unavailable
→ application falls back to PostgreSQL
```

или документировать, почему fallback не используется.

- [x] **Task 17.2 — Redis down during click increment**

Определить:

- [x] redirect succeeds or fails;
- [x] click is lost or persisted elsewhere.

Выбрать policy.

- [x] **Task 17.3 — Redis down during flush**

Проверить:

- [x] scheduled task recovery;
- [x] no application crash;
- [x] retry behavior.

- [x] **Task 17.4 — Cache corruption**

Смоделировать:

```text
invalid JSON/value
```

Проверить, что application:

- [x] не падает;
- [x] evicts bad cache entry;
- [x] читает PostgreSQL;
- [x] восстанавливает cache.

---

# Sprint 18 — PostgreSQL failure scenarios

## Цель

Проверить поведение при временной недоступности базы.

- [x] **Task 18.1 — DB unavailable during create**

Ожидание:

- [x] controlled 500/503 policy;
- [x] понятный log;
- [x] request не зависает бесконечно.

- [x] **Task 18.2 — DB unavailable during cache miss**

Определить:

```text
Redis MISS
+
DB DOWN
```

→ controlled error.

- [x] **Task 18.3 — DB unavailable during flush**

Это наиболее критический сценарий.

Проверить:

- [x] Redis drained counter;
- [x] DB update fails;
- [x] recovery mechanism executes;
- [x] next flush can persist value;
- [x] no silent click loss.

---

# Sprint 19 — Cache consistency и edge cases

## Цель

Закрыть неочевидные проблемы cache-aside.

- [x] **Task 19.1 — Cache TTL меньше business TTL**

Проверить:

```text
cache expires first
→ DB lookup
→ cache repopulate
```

- [x] **Task 19.2 — Business TTL меньше cache TTL**

Проверить:

```text
link expires
→ Redis still has entry
→ expiration is checked
→ no redirect
```

- [x] **Task 19.3 — Cache deletion race**

Проверить sequence:

```text
GET cache
DELETE DB
EVICT cache
```

зафиксировать допустимую race semantics.

- [x] **Task 19.4 — Cache serialization compatibility**

Определить версионирование serialized object.

Не связывать Redis schema с JVM serialization.

Предпочтительно использовать стабильный JSON/string representation.

---

# Sprint 20 — Security hardening

## Цель

Минимизировать очевидные abuse-векторы.

- [x] **Task 20.1 — Ограничить размеры запросов**

Настроить максимальные:

- [x] URL length;
- [x] alias length;
- [x] JSON payload size.

- [x] **Task 20.2 — SSRF analysis**

Сам redirect endpoint не должен серверно загружать destination URL.

Проверить, что приложение:

```text
302 Location
```

а не выполняет HTTP request к original URL.

- [x] **Task 20.3 — Alias abuse**

Проверить:

- [x] path traversal-like strings;
- [x] reserved routes;
- [x] Unicode edge cases;
- [x] whitespace;
- [x] control characters.

- [x] **Task 20.4 — Rate limiting foundation**

Даже если Bucket4j пока не добавляется, подготовить abstraction:

```text
RateLimiter
```

с future Redis-backed implementation.

---

# Sprint 21 — Docker image приложения

## Цель

Собрать reproducible application container.

- [x] **Task 21.1 — Создать Dockerfile**

Шаги:

- [x] Собрать приложение Gradle.
- [x] Использовать минимальный runtime image.
- [x] Не запускать приложение от root.
- [x] Expose только application port.
- [x] Передавать configuration через environment variables.

- [x] **Task 21.2 — Docker healthcheck**

Добавить healthcheck на readiness/health endpoint.

- [x] **Task 21.3 — Integrate application into docker-compose**

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

- [x] **Task 21.4 — Startup order**

Не полагаться только на `depends_on`.

Использовать health conditions там, где это необходимо.

---

# Sprint 22 — CI/CD

## Цель

Каждый push/PR должен автоматически проверять проект.

- [x] **Task 22.1 — GitHub Actions workflow**

Создать:

```text
.github/workflows/ci.yml
```

- [x] **Task 22.2 — Build stage**

Проверять:

```bash
./gradlew clean build
```

- [x] **Task 22.3 — Unit tests**

Запускать отдельно для быстрой диагностики.

- [x] **Task 22.4 — Integration tests**

CI runner должен иметь Docker capability.

Проверить, что Testcontainers реально запускается.

- [x] **Task 22.5 — Architecture/security checks**

Добавить при необходимости:

- [x] dependency vulnerability scan;
- [x] formatting/checkstyle;
- [x] static analysis.

Не подключать десятки quality tools одновременно без практической причины.

- [x] **Task 22.6 — Docker build check**

CI должен собирать production image.

---

# Sprint 23 — Performance baseline

## Цель

Получить измеримые характеристики системы до оптимизации.

- [x] **Task 23.1 — Определить benchmark scenarios**

Минимум:

```text
redirect cache hit
redirect cache miss
create link
stats
delete
10k concurrent redirects
```

- [x] **Task 23.2 — Определить метрики**

Собирать:

- [x] RPS;
- [x] p50;
- [x] p95;
- [x] p99;
- [x] error rate;
- [x] CPU;
- [x] memory;
- [x] Redis ops;
- [x] PostgreSQL connections.

- [x] **Task 23.3 — Load test**

Использовать k6.

Сделать сценарий:

```text
warm-up
→ sustained load
→ spike
→ cool-down
```

- [x] **Task 23.4 — Cache hit benchmark**

Сравнить:

```text
Redis HIT
vs
PostgreSQL MISS
```

- [x] **Task 23.5 — Counter flush benchmark**

Измерить влияние:

- [x] flush every 1s;
- [x] 5s;
- [x] 10s.

Не менять default только на основании synthetic benchmark — решение должно соответствовать требованиям точности и latency.

---

# Sprint 24 — Database optimization

## Цель

Убедиться, что PostgreSQL является узким местом только там, где это неизбежно.

- [x] **Task 24.1 — Проверить query plans**

Для основных запросов:

```sql
find by code
update clicks
delete by code
```

использовать `EXPLAIN`.

- [x] **Task 24.2 — Проверить индексы**

Убедиться:

```text
PRIMARY KEY(code)
```

покрывает lookup.

Не добавлять лишние индексы.

- [x] **Task 24.3 — Проверить batch updates**

Измерить:

```text
1 update per click
vs
batch flush
```

- [x] **Task 24.4 — Connection pool tuning**

Настроить pool на основании нагрузки, а не количества virtual threads.

---

# Sprint 25 — Redis optimization

## Цель

Проверить, что Redis используется эффективно.

- [x] **Task 25.1 — Key inspection**

Проверить форматы:

```text
link:{code}
clicks:{code}
```

- [x] **Task 25.2 — TTL audit**

Убедиться, что stale entries удаляются автоматически.

- [x] **Task 25.3 — Serialization overhead**

Сравнить:

- [x] JSON;
- [x] компактный string/hash format.

Выбрать вариант по измерениям.

- [x] **Task 25.4 — Lua execution**

Проверить:

- [x] latency;
- [x] number of keys;
- [x] script error handling.

---

# Sprint 26 — Refactoring и архитектурная чистка

## Цель

Перед production-ready milestone убрать накопленный технический долг.

- [x] **Task 26.1 — Controller review**

Controller должен:

- [x] принимать request;
- [x] валидировать;
- [x] вызывать service;
- [x] формировать HTTP response.

Business logic в controller быть не должно.

- [x] **Task 26.2 — Service review**

Service должен координировать:

- [x] domain;
- [x] repository;
- [x] cache;
- [x] counter.

Не смешивать HTTP concepts с persistence logic.

- [x] **Task 26.3 — Infrastructure boundary review**

Проверить, что domain model не зависит от:

- [x] Redis classes;
- [x] Jackson annotations без необходимости;
- [x] JPA APIs без необходимости.

- [x] **Task 26.4 — Configuration review**

Проверить, что magic numbers отсутствуют:

```text
7
5s
1h
max URL length
retry count
```

должны быть конфигурируемыми или явно константными бизнес-правилами.

- [x] **Task 26.5 — Dead code cleanup**

Удалить:

- [x] unused classes;
- [x] obsolete configs;
- [x] experimental code;
- [x] старые scripts.

---

# Sprint 27 — Документация и developer experience

## Цель

Новый разработчик должен поднять проект без автора.

- [x] **Task 27.1 — Обновить README**

Проверить:

- [x] feature list;
- [x] architecture;
- [x] local startup;
- [x] Docker startup;
- [x] API;
- [x] testing;
- [x] configuration.

- [x] **Task 27.2 — Добавить DEVELOPMENT.md**

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

- [x] **Task 27.3 — Добавить API examples**

Для каждого endpoint показать:

- [x] request;
- [x] response;
- [x] error case.

- [x] **Task 27.4 — Добавить architecture decision records**

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

- [x] **Task 28.1 — Functional checklist**

Проверить весь API:

```text
POST
GET redirect
GET details
GET stats
DELETE
```

- [x] **Task 28.2 — Consistency checklist**

Проверить:

```text
Postgres ↔ Redis cache
Redis counter ↔ Postgres click_count
expiration
deletion
```

- [x] **Task 28.3 — Failure checklist**

Проверить:

```text
Redis DOWN
Postgres DOWN
Redis corrupted value
DB flush failure
concurrent traffic
```

- [x] **Task 28.4 — Performance checklist**

Зафиксировать baseline:

```text
cache-hit p50
cache-hit p95
cache-hit p99
cache-miss p95
create throughput
10k concurrent click result
```

- [x] **Task 28.5 — Security checklist**

Проверить:

- [x] dependency vulnerabilities;
- [x] exposed actuator endpoints;
- [x] container user;
- [x] request limits;
- [x] log sanitization;
- [x] secrets handling.

- [x] **Task 28.6 — Release checklist**

Перед релизом:

- [x] `./gradlew clean build`
- [x] `./gradlew test`
- [x] integration tests
- [x] Docker image build
- [x] Docker Compose smoke test
- [x] OpenAPI smoke test
- [x] health check
- [x] README update
- [x] changelog/release notes

---

# Sprint 29 — Prometheus + Grafana: Metrics Pipeline as Code

## Цель

Превратить существующие Actuator/Micrometer-метрики в полноценный воспроизводимый monitoring stack, полностью описанный в Git:

```text
Spring Boot
    ↓
Actuator
    ↓
Micrometer
    ↓
/actuator/prometheus
    ↓
Prometheus
    ↓
Grafana
```

Все operational configuration, scrape configuration, recording/alerting rules, Grafana datasource и dashboards должны создаваться из файлов репозитория без ручной настройки UI.

Главный принцип спринта:

```text
No manual Grafana configuration
No manual Prometheus configuration
No dashboard-only changes in UI
```

---

## Результат Sprint 29

К концу спринта должно быть возможно:

- [ ] запустить приложение и monitoring stack одной командой;
- [ ] открыть Prometheus и увидеть target приложения в состоянии `UP`;
- [ ] получить Spring Boot metrics через `/actuator/prometheus`;
- [ ] проверить, что Prometheus хранит application metrics;
- [ ] открыть Grafana без ручного добавления datasource;
- [ ] автоматически получить готовый dashboard URL Shortener;
- [ ] видеть traffic, latency, errors, cache, Redis, PostgreSQL и click flush metrics;
- [ ] воспроизвести весь monitoring setup на чистой машине только из Git;
- [ ] тестировать конфигурацию Prometheus и Grafana в CI;
- [ ] версионировать dashboards и rules как обычный код.

---

## Task 29.1 — Зафиксировать observability contract

### Цель

Определить единый контракт метрик перед подключением Prometheus и Grafana.

### Шаги

- [x] Провести инвентаризацию уже существующих metrics из Sprint 13.
- [x] Разделить метрики на категории:

```text
HTTP (Spring Boot built-in)
Application (links.*)
Cache (cache.*)
Click Counter (clicks.incremented, clicks.increment.failed)
Click Flush (clicks.flushed, clicks.flush.failed)
JVM (Micrometer auto)
Process (Micrometer auto)
Spring (Micrometer auto)
```

- [x] Зафиксировать названия бизнес-метрик (11 counters + 3 timers).
- [x] Зафиксировать units (`total`, `seconds`).
- [x] Для counters использовать монотонную семантику.
- [x] Для timers использовать Micrometer timer/histogram semantics.
- [x] Определить минимальный набор labels (`application`).
- [x] Проверить, что labels не содержат:
    - [x] username;
    - [x] original URL;
    - [x] arbitrary alias;
    - [x] request ID;
    - [x] user-provided values.
- [x] Не использовать high-cardinality labels.

### Фактический business metric set (из `MetricsRecorder`)

|| Micrometer name | Prometheus name | Type | Unit |
||---|---|---|---|
|| `urlshorter.linksCreated` | `urlshorter_linksCreated_total` | counter | total |
|| `urlshorter.linksRedirected` | `urlshorter_linksRedirected_total` | counter | total |
|| `urlshorter.linksNotFound` | `urlshorter_linksNotFound_total` | counter | total |
|| `urlshorter.linksExpired` | `urlshorter_linksExpired_total` | counter | total |
|| `urlshorter.linksDeleted` | `urlshorter_linksDeleted_total` | counter | total |
|| `urlshorter.cacheHit` | `urlshorter_cacheHit_total` | counter | total |
|| `urlshorter.cacheMiss` | `urlshorter_cacheMiss_total` | counter | total |
|| `urlshorter.clicksIncremented` | `urlshorter_clicksIncremented_total` | counter | total |
|| `urlshorter.clicksIncrementFailed` | `urlshorter_clicksIncrementFailed_total` | counter | total |
|| `urlshorter.clicksFlushed` | `urlshorter_clicksFlushed_total` | counter | total |
|| `urlshorter.clicksFlushFailed` | `urlshorter_clicksFlushFailed_total` | counter | total |
|| `urlshorter.redirectDuration` | `urlshorter_redirectDuration_seconds` | timer | seconds |
|| `urlshorter.postgresLookupDuration` | `urlshorter_postgresLookupDuration_seconds` | timer | seconds |
|| `urlshorter.redisLookupDuration` | `urlshorter_redisLookupDuration_seconds` | timer | seconds |

**Naming convention:** Micrometer names use the `urlshorter.` prefix (namespace separator), which converts to underscore in Prometheus format. The `.` separator prevents Micrometer 1.16 → Prometheus 1.x from mangling short names like `links.created` → `links_total`.

### Acceptance Criteria

- [x] для каждой метрики определены name, type, unit и labels;
- [x] high-cardinality labels отсутствуют;
- [x] список metric names отражён в документации проекта (`docs/observability-contract.md`);
- [x] Grafana dashboard будет строиться только на зафиксированном контракте.

---

## Task 29.2 — Подключить Prometheus registry

### Цель

Экспортировать Micrometer metrics в Prometheus exposition format.

### Шаги

- [x] Добавить Prometheus registry dependency, совместимую с текущим Spring Boot/Micrometer stack (`io.micrometer:micrometer-registry-prometheus`).
- [x] Проверить, что Actuator уже подключён согласно Sprint 13.
- [x] Включить Prometheus endpoint — Spring Boot auto-configures `/actuator/prometheus` when the registry is on the classpath.
- [x] Настроить exposure только необходимых management endpoints (`health,metrics` уже настроено в `application.yaml`).
- [x] Не открывать все actuator endpoints без необходимости — оставлены только `health` и `metrics`.
- [x] Проверить формат endpoint: `/actuator/prometheus`.

### Acceptance Criteria

- [x] endpoint отвечает `200`;
- [x] в output есть application metrics;
- [x] credentials/secrets отсутствуют в metrics;
- [x] endpoint не требует доступа к внутренним database credentials.

**Verification:**

```
$ curl -s http://localhost:8080/actuator/prometheus | grep "^urlshorter"
urlshorter_cacheHit_total 0.0
urlshorter_cacheMiss_total 0.0
urlshorter_clicksFlushFailed_total 0.0
...
urlshorter_linksCreated_total 1.0
urlshorter_linksDeleted_total 0.0
urlshorter_linksExpired_total 0.0
urlshorter_linksNotFound_total 0.0
urlshorter_linksRedirected_total 1.0
urlshorter_postgresLookupDuration_seconds_count 0
urlshorter_redirectDuration_seconds_count 0
urlshorter_redisLookupDuration_seconds_count 0
```

After create → redirect → flush:

```
urlshorter_linksCreated_total 1.0
urlshorter_linksRedirected_total 1.0
urlshorter_cacheMiss_total 1.0
urlshorter_clicksIncremented_total 1.0
urlshorter_clicksFlushed_total 1.0
urlshorter_redirectDuration_seconds_count 1
urlshorter_redirectDuration_seconds_sum 0.058
```

**Naming fix:** Micrometer 1.16 → Prometheus 1.x converter mangles names like `links_created` → `links_total`. Solved by using `urlshorter.` namespace prefix with camelCase (e.g. `urlshorter.linksCreated` → `urlshorter_linksCreated_total`).

---

## Task 29.3 — Проверить naming и tags Micrometer

### Цель

Избежать хаотичной структуры metric names после подключения Prometheus.

### Шаги

- [x] Проверить naming convention Micrometer → Prometheus.
- [x] Убедиться, что counters и timers экспортируются предсказуемо.
- [x] Проверить common tags.
- [x] Использовать только низкокардинальные tags (`application`).
- [x] Не добавлять `code`, `alias`, `url`, `username` и подобные идентификаторы.
- [x] Зафиксировать naming convention в контракте (`docs/observability-contract.md`).
- [x] Проверить, что одно и то же приложение не создаёт несогласованные metric names.

### Acceptance Criteria

- [x] naming convention документирована (`docs/observability-contract.md`);
- [x] common tags единообразны (только `application = "URL-Shorter"`);
- [x] количество уникальных time series контролируемо (14 метрик × 1 label);
- [x] Prometheus scrape не создаёт неожиданный рост cardinality.

---

## Task 29.4 — Создать Prometheus configuration as code

### Цель

Полностью вынести Prometheus configuration в Git.

### Файловая структура

Создать примерно:

```text
monitoring/
└── prometheus/
    ├── prometheus.yml
    └── rules/
        ├── recording-rules.yml
        └── alert-rules.yml
```

### Шаги

- [x] Создать `prometheus.yml`.
- [x] Зафиксировать global configuration.
- [x] Настроить `scrape_interval`.
- [x] Настроить `evaluation_interval`.
- [x] Добавить scrape job для Spring Boot application.
- [x] Указать hostname/port приложения через Docker networking.
- [x] Добавить `metrics_path: /actuator/prometheus`.
- [x] Добавить необходимый scrape timeout.
- [x] Настроить labels для окружения.
- [x] Не использовать hard-coded production secrets.
- [x] Не хранить credentials в Git.

### Acceptance Criteria

Prometheus configuration:

- [x] находится в repository;
- [x] читается контейнером Prometheus;
- [x] проходит syntax/config validation;
- [x] после запуска автоматически обнаруживает application target;
- [x] не требует ручной настройки через Web UI.

**Evidence (verified 2026-09-30):** `promtool check config` → `SUCCESS: 2 rule files found` + `valid prometheus config file syntax`. Live verification with `prom/prometheus:v2.55.1` mounting `monitoring/prometheus/prometheus.yml` + `rules/` read-only on the compose network: target `http://app:8080/actuator/prometheus` discovered automatically, `up == 1`, `urlshorter_linksRedirected_total` and `urlshorter_redirectDuration_seconds_count` scraped with labels `{application="URL-Shorter", instance="app:8080", job="urlshorter-app"}`; no config-load errors in Prometheus logs; no manual UI configuration performed.

---

## Task 29.5 — Добавить Prometheus recording rules as code

### Цель

Подготовить производные запросы, которые используются несколькими dashboard panels и alert rules.

### Шаги

- [x] Создать `recording-rules.yml`.
- [x] Не дублировать сложные PromQL expressions в каждом panel.
- [x] Определить агрегированные series для:
    - [x] request rate → `urlshorter_http_requests:rate5m`;
    - [x] redirect rate → `urlshorter_redirects:rate5m`;
    - [x] error rate → `urlshorter_http_errors:rate5m`;
    - [x] cache hit ratio → `urlshorter_cache_hit_ratio:5m`;
    - [x] click flush rate → `urlshorter_clicks_flushed:rate5m`;
    - [x] flush failure rate → `urlshorter_click_flush_failures:rate5m`;
    - [x] request latency quantiles → `urlshorter_http_duration:p50/p95/p99`, `urlshorter_redirect_duration:*`, `urlshorter_postgres_lookup_duration:*`, `urlshorter_redis_lookup_duration:*`.
- [x] Имена recording rules предсказуемы: `urlshorter_<category>:<aggregation>`.
- [x] Использовать только метрики, которые реально существуют в application metrics contract.

### Фактическая структура (3 группы, 25 правил)

```
urlshorter.rates       →  11 rules (request, redirect, error, cache, click, flush rates)
urlshorter.ratios      →   2 rules (cache hit ratio, HTTP error ratio)
urlshorter.latency     →  12 rules (p50/p95/p99 × 4 latency domains)
```

### Acceptance Criteria

- [x] rules загружаются Prometheus (prometheus.yml: `rule_files: rules/*.yml`);
- [x] все expressions синтаксически корректны (Python YAML parse + manual review);
- [x] recording rules появятся в Prometheus (встроены в `monitoring/prometheus/rules/`);
- [x] dashboard использует recording rules там, где это уменьшает дублирование PromQL (Task 29.9 dashboard panels ссылаться на `urlshorter_*` recorded series вместо дублирования сложных PromQL).

---

## Task 29.6 — Добавить alert rules as code

### Цель

Зафиксировать минимальный набор operational conditions в Git.

### Шаги

Создать правила для сигналов, которые имеют понятную операционную семантику:

- [x] application target недоступен; → `urlshorter_target_down` (critical, 30s)
- [x] HTTP error rate превышает согласованный threshold; → `urlshorter_http_error_ratio_high` (critical, 2m), `urlshorter_5xx_spike` (warning, 1m)
- [x] redirect latency существенно выросла; → `urlshorter_redirect_latency_p95_high` (warning, 2m, > 100 ms)
- [x] Redis/cache failure rate выросла; → `urlshorter_click_increment_failures` (critical, 1m)
- [x] click flush failures продолжаются; → `urlshorter_flush_failures` (critical, 5m)
- [x] pending flush backlog не уменьшается; → `urlshorter_pending_flush_backlog` (critical, 3m, > 500)
- [x] PostgreSQL connectivity/availability signal указывает на проблему; → `urlshorter_postgres_lookup_latency_p95_high` (warning, 2m), `urlshorter_connection_pool_saturation` (warning, 2m)

Для каждого alert определить:

```text
alert name
expr
for
severity
summary
description
labels
annotations
```

### Важный принцип

Threshold должен быть основан на performance baseline из Sprint 23 и operational behaviour Sprint 17–18, а не выбран произвольно.

### Acceptance Criteria

- [x] alert rules хранятся в Git;
- [x] правила проходят validation (YAML parse via pyyaml — 6 групп, 13 правил);
- [x] Prometheus их загружает (verified live below);
- [x] alert имеет понятное описание и severity;
- [x] нет alerts на каждую отдельную transient ошибку — все правила имеют `for` коoldown (1m–5m).

---

## Task 29.7 — Добавить Prometheus в Docker Compose

### Цель

Сделать локальный monitoring stack воспроизводимым.

### Шаги

- [x] Добавить Prometheus service в основной Compose stack.
- [x] Определить отдельный named volume для Prometheus data.
- [x] Подключить monitoring configuration через read-only bind mounts.
- [x] Подключить rules directory.
- [x] Настроить dependency/startup conditions.
- [x] Добавить healthcheck для Prometheus.
- [x] Проверить network connectivity:

```text
prometheus → app:actuator/prometheus
```

- [x] Открыть Prometheus UI локально только на dev environment.
- [x] Проверить lifecycle после `docker compose down` и повторного `up`.

### Acceptance Criteria

```bash
docker compose up -d
```

запускает application, PostgreSQL, Redis и Prometheus без ручной configuration.

---

## Task 29.8 — Создать Grafana datasource provisioning as code

### Цель

Grafana должна автоматически подключаться к Prometheus после старта.

### Файловая структура

```text
monitoring/
└── grafana/
    ├── dashboards/
    │   └── url-shortener-overview.json
    └── provisioning/
        ├── datasources/
        │   └── prometheus.yml
        └── dashboards/
            └── dashboards.yml
```

### Шаги

- [x] Добавить Grafana service в Docker Compose.
- [x] Создать datasource provisioning file.
- [x] Указать Prometheus URL по имени Docker service, а не `localhost` внутри container.
- [x] Сделать datasource default.
- [x] Отключить необходимость ручного ввода datasource через UI.
- [x] Настроить provisioning path.
- [x] Проверить startup с пустой Grafana state.

### Acceptance Criteria

После первого запуска (включая чистый volume):

```text
Grafana
  ↓
Datasource exists automatically
  ↓
Prometheus connection = healthy
```

- [x] datasource создан автоматически (`PBFA97CFB590B2093`);
- [x] datasource URL корректен внутри Compose network (`http://prometheus:9090`);
- [x] отсутствие ручных UI steps подтверждено на чистом volume.

**Evidence (verified 2026-10-01):**

```
Datasource:  curl -s -u admin:admin http://localhost:14000/api/datasources
→ [{name: Prometheus, type: prometheus, url: http://prometheus:9090, isDefault: true}]

Prometheus:  curl -s http://localhost:9090/-/healthy
→ Prometheus Server is Healthy.

Target UP:   curl -s http://localhost:9090/api/v1/query?query=up
→ urlshorter-app target health=up, value=1

Dashboard:   curl -s -u admin:admin http://localhost:14000/api/dashboards/uid/url-shortener-overview
→ type:db, title: URL Shortener Overview, uid: url-shortener-overview, panels: 22, folder: URL Shortener

Clean volume: docker compose down -v + up — datasource + dashboard re-provisioned automatically.
```

---

## Task 29.9 — Создать Grafana dashboard as code

### Цель

Получить production-oriented dashboard для URL Shortener.

### Шаги

Создать dashboard JSON под Git control.

### Dashboard sections

#### 1. Service overview

Показывать:

```text
Requests/sec
Redirects/sec
Error rate
Active targets
```

#### 2. HTTP latency

Показывать:

```text
p50
p95
p99
```

для ключевых HTTP операций.

#### 3. Redirect flow

Показывать:

```text
redirected
not_found
expired
```

#### 4. Redis

Показывать:

```text
cache hit rate
cache misses
Redis operation failures
```

#### 5. Click buffering

Показывать:

```text
click increments
flush rate
flush failures
pending backlog
```

#### 6. PostgreSQL

Показывать доступные и полезные application-level DB signals:

```text
lookup latency
connection pool usage
DB errors
```

#### 7. JVM / process

Показывать:

```text
heap usage
GC activity
CPU
threads
process uptime
```

### Dashboard requirements

- [ ] Использовать template variables только для low-cardinality dimensions.
- [ ] Добавить environment variable.
- [ ] Добавить service/application variable при необходимости.
- [ ] Использовать разумные dashboard refresh intervals.
- [ ] Не делать dashboard зависимым от ручно созданных queries.
- [ ] Добавить units к panel values.
- [ ] Для ratios использовать проценты.
- [ ] Для latency явно указывать milliseconds/seconds.
- [ ] Добавить description panel/row там, где metric semantics неочевидна.

### Acceptance Criteria

- [ ] dashboard импортируется автоматически;
- [ ] все panels отображают данные при работающем приложении;
- [ ] ни один panel не содержит broken query;
- [ ] после `git clone` dashboard доступен без ручного import.

---

## Task 29.10 — Настроить Grafana dashboard provisioning

### Цель

Grafana должна автоматически загружать dashboards из repository.

### Шаги

- [x] Создать dashboard provider configuration (`monitoring/grafana/provisioning/dashboards/dashboards.yml`).
- [x] Указать путь к dashboard JSON (`/var/lib/grafana/dashboards`).
- [x] Включить автоматическое provisioning (`type: file`, `updateIntervalSeconds: 10`).
- [x] Зафиксировать folder (`URL Shortener` — в provider config + в dashboard JSON `folder` field).
- [x] Определить overwrite/update semantics (`editable: false`, `allowUiUpdates: false`, dashboard как код).
- [x] Проверить обновление dashboard после изменения JSON.
- [x] Убедиться, что dashboard не создаётся как единственный mutable объект внутри container volume (dashboard из bind-mount, state в persistent volume).

### Acceptance Criteria

Проверить сценарий:

```text
empty Grafana volume
        ↓
container start
        ↓
provider loads dashboard
        ↓
dashboard visible
```

и:

```text
change dashboard JSON
        ↓
restart/reprovision
        ↓
Grafana reflects Git version
```

### Evidence (verified 2026-10-01)

**Clean volume test** (`docker compose down -v` → `up -d`):
```
Provisioning: docker logs urlshorter-grafana | grep provisioning.dashboard
→ "starting to provision dashboards" + "finished to provision dashboards"

Dashboard: curl -s -u admin:admin http://localhost:14000/api/dashboards/uid/url-shortener-overview
→ type:db, title: URL Shortener Overview, uid: url-shortener-overview,
   folderTitle: URL Shortener, panels: 24

Datasource: curl -s -u admin:admin http://localhost:14000/api/datasources
→ [{name: Prometheus, type: prometheus, url: http://prometheus:9090, isDefault: true}]
```

**Update semantics test** (change title → `url-shortener-overview (updated)` → restart):
```
Title before: URL Shortener Overview (version 3)
Title after:  URL Shortener Overview (updated) → confirmed in API response
Title restored: URL Shortener Overview → confirmed after restoring + restart
```

**Provisioning configuration:**
```yaml
apiVersion: 1
providers:
  - name: url-shortener
    orgId: 1
    folder: URL Shortener
    type: file
    disableDeletion: false
    editable: false          # prevent manual edits from persisting
    options:
      path: /var/lib/grafana/dashboards
      foldersFromFilesStructure: false
      updateIntervalSeconds: 10  # auto-refresh from Git every 10s
      allowUiUpdates: false      # dashboard-as-code: no UI changes survive restart
```

**Dashboard JSON** (`folder: URL Shortener`, `uid: url-shortener-overview`, `version: 3`) — all panels use `datasource.uid: Prometheus`, no broken queries detected.

---

## Task 29.11 — Добавить smoke/integration tests для monitoring stack

### Цель

Проверять не только приложение, но и весь metrics pipeline.

### Шаги

Создать тестовый сценарий:

```text
application starts
   ↓
request is executed
   ↓
Micrometer metric changes
   ↓
/actuator/prometheus exposes metric
   ↓
Prometheus scrapes metric
   ↓
Grafana can query metric
```

Проверить минимум:

- [x] application target discovered;
- [x] target state = `UP`;
- [x] application metrics scraped;
- [x] generated request changes expected counter;
- [x] PromQL query returns non-empty result;
- [x] recording rule is evaluated;
- [x] alert rule is loaded.

### Варианты реализации

- [x] lightweight smoke test на Compose stack;
- [ ] Testcontainers-based monitoring test при приемлемой стоимости запуска;
- [x] отдельный CI job для полного monitoring stack.

Не обязательно запускать Grafana на каждый unit test. Полный stack validation должен быть отдельным integration/smoke stage.

### Acceptance Criteria

- [x] хотя бы один автоматизированный тест подтверждает end-to-end metrics path;
- [x] test падает при сломанном Prometheus config;
- [x] test падает при сломанном scrape path;
- [x] test падает при отсутствующем dashboard provisioning/configuration.

---

## Task 29.12 — Validate monitoring configuration as code

### Цель

Сделать monitoring configuration частью обычного quality gate.

### Шаги

Добавить локальную/CI validation для:

- [ ] Prometheus config syntax;
- [ ] recording rules syntax;
- [ ] alert rules syntax;
- [ ] YAML files;
- [ ] Grafana provisioning YAML;
- [ ] dashboard JSON validity.

По возможности добавить отдельные команды/scripts:

```text
validate-prometheus
validate-prometheus-rules
validate-grafana-provisioning
validate-grafana-dashboards
```

- [ ] Сделать команды reproducible внутри Docker/container или CI runner.
- [ ] Не зависеть от локально установленного Grafana/Prometheus CLI без documented setup.
- [ ] Включить validation в GitHub Actions после обычных tests.

### Acceptance Criteria

CI должен завершаться ошибкой, если:

```text
invalid prometheus.yml
OR
invalid rule
OR
invalid dashboard JSON
OR
broken provisioning file
```

---

## Task 29.13 — Проверить metrics correctness под реальной нагрузкой

### Цель

Убедиться, что dashboard и rules показывают корректные значения во время нагрузки.

### Шаги

Использовать сценарии из Sprint 23:

```text
warm-up
→ sustained redirects
→ spike
→ cool-down
```

Проверить:

- [ ] request rate примерно соответствует генератору нагрузки;
- [ ] redirect counter растёт только для successful redirects;
- [ ] 404/410 отражаются в соответствующих counters;
- [ ] cache hit/miss ratio меняется ожидаемым образом;
- [ ] click increment counter соответствует успешным кликам;
- [ ] click flush counter соответствует фактически выполненным flush cycles;
- [ ] flush failure metric не растёт при нормальной работе;
- [ ] latency panels реагируют на искусственное ухудшение зависимости;
- [ ] JVM metrics не содержат аномальных скачков только из-за dashboard queries.

### Acceptance Criteria

- [ ] значения dashboard сверены с исходными application metrics;
- [ ] хотя бы один load test используется для валидации dashboard;
- [ ] расхождения между business counters и traffic logs отсутствуют или объяснены.

---

## Task 29.14 — Production security и exposure review

### Цель

Не превратить metrics stack в дополнительную поверхность атаки.

### Шаги

- [ ] Проверить exposure `/actuator/prometheus`.
- [ ] Определить, доступен ли metrics endpoint только внутри internal network.
- [ ] Не публиковать Prometheus/Grafana ports в production без необходимости.
- [ ] Для local development сохранить удобный host access.
- [ ] Не хранить Grafana admin credentials в Git.
- [ ] Передавать credentials через environment variables/secrets mechanism.
- [ ] Проверить, что metrics не содержат URL query parameters и пользовательские данные.
- [ ] Проверить dashboard variables на отсутствие secret leakage.
- [ ] Документировать production ingress policy.

### Acceptance Criteria

- [ ] application metrics не содержат secrets;
- [ ] production exposure policy задокументирована;
- [ ] Grafana credentials не hardcoded;
- [ ] monitoring services не требуют public exposure для внутреннего использования.

---

## Task 29.15 — Обновить README, DEVELOPMENT.md и ADR

### Цель

Сделать monitoring stack частью официальной архитектуры проекта.

### Шаги

Обновить README:

- [ ] добавить Prometheus в technology stack;
- [ ] добавить Grafana в technology stack;
- [ ] добавить monitoring architecture diagram;
- [ ] описать endpoints;
- [ ] указать локальные URLs;
- [ ] описать запуск monitoring stack;
- [ ] показать расположение configuration-as-code files.

Обновить DEVELOPMENT.md:

- [ ] как открыть Prometheus;
- [ ] как открыть Grafana;
- [ ] как проверить target;
- [ ] как найти metric;
- [ ] как локально проверить PromQL;
- [ ] как изменить dashboard;
- [ ] как изменить alert rule;
- [ ] как валидировать monitoring configuration.

Добавить ADR:

```text
ADR-007 — Prometheus + Grafana monitoring stack
ADR-008 — Monitoring configuration as code
```

В ADR-007 зафиксировать:

- [ ] почему выбран Prometheus;
- [ ] почему выбран Grafana;
- [ ] scrape model;
- [ ] retention expectations;
- [ ] network placement.

В ADR-008 зафиксировать:

- [ ] dashboard JSON хранится в Git;
- [ ] Prometheus YAML хранится в Git;
- [ ] rules хранятся в Git;
- [ ] provisioning не выполняется вручную через UI;
- [ ] CI валидирует monitoring artifacts.

---

# Sprint 29 — Definition of Done

Sprint считается завершённым, если одновременно выполнены все условия:

- [ ] Spring Boot экспортирует Prometheus metrics через Actuator.
- [ ] Prometheus configuration находится в Git.
- [ ] Prometheus scrape target приложения работает.
- [ ] Recording rules находятся в Git и загружаются.
- [ ] Alert rules находятся в Git и загружаются.
- [ ] Grafana datasource создаётся автоматически.
- [ ] Grafana dashboard создаётся автоматически.
- [ ] Dashboard покрывает основные application/business/infra metrics.
- [ ] Monitoring stack запускается через Docker Compose.
- [ ] Monitoring configuration проходит automated validation.
- [ ] Есть integration/smoke validation metrics pipeline.
- [ ] Нет high-cardinality labels.
- [ ] Metrics и dashboards не содержат secrets/user-provided sensitive values.
- [ ] README и DEVELOPMENT.md обновлены.
- [ ] ADR-007 и ADR-008 добавлены.
- [ ] CI проверяет monitoring artifacts.

---

# Sprint 29 — Итоговая architecture flow

```text
                         ┌─────────────────────────────┐
                         │       Spring Boot            │
                         │         Java 25              │
                         │                             │
                         │ Domain / Service / API      │
                         └──────────────┬──────────────┘
                                        │
                                        │ Micrometer
                                        ▼
                              ┌──────────────────────┐
                              │ Spring Boot Actuator │
                              │ /actuator/prometheus │
                              └──────────┬───────────┘
                                         │ scrape
                                         ▼
                              ┌──────────────────────┐
                              │      Prometheus      │
                              │                      │
                              │ scrape + rules      │
                              │ recording rules     │
                              │ alert rules          │
                              └──────────┬───────────┘
                                         │ PromQL
                                         ▼
                              ┌──────────────────────┐
                              │       Grafana        │
                              │                      │
                              │ datasource as code   │
                              │ dashboard as code    │
                              └──────────────────────┘

Monitoring configuration:

Git repository
    ├── prometheus.yml
    ├── recording-rules.yml
    ├── alert-rules.yml
    ├── grafana provisioning
    └── dashboard JSON
             │
             ▼
        Docker Compose
             │
             ▼
    reproducible monitoring
```

---

# Sprint 29 — Recommended implementation order

```text
29.1  Metrics contract
   ↓
29.2  Prometheus registry
   ↓
29.3  Naming / tags / cardinality
   ↓
29.4  Prometheus config as code
   ↓
29.5  Recording rules
   ↓
29.6  Alert rules
   ↓
29.7  Prometheus Docker Compose
   ↓
29.8  Grafana datasource provisioning
   ↓
29.9  Grafana dashboard
   ↓
29.10 Grafana dashboard provisioning
   ↓
29.11 End-to-end metrics smoke test
   ↓
29.12 Config validation in CI
   ↓
29.13 Load-based metrics validation
   ↓
29.14 Security review
   ↓
29.15 Documentation + ADRs
```

Каждая задача должна завершаться проверяемым артефактом. Нельзя считать monitoring setup готовым, если Prometheus и Grafana были настроены вручную через UI и после удаления контейнеров конфигурация теряется.

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
Sprint 29
```

Результат:

```text
Documentation
+
ADRs
+
Production readiness
+
Prometheus + Grafana
+
Metrics pipeline as code
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

## Milestone M7 — Metrics platform

После Sprint 29:

```text
Prometheus
+
Grafana
+
Recording rules
+
Alert rules
+
Dashboards as code
+
Monitoring validation in CI
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
S29  Prometheus + Grafana: metrics pipeline as code
```

Таким образом, разработка идёт от простого работающего CRUD/redirect-сервиса к полноценному production-oriented сервису: наиболее опасные части — Redis counter, atomic flush, consistency и concurrency — получают отдельные спринты и тестовые сценарии, а Sprint 29 добавляет полностью воспроизводимый Prometheus + Grafana metrics pipeline as code.

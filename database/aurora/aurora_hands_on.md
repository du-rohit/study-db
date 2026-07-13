# Amazon Aurora — Hands-On Guide (Java / Spring Boot)

> Purpose: interview-ready, hands-on coding reference. Companies hiring senior EM/Director roles increasingly
> probe for "can you still write it" — this document is optimized for that: working, idiomatic code you could
> reproduce on a whiteboard or shared editor, not just concepts. Pairs with `aurora.md` (internals) and
> `aurora_em.md` (decision-making) — this one assumes you already know *why*, and focuses on *how*.
>
> Stack: **Aurora PostgreSQL-compatible edition**, Spring Data JPA / Hibernate over standard `java.sql`/JDBC —
> because Aurora is wire-compatible with PostgreSQL, this is exactly the same code a team would write against
> plain RDS PostgreSQL or self-managed Postgres. The Aurora-specific parts of this doc are the operational
> concerns layered on top: connection pooling sized for `max_connections`, RDS Proxy, IAM auth, and
> failover-aware client configuration — not the JPA/SQL code itself. Where Aurora MySQL differs meaningfully,
> it's called out inline.

---

# Part 1 — Project Setup

## Maven Dependencies

```xml
<dependencies>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
  </dependency>
  <dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
  </dependency>
  <!-- schema migrations -->
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
  </dependency>
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
  </dependency>
  <!-- IAM database authentication token generation -->
  <dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>rds</artifactId>
  </dependency>

  <!-- test scope: real Postgres in a container -->
  <dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

## application.yml

```yaml
spring:
  application:
    name: order-service
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:orders}
    username: ${DB_USER:app}
    # password intentionally absent here when using IAM auth — see Part 6
    password: ${DB_PASSWORD:}
    hikari:
      # Sized deliberately against the instance's max_connections, NOT left at
      # the HikariCP default (10) — see Part 5 for the sizing rationale.
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 3000
      # Fail fast on a dead connection after failover rather than hand it out stale.
      validation-timeout: 2000
  jpa:
    hibernate:
      ddl-auto: validate   # schema owned by Flyway, never Hibernate auto-DDL in real environments
    properties:
      hibernate:
        jdbc.batch_size: 50
        order_inserts: true
        order_updates: true
  flyway:
    locations: classpath:db/migration
```

**Why `ddl-auto: validate`, not `update`:** letting Hibernate auto-generate schema changes is fine for a prototype but is a real production risk on a shared relational database — an unreviewed, auto-generated `ALTER TABLE` can lock a large table or silently diverge from what's actually been reviewed. Flyway-owned, version-controlled migrations are the standard for anything beyond a throwaway demo, and this is a near-guaranteed interview follow-up if you mention JPA at all.

---

# Part 2 — Entity Modeling with JPA

```java
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(nullable = false)
    private Instant createdAt;

    // Optimistic locking — Hibernate adds a hidden WHERE version = ? clause
    // to every UPDATE and throws OptimisticLockException on a mismatch.
    @Version
    private Long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() { }  // JPA requires a no-arg constructor

    public Order(UUID userId, BigDecimal total) {
        this.userId = userId;
        this.total = total;
        this.status = "PENDING";
        this.createdAt = Instant.now();
    }

    // getters; setters only where mutation is intentional (status transitions, etc.)
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getTotal() { return total; }
    public List<OrderItem> getItems() { return items; }
}

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    private String sku;
    private Integer quantity;

    protected OrderItem() { }

    public OrderItem(Order order, String sku, Integer quantity) {
        this.order = order;
        this.sku = sku;
        this.quantity = quantity;
    }
}
```

**Flyway migration (`V1__create_orders.sql`):**

```sql
CREATE TABLE orders (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    total NUMERIC(12, 2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_orders_user_id ON orders (user_id);

CREATE TABLE order_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders (id),
    sku VARCHAR(64) NOT NULL,
    quantity INTEGER NOT NULL
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
```

**Interview point:** `FetchType.LAZY` on the `@ManyToOne` and explicit indexes on every foreign key column are both worth calling out unprompted — a candidate who defaults associations to eager fetch, or forgets that Postgres does **not** automatically index foreign key columns (unlike the primary key), is a common source of production N+1 and slow-join incidents.

---

# Part 3 — Repositories and CRUD

```java
public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByUserId(UUID userId);

    Optional<Order> findByIdAndUserId(UUID id, UUID userId);

    // Derived query — Spring Data generates the SQL from the method name
    List<Order> findByStatusOrderByCreatedAtDesc(String status);

    // Explicit JPQL when the derived-query naming gets unwieldy
    @Query("SELECT o FROM Order o WHERE o.total > :minTotal AND o.status = :status")
    List<Order> findLargeOrdersByStatus(@Param("minTotal") BigDecimal minTotal,
                                         @Param("status") String status);
}
```

```java
@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public Order createOrder(UUID userId, BigDecimal total, List<OrderItemRequest> items) {
        Order order = new Order(userId, total);
        items.forEach(i -> order.getItems().add(new OrderItem(order, i.sku(), i.quantity())));
        return orderRepository.save(order);
    }

    @Transactional(readOnly = true)
    public List<Order> findOrdersForUser(UUID userId) {
        return orderRepository.findByUserId(userId);
    }
}
```

**Why `@Transactional(readOnly = true)` on the read path:** it's not just documentation — Hibernate skips dirty-checking overhead for read-only sessions, and (with the routing DataSource in Part 7) this is also the flag a routing layer inspects to decide whether a query is safe to send to a read replica.

---

# Part 4 — Pagination and Native Queries

```java
public interface OrderRepository extends JpaRepository<Order, UUID> {

    Page<Order> findByUserId(UUID userId, Pageable pageable);

    // Native SQL for something JPQL can't express cleanly (window functions,
    // Postgres-specific syntax) — an escape hatch, used sparingly.
    @Query(value = """
        SELECT * FROM orders
        WHERE user_id = :userId
        ORDER BY created_at DESC
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<Order> findRecentNative(@Param("userId") UUID userId,
                                  @Param("limit") int limit,
                                  @Param("offset") int offset);
}
```

```java
public PageResponse<Order> listOrders(UUID userId, int page, int size) {
    Page<Order> result = orderRepository.findByUserId(userId, PageRequest.of(page, size,
            Sort.by(Sort.Direction.DESC, "createdAt")));
    return new PageResponse<>(result.getContent(), result.getTotalElements(), result.hasNext());
}
```

**Interview point:** `LIMIT`/`OFFSET` pagination degrades on large offsets (Postgres still scans and discards the skipped rows) — for genuinely large, deep-paginated datasets, keyset pagination (`WHERE created_at < :lastSeenCreatedAt ORDER BY created_at DESC LIMIT :size`) is the standard fix, worth mentioning even if the example above uses offset for simplicity.

---

# Part 5 — Transactions and Isolation

```java
@Service
public class FundsTransferService {

    private final AccountRepository accountRepository;

    public FundsTransferService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public void transfer(UUID fromId, UUID toId, BigDecimal amount) {
        Account from = accountRepository.findById(fromId)
                .orElseThrow(() -> new AccountNotFoundException(fromId));
        Account to = accountRepository.findById(toId)
                .orElseThrow(() -> new AccountNotFoundException(toId));

        if (from.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(fromId);
        }

        from.setBalance(from.getBalance().subtract(amount));
        to.setBalance(to.getBalance().add(amount));
        // no explicit save() needed — both entities are managed within the
        // transaction; changes flush to the DB at commit (dirty checking)
    }
}
```

- Default isolation for Aurora PostgreSQL, like standard Postgres, is **Read Committed** — each statement sees a fresh snapshot of committed data, but two statements in the same transaction can see different snapshots if a concurrent commit lands between them. This is the source of classic "lost update" bugs when a transaction reads a value, computes a new value in application code, then writes it back without a guard.
- `@Version` (Part 2) is the standard **optimistic** guard against lost updates for single-entity mutations — Hibernate's generated `UPDATE ... WHERE id = ? AND version = ?` fails (0 rows affected → `OptimisticLockException`) if another transaction committed first.
- For a case needing a **pessimistic** guard instead (e.g., high-contention rows where retrying an optimistic failure repeatedly under load is worse than blocking), use `@Lock(LockModeType.PESSIMISTIC_WRITE)` on the repository method — translates to `SELECT ... FOR UPDATE`.

```java
public interface AccountRepository extends JpaRepository<Account, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);
}
```

**Interview framing:** be ready to explain *when* you'd reach for `SELECT ... FOR UPDATE` over `@Version` — pessimistic locking trades throughput (blocked transactions wait) for eliminating retry logic entirely, and is the right call specifically when contention on the same row is frequent enough that optimistic retries would themselves become a throughput problem.

---

# Part 6 — Connection Pooling and RDS Proxy

## Why Pool Sizing Is an Aurora-Specific Concern

Aurora instances have a hard `max_connections` ceiling tied to instance class (a rough formula based on available memory) — unlike DynamoDB's stateless HTTP calls, every open JDBC connection consumes a slot on that ceiling for as long as it's held. A naive default (HikariCP defaults to 10 per pool) multiplied across many application instances can still exhaust `max_connections` under horizontal scaling, and a per-request-opened connection (a mistake more common in hastily-written Lambda handlers) exhausts it far faster.

```java
@Bean
public HikariDataSource dataSource(DataSourceProperties properties) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(properties.getUrl());
    config.setUsername(properties.getUsername());
    config.setPassword(properties.getPassword());

    // Size per-instance pool as (instance max_connections / expected number
    // of concurrently-running application instances), leaving headroom for
    // admin/monitoring connections and other services sharing the cluster.
    config.setMaximumPoolSize(20);
    config.setMinimumIdle(5);

    // Fail fast rather than hang indefinitely waiting for a pool slot.
    config.setConnectionTimeout(Duration.ofSeconds(3).toMillis());

    // Aggressively recycle connections so a demoted/failed-over instance's
    // stale connections don't linger in the pool past their usefulness.
    config.setMaxLifetime(Duration.ofMinutes(10).toMillis());
    config.setKeepaliveTime(Duration.ofMinutes(2).toMillis());

    return new HikariDataSource(config);
}
```

**Why `maxLifetime` matters specifically on Aurora:** after a failover, the old writer's DNS entry now points elsewhere, but a connection pool can keep using an already-open TCP connection to the demoted instance until it's explicitly recycled. A shorter `maxLifetime` bounds how long the application can keep talking to a stale instance after a failover event, trading a small amount of connection-churn overhead for faster self-healing post-failover.

## RDS Proxy as an Alternative to Application-Level Pooling

Rather than (or in addition to) tuning HikariCP, RDS Proxy sits between the application and the cluster, multiplexing many client-side connections onto a smaller, managed pool of actual database connections, and holding client connections open transparently across a failover event (masking the reconnect from the application entirely). Point the JDBC URL at the RDS Proxy endpoint instead of the cluster endpoint directly — no other application code change required:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://order-service-proxy.proxy-xxxxx.us-east-1.rds.amazonaws.com:5432/orders
```

**When to reach for RDS Proxy vs a well-tuned HikariCP pool:** RDS Proxy is the better fit for highly elastic compute (Lambda, or an autoscaling fleet with a wide instance-count range) where the number of application-side pools is itself unpredictable — application-level pooling alone can't coordinate a *global* connection budget across instances the way a shared proxy can.

---

# Part 7 — IAM Database Authentication

```java
@Component
public class IamAuthTokenDataSource extends AbstractRoutingDataSource {
    // For most applications, static credentials via Secrets Manager (with
    // automatic rotation) are simpler and sufficient — IAM auth is worth
    // reaching for specifically when eliminating long-lived DB passwords
    // entirely is a stated security requirement.
}

@Configuration
public class IamAuthConfig {

    @Value("${aws.rds.hostname}")
    private String hostname;
    @Value("${aws.rds.port}")
    private int port;
    @Value("${aws.rds.username}")
    private String username;
    @Value("${aws.region}")
    private String region;

    @Bean
    public DataSource dataSource() {
        RdsUtilities rdsUtilities = RdsUtilities.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.of(region))
                .build();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://%s:%d/orders".formatted(hostname, port));
        config.setUsername(username);
        // The auth "token" is a short-lived (15-minute) signed value used as
        // the password — HikariCP's DataSourceProperties password supplier
        // is refreshed via a scheduled task, not set once at startup.
        config.setPassword(generateAuthToken(rdsUtilities, hostname, port, username));
        config.setMaxLifetime(Duration.ofMinutes(10).toMillis());  // shorter than token TTL
        return new HikariDataSource(config);
    }

    private String generateAuthToken(RdsUtilities rdsUtilities, String hostname, int port, String username) {
        return rdsUtilities.generateAuthenticationToken(builder -> builder
                .hostname(hostname)
                .port(port)
                .username(username));
    }
}
```

**Interview point:** the token is valid for 15 minutes, and `maxLifetime` on the pool must be set **shorter** than that so connections are recycled (and re-authenticated with a fresh token) before the token they were created with expires — a common bug is leaving `maxLifetime` at a default longer than the token TTL, causing intermittent auth failures on long-lived pooled connections well after startup.

---

# Part 8 — Read/Write Splitting via a Routing DataSource

```java
public class ReplicaRoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        // TransactionSynchronizationManager exposes whether the current
        // transaction was marked readOnly — the same flag set by
        // @Transactional(readOnly = true) in Part 3.
        boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        return readOnly ? "reader" : "writer";
    }
}

@Configuration
public class RoutingDataSourceConfig {

    @Bean
    public DataSource routingDataSource(
            @Qualifier("writerDataSource") DataSource writer,
            @Qualifier("readerDataSource") DataSource reader) {

        ReplicaRoutingDataSource routingDataSource = new ReplicaRoutingDataSource();
        routingDataSource.setTargetDataSources(Map.of("writer", writer, "reader", reader));
        routingDataSource.setDefaultTargetDataSource(writer);
        return routingDataSource;
    }
}
```

- `writerDataSource` points at the **cluster (writer) endpoint**; `readerDataSource` points at the **reader endpoint** (load-balanced across replicas) — see `aurora.md` Part 3 for what each endpoint means.
- **This routes at the transaction-attribute level, not per-query** — a `@Transactional(readOnly = true)` service method sends every statement inside it to the reader pool. Mixing a write inside a method marked `readOnly = true` will either fail (Postgres rejects writes on certain replica-routed sessions) or silently misroute — keep the boundary strict.
- **Explicitly not safe for read-your-writes:** immediately reading back a value just written in a different (non-readOnly) transaction can hit a replica that hasn't caught up yet (Part 3 of `aurora.md` — replica lag is real, if small). For any code path needing strict read-after-write consistency, keep that specific read inside the same writer-routed transaction, or use a non-`readOnly` transaction deliberately even though it's logically just a read.

---

# Part 9 — Fast Cloning for Migration Testing (Hero Feature)

This is worth demonstrating live because it's Aurora's most distinctive operational capability with no equivalent in plain self-managed Postgres/MySQL, and it's directly relevant to how a senior engineer should validate a risky schema change.

```java
// This isn't application code — it's the AWS SDK call an engineer would
// script as part of a migration-testing pipeline, worth being able to
// sketch even outside a Spring Boot context.
@Component
public class ClusterCloneService {

    private final RdsClient rdsClient;

    public ClusterCloneService(RdsClient rdsClient) {
        this.rdsClient = rdsClient;
    }

    public String cloneForMigrationTest(String sourceClusterId) {
        String cloneId = sourceClusterId + "-migration-test-" + System.currentTimeMillis();

        rdsClient.restoreDbClusterToPointInTime(builder -> builder
                .sourceDbClusterIdentifier(sourceClusterId)
                .dbClusterIdentifier(cloneId)
                .restoreType("copy-on-write")   // the actual Aurora fast-clone mechanism
                .useLatestRestorableTime(true));

        return cloneId;
    }
}
```

**Interview framing:** the point isn't memorizing this exact SDK call — it's being able to explain *why* this is fast (copy-on-write at the storage layer, no upfront data copy, minutes regardless of database size — see `aurora.md` Part 7) and *when* a senior engineer would reach for it: testing a schema migration or a risky bulk `UPDATE` against real production-scale data without touching production, then discarding the clone. Contrast explicitly with restoring from a snapshot, which is slower because it materializes a full independent copy.

---

# Part 10 — Testing with Testcontainers

```java
@Testcontainers
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("orders")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void savesAndFindsOrdersForUser() {
        UUID userId = UUID.randomUUID();
        orderRepository.save(new Order(userId, new BigDecimal("49.99")));

        List<Order> found = orderRepository.findByUserId(userId);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getTotal()).isEqualByComparingTo("49.99");
    }

    @Test
    void optimisticLockingRejectsStaleUpdate() {
        Order saved = orderRepository.save(new Order(UUID.randomUUID(), BigDecimal.TEN));

        Order copy1 = orderRepository.findById(saved.getId()).orElseThrow();
        Order copy2 = orderRepository.findById(saved.getId()).orElseThrow();

        copy1.setStatus("SHIPPED");
        orderRepository.saveAndFlush(copy1);

        copy2.setStatus("CANCELLED");
        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> orderRepository.saveAndFlush(copy2));
    }
}
```

**Why plain `postgres:16`, not an Aurora-specific image:** no official Aurora emulator exists — Aurora's wire-compatibility with standard PostgreSQL means the application/JPA layer is fully testable against real Postgres, but Aurora-specific behaviors (replica lag, fast cloning, failover timing, storage-layer durability characteristics) cannot be exercised this way and need a real Aurora cluster (typically a shared dev/staging environment) for that level of validation. Say this explicitly if asked "how do you test Aurora locally" — pretending a Postgres container fully substitutes for Aurora is a gap worth naming, not hiding.

---

# Part 11 — End-to-End Spring Boot Service Example

```java
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) { this.orderService = orderService; }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@RequestBody CreateOrderRequest request) {
        Order order = orderService.createOrder(request.userId(), request.total(), request.items());
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
    }

    @GetMapping("/{userId}")
    public List<OrderResponse> listForUser(@PathVariable UUID userId) {
        return orderService.findOrdersForUser(userId).stream().map(OrderResponse::from).toList();
    }
}

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public Order createOrder(UUID userId, BigDecimal total, List<OrderItemRequest> items) {
        Order order = new Order(userId, total);
        items.forEach(i -> order.getItems().add(new OrderItem(order, i.sku(), i.quantity())));
        return orderRepository.save(order);
    }

    @Transactional(readOnly = true)
    public List<Order> findOrdersForUser(UUID userId) {
        return orderRepository.findByUserId(userId);
    }
}
```

This is the layering worth defending in an interview: **Controller → Service (transaction boundaries, business rules, read/write routing via `@Transactional(readOnly=...)`) → Repository (JPA/SQL specifics)** — the same separation-of-concerns argument as any Spring Boot data-access layer, with the added Aurora-specific nuance that the `readOnly` flag on `@Transactional` isn't just documentation here, it's a real routing signal (Part 8).

---

# Part 12 — Common "Write This Code" Interview Exercises

**"Implement optimistic-concurrency-safe order status update."**
→ Part 2's `@Version` field plus catching `ObjectOptimisticLockingFailureException` at the service layer, retrying or surfacing a 409 to the caller.

**"Implement a funds transfer between two accounts, safe against lost updates."**
→ Part 5's `transfer` method — either `@Version`-based optimistic retry, or `SELECT ... FOR UPDATE` via `@Lock(LockModeType.PESSIMISTIC_WRITE)` if contention on the same accounts is frequent enough to justify blocking over retrying.

**"How would you route read-only traffic to a read replica without duplicating repository code?"**
→ Part 8's `AbstractRoutingDataSource` keyed off `TransactionSynchronizationManager.isCurrentTransactionReadOnly()`, driven by `@Transactional(readOnly = true)` at the service layer — no repository-level changes needed.

**"Size a connection pool for a service running N instances against an Aurora cluster with a known `max_connections`."**
→ Part 6's sizing rationale: `maximumPoolSize` per instance ≈ `max_connections / expected concurrent application instance count`, with headroom left for admin/monitoring connections — plus `maxLifetime` shorter than any credential TTL (IAM token) and short enough to recycle stale connections promptly after a failover.

**"Design and code a safe way to test a schema migration against production-scale data."**
→ Part 9's fast-clone approach — copy-on-write, minutes regardless of size, explicitly contrasted with a slower snapshot-restore.

**"How would you test this repository layer without a live Aurora cluster?"**
→ Part 10's Testcontainers + plain `postgres` image, with the explicit caveat about what it can't validate (replica lag, failover, cloning).

**"Paginate through a large result set for an API response, and explain the tradeoff of your approach."**
→ Part 4's offset pagination for the simple case, with keyset pagination (`WHERE created_at < :cursor`) named as the fix for deep pagination over a large table where `OFFSET` becomes a real cost.

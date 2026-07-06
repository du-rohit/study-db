# Amazon DynamoDB — Hands-On Guide (Java / Spring Boot)

> Purpose: interview-ready, hands-on coding reference. Companies hiring senior EM/Director roles increasingly
> probe for "can you still write it" — this document is optimized for that: working, idiomatic code you could
> reproduce on a whiteboard or shared editor, not just concepts. Pairs with `dynamodb.md` (internals) and
> `dynamodb_em.md` (decision-making) — this one assumes you already know *why*, and focuses on *how*.
>
> Stack: AWS SDK for Java v2, **DynamoDB Enhanced Client** (the modern, annotation-driven mapper — not the
> legacy v1 `DynamoDBMapper`, and not the community "Spring Data DynamoDB" project, which is unofficial and
> lightly maintained). This is what most production Spring Boot shops actually use today.

---

# Part 1 — Project Setup

## Maven Dependencies

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>software.amazon.awssdk</groupId>
      <artifactId>bom</artifactId>
      <version>2.27.21</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <!-- low-level client -->
  <dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>dynamodb</artifactId>
  </dependency>
  <!-- annotation-driven object mapper on top of the low-level client -->
  <dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>dynamodb-enhanced</artifactId>
  </dependency>
  <!-- non-blocking HTTP client; swap for apache-client if you prefer sync/blocking -->
  <dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>netty-nio-client</artifactId>
  </dependency>

  <!-- test scope: run DynamoDB Local in a container for integration tests -->
  <dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
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
aws:
  region: us-east-1
  dynamodb:
    # unset in real AWS; point at DynamoDB Local for dev/test
    endpoint-override: ${DYNAMODB_ENDPOINT_OVERRIDE:}
    table:
      orders: Orders
      accounts: Accounts

spring:
  application:
    name: order-service
```

## Client Configuration Bean

```java
@Configuration
public class DynamoDbConfig {

    @Value("${aws.region}")
    private String region;

    @Value("${aws.dynamodb.endpoint-override:}")
    private String endpointOverride;

    @Bean
    public DynamoDbClient dynamoDbClient() {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(region));

        // In real AWS, credentials come from the default provider chain
        // (IAM role on ECS/EKS/Lambda) — never hardcode keys.
        if (StringUtils.hasText(endpointOverride)) {
            // Local/dev only: DynamoDB Local ignores real credentials but the
            // SDK still requires *some* value to be present.
            builder.endpointOverride(URI.create(endpointOverride))
                   .credentialsProvider(StaticCredentialsProvider.create(
                           AwsBasicCredentials.create("dummy", "dummy")));
        }
        return builder.build();
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient client) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(client)
                .build();
    }
}
```

**Why a separate `endpointOverride` toggle:** this is the single config knob that lets the exact same Spring Boot application code run against DynamoDB Local in tests/dev and real DynamoDB in AWS — you never want environment-specific `if (isProd)` branching inside repository code.

---

# Part 2 — Modeling a Table with the Enhanced Client

```java
@DynamoDbBean
public class Order {

    private String userId;      // partition key
    private String orderId;     // sort key
    private String status;
    private BigDecimal total;
    private Instant createdAt;
    private Long version;       // optimistic locking — see Part 10

    @DynamoDbPartitionKey
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    @DynamoDbSortKey
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    // Exposes this attribute as a GSI partition key too — same field,
    // queryable both via the base table (by user) and this index (by status).
    @DynamoDbSecondaryPartitionKey(indexNames = "status-index")
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    @DynamoDbSecondarySortKey(indexNames = "status-index")
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }

    @DynamoDbVersionAttribute
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
```

Notes:
- `@DynamoDbBean` requires a public no-arg constructor and standard getter/setter pairs — the Enhanced Client uses reflection over the bean, similar in spirit to a JPA `@Entity` but with no ORM-style relationship mapping (there are no relationships to map).
- `BigDecimal` is the correct Java type for DynamoDB's `N` — using `double`/`float` risks the precision issues DynamoDB's string-backed number type was designed to avoid.
- `Instant` is supported out of the box via a built-in converter (serialized as an ISO-8601 string). Custom types need a custom `AttributeConverter` (Part 2a below).

## Building the Table Reference

```java
@Configuration
public class OrderTableConfig {

    @Bean
    public DynamoDbTable<Order> orderTable(DynamoDbEnhancedClient enhancedClient) {
        return enhancedClient.table("Orders", TableSchema.fromBean(Order.class));
    }
}
```

## Custom Attribute Converters

```java
public class MoneyConverter implements AttributeConverter<Money> {
    @Override
    public AttributeValue transformFrom(Money input) {
        return AttributeValue.builder().n(input.getAmount().toPlainString()).build();
    }
    @Override
    public Money transformTo(AttributeValue input) {
        return new Money(new BigDecimal(input.n()));
    }
    @Override
    public EnhancedType<Money> type() { return EnhancedType.of(Money.class); }
    @Override
    public AttributeValueType attributeValueType() { return AttributeValueType.N; }
}

// usage on the bean:
@DynamoDbConvertedBy(MoneyConverter.class)
public Money getBalance() { return balance; }
```

## Creating the Table (Infra Note)

In real projects, table creation belongs in infrastructure-as-code (Terraform/CDK/CloudFormation), not application startup — this is worth saying explicitly in an interview, since "how do you provision this" is a common follow-up. For local/test environments only:

```java
orderTable.createTable(builder -> builder
    .provisionedThroughput(b -> b.readCapacityUnits(5L).writeCapacityUnits(5L).build())
    .globalSecondaryIndices(gsi -> gsi
        .indexName("status-index")
        .projection(p -> p.projectionType(ProjectionType.ALL))
        .provisionedThroughput(b -> b.readCapacityUnits(5L).writeCapacityUnits(5L).build())));
```

---

# Part 3 — CRUD Operations

```java
@Repository
public class OrderRepository {

    private final DynamoDbTable<Order> table;

    public OrderRepository(DynamoDbTable<Order> table) {
        this.table = table;
    }

    public void save(Order order) {
        table.putItem(order);   // full-item overwrite
    }

    public Optional<Order> findByUserAndOrderId(String userId, String orderId) {
        Order result = table.getItem(Key.builder()
                .partitionValue(userId)
                .sortValue(orderId)
                .build());
        return Optional.ofNullable(result);
    }

    // Query: partition key required, sort key condition optional — efficient,
    // reads only the matching items (unlike a Scan).
    public List<Order> findAllForUser(String userId) {
        return table.query(QueryConditional.keyEqualTo(Key.builder()
                        .partitionValue(userId)
                        .build()))
                .items()
                .stream()
                .collect(Collectors.toList());
    }

    public List<Order> findRecentOrdersForUser(String userId, String sinceOrderId) {
        return table.query(QueryConditional.sortGreaterThan(Key.builder()
                        .partitionValue(userId)
                        .sortValue(sinceOrderId)
                        .build()))
                .items()
                .stream()
                .toList();
    }

    public void delete(String userId, String orderId) {
        table.deleteItem(Key.builder().partitionValue(userId).sortValue(orderId).build());
    }
}
```

### Conditional Writes (Optimistic Concurrency, No Version Attribute)

```java
public void createIfAbsent(Order order) {
    PutItemEnhancedRequest<Order> request = PutItemEnhancedRequest.builder(Order.class)
            .item(order)
            .conditionExpression(Expression.builder()
                    .expression("attribute_not_exists(userId)")
                    .build())
            .build();
    try {
        table.putItem(request);
    } catch (ConditionalCheckFailedException e) {
        throw new OrderAlreadyExistsException(order.getOrderId());
    }
}
```

### Partial Updates via the Low-Level Client

The Enhanced Client's `updateItem` replaces the whole item by default (nulling out fields not present on the Java object) unless you opt into `ignoreNulls(true)`. For a true partial/atomic update (e.g., "increment a counter without touching anything else"), drop to the low-level client's `UpdateExpression` — same reasoning as the CQL/PartiQL distinction covered in `dynamodb.md`.

```java
@Repository
public class OrderCounterRepository {

    private final DynamoDbClient client;

    public void incrementViewCount(String userId, String orderId) {
        client.updateItem(UpdateItemRequest.builder()
                .tableName("Orders")
                .key(Map.of(
                        "userId", AttributeValue.fromS(userId),
                        "orderId", AttributeValue.fromS(orderId)))
                .updateExpression("ADD viewCount :incr")
                .expressionAttributeValues(Map.of(":incr", AttributeValue.fromN("1")))
                .build());
    }
}
```

**Enhanced Client with `ignoreNulls`:**

```java
Order patch = new Order();
patch.setUserId(userId);
patch.setOrderId(orderId);
patch.setStatus("SHIPPED");   // only this field changes

table.updateItem(UpdateItemEnhancedRequest.builder(Order.class)
        .item(patch)
        .ignoreNulls(true)
        .build());
```

---

# Part 4 — Querying Secondary Indexes

```java
@Bean
public DynamoDbIndex<Order> statusIndex(DynamoDbTable<Order> orderTable) {
    return orderTable.index("status-index");
}

public List<Order> findByStatus(String status) {
    return statusIndex.query(QueryConditional.keyEqualTo(Key.builder()
                    .partitionValue(status)
                    .build()))
            .stream()
            .flatMap(page -> page.items().stream())
            .toList();
}
```

**GSI results are always eventually consistent** — the Enhanced Client has no `consistentRead(true)` option for an index query, because DynamoDB itself doesn't support it (see `dynamodb.md`, Part 5). If asked "why can't you request a strongly consistent read here," this is the answer.

---

# Part 5 — Batch Operations

```java
public Map<String, Order> batchGet(List<Key> keys) {
    ReadBatch readBatch = keys.stream()
            .reduce(ReadBatch.builder(Order.class).mappedTableResource(table),
                    (builder, key) -> builder.addGetItem(key),
                    (b1, b2) -> b1)
            .build();

    BatchGetResultPageIterable results = enhancedClient.batchGetItem(
            BatchGetItemEnhancedRequest.builder().readBatches(readBatch).build());

    return results.resultsForTable(table).stream()
            .collect(Collectors.toMap(Order::getOrderId, o -> o));
}

public void batchPut(List<Order> orders) {
    WriteBatch.Builder<Order> batchBuilder = WriteBatch.builder(Order.class)
            .mappedTableResource(table);
    orders.forEach(batchBuilder::addPutItem);

    BatchWriteResult result = enhancedClient.batchWriteItem(
            BatchWriteItemEnhancedRequest.builder()
                    .writeBatches(batchBuilder.build())
                    .build());

    // BatchWriteItem is NOT atomic and NOT guaranteed to apply every item —
    // always check and retry unprocessed items.
    List<Order> unprocessed = result.unprocessedPutItemsForTable(table);
    if (!unprocessed.isEmpty()) {
        // retry with exponential backoff, or dead-letter after N attempts
    }
}
```

**Interview point:** `BatchWriteItem`/`BatchGetItem` are convenience batching of otherwise-independent requests, not a transaction. Contrast this explicitly with Part 6 — this distinction is a common gap in candidates who've only used DynamoDB casually.

---

# Part 6 — Transactions

```java
public void transferFunds(String fromAccountId, String toAccountId, BigDecimal amount) {
    enhancedClient.transactWriteItems(TransactWriteItemsEnhancedRequest.builder()
            .addUpdateItem(accountTable, TransactUpdateItemEnhancedRequest.builder(Account.class)
                    .item(debit(fromAccountId, amount))
                    .conditionExpression(Expression.builder()
                            .expression("balance >= :amount")
                            .putExpressionValue(":amount", AttributeValue.fromN(amount.toPlainString()))
                            .build())
                    .build())
            .addUpdateItem(accountTable, TransactUpdateItemEnhancedRequest.builder(Account.class)
                    .item(credit(toAccountId, amount))
                    .build())
            .build());
}
```

- Throws `TransactionCanceledException` on failure — inspect `getCancellationReasons()` to determine *which* item's condition failed (each reason maps positionally to a request item).
- For idempotency (safe client-side retry on a timeout without double-applying), use the **low-level** client directly with a `ClientRequestToken` — the Enhanced Client's transaction API doesn't expose this parameter:

```java
dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
        .clientRequestToken(idempotencyKey)   // e.g. a UUID generated once per logical operation
        .transactItems(/* ... */)
        .build());
```

---

# Part 7 — Single-Table Design in Java

Modeling multiple entity types in one physical table with generic `PK`/`SK` attributes — the pattern covered conceptually in `dynamodb.md`, Part 7. Two common Java approaches:

## Approach A: One Bean Per Entity Type, Shared Table

```java
@DynamoDbBean
public class OrgMembership {
    private String pk;   // "ORG#<orgId>"
    private String sk;   // "USER#<userId>"
    private String role;

    @DynamoDbPartitionKey
    public String getPk() { return pk; }
    public void setPk(String pk) { this.pk = pk; }

    @DynamoDbSortKey
    public String getSk() { return sk; }
    public void setSk(String sk) { this.sk = sk; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public static OrgMembership of(String orgId, String userId, String role) {
        OrgMembership m = new OrgMembership();
        m.setPk("ORG#" + orgId);
        m.setSk("USER#" + userId);
        m.setRole(role);
        return m;
    }
}

@DynamoDbBean
public class OrgMetadata {
    private String pk;   // "ORG#<orgId>"
    private String sk;   // "METADATA#"
    private String name;
    // ... same PK/SK annotations as above, different attribute set
}
```

Both classes map to the same physical `TableSchema`-backed `DynamoDbTable`, but only differ in which Java type you ask the Enhanced Client to (de)serialize into. A single `Query` on `pk = "ORG#1"` returns raw items spanning both types; you inspect the `sk` prefix to decide how to deserialize each row (or use a discriminator field — Approach B).

## Approach B: Adjacency List Query, Manual Row Dispatch

```java
public OrgWithMembers loadOrgWithMembers(String orgId) {
    QueryEnhancedRequest request = QueryEnhancedRequest.builder()
            .queryConditional(QueryConditional.keyEqualTo(
                    Key.builder().partitionValue("ORG#" + orgId).build()))
            .build();

    // Query the table using a raw item schema, then branch on SK prefix —
    // common when entity types don't share enough attributes for one bean.
    List<Map<String, AttributeValue>> rawItems = dynamoDbClient.query(QueryRequest.builder()
                    .tableName("AppData")
                    .keyConditionExpression("pk = :pk")
                    .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS("ORG#" + orgId)))
                    .build())
            .items();

    OrgMetadata metadata = null;
    List<OrgMembership> members = new ArrayList<>();
    for (Map<String, AttributeValue> item : rawItems) {
        String sk = item.get("sk").s();
        if (sk.startsWith("METADATA#")) {
            metadata = fromAttributeMap(item, OrgMetadata.class);
        } else if (sk.startsWith("USER#")) {
            members.add(fromAttributeMap(item, OrgMembership.class));
        }
    }
    return new OrgWithMembers(metadata, members);
}
```

**Interview framing:** single-table design in code is fundamentally "one physical table, several logical schemas, dispatched by a key prefix convention" — the complexity moves from the database into the application's mapping layer. Be ready to say explicitly that this trades query-time simplicity (one round trip) for mapping-code complexity, and that plenty of production Spring Boot services skip this and use one table per entity type until scale genuinely demands otherwise.

---

# Part 8 — Pagination

```java
public List<Order> findAllForUserAcrossPages(String userId) {
    PageIterable<Order> pages = table.query(QueryConditional.keyEqualTo(
            Key.builder().partitionValue(userId).build()));

    // The Enhanced Client's PageIterable transparently follows LastEvaluatedKey —
    // iterating `.items()` walks every page without manual cursor handling.
    return pages.items().stream().toList();
}
```

For an API endpoint that must expose pagination to its own caller (rather than fetch-everything server-side), pass the cursor through explicitly instead of exhausting all pages internally:

```java
public PageResult<Order> findPageForUser(String userId, Map<String, AttributeValue> exclusiveStartKey) {
    QueryEnhancedRequest.Builder requestBuilder = QueryEnhancedRequest.builder()
            .queryConditional(QueryConditional.keyEqualTo(
                    Key.builder().partitionValue(userId).build()))
            .limit(20);

    if (exclusiveStartKey != null) {
        requestBuilder.exclusiveStartKey(exclusiveStartKey);
    }

    Page<Order> page = table.query(requestBuilder.build()).iterator().next();
    return new PageResult<>(page.items(), page.lastEvaluatedKey());
}
```

---

# Part 9 — Error Handling and Retries

```java
try {
    table.putItem(request);
} catch (ConditionalCheckFailedException e) {
    // optimistic concurrency / idempotent-insert conflict — usually a 4xx-style
    // "already exists" or "stale write" response to the caller, not a retry
    throw new OptimisticLockException(e);
} catch (ProvisionedThroughputExceededException e) {
    // capacity throttling — safe to retry with backoff; the SDK's default
    // retry policy already does this for you on most operations
    throw new TransientDynamoDbException(e);
} catch (TransactionCanceledException e) {
    e.cancellationReasons().forEach(reason ->
            log.warn("Transaction item failed: {} - {}", reason.code(), reason.message()));
    throw new TransactionFailedException(e);
} catch (DynamoDbException e) {
    throw new DynamoDbOperationException(e);
}
```

The SDK's default retry policy (exponential backoff + jitter) already handles transient throttling for most single-item operations — don't hand-roll a retry loop around `PutItem`/`GetItem` unless you have a specific reason to override the policy:

```java
DynamoDbClient.builder()
        .overrideConfiguration(ClientOverrideConfiguration.builder()
                .retryPolicy(RetryPolicy.builder()
                        .numRetries(5)
                        .backoffStrategy(BackoffStrategy.defaultStrategy())
                        .build())
                .build())
        .build();
```

`BatchWriteItem`/`BatchGetItem` **unprocessed items are not auto-retried** by the SDK — that retry loop is the caller's responsibility (shown in Part 5).

---

# Part 10 — Optimistic Locking with `@DynamoDbVersionAttribute`

This is one of the highest-signal things to demonstrate live — it shows you know the Enhanced Client goes beyond a thin wrapper.

```java
@DynamoDbBean
public class Account {
    private String accountId;
    private BigDecimal balance;
    private Long version;

    @DynamoDbPartitionKey
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }

    @DynamoDbVersionAttribute
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
```

With `@DynamoDbVersionAttribute`, the Enhanced Client automatically:
- Sets `version = 1` on the first `putItem` (leave it `null` when constructing a new item).
- On every subsequent `updateItem`/`putItem`, adds a hidden condition (`version = <current value>`) and increments the stored version — **without you writing any `ConditionExpression` yourself.**
- Throws `ConditionalCheckFailedException` if another writer updated the item first (version mismatch), the same exception type as a manual condition failure.

```java
public void applyInterest(String accountId, BigDecimal rate) {
    int maxAttempts = 3;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            Account account = accountTable.getItem(Key.builder().partitionValue(accountId).build());
            account.setBalance(account.getBalance().multiply(BigDecimal.ONE.add(rate)));
            accountTable.updateItem(account);   // version check is automatic
            return;
        } catch (ConditionalCheckFailedException e) {
            if (attempt == maxAttempts) throw e;
            // simple retry — re-read and reapply; a real implementation
            // would add jittered backoff between attempts
        }
    }
}
```

---

# Part 11 — TTL

```java
@DynamoDbBean
public class Session {
    private String sessionId;
    private String userId;
    private Long expiresAt;   // epoch seconds

    @DynamoDbPartitionKey
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }

    public static Session create(String sessionId, String userId, Duration ttl) {
        Session s = new Session();
        s.setSessionId(sessionId);
        s.setUserId(userId);
        s.setExpiresAt(Instant.now().plus(ttl).getEpochSecond());
        return s;
    }
}
```

The item attribute is just a plain `Long` — TTL is activated at the **table level** (via `UpdateTimeToLiveRequest`, an infra/admin operation, typically done once via IaC, not per-request application code):

```java
dynamoDbClient.updateTimeToLive(UpdateTimeToLiveRequest.builder()
        .tableName("Sessions")
        .timeToLiveSpecification(TimeToLiveSpecification.builder()
                .attributeName("expiresAt")
                .enabled(true)
                .build())
        .build());
```

**Interview point:** TTL deletion is background, best-effort (typically within 48 hours of expiry, not immediate) — if asked "how would you build a session store with hard expiry guarantees," the honest answer is TTL alone isn't sufficient; check `expiresAt` at read time too and treat TTL as a cost-saving cleanup mechanism, not a correctness guarantee.

---

# Part 12 — Testing with DynamoDB Local (Testcontainers)

```java
@Testcontainers
class OrderRepositoryIntegrationTest {

    @Container
    static GenericContainer<?> dynamoDbLocal =
            new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:2.5.2"))
                    .withExposedPorts(8000)
                    .withCommand("-jar DynamoDBLocal.jar -inMemory -sharedDb");

    static DynamoDbTable<Order> orderTable;

    @BeforeAll
    static void setUp() {
        String endpoint = "http://" + dynamoDbLocal.getHost() + ":" + dynamoDbLocal.getMappedPort(8000);

        DynamoDbClient client = DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("dummy", "dummy")))
                .build();

        DynamoDbEnhancedClient enhancedClient =
                DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();

        orderTable = enhancedClient.table("Orders", TableSchema.fromBean(Order.class));
        orderTable.createTable(b -> b.provisionedThroughput(
                pt -> pt.readCapacityUnits(5L).writeCapacityUnits(5L).build()));
    }

    @Test
    void putAndGetRoundTrips() {
        Order order = new Order();
        order.setUserId("u#1");
        order.setOrderId("o#1");
        order.setStatus("PENDING");
        order.setTotal(new BigDecimal("19.99"));

        orderTable.putItem(order);

        Order fetched = orderTable.getItem(Key.builder()
                .partitionValue("u#1").sortValue("o#1").build());

        assertThat(fetched.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void conditionalPutFailsOnDuplicateKey() {
        Order order = new Order();
        order.setUserId("u#2");
        order.setOrderId("o#1");
        orderTable.putItem(order);

        PutItemEnhancedRequest<Order> duplicate = PutItemEnhancedRequest.builder(Order.class)
                .item(order)
                .conditionExpression(Expression.builder()
                        .expression("attribute_not_exists(userId)").build())
                .build();

        assertThrows(ConditionalCheckFailedException.class, () -> orderTable.putItem(duplicate));
    }
}
```

**Why this matters for an interview:** it's the fastest credible answer to "how would you test this without hitting real AWS" — no LocalStack needed for pure DynamoDB testing, `amazon/dynamodb-local` is the official, lightweight, purpose-built image. `-inMemory -sharedDb` keeps each container run fast and isolated per test class.

---

# Part 13 — Consuming DynamoDB Streams

## Lambda Handler (Java)

```java
public class OrderStreamHandler implements RequestHandler<DynamoDBEvent, Void> {

    @Override
    public Void handleRequest(DynamoDBEvent event, Context context) {
        for (DynamoDBEvent.DynamodbStreamRecord record : event.getRecords()) {
            String eventName = record.getEventName();   // INSERT / MODIFY / REMOVE
            Map<String, AttributeValue> newImage = record.getDynamodb().getNewImage();

            if ("INSERT".equals(eventName) && newImage != null) {
                String orderId = newImage.get("orderId").getS();
                // e.g., publish a domain event, update a search index, invalidate a cache
                publishOrderCreated(orderId);
            }
        }
        return null;
    }
}
```

- `record.getDynamodb().getNewImage()` requires the stream's view type to include `NEW_IMAGE` (or `NEW_AND_OLD_IMAGES`) — a common early bug is enabling a stream with `KEYS_ONLY` and then wondering why the handler sees no attribute data.
- **This handler must be idempotent.** Lambda's event-source mapping for DynamoDB Streams guarantees at-least-once delivery — the same record can be delivered more than once (e.g., after a retry following a partial batch failure). Design `publishOrderCreated` to be safe to call twice for the same `orderId` (e.g., check-then-act against an idempotency table, or make the downstream operation itself idempotent).

---

# Part 14 — End-to-End Spring Boot Service Example

```java
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) { this.orderService = orderService; }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@RequestBody CreateOrderRequest request) {
        Order order = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
    }

    @GetMapping("/{userId}")
    public List<OrderResponse> listForUser(@PathVariable String userId) {
        return orderService.findAllForUser(userId).stream().map(OrderResponse::from).toList();
    }
}

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) { this.orderRepository = orderRepository; }

    public Order createOrder(CreateOrderRequest request) {
        Order order = new Order();
        order.setUserId(request.userId());
        order.setOrderId(UUID.randomUUID().toString());
        order.setStatus("PENDING");
        order.setTotal(request.total());
        order.setCreatedAt(Instant.now());

        orderRepository.createIfAbsent(order);   // idempotent-insert pattern, Part 3
        return order;
    }

    public List<Order> findAllForUser(String userId) {
        return orderRepository.findAllForUser(userId);
    }
}
```

This is the layering worth defending in an interview: **Controller → Service (business logic, idempotency/consistency decisions) → Repository (DynamoDB-specific query/key construction)** — the Enhanced Client's types (`Key`, `Expression`, `QueryConditional`) should stay inside the repository layer, not leak into the service or controller, exactly the same separation-of-concerns argument you'd make for a JPA repository.

---

# Part 15 — Common "Write This Code" Interview Exercises

**"Implement an idempotent order creation endpoint."**
→ Part 3's `createIfAbsent` with `attribute_not_exists(pk)`, or a `ClientRequestToken`-based transaction if it must span multiple items.

**"Implement an atomic view counter."**
→ Part 3's `UpdateExpression` with `ADD`, via the low-level client — explicitly note the Enhanced Client's `updateItem` isn't the right tool here since it isn't a targeted atomic increment.

**"A single order ID is getting hammered with reads — how would you fix it in code, not just infra?"**
→ Write sharding:
```java
public String shardedKey(String orderId, int shardCount) {
    int shard = Math.floorMod(orderId.hashCode(), shardCount);
    return orderId + "#shard" + shard;
}
// write: pick a random shard 0..N-1 when incrementing
// read: fan out GetItem/Query across all N shards, sum client-side
```

**"Implement optimistic-concurrency-safe balance transfer between two accounts."**
→ Part 6's `TransactWriteItems` with a `ConditionExpression` on the debit side (`balance >= :amount`), or Part 10's `@DynamoDbVersionAttribute` retry loop if it's a single-item update.

**"Paginate through a large query result set for an API response."**
→ Part 8's cursor-passthrough pattern (`exclusiveStartKey` / `lastEvaluatedKey`), not the auto-following `PageIterable`, since the caller — not this service — owns when to fetch the next page.

**"How would you test this repository without a real AWS account?"**
→ Part 12's Testcontainers + `amazon/dynamodb-local` setup.

**"Design and code a session store with expiry."**
→ Part 11's TTL-attributed bean, plus an explicit read-time expiry check, plus the caveat that TTL deletion itself is best-effort/delayed and shouldn't be relied on alone for correctness.

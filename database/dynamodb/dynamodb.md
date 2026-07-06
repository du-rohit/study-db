# Amazon DynamoDB — In-Depth Study Notes

> Organized to build understanding progressively:
> foundations → data model → architecture → internals → capacity/consistency → application layer → operations → review.

---

# Part 1 — What and Why

## What is DynamoDB?

DynamoDB is AWS's fully managed, serverless, key-value/document NoSQL database. Launched in 2012, it is a from-scratch rewrite inspired by the lessons of the original 2007 Amazon "Dynamo" paper — it shares philosophy (partitioning, replication, tunable consistency) but not code or architecture with that system or with Cassandra (which was itself inspired by the same 2007 paper).

- **Data model:** Key-value / document store (tables → items → attributes)
- **Query interface:** Low-level API (GetItem/PutItem/Query/Scan/...) + PartiQL (SQL-compatible dialect) as an alternative syntax over the same engine
- **CAP classification:** Tunable per-request — eventually consistent reads by default, strongly consistent reads available, ACID transactions available at extra cost
- **Replication:** Leader-based per partition (Multi-Paxos), not leaderless — this is a key architectural difference from Cassandra
- **Operational model:** Fully managed / serverless — no nodes, no OS, no JVM, no version upgrades to plan. This is DynamoDB's defining characteristic vs every other database in this study series.
- **Storage engine:** B-tree based (not LSM) — each replica is a local B-tree with a write-ahead log, per the 2022 USENIX paper "Amazon DynamoDB: A Scalable, Predictably Performant, and Fully Managed NoSQL Database Service"

---

## DynamoDB vs Other Databases

| | DynamoDB | Cassandra | MongoDB | Aurora/PostgreSQL | Redis |
|---|---|---|---|---|---|
| Model | Key-value/document | Wide-column | Document | Relational | In-memory KV |
| Ops model | Fully managed only | Self-hosted or managed | Self-hosted or managed (Atlas) | Managed (Aurora) or self-hosted | Managed (ElastiCache) or self-hosted |
| Consistency | Tunable (eventual/strong/ACID txn) | Tunable (AP) | Tunable | Strong (ACID) | Single-node strong |
| Replication | Leader-based per partition (Multi-Paxos) | Leaderless, peer-to-peer | Leader-based (replica sets) | Leader + read replicas | Primary-replica |
| Joins | No | No | No (lookup/$lookup approximation) | Yes | No |
| Schema | Schemaless (only PK is fixed) | Fixed schema per table (query-driven) | Schemaless | Fixed schema | N/A |
| Query language | API / PartiQL | CQL | MQL (JSON-based) | SQL | Commands |
| Scaling | Automatic (on-demand) or provisioned+autoscaling | Manual (add nodes) | Manual sharding or Atlas auto | Vertical + read replicas | Manual (Cluster mode) |
| Multi-region | Global Tables (multi-active) | NetworkTopologyStrategy (multi-active) | Global Clusters | Aurora Global Database (mostly read replicas) | Active-Active (Enterprise) |
| Best for | AWS-native serverless apps, unpredictable traffic, zero-ops requirement | Multi-cloud high-throughput time-series/messaging | Flexible schema, general-purpose document workloads | Transactional, relational, reporting | Caching, sessions, leaderboards |

---

## Timeline of Major Capabilities

- **2012:** Launch — provisioned throughput only, simple/composite primary keys
- **2013:** Local Secondary Indexes (LSI)
- **2014:** Global Secondary Indexes (GSI)
- **2015:** DynamoDB Streams
- **2016:** VPC endpoints, encryption at rest (optional)
- **2017:** Adaptive capacity (auto-balances throughput to hot partitions), DAX (DynamoDB Accelerator)
- **2018:** On-Demand capacity mode, encryption at rest **on by default** for all tables, Global Tables (v1, stream-based, last-writer-wins)
- **2019:** Transactions (TransactGetItems/TransactWriteItems — ACID), On-Demand Backup, Point-in-Time Recovery (PITR), adaptive capacity made instantaneous
- **2020:** Global Tables v2 (2019.11.21 — simplified setup, no more manual stream management), PartiQL support
- **2022:** Publication of the DynamoDB internals paper (USENIX ATC) detailing request routers, storage nodes, Multi-Paxos, MemDS; standard + infrequent-access table classes
- **2023-2024:** Resource-based policies, incremental export improvements, attribute-based access control refinements

---

# Part 2 — Data Model and API

## Core Concepts

```
Table
  └── Item (analogous to a row — but schemaless beyond the key)
        └── Attribute (analogous to a column — name/value pair, types vary per item)
```

- A **table** has a fixed **primary key schema**; every other attribute is optional and can vary freely per item.
- An **item** is a collection of attributes, uniquely identified by its primary key. Max item size: **400 KB** (includes attribute names, values, all encoding overhead).
- There is no concept of a "null" column costing storage the way Cassandra does — an attribute that isn't present on an item simply isn't stored. This part is *similar* to Cassandra's missing-column behavior, but DynamoDB has no tombstone concept for absent attributes (deletes are whole-item or attribute-level `REMOVE`, not tracked via a versioned delete marker the client can accidentally trigger with `null`).

### Primary Key Types

**Simple primary key** — partition key only:
```
PK: user_id
→ GetItem must supply user_id; each user_id maps to exactly one item
```

**Composite primary key** — partition key + sort key:
```
PK: user_id (partition key)   SK: order_id (sort key)
→ Multiple items can share a partition key, differentiated by sort key
→ Query can fetch all items for a user_id, optionally filtered/ranged by sort key
```

This composite key is the DynamoDB analogue of Cassandra's partition key + clustering column, and it's the mechanism that makes one-to-many and hierarchical modeling possible without joins.

### Partition Key Hashing

The partition key is hashed (internally, an MD5-based hash historically; implementation detail, not exposed) to decide which physical partition holds the item. Items with the same partition key are always co-located and returned in sort-key order from a `Query`. As with Cassandra, **partition key choice determines load distribution** — a partition key with low cardinality or skewed access is DynamoDB's version of a hot partition.

---

## Attribute Data Types

| Category | Types |
|---|---|
| Scalar | `S` (String), `N` (Number), `B` (Binary), `BOOL`, `NULL` |
| Document | `M` (Map — nested key/value), `L` (List — ordered array, mixed types) |
| Set | `SS` (String Set), `NS` (Number Set), `BS` (Binary Set) — no duplicates, **cannot be empty** |

- **Number (`N`)** is stored as a string internally to preserve arbitrary precision (up to 38 digits) without floating-point rounding issues — this is why SDKs often represent DynamoDB numbers as `Decimal` rather than `float`.
- **Sets** cannot contain an empty set — attempting to write one raises a validation error. A common bug source: removing the last element of a set via `DELETE` silently removing the whole attribute versus application code expecting an empty set to remain.
- There is no dedicated integer vs float type — just `N`.

---

## API Basics

```python
import boto3
table = boto3.resource("dynamodb").Table("Orders")

# PutItem — full item overwrite (no partial update)
table.put_item(Item={
    "user_id": "u#123",
    "order_id": "o#456",
    "status": "PENDING",
    "total": Decimal("49.99"),
    "items": [{"sku": "A1", "qty": 2}],
})

# GetItem — point lookup by full primary key
resp = table.get_item(Key={"user_id": "u#123", "order_id": "o#456"})

# Query — partition key required (equality), sort key optional (range condition)
resp = table.query(
    KeyConditionExpression="user_id = :u AND begins_with(order_id, :prefix)",
    ExpressionAttributeValues={":u": "u#123", ":prefix": "o#"},
)

# UpdateItem — partial update, atomic counter via ADD, conditional write
table.update_item(
    Key={"user_id": "u#123", "order_id": "o#456"},
    UpdateExpression="SET #s = :new_status ADD view_count :incr",
    ConditionExpression="#s = :old_status",     # optimistic concurrency
    ExpressionAttributeNames={"#s": "status"},  # "status" is a reserved word
    ExpressionAttributeValues={
        ":new_status": "SHIPPED", ":old_status": "PENDING", ":incr": 1,
    },
)

# DeleteItem
table.delete_item(Key={"user_id": "u#123", "order_id": "o#456"})

# BatchGetItem / BatchWriteItem — up to 100 items / 25 items per call respectively,
# NOT atomic and NOT transactional — a partial batch failure returns UnprocessedItems
# that the client must retry.
resp = boto3.resource("dynamodb").batch_get_item(RequestItems={
    "Orders": {"Keys": [{"user_id": "u#123", "order_id": "o#456"}]}
})

# Scan — reads the ENTIRE table (or GSI), page by page. Expensive; avoid on hot paths.
resp = table.scan(FilterExpression="attribute_exists(overdue)")
```

### Expression Attribute Names and Values

- `ExpressionAttributeNames` (`#alias`) is required whenever an attribute name collides with a DynamoDB reserved word (`status`, `name`, `data`, `count`, and ~570 others) or contains characters not valid in expression syntax.
- `ExpressionAttributeValues` (`:placeholder`) binds literal values into expressions — functionally similar to bind parameters in a prepared SQL statement, and for the same reason: avoiding string interpolation into the expression text.

### Condition and Filter Expressions

- **`ConditionExpression`** (on writes) — the write only succeeds if the condition is true; otherwise raises `ConditionalCheckFailedException`. This is DynamoDB's compare-and-swap primitive, used for optimistic concurrency control and idempotent inserts (`attribute_not_exists(pk)`).
- **`FilterExpression`** (on `Query`/`Scan`) — applied **after** items are read from storage and **after** RCU is charged. A filter does not reduce read cost; it only reduces what's returned to the client. This is a frequent source of surprise cost: a `Query` that reads 1,000 items and filters down to 5 still consumes RCU for all 1,000.

---

## PartiQL for DynamoDB

```sql
-- Equivalent to Query above
SELECT * FROM "Orders" WHERE "user_id" = 'u#123' AND begins_with("order_id", 'o#');

-- INSERT / UPDATE / DELETE also supported
INSERT INTO "Orders" VALUE {'user_id': 'u#123', 'order_id': 'o#789', 'status': 'PENDING'};
UPDATE "Orders" SET "status" = 'SHIPPED' WHERE "user_id" = 'u#123' AND "order_id" = 'o#456';
```

PartiQL is a syntax layer over the same engine and the same partition-key-required rules apply — it does not add ad-hoc query flexibility that the underlying key-value engine doesn't have. A `SELECT *` without a partition key equality condition compiles to a full `Scan`, same cost profile as the low-level API.

---

# Part 3 — Architecture

## Managed Service Topology

Unlike Cassandra (where you manage nodes directly), DynamoDB's internals are AWS-operated, but the 2022 USENIX paper discloses the high-level design:

```
Client SDK
   │
   ▼
Request Router fleet (stateless, multi-tenant)
   │  - authenticates request (IAM)
   │  - looks up partition metadata (which storage nodes own this key)
   │  - applies Global Admission Control (GAC) — token-bucket throttling
   │
   ▼
Storage Nodes (organized into replication groups per partition)
   │  - one LEADER + typically 2 FOLLOWERS, one per AZ
   │  - leader owns all writes; replicates via Multi-Paxos
   │  - each node: local B-tree storage engine + write-ahead log on SSD
   │
   ▼
MemDS (in-memory metadata service) — tracks partition → storage node mapping,
      replicated and cached aggressively by request routers to avoid a
      metadata lookup on every request
```

Key architectural facts from the paper:
- **Replication protocol:** Multi-Paxos (not a simple primary/replica async setup) — a write is only durable once a **majority of the replication group** (2 of 3) has persisted it via the WAL.
- **Storage engine:** a B-tree, not an LSM tree. This is a deliberate divergence from Cassandra/ScyllaDB — DynamoDB's workload profile (point lookups and small range queries dominate) favors B-tree read characteristics, and continuous background compaction (the LSM tradeoff) isn't required because DynamoDB manages partition splits explicitly rather than accumulating write-amplification-heavy SSTables.
- **Partition splits:** as a partition grows in size (>10 GB) or sustained throughput, DynamoDB splits the replication group in two, each half getting its own leader/follower set. This is fully automatic and invisible to the client — no resharding downtime, unlike a manually-sharded system.
- **Global Admission Control (GAC):** introduced to fix an early adaptive-capacity weakness — individual request routers used to make local throttling decisions without global visibility, causing tables to be over- or under-throttled inconsistently across the fleet. GAC centralizes token-bucket state so throttling decisions reflect the table's actual aggregate consumption across all routers.
- **TLA+ formal verification:** AWS has publicly described using TLA+ to verify the correctness of DynamoDB's Multi-Paxos replication and partition membership change protocols — relevant context if asked "how does AWS get confidence in a leader-based replication protocol without an army of ops engineers running repairs like Cassandra."

---

## Partitions and Throughput Limits

Each physical partition (one replication group) has a **hard ceiling**, independent of your table's overall provisioned capacity:

| Limit | Value |
|---|---|
| Max throughput per partition | 3,000 RCU **or** 1,000 WCU (combined, proportionally) |
| Max storage per partition | 10 GB |

DynamoDB automatically adds partitions as your table's storage or throughput requirements grow — but the *per-partition* ceiling is why a single very-hot key can throttle even when your table-level provisioned capacity looks generous. Table-level capacity is divided (roughly) evenly across partitions; a partition key receiving disproportionate traffic hits its own 3,000 RCU/1,000 WCU ceiling regardless of how much unused capacity sits on other partitions.

### Adaptive Capacity

Since 2017 (made instantaneous in 2019), DynamoDB automatically shifts throughput allocation toward partitions receiving disproportionate traffic, up to the partition maximum, without manual intervention or overprovisioning the whole table. This softens — but does not eliminate — the hot-partition problem: a key so hot it alone exceeds 3,000 RCU or 1,000 WCU will still throttle, because that ceiling is physical (one partition = one replication group = finite hardware).

---

# Part 4 — Consistency Models and Transactions

## Read Consistency

| Read type | Behavior | Cost |
|---|---|---|
| **Eventually consistent** (default) | May return stale data if a very recent write hasn't propagated to the replica serving the read; typically resolves within a second | 1 RCU per 4 KB |
| **Strongly consistent** (`ConsistentRead=True`) | Always reflects the most recent successful write; served from (or confirmed by) the leader replica | 2 RCU per 4 KB (2× cost) |
| **Transactional read** (`TransactGetItems`) | Serializable isolation guarantee across up to 100 items | 2 RCU per item (regardless of item size tier, minimum) |

**Important constraint:** Global Secondary Indexes are **always eventually consistent** — you cannot request a strongly consistent read against a GSI, because GSI data is asynchronously propagated from the base table. If your access pattern needs strong consistency, it must go through the base table or an LSI (which *can* be strongly consistent, since it shares the base table's partition).

## Write Consistency

Every write (`PutItem`, `UpdateItem`, `DeleteItem`) is synchronously replicated to a majority of the replication group (2 of 3) before being acknowledged — there is no tunable write consistency level the way Cassandra exposes `ONE`/`QUORUM`/`ALL`. A successful write response means it is durable and will be visible on a subsequent strongly consistent read.

## Transactions (ACID)

```python
dynamodb = boto3.client("dynamodb")
dynamodb.transact_write_items(TransactItems=[
    {"Update": {"TableName": "Accounts", "Key": {"id": {"S": "A"}},
                "UpdateExpression": "ADD balance :neg",
                "ConditionExpression": "balance >= :amount",
                "ExpressionAttributeValues": {":neg": {"N": "-100"}, ":amount": {"N": "100"}}}},
    {"Update": {"TableName": "Accounts", "Key": {"id": {"S": "B"}},
                "UpdateExpression": "ADD balance :amt",
                "ExpressionAttributeValues": {":amt": {"N": "100"}}}},
])
```

- `TransactWriteItems` / `TransactGetItems` provide full ACID semantics (atomicity across up to 100 items / 4 MB as of current limits — originally 25 items) spanning **multiple tables**.
- Implemented as a **two-phase commit** internally: a *prepare* phase validates all condition expressions and locks items, then a *commit* phase applies them all or rolls back. This is why transactional operations cost **2× the RCU/WCU** of the equivalent non-transactional operation — you're paying for both phases.
- Use `ClientRequestToken` to make a `TransactWriteItems` call idempotent — safe to retry on a client-side timeout without risking a double-apply, unlike Cassandra's counter columns which have no such idempotency token.
- Transactions are **not** a substitute for conditional writes on a single item — a single-item `ConditionExpression` on `PutItem`/`UpdateItem` is cheaper and sufficient; reach for transactions only when atomicity must span multiple items/tables.

---

# Part 5 — Secondary Indexes

## Local Secondary Index (LSI)

- Same partition key as the base table, **different sort key**.
- Must be created **at table creation time** — cannot be added later, cannot be removed without recreating the table.
- Shares the base table's provisioned RCU/WCU (no separate capacity to manage).
- **Can** be read with strong consistency, because it's colocated with the base table's partition.
- Constraint: the combined size of all items sharing a partition key, across the base table **and** all its LSIs, is capped at 10 GB — a real constraint for unbounded one-to-many relationships (e.g., "all orders for a customer" if a customer can have unbounded orders).
- Max 5 LSIs per table.

## Global Secondary Index (GSI)

- **Independent** partition key and sort key — can be entirely different attributes than the base table's key.
- Can be added or removed **after** table creation.
- Provisioned mode: GSIs have **their own RCU/WCU** allocation, separate from the base table — under-provisioning a GSI's WCU throttles writes to the *base table* too, because every base-table write that touches an indexed attribute must also propagate to the GSI. This is one of the most common DynamoDB production incidents: base table looks fine, but a GSI is under-provisioned and throttling silently backs up base-table writes.
- Always **eventually consistent** — propagation from base table to GSI is asynchronous (typically sub-second, but not guaranteed).
- Max 20 GSIs per table (soft limit, increasable via support request).
- **Sparse index pattern:** if an item doesn't have the GSI's key attribute(s) set, it simply doesn't appear in the index. This is used deliberately — e.g., a GSI keyed on an `overdue_date` attribute that's only set on overdue orders gives you an efficient "query all overdue orders" pattern without scanning the whole table, because paid/closed orders never appear in that index at all.

```
Example: "GSI overloading" — one GSI serving multiple entity access patterns
by using generic attribute names (GSI1PK / GSI1SK) populated differently
per item type. Covered in the single-table design section (Part 7).
```

---

# Part 6 — Capacity Modes

## Provisioned Capacity

- You specify **RCU** (read capacity units) and **WCU** (write capacity units) per table (and per GSI).
- **1 RCU** = one strongly consistent read of up to 4 KB/sec, or two eventually consistent reads of up to 4 KB/sec.
- **1 WCU** = one write of up to 1 KB/sec.
- Larger items consume proportionally more units — a 10 KB item read strongly consistent = ceil(10/4) = 3 RCU.
- **Auto Scaling** can be attached — a target-tracking policy adjusts provisioned RCU/WCU within min/max bounds based on consumed-capacity utilization, similar in spirit to EC2 Auto Scaling but reactive (there is a ramp-up lag of a few minutes, so it does not help with instantaneous spikes).
- **Burst capacity:** unused throughput from the previous ~300 seconds (5 minutes) is retained and can absorb short bursts above the provisioned rate — but this reserve is consumed quickly under sustained load and is not something to design around for predictable traffic.

## On-Demand Capacity

- No capacity planning — pay per request (per RRU/WRU — read/write request unit), scales automatically.
- DynamoDB immediately accommodates up to **2× the previous traffic peak**; sustained growth beyond that within a 30-minute window can throttle until the table scales further — "instant" is not "infinite."
- Meaningfully more expensive per request than well-utilized provisioned capacity (roughly 5–7× list price per unit, varies by region) — the tradeoff is zero planning effort and natural fit for spiky/unpredictable workloads (new products, marketing-driven traffic spikes).
- Can switch between on-demand and provisioned at most once per 24 hours.

## Decision Framework

| Workload shape | Recommendation |
|---|---|
| Steady, predictable traffic | Provisioned + Auto Scaling — meaningfully cheaper at sustained volume |
| Spiky / unknown / early-stage | On-Demand — avoids under-provisioning throttles and over-provisioning waste |
| Extreme, sudden 10×+ spikes | On-Demand still has a ramp limit (2× prior peak) — pre-warming or a provisioned floor may be needed for known events (e.g. a flash sale) |
| Cost-sensitive, high and steady volume | Provisioned, reserved capacity (upfront commitment discount) |

---

# Part 7 — Data Modeling and Access Patterns

## Single-Table Design

This is DynamoDB's most distinctive modeling philosophy, and the single biggest mental shift for engineers coming from either SQL or Cassandra.

In Cassandra, each query pattern typically gets its **own table**. In DynamoDB, at scale, the idiomatic pattern is often the opposite: **one table holding multiple entity types**, distinguished by generic, overloaded key attribute names.

```
Table: AppData
PK              SK                  Attributes
USER#123        METADATA#           name, email, created_at
USER#123        ORDER#2024-01-15    total, status
USER#123        ORDER#2024-02-03    total, status
ORDER#o456      METADATA#           user_id, total, items[]
ORDER#o456      SHIPMENT#1          carrier, tracking_no
```

- `PK`/`SK` are generic attribute names; different item "types" populate them with different prefixed values (`USER#`, `ORDER#`, `SHIPMENT#`).
- A single `Query` on `PK = "USER#123"` retrieves the user's metadata **and** all their orders in one round trip (an "item collection") — this replaces a JOIN.
- **Why this exists:** DynamoDB has no cross-table joins and (historically) no cheap way to batch heterogeneous lookups; single-table design front-loads the "pre-join" work into the write path so reads become single-partition, single-request lookups — trading write-time complexity and denormalization for read-time simplicity and cost predictability, which matters because DynamoDB bills per request/RCU rather than per query complexity.
- **The tradeoff:** single-table design is significantly harder to reason about, harder to explore ad hoc (a raw table scan is nearly unreadable — everything looks like opaque prefixed strings), and commits you hard to the access patterns you designed for upfront. Adding a genuinely new access pattern later often means a new GSI (or a backfill), same as adding a new table would in Cassandra.
- **When multi-table is fine:** smaller applications, low request volume where cost/latency of an extra request is irrelevant, or teams that value query flexibility and debuggability over the marginal efficiency gain. Single-table design is a scaling optimization, not a requirement — plenty of production DynamoDB usage is plain one-table-per-entity and is perfectly fine below significant scale.

## Adjacency List Pattern (Many-to-Many / Hierarchical Data)

```
PK          SK               Use
ORG#1       ORG#1            org metadata
ORG#1       USER#42          user 42 is a member of org 1
ORG#1       USER#77          user 77 is a member of org 1
USER#42     ORG#1            (via GSI: inverse lookup — orgs for user 42)
USER#42     ORG#9            user 42 is also a member of org 9
```

Query `PK = "ORG#1"` → all members of org 1. Query a GSI with `GSI-PK = "USER#42"` → all orgs user 42 belongs to. This is the standard way to model graph-like/many-to-many relationships without a join table.

## GSI Overloading

Rather than creating a dedicated GSI per access pattern, populate a small number of generic GSI key attributes (`GSI1PK`, `GSI1SK`, `GSI2PK`, ...) differently per item type, so one GSI serves several unrelated query needs simultaneously. This keeps you under the GSI count limit and reduces per-write propagation cost, at the expense of the index being unreadable without the application's key-construction logic in hand.

## Write Sharding (Hot Partition Mitigation)

```
Bad:  PK = "GLOBAL_COUNTER"                     → every increment hits one partition
Good: PK = "GLOBAL_COUNTER#" + str(random.randint(0, 9))  → spread across 10 partitions
      Read path: Query/GetItem all 10 shards, sum client-side
```

Same fundamental fix as Cassandra's shard-bucketing for hot partitions — introduce entropy into the partition key for write-heavy, low-cardinality keys, and fan out on read.

## Query vs Scan

- **`Query`** requires a partition key equality condition; cost is proportional to items matched (efficient).
- **`Scan`** reads every item in the table/index; cost is proportional to the **entire table size**, only used for: infrequent batch/analytics jobs, one-off data fixes, or exporting to another system (prefer `S3 Export` for that). Never on a request-serving hot path.
- **`ParallelScan`** (`Segment`/`TotalSegments` parameters) divides a Scan across concurrent workers to reduce wall-clock time — does not reduce total RCU cost, only latency for background jobs.
- Both support pagination via `LastEvaluatedKey` — DynamoDB caps a single `Query`/`Scan` response at 1 MB of data regardless of `Limit`, so consuming all matching items requires following the pagination token in a loop.

## Time-To-Live (TTL)

```python
table.update_item(
    Key={"user_id": "u#123", "order_id": "o#456"},
    UpdateExpression="SET expires_at = :epoch",
    ExpressionAttributeValues={":epoch": int(time.time()) + 86400 * 30},
)
```

- TTL attribute holds a Unix epoch timestamp; a background process deletes expired items, typically within 48 hours of expiry (not immediate — do not rely on TTL for time-sensitive deletion SLAs).
- TTL deletions **do not consume WCU** and **do appear in DynamoDB Streams**, tagged so consumers can distinguish a TTL-driven delete from an application-driven delete.

---

# Part 8 — DynamoDB Streams and Event-Driven Patterns

- A **Stream** is an ordered, 24-hour-retained change log of item-level modifications (insert/update/delete), organized into shards, conceptually similar to a Kafka topic's partitions but managed entirely by DynamoDB.
- View types: `KEYS_ONLY`, `NEW_IMAGE`, `OLD_IMAGE`, `NEW_AND_OLD_IMAGES` — controls how much of the changed item is included per record.
- Common consumers: **Lambda triggers** (most common — DynamoDB invokes a Lambda per batch of stream records automatically), or the Kinesis Client Library (KCL) adapter for custom consumers needing checkpointing/parallelism control.
- Use cases: cache invalidation, search index sync (e.g., to OpenSearch), audit logging, cross-service event propagation (a poor-man's outbox pattern — though note it's *not* transactional with the write itself in the two-phase-commit sense; it's derived from the WAL after the write commits, which is normally sufficient for eventual-consistency integration patterns).
- **Global Tables are built on Streams internally** — replication to other regions is implemented as a managed stream consumer that applies changes cross-region.

---

# Part 9 — Global Tables (Multi-Region Replication)

## How It Works

- Global Tables replicate a table across 2+ AWS regions in a **multi-active** (not primary/standby) configuration — any region can accept both reads and writes.
- Replication is asynchronous, typically **sub-second** propagation, powered by DynamoDB Streams under the hood.
- **Conflict resolution: last-writer-wins (LWW)** based on an internal timestamp — if the same item is written concurrently in two regions, one write silently overwrites the other after replication converges. There is no application-level conflict resolution hook (no CRDTs, no vector clocks exposed) — this is functionally identical to Cassandra's LWW model for active-active, just implemented via a different replication substrate.

## Version History

- **Global Tables v1 (2017):** required manually enabling Streams and manually managing the cross-region replication relationship; more operational overhead, some edge cases around initial table sync.
- **Global Tables v2 / 2019.11.21 (2020):** simplified setup (a few clicks/API calls to add a region to an existing table), automatic backfill of existing data to new replica regions, improved conflict resolution consistency across regions.

## Design Implications

- Because it's LWW, **do not** design a Global Table access pattern where two regions might race to update the *same item* under normal operation — route writes for a given item consistently to one "home" region where possible, and reserve true multi-region concurrent writes to the same item for cases where losing one side (e.g., "last profile edit wins") is actually acceptable business semantics.
- Global Tables replication cost is billed **per replicated write** in each additional region — a write to a 3-region Global Table is billed as 1 local write + 2 replicated writes, not free bandwidth.
- RPO in a region-loss event: effectively the replication lag at time of failure (typically sub-second to low-seconds). RTO: near-zero for reads/writes to a *surviving* region — clients simply route elsewhere; DNS/endpoint failover is the client's responsibility (Route 53 health checks, or an SDK-level multi-region client), not automatic the way a single-region table's availability is.

---

# Part 10 — DynamoDB Accelerator (DAX)

- DAX is a **separate, managed in-memory caching layer** that sits in front of a DynamoDB table, API-compatible with the DynamoDB SDK (mostly a drop-in client swap).
- Provides microsecond read latency for cache hits, vs single-digit-millisecond for DynamoDB itself.
- **Write-through:** writes go through DAX to DynamoDB and update the cache atomically — unlike a naive cache-aside pattern, DAX avoids the classic "stale cache after write" race as long as all writes go through the DAX client.
- Two internal caches: an **item cache** (GetItem/BatchGetItem results, keyed by primary key) and a **query cache** (Query/Scan result sets, keyed by the request parameters — invalidated more aggressively since any write to the table can affect a query's result set).
- **Only serves eventually consistent reads** — a strongly consistent read request is passed through to DynamoDB directly, bypassing the cache.
- Good fit: read-heavy workloads with a hot-key skew (leaderboard, product catalog front page) where microsecond latency and offloading read pressure from the base table's provisioned capacity matter. Not a fit for write-heavy or strongly-consistent-read-dependent workloads.

---

# Part 11 — Security

## IAM and Fine-Grained Access Control

```json
{
  "Effect": "Allow",
  "Action": ["dynamodb:GetItem", "dynamodb:Query"],
  "Resource": "arn:aws:dynamodb:us-east-1:111111111111:table/AppData",
  "Condition": {
    "ForAllValues:StringEquals": {
      "dynamodb:LeadingKeys": ["${cognito-identity.amazonaws.com:sub}"]
    }
  }
}
```

- IAM policies can restrict access **down to the item level** using the `dynamodb:LeadingKeys` condition key — matching the partition key value to a token from the caller's identity (e.g., a Cognito identity ID). This is the standard pattern for multi-tenant apps where end-user devices call DynamoDB directly (mobile/web clients with temporary AWS credentials) and must be structurally prevented from reading another tenant's partition, independent of application-layer bugs.
- `dynamodb:Attributes` condition key can further restrict which **attributes** a caller may read/write within an item.

## Encryption

- **At rest:** encrypted by default since 2018 for all tables, using AWS-owned keys, AWS-managed KMS keys, or customer-managed KMS keys (CMK) for audit/rotation control.
- **In transit:** TLS enforced for all API calls.
- **VPC endpoints:** a Gateway endpoint for DynamoDB allows private access from within a VPC without traversing the public internet/NAT gateway — standard for workloads with no-internet-egress compliance requirements.

---

# Part 12 — Backup, Restore, and Recovery

## Point-in-Time Recovery (PITR)

- Continuous backups; can restore a table to **any second within the trailing 35 days**.
- Restore always creates a **new table** — there is no in-place restore, so a restore-and-cutover runbook (rename/repoint application config) is part of any real incident-response plan.
- No performance impact on the live table — implemented via continuous log capture, not a blocking snapshot.

## On-Demand Backups

- Full, manually (or EventBridge-scheduled) triggered backups, retained until explicitly deleted (unlike PITR's rolling 35-day window).
- Also non-blocking and does not consume the table's provisioned RCU/WCU.
- Restore, like PITR, creates a new table.

## Export to S3

- Full or incremental (PITR-window-based) export directly to S3 in DynamoDB JSON or Ion format, without consuming table RCU — the standard mechanism for feeding a data warehouse or running analytics without hitting the live table with a `Scan`.

---

# Part 13 — Monitoring and Limits

## Key CloudWatch Metrics

| Metric | What it signals |
|---|---|
| `ConsumedReadCapacityUnits` / `ConsumedWriteCapacityUnits` | Actual usage vs provisioned — trending toward the ceiling predicts throttling |
| `ThrottledRequests` / `ReadThrottleEvents` / `WriteThrottleEvents` | Direct throttling signal — non-zero means clients are seeing `ProvisionedThroughputExceededException` |
| `SystemErrors` | DynamoDB-side errors (rare; usually transient, retry with backoff) |
| `UserErrors` | Client-side mistakes — malformed requests, validation failures, auth failures |
| `ReplicationLatency` (Global Tables) | Cross-region propagation lag — alert if trending upward, indicates replication falling behind |
| `AccountMaxTableLevelReads/WritesThrottleEvents` | Distinguishes per-table throttling from account-level limits being hit |

## Contributor Insights

CloudWatch Contributor Insights for DynamoDB surfaces the most-accessed and most-throttled partition keys without needing to instrument the application — the direct tool for diagnosing "which key is hot" during an incident, analogous to identifying a hot partition in Cassandra via `nodetool` but delivered as a managed report instead of an ops command.

## Key Hard Limits

| Limit | Value |
|---|---|
| Max item size | 400 KB |
| Max partition throughput | 3,000 RCU or 1,000 WCU |
| Max partition storage | 10 GB |
| GSIs per table | 20 (soft, increasable) |
| LSIs per table | 5 (fixed at table creation, not increasable) |
| BatchGetItem | 100 items per call |
| BatchWriteItem | 25 items per call |
| TransactWriteItems / TransactGetItems | 100 items, 4 MB total, per call |
| Table name length | 3–255 characters |

---

# Part 14 — Cost Model

| Mode | Pricing basis | Notes |
|---|---|---|
| Provisioned | Per RCU-hour / WCU-hour provisioned (whether consumed or not) | Cheapest at steady, well-utilized volume; auto-scaling helps but has ramp lag |
| On-Demand | Per read/write request unit actually consumed | No waste from over-provisioning, but ~5–7× per-unit list price vs provisioned |
| Storage | Per GB-month | Standard vs Standard-Infrequent Access table class trades lower storage cost for higher throughput cost — pick IA for large, rarely-accessed tables (e.g., audit logs) |
| GSIs | Separate RCU/WCU billing in provisioned mode; folded into request cost in on-demand | An unused or over-provisioned GSI is pure waste — audit GSI necessity periodically |
| Backups | PITR and on-demand backups billed per GB-month, roughly comparable to base storage cost | 35-day PITR window on a large, high-churn table can materially add to bill — factor into cost reviews |
| Global Tables | Each additional region is billed for its own storage + its own replicated write units | A 3-region Global Table roughly triples the write-side bill, independent of read cost per region |
| Streams | Free to enable; consumers (Lambda invocations, KCL compute) billed separately | |

Exact prices vary by region and change over time — treat the above as relative cost drivers to reason about, not fixed numbers to quote.

---

# Part 15 — Local Development

- **DynamoDB Local** — a downloadable JAR (or Docker image) that emulates the DynamoDB API for local dev/test without any AWS account or billing. Does not emulate capacity throttling, Global Tables, or Streams-triggered Lambda invocation realistically — integration tests against a real (or `sam local`-orchestrated) environment are still needed before trusting production-like behavior.
- **NoSQL Workbench** — AWS's GUI tool for visually designing single-table schemas and generating sample data/queries; commonly used when onboarding a team to single-table design, since raw item dumps are otherwise hard to reason about.

---

# Part 16 — Review: Common Study Questions

**Q: Why can't DynamoDB do joins, and how do teams work around it?**
Data is distributed by partition key with no cross-partition query engine exposed to the client — a join would require scatter-gather across arbitrary partitions, which DynamoDB deliberately doesn't expose (unlike Cassandra, which technically allows `ALLOW FILTERING` scatter-gather, just at a steep and clearly-flagged cost). The workaround is denormalization: single-table design, adjacency lists, and GSI overloading to pre-compute the access patterns you'll need at write time.

**Q: What's the actual difference between an LSI and a GSI?**
LSI shares the base table's partition key and its provisioned capacity, must be defined at table creation, can be read with strong consistency, and is capped by the 10 GB per-partition-key limit shared with the base table. GSI has an independent key schema and independent capacity, can be added/removed anytime, but is always eventually consistent.

**Q: Why did my base table writes start throttling when the base table's provisioned WCU looks fine?**
An under-provisioned GSI. Every base-table write that touches an indexed attribute must also propagate to each GSI; if a GSI's own WCU is exhausted, the base table write itself is throttled, even though the base table's own capacity metrics look healthy. Always check GSI-level `ThrottledRequests`, not just the base table's.

**Q: How does DynamoDB replicate writes internally, and how does that compare to Cassandra?**
Each partition is a replication group with one leader and typically two followers (one per AZ), using Multi-Paxos — a write commits once a majority (2 of 3) persist it to their local write-ahead log. This is leader-based, unlike Cassandra's leaderless model where any replica can coordinate and consistency is tuned per-request across all replicas independently, not funneled through one elected leader per partition.

**Q: What is adaptive capacity and what does it not solve?**
Adaptive capacity automatically shifts a table's throughput allocation toward disproportionately busy partitions, without requiring the whole table to be over-provisioned to cover one hot key. It does not raise the hard per-partition ceiling (3,000 RCU / 1,000 WCU) — a single key hot enough to exceed that ceiling alone will still throttle; the fix at that point is data modeling (write sharding), not capacity configuration.

**Q: Why does a `Query` with a restrictive `FilterExpression` still cost a lot?**
`FilterExpression` is applied after data is read from storage and after RCU is already charged for every item matching the key condition — it only reduces what's returned over the wire, not what's paid for. To reduce cost, narrow the key condition (sort key range) or use a GSI/sparse index shaped around the actual filter criteria.

**Q: What does "last-writer-wins" mean for Global Tables, concretely?**
If the same item is updated in two regions within the cross-region replication window (sub-second to low-seconds), both writes succeed locally, but after replication converges, only one survives — determined by an internal timestamp, not application logic. There's no merge or conflict callback exposed. Design around this by routing writes for a given item to a consistent "home" region rather than expecting concurrent multi-region writes to the same item to merge sensibly.

**Q: When would you reach for TransactWriteItems instead of a single ConditionExpression?**
Only when atomicity must span multiple items or multiple tables — e.g., debit one account and credit another, or update an order item and a denormalized summary item together. A single item's optimistic-concurrency check (`ConditionExpression` on `PutItem`/`UpdateItem`) is cheaper and sufficient when only one item's state must be consistent.

**Q: What's the practical difference between On-Demand and Provisioned capacity for a spiky workload?**
On-Demand auto-scales but only up to 2× the previous traffic peak "instantly" — a sudden 10× spike (e.g., an unannounced marketing blast) can still throttle until the table ramps further. Provisioned + Auto Scaling reacts even more slowly (minutes of ramp-up lag on a target-tracking policy). For a *known* extreme event, pre-warming provisioned capacity ahead of time is still sometimes necessary even on On-Demand tables.

**Q: Why is DAX "write-through" significant compared to a typical cache-aside Redis setup in front of a database?**
In a naive cache-aside pattern, a write to the DB and an invalidate/update of the cache are two separate operations that can race, leaving a stale cache entry visible until the next natural expiry. DAX's write-through path updates the cache as part of the same client call that writes to DynamoDB, closing that race for any traffic that goes through the DAX client consistently.

**Q: What happens to a DynamoDB Streams-based Lambda consumer that falls behind?**
Stream records are retained for 24 hours; a consumer that falls behind that far permanently loses the unprocessed records (unlike Cassandra CDC, which instead applies back-pressure by pausing writes when its local log fills). This is a meaningfully different failure mode to design monitoring around: DynamoDB Streams can silently drop data on a slow consumer, whereas Cassandra CDC protects data at the cost of halting writes.

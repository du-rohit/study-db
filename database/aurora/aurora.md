# Amazon Aurora — In-Depth Study Notes

> Organized to build understanding progressively:
> foundations → architecture → replication/consistency/HA → data model & SQL → capacity modes → multi-region → operations → review.

---

# Part 1 — What and Why

## What is Aurora?

Aurora is AWS's fully managed, relational database engine that is **wire-compatible with MySQL and PostgreSQL** but replaces their storage engines with a purpose-built, distributed, log-structured storage layer. Launched in 2014 (MySQL-compatible edition first; PostgreSQL-compatible edition in 2017), Aurora is not a fork of MySQL/PostgreSQL's storage internals — it keeps the query processing, SQL layer, and client protocol of each engine, but swaps out everything below the buffer pool for AWS's own distributed storage service.

- **Data model:** Relational (tables, rows, foreign keys, joins, SQL) — same model as any RDBMS
- **Query interface:** Standard MySQL or PostgreSQL wire protocol and SQL dialect — existing drivers, ORMs (Hibernate, JPA), and tools work unmodified
- **CAP classification:** Strong consistency for reads against the writer; asynchronous, typically sub-100ms-lag read replicas — this is a single-writer architecture, not a tunable-consistency distributed database like DynamoDB or Cassandra
- **Replication:** Storage-layer replication (6 copies across 3 AZs, quorum writes/reads), decoupled from compute-layer read replicas, which stream a redo log rather than replicate at the SQL statement level
- **Operational model:** Fully managed (patching, backups, storage scaling) but **not serverless-only** — you still choose and pay for compute instances (or Serverless v2 capacity units), unlike DynamoDB's pure request-based model
- **Storage engine:** A distributed, log-structured, append-only storage service, purpose-built by AWS — this is Aurora's core architectural innovation, detailed in the 2017 SIGMOD paper "Amazon Aurora: Design Considerations for High Throughput Cloud-Native Relational Databases"

---

## Aurora vs Other Databases

| | Aurora | RDS (standard MySQL/Postgres) | DynamoDB | Cassandra | Self-managed Postgres/MySQL |
|---|---|---|---|---|---|
| Model | Relational | Relational | Key-value/document | Wide-column | Relational |
| Storage architecture | Distributed, log-structured, storage/compute separated | Local EBS volume, coupled to instance | Distributed B-tree, AWS-internal | Distributed LSM, peer-to-peer | Local disk, coupled to instance |
| Ops model | Managed (AWS) | Managed (AWS) | Fully managed only | Self-hosted or managed | Fully self-hosted |
| Consistency | Strong on writer; async read replicas | Strong on writer; async read replicas | Tunable (eventual/strong/ACID txn) | Tunable (AP) | Strong on writer; async read replicas |
| Replication | 6-way quorum at storage layer + up to 15 async compute read replicas | Single EBS volume + optional async read replicas | Leader-based per partition (Multi-Paxos) | Leaderless, peer-to-peer | Manual (streaming replication) |
| Joins | Yes (full SQL) | Yes (full SQL) | No | No | Yes (full SQL) |
| Schema | Fixed (relational) | Fixed (relational) | Schemaless (only PK fixed) | Fixed per table (query-driven) | Fixed (relational) |
| Failover time | ~30s (storage already shared; no data copy needed) | 60–120s (EBS volume reattach) | N/A (no failover concept exposed to client) | N/A (no single point of failure) | Manual or tooling-dependent (minutes) |
| Scaling | Storage: automatic to 128 TB. Compute: vertical (instance resize) or Serverless v2 (seconds) or read replicas (horizontal reads) | Storage: manual provisioning. Compute: vertical only | Automatic (on-demand) or provisioned+autoscaling | Manual (add nodes) | Fully manual |
| Multi-region | Aurora Global Database (1 writer region + up to 5 read regions, <1s typical lag) | Cross-region read replicas (higher lag, manual setup) | Global Tables (multi-active) | NetworkTopologyStrategy (multi-active) | Manual (logical replication, third-party tools) |
| Best for | Relational workloads needing AWS-managed HA/scale without giving up SQL/joins/transactions | Relational workloads not needing Aurora's scale/HA ceiling, or needing an engine Aurora doesn't support (SQL Server, Oracle) | AWS-native serverless apps, key-based access, unpredictable traffic | Multi-cloud high-throughput time-series/messaging | Full control, no cloud dependency, or unsupported engine/extension |

**The core distinction to be able to articulate:** Aurora is not "RDS but faster" for an arbitrary reason — it wins specifically because it decouples storage from compute and pushes replication down into a purpose-built distributed storage layer, which is what makes 6-way durability, sub-30-second failover, and up to 15 low-lag read replicas possible without the write-amplification and network overhead a traditional MySQL/PostgreSQL replication topology would incur doing the equivalent over standard block storage.

---

## Timeline of Major Capabilities

- **2014:** Aurora (MySQL-compatible) announced at re:Invent, GA 2015
- **2017:** Aurora PostgreSQL-compatible edition GA; SIGMOD paper published detailing the log-structured storage architecture; Aurora Serverless v1 announced (GA 2018)
- **2018:** Backtrack (MySQL edition) — rewind a cluster in place without a restore; Parallel Query (MySQL edition) — pushes scan/filter/aggregation work down into the storage layer
- **2019:** Aurora Global Database GA; Aurora Multi-Master (MySQL edition) GA — later effectively deprecated in favor of simpler single-writer + read replica patterns
- **2020:** Babelfish for Aurora PostgreSQL announced (SQL Server wire-protocol compatibility layer) — GA 2021
- **2021:** RDS Proxy support for Aurora; Data API GA for Aurora Serverless (HTTP-based query execution, no persistent connection needed — relevant for Lambda)
- **2022:** Aurora Serverless v2 GA — fine-grained, fast (seconds) autoscaling per-ACU, usable alongside provisioned instances in the same cluster (unlike v1, which was an isolated deployment mode)
- **2023:** Aurora I/O-Optimized configuration (predictable pricing for I/O-heavy workloads, trading a higher instance price for zero per-I/O charges); Trusted Language Extensions (PostgreSQL edition)
- **2023–2024:** Aurora Optimized Reads (local NVMe-based caching tier ahead of the distributed storage layer, reduces read latency for working sets larger than the buffer pool); zero-ETL integrations to Redshift

---

# Part 2 — Architecture

## Storage/Compute Separation

This is Aurora's defining architectural idea, and the single most important thing to be able to draw on a whiteboard.

```
Compute layer (what you provision/pay for as an "instance")
   │
   │  writer instance ships REDO LOG RECORDS ONLY to storage
   │  (not full data pages — this is the key efficiency gain)
   ▼
Distributed Storage Service (shared, AWS-managed, NOT something you provision directly)
   │
   ├── 6 copies of every 10 GB "protection group" (segment),
   │   spread 2 copies per AZ across 3 AZs
   │
   ├── Storage nodes apply redo log records to build data pages
   │   asynchronously and in the background — NOT on the write's critical path
   │
   └── Quorum model:
       - Write quorum: 4 of 6 copies must acknowledge (tolerates losing
         a full AZ + one additional node without blocking writes)
       - Read quorum: 3 of 6 copies (only needed if reading directly from
         storage rather than the writer's buffer pool, e.g. during crash recovery)
```

Contrast with traditional MySQL/PostgreSQL replication: a standard engine's replica applies a stream of **full write-ahead log records describing data page changes**, and the primary itself must synchronously flush data pages to its own local (or EBS) storage before considering a write durable. Aurora's writer **never flushes data pages as part of the write path** — it ships the redo log record to the distributed storage service and waits only for write-quorum acknowledgment; storage nodes reconstruct pages from the log independently and asynchronously. This is why Aurora's SIGMOD paper describes the core optimization as "the log is the database."

## Why This Reduces Network I/O

In a traditional replicated setup, a single write can require replicating the WAL, then replicating data page flushes, checkpoint traffic, and double-write-buffer traffic (InnoDB) — a large multiplied write amplification over the network. Aurora ships **only the log record** (not data pages) over the network to storage, cutting network I/O for writes by roughly an order of magnitude in AWS's published benchmarks versus stock MySQL replication over equivalent infrastructure. This is the mechanical reason Aurora can sustain higher write throughput than a same-sized traditional RDS instance on comparable hardware.

## Crash Recovery

Because storage nodes are continuously (asynchronously) applying the redo log to materialize data pages, Aurora does not need the traditional InnoDB/Postgres crash-recovery step of replaying the entire WAL from the last checkpoint at restart. Recovery is near-instant (seconds) because the durable state is already mostly materialized in the distributed storage layer — the paper frames this as recovery being "spread out" continuously rather than concentrated into a startup-time replay.

## Segments and Self-Healing

- Storage is partitioned into 10 GB **protection groups** (6 copies each, 2 per AZ).
- Storage nodes are continuously monitored; a failed or lagging copy is detected and **repaired in the background from the other 5 copies**, without any application-visible interruption and without a DBA-triggered repair (unlike Cassandra's manual/scheduled `nodetool repair`).
- Because repair operates per-10-GB-segment rather than across the whole volume, a failure only requires healing the affected segment(s), not a full-volume resync — this bounds repair time regardless of overall database size.

---

# Part 3 — Replication, Consistency, and High Availability

## Two Separate Replication Concepts

Aurora has **two distinct replication mechanisms** that are frequently conflated — a common interview trap:

1. **Storage-layer replication** (always on, not configurable): the 6-way quorum described in Part 2, invisible to the application, exists purely for durability, not for scaling reads.
2. **Compute-layer read replicas** (up to 15, explicitly provisioned): separate compute instances that attach to the **same shared distributed storage volume** as the writer and stream the writer's redo log to keep their in-memory buffer pool caches current.

The critical distinction from traditional MySQL/Postgres read replicas: **Aurora read replicas do not have their own copy of the data on separate storage** — they share the exact same storage volume as the writer. Promoting a replica to writer, or adding a new replica, does not require copying gigabytes of data; it only requires attaching compute to already-shared storage. This is why Aurora failover is measured in seconds, not minutes.

## Replica Lag

- Typical replica lag is **single-digit milliseconds to under 100ms**, far lower than typical async streaming replication lag on standard MySQL/Postgres, because replicas aren't waiting on data page shipment — only redo log records need to arrive and be applied to update cached pages.
- Lag can still grow under a replica's own resource pressure (large query load, insufficient instance size, DDL replay lock contention) — replica lag is a per-replica metric to monitor, not a fixed guarantee.
- **Read-after-write consistency is not guaranteed on a read replica** — a read immediately following a write on the writer may not yet be visible on a replica, functionally the same tradeoff as any async-replicated relational system. Aurora does not offer a strongly-consistent-read option on replicas the way DynamoDB does; if strict read-your-writes is required, read from the writer endpoint or the specific replica whose LSN you've confirmed has caught up.

## Endpoints

| Endpoint type | Points to | Use |
|---|---|---|
| **Cluster (writer) endpoint** | Always the current writer instance | All writes; reads that need strong consistency |
| **Reader endpoint** | Load-balances across all available read replicas | Read-heavy traffic that can tolerate replica lag |
| **Custom endpoint** | A user-defined subset of instances (e.g., a specific instance class for reporting queries) | Workload isolation — e.g., routing analytics queries to larger replicas without affecting general read traffic |
| **Instance endpoint** | One specific instance directly | Debugging, or application-level logic that needs a pinned connection to a known replica |

A DNS-level redirect is how failover and reader load balancing work — the endpoint hostname doesn't change, but its DNS target does, which is why client-side DNS caching (a notoriously common issue with the JVM's default infinite DNS TTL caching) can cause an application to keep hitting a demoted instance after failover unless `networkaddress.cache.ttl` is tuned or a connection pool with health checks (or RDS Proxy) is used.

## Failover

- Failover promotes an existing **read replica** to writer — because it already shares the storage volume, no data copy is needed, and failover typically completes in **~30 seconds** (frequently faster).
- **Failover priority tiers** (0–15, set per replica) let you control which replica is promoted first — set your largest/most-provisioned replica to the highest priority tier so failover doesn't promote an undersized instance into the writer role under load.
- If a cluster has **no read replicas**, failover instead requires provisioning a brand-new writer instance and attaching it to the existing (already-durable) storage volume — slower than promoting an existing replica (though still faster than a traditional restore, since storage itself doesn't need to be recreated), which is why production clusters should generally run at least one replica purely for HA, independent of read-scaling needs.
- Aurora also supports **cross-region failover** via Global Database (Part 7) for full-region loss scenarios, which is a distinct, slower (typically well under a minute for planned/managed failover, longer for unplanned) mechanism from same-region replica promotion.

## Multi-Master (Historical Note)

Aurora MySQL briefly supported a multi-master mode (multiple writer instances, each able to accept writes, conflict handled via first-committer-wins). AWS has since steered customers toward single-writer + read replica topologies for new designs — multi-master added meaningful conflict-handling complexity for a narrower benefit than the simpler pattern, and it is not available on Aurora PostgreSQL at all. Worth knowing it exists for historical/trivia purposes, not worth designing around today.

---

# Part 4 — Data Model and SQL

## Nothing New at the Data-Model Layer

Unlike DynamoDB or Cassandra, Aurora does not introduce a new data modeling paradigm — it is exactly the relational model of whichever engine you chose (MySQL or PostgreSQL), with standard normalization, foreign keys, joins, and transactions. The study effort here is not "how does Aurora structure data" but "which parts of standard MySQL/Postgres behavior does Aurora change or extend."

## PostgreSQL vs MySQL Edition — Picking One

| | Aurora PostgreSQL | Aurora MySQL |
|---|---|---|
| SQL/feature depth | Richer type system (JSONB, arrays, ranges, extensions), window functions, CTEs long-standing | Simpler type system, historically weaker JSON support (improved over versions) |
| Extension ecosystem | `pg_stat_statements`, PostGIS, `pgvector` (similarity search), Trusted Language Extensions, Babelfish (SQL Server compatibility) | More limited plugin ecosystem |
| Parallel Query (storage-layer pushdown) | Not available | Available — pushes scan/filter/aggregate work into storage nodes for large analytical-style queries over an OLTP dataset |
| Backtrack (rewind cluster in place) | Not available | Available |
| Typical fit | Teams wanting Postgres's richer SQL/extension surface, geospatial (PostGIS), vector search (`pgvector`) for RAG/embeddings workloads | Teams already on MySQL, or needing Parallel Query/Backtrack specifically |

**This is a real decision, not a coin flip** — pick based on which engine's SQL dialect, extensions, and tooling the team already knows, and which edition-specific feature (Parallel Query/Backtrack on MySQL vs `pgvector`/PostGIS/Babelfish on PostgreSQL) matters for the workload.

## Extensions Worth Knowing

- **`pgvector`** (PostgreSQL edition) — stores vector embeddings as a native column type with approximate-nearest-neighbor indexing (IVFFlat, HNSW); increasingly relevant given RAG/LLM-application workloads wanting a single relational store rather than a separate vector database.
- **PostGIS** (PostgreSQL edition) — geospatial queries and indexing.
- **`pg_stat_statements`** — per-query performance statistics, the foundation Performance Insights builds on.

## Connection Management

Because Aurora is a traditional connection-oriented relational protocol (not HTTP/request-based like DynamoDB's API), **connection count is a real, finite resource** tied to instance size — a `max_connections` ceiling exists per instance class, and naively opening a connection per request (instead of pooling) exhausts it quickly under load. This is the single most common Aurora production issue for teams new to it, especially from serverless/Lambda compute where each concurrent invocation can otherwise open its own connection.

- **RDS Proxy** — a managed connection pooler that sits between the application and the cluster; multiplexes many client connections onto a smaller pool of actual database connections, and also smooths over failover (holds client connections open across a writer promotion instead of every client needing to reconnect).
- **Aurora Serverless Data API** — an HTTP-based query execution API (no persistent connection at all) — trades per-query latency overhead for eliminating the connection-management problem entirely; most relevant for Lambda-based access patterns with high connection concurrency and low per-invocation duration.

---

# Part 5 — Capacity Modes

## Provisioned Instances

- You choose an instance class (e.g., `db.r6g.xlarge`) for the writer and each reader — standard vertical-scaling tradeoffs apply (bigger instance = more memory for buffer pool, more vCPU, more max connections).
- Resizing an instance is an online operation with a brief (seconds to low-minutes, engine-dependent) interruption, or can be done with zero downtime by resizing a replica first, promoting it, then resizing the old writer.
- Because storage is decoupled from compute, **resizing compute does not involve any data migration** — this is a meaningfully different (faster, lower-risk) operation than resizing a traditional RDS instance's attached EBS-backed compute, though EBS-based RDS resize is also online, just with a different underlying mechanism.

## Aurora Serverless v2

- Scales compute in fine-grained **Aurora Capacity Units (ACUs)** — each ACU is roughly 2 GB of memory plus proportional CPU/network — adjusting in fractional increments within seconds based on load, without a connection-dropping failover-style event the way v1's scaling did.
- Can be **mixed with provisioned instances in the same cluster** (e.g., a Serverless v2 writer with a provisioned reader, or vice versa) — this is the key advance over v1, which required an isolated, all-or-nothing serverless deployment.
- Billed per ACU-second actually used, within a configured min/max ACU range you set — similar cost-model philosophy to DynamoDB On-Demand, but at the compute layer rather than the request layer, since Aurora is still a connection-oriented relational engine underneath.
- Good fit: spiky or unpredictable relational workloads, dev/test environments that should scale to near-zero when idle, multi-tenant SaaS patterns with many small, intermittently-active databases.

## Aurora Serverless v1 (Legacy)

- The original serverless mode — scaled in discrete steps, required a brief connection-dropping pause during scaling events, and could not coexist with provisioned instances in the same cluster. AWS recommends v2 for all new work; only relevant to recognize if inheriting an older cluster.

## Decision Framework

| Workload shape | Recommendation |
|---|---|
| Steady, predictable, production OLTP | Provisioned instances — most cost-efficient at sustained utilization, and most predictable for capacity planning |
| Spiky/unpredictable, or many small per-tenant databases | Serverless v2 — avoids both under-provisioning throttles and over-provisioning waste |
| Dev/test environments | Serverless v2 with a low min-ACU — scales toward near-zero cost when idle, without a manual stop/start operational burden |
| Heavy analytical/reporting queries alongside OLTP | Provisioned read replica (custom endpoint) sized specifically for that workload, isolated from the primary read traffic |
| I/O-heavy workload with unpredictable per-I/O billing spikes | Aurora I/O-Optimized configuration — higher flat instance price, zero per-I/O charges, more predictable bill |

---

# Part 6 — Global Database (Multi-Region)

## How It Works

```
Primary Region                         Secondary Region(s) — up to 5
┌─────────────────┐                    ┌─────────────────┐
│ Writer instance  │──storage-layer────▶│ Read replica(s)  │
│ + read replicas  │  physical          │ (read-only)      │
└─────────────────┘  replication       └─────────────────┘
                      (typically <1s
                       lag, via
                       dedicated
                       replication
                       infrastructure,
                       NOT via redo-log-
                       over-standard-
                       network like
                       same-region
                       replicas)
```

- Aurora Global Database replicates at the **storage layer** across regions using purpose-built replication infrastructure, achieving typical lag under 1 second — meaningfully faster than logical/streaming replication a self-managed cross-region setup would achieve.
- **Single-writer model**, unlike DynamoDB Global Tables' multi-active design — exactly one region accepts writes at a time; secondary regions are strictly read-only until a managed or unplanned failover promotes one to primary.
- **Managed planned failover**: a single API call promotes a secondary region to primary with typically under a minute of write unavailability, used for disaster-recovery drills or deliberate region relocation.
- **Unplanned failover**: if the primary region is fully lost, promoting a secondary is a manual (or automation-triggered) operation — Global Database does not auto-failover across regions the way a single-region cluster auto-fails-over across AZs, because cross-region failover has bigger blast-radius implications (DNS, application routing, potential data loss window) that AWS deliberately leaves under customer control rather than fully automating.

## Why Single-Writer Instead of Multi-Active

Unlike DynamoDB, Aurora is a full relational engine with foreign keys, unique constraints, and transactional guarantees that multi-active writes across regions would make very difficult to preserve consistently (the same fundamental tension that makes distributed SQL hard in general). Single-writer Global Database sidesteps this entirely: exactly one region's writer enforces all constraints, and secondary regions are pure read scale-out / DR targets, not places where conflicting concurrent writes could occur.

## Design Implications

- Route all writes to the primary region; secondary regions serve **read-only** traffic (reporting, local read latency for geographically distributed users) — there's no failover-free way to accept writes in a secondary region.
- RPO in an unplanned regional failure: bounded by replication lag at the moment of failure (typically well under a second of data, but not a hard zero guarantee — treat it like any async replication RPO).
- RTO: bounded by how quickly you (or your automation) trigger and complete the promotion of a secondary — not instantaneous or automatic, budget and rehearse an actual runbook.

---

# Part 7 — Backup, Restore, Backtrack, and Cloning

## Automated Backups and Point-in-Time Recovery

- Continuous, incremental backups to S3, enabling restore to **any point within the retention window** (1–35 days, configurable) — conceptually similar to DynamoDB's PITR, mechanically different (Aurora captures the log stream continuously rather than a state-diff mechanism).
- Restoring, like DynamoDB, creates a **new cluster** — no true in-place restore via the standard backup mechanism; a cutover step (repoint application config / swap DNS) is part of any real restore runbook.
- Backups do not degrade the live cluster's performance — captured from the already-durable storage layer, not a blocking snapshot process.

## Backtrack (Aurora MySQL only)

- Rewinds an entire cluster **in place**, to a point within the past 72 hours, without creating a new cluster and without a restore-from-backup operation — mechanically implemented by tracking enough log history to "undo" forward, rather than reapplying a backup.
- Meaningfully faster than a restore for the specific "we just ran a bad migration/DELETE and need to undo it right now" incident — but it rewinds the **entire cluster**, including tables unrelated to the mistake, so it's a blunt instrument, not a targeted fix. Not available on the PostgreSQL edition.

## Fast Database Cloning

- Creates a new cluster from an existing one using **copy-on-write** at the storage layer — the clone shares the same underlying data pages initially and only diverges (consuming additional storage) as either the source or the clone is modified.
- Completes in **minutes regardless of database size**, because no actual data copy happens upfront — this is the standard mechanism for spinning up a realistic-sized staging/test environment, or a one-off environment to safely test a risky migration against production-scale data without touching production.

## Export to S3 / Analytics Integration

- Aurora supports exporting snapshot data to S3 in Parquet format for analytics without querying the live cluster, and **zero-ETL integration to Redshift** (near-real-time replication into a Redshift warehouse without a customer-managed ETL pipeline) — the relational-world analogue of DynamoDB's Streams-to-warehouse pattern.

---

# Part 8 — Security

## IAM and Authentication

- Standard database username/password authentication works as on any MySQL/PostgreSQL instance.
- **IAM database authentication** — an alternative that issues short-lived auth tokens via IAM instead of long-lived static database passwords, removing password rotation/storage as an operational burden. Token-based, so the application must refresh tokens periodically (typically valid 15 minutes) — most commonly paired with RDS Proxy, which handles this transparently.
- **Secrets Manager integration** — the more common pattern for static-credential rotation when IAM auth isn't used: Aurora clusters can be configured to rotate the master password automatically via a Secrets Manager-managed rotation Lambda.

## Encryption

- **At rest:** KMS-based encryption, enabled at cluster creation (cannot be toggled on for an existing unencrypted cluster in place — requires a snapshot-restore-into-encrypted-cluster migration, an important operational fact for a "we forgot to enable encryption" incident).
- **In transit:** TLS enforceable via parameter group settings (`rds.force_ssl` on PostgreSQL, `require_secure_transport` on MySQL).
- Read replicas and Global Database secondary regions inherit the primary's encryption key relationship (cross-region replication re-encrypts under a region-specific KMS key, coordinated automatically).

## Network Isolation

- Aurora clusters live inside a VPC; security groups control inbound access at the instance/cluster level, same model as any RDS deployment — there's no Aurora-specific network primitive beyond standard VPC/security-group/subnet-group configuration.

---

# Part 9 — Monitoring and Performance

## Key CloudWatch Metrics

| Metric | What it signals |
|---|---|
| `CPUUtilization` / `FreeableMemory` | Standard instance resource pressure — the first thing to check for a slow-query incident |
| `DatabaseConnections` | Trending toward `max_connections` predicts connection exhaustion — the most common Aurora incident for teams not using a connection pooler |
| `AuroraReplicaLag` | Per-replica lag in milliseconds — the direct signal for "is this replica safe to read from for near-real-time data" |
| `VolumeBytesUsed` / `VolumeReadIOPs` / `VolumeWriteIOPs` | Storage-layer usage and I/O — relevant for I/O-Optimized vs standard pricing decisions |
| `ServerlessDatabaseCapacity` (Serverless v2) | Current ACU usage — trending against the configured max signals need to raise the ceiling |

## Performance Insights

- A built-in, low-overhead query performance dashboard (built on `pg_stat_statements`/the MySQL performance schema under the hood) showing top wait events and top SQL by load — the direct tool for "why is this database slow right now" without needing to manually instrument or attach an external APM tool.
- Free tier retains 7 days of history; paid tier extends retention for trend analysis.

## Parallel Query (Aurora MySQL only)

Pushes scan, filter, and aggregation work down into the distributed storage nodes rather than pulling all matching data pages up into the compute instance first — meaningfully reduces I/O and buffer pool pressure for large analytical-style scans over an otherwise-OLTP-sized instance, without needing a separate data warehouse for moderate reporting workloads.

---

# Part 10 — Cost Model

| Driver | Detail |
|---|---|
| Compute (provisioned) | Per-instance-hour, by instance class, for writer + each read replica — the largest cost line for most steady workloads |
| Compute (Serverless v2) | Per ACU-second actually used, within your min/max range — no cost floor below the configured minimum, but also not free at true zero unless min-ACU is set very low |
| Storage | Per GB-month actually consumed, auto-scales up to 128 TB with no manual provisioning step (unlike standard RDS, which requires pre-provisioning a volume size) |
| I/O | Standard configuration bills per I/O request; I/O-Optimized configuration folds I/O cost into a higher flat instance price — the crossover point depends on I/O density, worth modeling explicitly for I/O-heavy workloads rather than guessing |
| Backup storage | Backup storage up to 100% of your cluster's total data size is included free; beyond that, billed per GB-month |
| Data transfer | Cross-AZ replication (storage layer) within a region is not separately billed; cross-region Global Database replication and general cross-region data transfer are |
| Read replicas | Each is a full additional compute cost (own instance class), even though storage is shared — a common budgeting mistake is underestimating replica compute cost while correctly assuming storage is "free" to share |

Exact prices vary by region and instance class — treat the above as relative cost drivers, not fixed numbers to quote.

---

# Part 11 — Local Development

- **No official Aurora-specific local emulator** exists (unlike DynamoDB Local) — because Aurora is wire-compatible with standard MySQL/PostgreSQL, local development and most integration testing use a **plain MySQL or PostgreSQL container** (e.g., via Testcontainers), accepting that Aurora-specific behaviors (failover timing, storage-layer replication lag, Backtrack, Parallel Query pushdown) cannot be exercised locally and need a real Aurora cluster (typically a shared dev/staging cluster) to validate.
- **LocalStack** provides a limited RDS/Aurora API mock for infrastructure-as-code testing (cluster creation calls, etc.) but does not emulate actual query execution against Aurora's storage engine — not a substitute for a real Postgres/MySQL container in application-level tests.

---

# Part 12 — Review: Common Study Questions

**Q: What's the actual architectural innovation in Aurora — what makes it faster than "RDS with better marketing"?**
Storage/compute separation with a log-structured, distributed storage service that the writer talks to by shipping only redo log records (not full data pages), combined with storage nodes asynchronously materializing pages themselves. This cuts network I/O for writes dramatically versus traditional replication, and it's what makes near-instant crash recovery and fast (~30s) failover possible — failover doesn't need to copy data because storage is already shared between the old and new writer.

**Q: How is an Aurora read replica different from a standard MySQL/PostgreSQL read replica?**
A standard replica has its own full copy of the data on its own storage and applies a stream of WAL/binlog changes to keep that separate copy current. An Aurora read replica shares the exact same underlying distributed storage volume as the writer — it only needs to stream and apply redo log records to keep its in-memory buffer pool cache current, not maintain a separate on-disk copy. This is why adding a replica or promoting one during failover doesn't involve copying gigabytes of data.

**Q: Why does Aurora tolerate losing an entire AZ plus one more node without write impact?**
The 6-way storage quorum (2 copies per AZ across 3 AZs) requires only 4 of 6 acknowledgments for a write to succeed. Losing one full AZ removes 2 copies; losing one additional node removes a 3rd — 3 remaining copies out of 6 is still enough to serve reads (3-of-6 read quorum) but not enough for a *new* write quorum until repair catches up, so in practice this specific double-failure scenario is the edge of the tolerance envelope, but single-AZ-loss alone (the common case) leaves 4 copies, exactly meeting write quorum.

**Q: Aurora Global Database vs DynamoDB Global Tables — what's the fundamental design difference and why?**
Global Database is single-writer (one region accepts writes; others are read-only, promoted only via explicit failover) because Aurora must preserve full relational ACID guarantees (foreign keys, unique constraints, transactional isolation) that a multi-active write model across regions would make very hard to guarantee consistently. DynamoDB Global Tables is multi-active with last-writer-wins conflict resolution because DynamoDB's item-level model has no cross-item constraints to protect — LWW on a single item is an acceptable, simple conflict-resolution story for a key-value engine in a way it would not be for a relational schema with referential integrity.

**Q: When would you choose Aurora over plain RDS for MySQL/PostgreSQL?**
When you need Aurora's specific advantages: faster failover, higher write throughput at large scale, storage that auto-scales without manual provisioning, up to 15 low-lag read replicas, Global Database for multi-region reads/DR, or Serverless v2's fine-grained autoscaling. If the workload is small/steady and doesn't need any of those specifically, plain RDS is simpler and can be meaningfully cheaper at small scale (Aurora's compute-hour pricing carries a premium over equivalent RDS instance classes).

**Q: Why is connection management a bigger operational concern on Aurora than on DynamoDB?**
Aurora is a traditional connection-oriented relational protocol with a finite `max_connections` ceiling per instance class — unlike DynamoDB's stateless HTTP API, opening a connection per request (common in naive Lambda-based designs) can exhaust that ceiling under concurrency. RDS Proxy (connection multiplexing) or the Serverless Data API (HTTP-based, no persistent connection) are the two standard fixes, each suited to a different access pattern.

**Q: What does Aurora's "log is the database" idea mean concretely?**
The writer's durability unit is the redo log record, not a materialized data page — a write is durable once a quorum of storage nodes has persisted the log record, and those nodes independently, asynchronously replay the log to build/update actual data pages. Contrast with standard MySQL/PostgreSQL, where the primary itself must construct and eventually flush full data pages as part of maintaining its own durable state, and replicas replay a page-oriented (or statement-oriented) log against their own separately-materialized copy.

**Q: What's the practical difference between Backtrack and point-in-time restore?**
Backtrack rewinds the existing cluster in place (MySQL only, up to 72 hours) without creating a new cluster — fast, but blunt, since it reverts the entire cluster including unrelated tables. Point-in-time restore (available on both editions, up to 35 days) creates a brand-new cluster at the target time, leaving the original untouched — slower and requires an application cutover step, but is surgical (you can restore into a new cluster, extract just the needed rows, and leave production alone).

**Q: Why is fast cloning possible, and what's it actually for?**
Copy-on-write at the storage layer — a clone initially shares the same data pages as its source and only consumes additional storage as either diverges, so creation takes minutes regardless of database size instead of copying the full dataset upfront. Primary uses: spinning up a production-scale staging/test environment cheaply, or safely rehearsing a risky migration/schema change against real-scale data without touching production.

# Amazon Aurora — Engineering Manager's Guide

> This document is written for EMs who need to make architectural decisions, review designs,
> lead incident reviews, size teams, and talk about Aurora trade-offs with engineers and stakeholders.
> It does not cover implementation internals — see `aurora.md` for that depth.

---

# Part 1 — The Decision Layer: When to Use (and Not Use) Aurora

## What Problem Aurora Solves

Aurora is optimized for one thing above all else: **giving you a fully relational, ACID, SQL database with AWS-managed high availability and scale characteristics that a standard self-managed (or standard RDS) MySQL/PostgreSQL deployment can't match, without giving up SQL, joins, or transactions.**

The canonical use cases:
- **Transactional business systems** — orders, payments, inventory, anything needing real foreign keys, multi-table transactions, and strong consistency
- **Systems migrating off self-managed MySQL/PostgreSQL** — Aurora's wire compatibility means minimal application-code change, mostly a connection-string and operational-model change
- **Read-heavy relational workloads needing horizontal read scale** — up to 15 low-lag read replicas, versus a handful on standard RDS with materially higher replication lag
- **Multi-region relational data with a DR or geo-read requirement** — Global Database
- **Unpredictable or spiky relational workloads, or many small per-tenant databases** — Serverless v2

The common thread: **the domain is genuinely relational (real referential integrity, real multi-row transactions, real joins), and the team wants those semantics without owning the operational burden of running MySQL/PostgreSQL at scale themselves.**

---

## When Aurora Is a Strong Fit

| Question | Why it matters |
|---|---|
| Does the domain need real relational integrity — foreign keys, joins, multi-table transactions? | This is the entire reason to pick a relational engine over DynamoDB/Cassandra in the first place |
| Is the team already comfortable with MySQL or PostgreSQL? | Aurora doesn't require learning a new query language or data model — it's an operational/architectural upgrade, not a rewrite |
| Do you need higher write throughput or lower replication lag than standard RDS provides? | Aurora's storage/compute separation is specifically built for this |
| Do you need fast failover (~30s) for HA-sensitive transactional workloads? | Standard RDS failover (EBS reattach) is materially slower |
| Is traffic spiky, or do you run many small databases (multi-tenant SaaS)? | Serverless v2 fits this well; standard provisioned RDS does not scale down |
| Do you need multi-region reads or DR for relational data? | Global Database is purpose-built for this; self-managed cross-region replication is a real ongoing engineering project |

---

## When NOT to Use Aurora

**Your access patterns are simple, key-based, and you want zero connection/instance management**
If the workload doesn't actually need joins, transactions, or relational integrity, DynamoDB removes an entire category of operational concern (connection pooling, instance sizing, `max_connections` limits) that Aurora still has. Don't reach for Aurora out of habit if the data is genuinely key-value shaped.

**Your workload is small, steady, and cost-sensitive**
Aurora carries a real price premium over equivalent standard RDS instance classes. A small, steady-state application without a stated need for Aurora's HA/scale advantages is often better served — and meaningfully cheaper — on standard RDS or even a single well-backed-up Postgres instance.

**You need an engine Aurora doesn't support**
Aurora is MySQL- and PostgreSQL-compatible only. SQL Server, Oracle, and other engines need standard RDS (or self-managed, or a compatibility layer like Babelfish for a subset of SQL Server behavior on Aurora PostgreSQL — which is a partial answer, not a full substitute).

**You need portability (multi-cloud, on-prem, avoid vendor lock-in)**
Aurora clusters do not run outside AWS. If multi-cloud or on-prem portability is a stated architectural requirement, self-managed (or another cloud's managed) MySQL/PostgreSQL keeps that door open at the cost of owning more operations yourself.

**You need write scaling beyond a single writer**
Aurora is single-writer (Global Database secondaries are read-only; multi-master is effectively deprecated). A workload that genuinely needs multiple concurrent write regions/nodes accepting writes to the same logical dataset needs a different architecture (DynamoDB Global Tables, Cassandra, or an explicitly-sharded relational design) — Aurora will not solve that problem no matter how it's tuned.

---

## Aurora vs Alternatives — Decision Framework

| Scenario | Recommendation | Why |
|---|---|---|
| Relational domain, needs AWS-managed HA/scale beyond standard RDS | **Aurora** | Its core strength — storage/compute separation gives faster failover, higher throughput, more read replicas |
| Relational domain, small/steady, cost-sensitive | **Standard RDS (MySQL/PostgreSQL)** | Aurora's premium isn't justified without a stated need for its scale/HA advantages |
| Key-based access, no joins/transactions needed, want zero connection management | **DynamoDB** | Removes an entire category of Aurora's operational surface (connections, instance sizing) that doesn't buy anything if the data doesn't need relational integrity |
| Multi-cloud or on-prem portability required | **Self-managed MySQL/PostgreSQL** (or the other cloud's managed equivalent) | Aurora is AWS-only, full stop |
| Needs an engine other than MySQL/PostgreSQL | **Standard RDS** (SQL Server, Oracle) | Aurora doesn't support these engines |
| Multi-region active-active writes needed | **DynamoDB Global Tables / Cassandra** | Aurora is single-writer by design; not solvable by configuration |
| Spiky/unpredictable relational load, or many small tenant databases | **Aurora Serverless v2** | Purpose-built for this; standard RDS has no equivalent scale-to-near-zero story |
| Heavy analytics/reporting alongside OLTP | **Aurora with a custom read-replica endpoint**, or **zero-ETL to Redshift** for genuinely heavy analytical workloads | Isolate reporting load from transactional load; move to a warehouse once analytics needs outgrow "a bigger read replica" |

---

## Managed, Not Serverless-Only — There's Still a Compute Decision

Unlike DynamoDB, choosing Aurora doesn't eliminate the "how much compute do we provision" decision — you still pick instance classes (or a Serverless v2 ACU range) for a writer and however many read replicas you need. This is a real, ongoing capacity-planning responsibility your team retains, distinct from (and in addition to) the schema-design responsibility any relational database carries.

---

# Part 2 — Core Concepts an EM Must Own

## Storage/Compute Separation, at the Level You Need to Explain It

You don't need the full SIGMOD paper, but you need this mental model: **Aurora's writer ships log records, not data pages, to a distributed storage layer that's already replicated 6 ways across 3 AZs before your query returns.** This is why:
- Failover is fast (~30 seconds) — a replica is promoted onto storage it already shares, no data copy needed.
- Read replicas are cheap to add and low-lag — they stream a log, not a full independent copy.
- Crash recovery is near-instant — most of the "replay the log" work already happened continuously in the background, not at restart.

**When reviewing an HA design, ask:**
- Does this cluster have at least one read replica purely for failover speed, independent of read-scaling need? (A writer-only cluster's failover is slower — it needs a new instance provisioned, not just a promotion.)
- Is the failover priority tier set on the replica we actually want promoted first, or is it left at default and effectively random among equal-priority replicas?

## Single-Writer Is a Structural Constraint, Not a Configuration Gap

Aurora (including Global Database) has exactly one writer at a time. This is a deliberate trade for preserving full relational ACID guarantees — a multi-active write model across regions would make foreign keys, unique constraints, and transactional isolation very hard to guarantee consistently, which is the whole reason a team chose a relational engine in the first place.

**What this means for your team:** any requirement that sounds like "we need low-latency writes from users in multiple regions simultaneously to the same data" is a requirement Aurora cannot satisfy by tuning — it needs either a different architecture (accept a single write region and eat the latency for distant users) or a different database (DynamoDB Global Tables, at the cost of losing relational guarantees). Don't let this surface for the first time during a design review; it should be a named constraint in any multi-region conversation from the start.

## Connections Are a Real, Finite Resource

Unlike DynamoDB's stateless request model, Aurora is connection-oriented with a hard `max_connections` ceiling per instance class. The most common Aurora production incident for teams new to it — especially teams coming from serverless/Lambda compute — is connection exhaustion from not pooling.

**When reviewing a design, ask:**
- Is there a connection pooler (RDS Proxy, or an in-application pool like HikariCP) in front of this cluster, or is each request/invocation opening its own connection?
- If this is Lambda-based, has the team considered the Data API (HTTP-based, no persistent connection) as an alternative to managing pooled connections from a function that scales elastically and unpredictably?

## Read Replicas: Lag Is Real, Even Though It's Small

Aurora read replica lag is typically single-digit milliseconds to under 100ms — much better than standard async replication, but **not zero, and not guaranteed**. A read immediately following a write, routed to a replica, can still return stale data.

**Explaining this to a non-engineer:** "Reads from our main database are always current. Reads from a read replica — which we use to spread out read traffic — are very slightly behind, usually by a tiny fraction of a second, but not guaranteed to be perfectly current. For anywhere a user needs to see their own change immediately after making it, we read from the main database, not a replica."

---

# Part 3 — Multi-Region and Reliability

## Global Database: Read Scale-Out and DR, Not Multi-Active Writes

Global Database replicates a primary region's data to up to 5 secondary regions, typically with sub-second lag, using dedicated (not standard network) replication infrastructure. All secondary regions are **read-only** until an explicit failover promotes one.

**Implication for your team:** Global Database solves "users in other regions need fast local reads" and "we need a DR target for a full primary-region loss" — it does not solve "we need users in multiple regions to write with low latency to the same data." If a stakeholder asks for the latter, the honest answer is that it requires a different architecture, not a Global Database configuration change.

## RPO and RTO

- **Planned failover** (a deliberate region-relocation or DR drill): typically well under a minute of write unavailability — a single managed API call.
- **Unplanned failover** (full primary-region loss): RPO is bounded by replication lag at the moment of failure (typically well under a second of data, not a hard zero) — RTO depends on how quickly your team detects the loss and triggers promotion, since this is **not automatic** the way same-region AZ failover is. Budget for and rehearse an actual runbook; don't assume Global Database self-heals across regions the way a single-region cluster self-heals across AZs.

## What "Managed" Does and Doesn't Give You

Aurora's managed nature eliminates real operational burden (patching, storage provisioning, backup infrastructure, AZ-level failover automation) — but it does **not** eliminate:
- Schema and query design mistakes (a missing index, a bad migration, an N+1 query pattern are still your team's problem, same as on any relational database)
- Connection management discipline (pooling, `max_connections` sizing)
- Capacity planning (instance sizing, replica count, when to move to Serverless v2)
- The need for monitoring and incident response — replica lag, connection exhaustion, and slow queries are all real production incidents that still happen on Aurora, they just don't require SSHing into a box to diagnose

---

# Part 4 — Operational Overhead and Team Implications

## What It Takes to Run Aurora in Production

Compared to self-managed MySQL/PostgreSQL, the operational bar is dramatically lower (no patching, no manual failover tooling, no storage volume management) — but meaningfully higher than DynamoDB's near-zero operational surface. What your team still owns:

| Responsibility | Detail |
|---|---|
| Schema and query design | Indexing, normalization, migration planning — the same relational-database discipline any team running Postgres/MySQL needs, unaffected by whether it's self-managed or Aurora |
| Instance/capacity sizing | Provisioned instance classes and replica count, or Serverless v2 ACU range — revisited as traffic matures |
| Connection management | Pooling strategy (RDS Proxy vs application-level pool vs Data API) — a design decision, not a default that takes care of itself |
| Failover configuration | Replica count and priority tiers set deliberately, not left at whatever the console defaulted to |
| Backup/restore drills | PITR and snapshots exist, but restoring creates a new cluster — your team needs a tested cutover runbook |
| Monitoring dashboards/alerts | Replica lag, connection count, CPU/memory, slow-query signals (Performance Insights) — none of this is automatic without someone setting up the alarms |
| Multi-region runbook (if using Global Database) | Unplanned failover is not automatic — someone owns detecting the need and triggering promotion |

## Team Size and Skill Requirements

A team running Aurora well needs **standard relational database competence** (schema design, indexing, query tuning, migration discipline) plus AWS-specific operational literacy (instance sizing, RDS Proxy, CloudWatch/Performance Insights) — closer to "a competent backend team that already knows SQL well" than a dedicated DBA function, but not zero-DBA-skill the way DynamoDB can be for a well-scoped key-value workload. A team that has never operated a relational database at meaningful scale will still need to learn indexing and query-tuning discipline — Aurora doesn't abstract that away, only the infrastructure operations around it.

---

## Common Failure Modes and Their Business Impact

### Connection Exhaustion
Too many concurrent connections against a `max_connections` ceiling, usually from unpooled connections opened per-request (common from Lambda). **Impact:** new connection attempts fail outright — a hard, visible outage, not a graceful degradation. **Fix:** RDS Proxy or an application-level pool; for Lambda specifically, consider the Data API.

### Replica Lag Under Load
A read replica falls meaningfully behind under its own resource pressure (undersized instance, heavy query load, DDL lock contention during replay). **Impact:** stale reads on that replica — often noticed as "the data looks wrong" bug reports rather than an obvious outage, making it a slower, more confusing incident to diagnose.

### Missing Index / Slow Query Regression
A new query pattern or a schema change removes/misses an index, and a previously-fast query becomes a full scan. **Impact:** CPU pressure on the writer, cascading into general latency degradation across unrelated queries sharing the instance — this is a standard relational-database failure mode, not Aurora-specific, but worth naming because teams new to Aurora sometimes assume "managed" means this can't happen.

### Failover Promotes an Undersized Replica
Failover priority tiers weren't set deliberately, and an undersized replica (e.g., one kept small purely for read-scaling a lightweight background job) gets promoted to writer under a failover event. **Impact:** the new writer is under-provisioned for production write load immediately after a failover — compounding an already-stressful incident with a capacity problem.

### DNS Caching Masks Failover
A client (notably JVM applications with default infinite DNS TTL caching) keeps sending traffic to the old, now-demoted writer instance after a failover completes, because it cached the old DNS resolution. **Impact:** writes continue failing (or hit a now-read-only instance) well after Aurora itself has completed failover — looks like "failover didn't work" when the actual cause is client-side DNS caching.

### Cross-Region Global Database Failover Not Rehearsed
An unplanned regional failure happens, and the team discovers in the moment that no one has a tested runbook for triggering and completing promotion. **Impact:** RTO balloons far beyond what the underlying replication lag would suggest is possible, because the bottleneck is human/process readiness, not Aurora's technical capability.

## Incident Response Mental Model

1. **Check `DatabaseConnections` against the instance's `max_connections`** — the fastest way to rule in/out connection exhaustion, the most common Aurora-specific incident.
2. **Check `AuroraReplicaLag`** if the report is "stale data," not "errors" — distinguishes a lag issue from an application bug.
3. **Check Performance Insights for top wait events/top SQL by load** — the direct tool for "why is this suddenly slow," before assuming infrastructure is at fault.
4. **Check whether the issue correlates with a recent migration, deploy, or query pattern change** — a missing index or a new N+1 pattern is a more common root cause than Aurora itself misbehaving.
5. **For a Global Database region-loss scenario, confirm the runbook is being followed, not improvised** — this is exactly the kind of high-stakes, rarely-exercised procedure that benefits most from having been rehearsed in advance.

---

# Part 5 — Cost Model

## What Drives Cost

| Driver | Detail |
|---|---|
| Compute | The largest line for most workloads — instance-hour pricing for the writer and every read replica (Aurora carries a premium over equivalent standard RDS instance classes) |
| Serverless v2 ACU range | Cost-efficient for spiky load if the min-ACU floor is set sensibly low; a min-ACU set too high defeats the scale-to-near-zero benefit |
| Storage | Auto-scales with usage, no manual over-provisioning waste (unlike standard RDS, which bills for provisioned volume size regardless of actual usage) |
| I/O | Standard config bills per I/O request; I/O-Optimized trades a higher flat instance price for zero per-I/O billing — worth modeling explicitly for I/O-dense workloads rather than defaulting to standard |
| Read replica count | Each replica is a full additional compute cost, even though storage is shared — a common budgeting mistake is assuming replicas are "nearly free" because storage is shared |
| Cross-region (Global Database) | Additional compute in each secondary region, plus cross-region data transfer for replication |
| Backup storage | Free up to 100% of cluster data size; beyond that, billed — a long retention window on a large, high-churn cluster is a real, recurring line to review |

## Infrastructure Sizing Mental Model

Unlike DynamoDB, there is a real "how many instances, what size, how many replicas" conversation — this doesn't go away with Aurora, it just gets easier to execute (faster resizing, no data-copy-bound scaling). The sizing exercise that matters: right-sizing the writer for actual write/transaction load, right-sizing replicas for actual read traffic (not just copy-pasting the writer's size), and deciding whether Serverless v2 removes enough of this planning burden to be worth its per-unit cost premium for a given workload's shape.

## Total Cost of Ownership vs Self-Managed MySQL/PostgreSQL

Aurora's TCO advantage is overwhelmingly in **engineering/ops headcount avoided** (no patch management, no manual failover tooling, no storage capacity planning, no backup infrastructure to build) plus genuinely better HA/performance characteristics than a team is likely to hand-build themselves. It carries a real price premium in raw instance-hour cost versus self-managed compute on comparable hardware — the trade is nearly always worth it unless a team already has deep, dedicated database operations expertise and specifically needs to avoid AWS lock-in.

---

# Part 6 — Talking About Aurora With Stakeholders

## What Questions to Expect (and How to Answer Them)

**"Can we guarantee users always see their own latest update?"**

> "Yes, if we read from the primary database. We also use read replicas to spread out read traffic, and those are very slightly behind — usually milliseconds — which is fine for most of the app, but for anywhere a user needs to see their own change immediately, we make sure that read goes to the primary, not a replica."

**"What happens if the primary database instance fails?"**

> "A standby replica is promoted automatically, and because it already shares the same underlying storage as the failed instance, this typically completes in about 30 seconds — no data needs to be copied. We do need at least one replica running at all times for this to be fast, which is a cost we've budgeted for specifically because of this."

**"What happens if we lose an entire AWS region?"**

> "If this database is on Global Database across multiple regions, we have a read-only copy in another region with typically well under a second of data lag. Promoting it to become the new primary is a deliberate, manual step we'd trigger — it's not automatic the way single-region failover is, so how fast that actually happens depends on how quickly we detect the problem and execute our runbook. I can tell you how recently we've tested that runbook if that's useful."

**"Can we run a new report/analytics query against this data without slowing down the app?"**

> "We can route that to a dedicated read replica sized for that purpose, isolated from the traffic serving the app. If the reporting need grows into something heavier — regular complex analytics, not occasional queries — we'd look at streaming the data into a proper data warehouse instead of leaning on a bigger read replica indefinitely."

**"We deleted some records by mistake — can we get them back?"**

> "Yes, within our backup retention window, we can restore to the exact point in time before the mistake. That restore creates a new database, so there's a short cutover step rather than an instant undo. Do we know precisely when the bad change happened?"

**"Why did the app suddenly start throwing connection errors even though the database itself seems fine?"**

> "Most likely we hit our maximum connection limit — probably from something opening more simultaneous connections than usual without going through our connection pool. This is a capacity/configuration issue, not data loss — we're checking connection counts now."

---

## The Questions You Should Be Asking Your Team

**On architecture and design:**
- Does this cluster have at least one read replica, and is it there for HA, read scaling, or both — and is that intentional?
- Are failover priority tiers set deliberately, or left at whatever the default gave us?
- Is there a connection pooler in front of this cluster, or are we relying on the application to manage connections itself?

**On capacity and cost:**
- Provisioned or Serverless v2, and was that decision revisited after we understood our actual traffic shape?
- Are read replicas sized appropriately for their actual traffic, or did we just copy the writer's instance class?
- Is our I/O pattern dense enough that I/O-Optimized pricing would actually be cheaper than standard?

**On reliability:**
- For places where staleness matters, are we deliberately reading from the primary, or did we just take the default (which endpoint) without thinking about it?
- If we use Global Database, do we have a tested, documented runbook for unplanned regional failover — not just the technical capability?
- Is our backup retention window sufficient for what we could realistically need to recover from?

**On operations:**
- Do we have alerting on connection count approaching `max_connections`, and on replica lag?
- Has anyone actually rehearsed a restore, not just confirmed backups are "enabled"?

---

# Part 7 — Hiring: What Good Aurora Expertise Looks Like

## Interview Questions That Reveal Real Understanding

**"How is Aurora's replication architecture different from standard MySQL/PostgreSQL replication, and why does it matter operationally?"**
Listen for: storage/compute separation, log-shipping instead of data-page replication, and a concrete explanation of *why* this makes failover and replica provisioning fast — not just "it's faster because AWS."

**"A team wants multi-region active-active writes to the same relational data on Aurora. What do you tell them?"**
Listen for: recognizing Aurora is single-writer by design (including Global Database), and that this isn't solvable by configuration — the honest answer is a different architecture or a different database, not a deeper Aurora feature they haven't found yet.

**"The application is throwing connection errors under load, but CPU and memory look fine. What's your first hypothesis and how do you confirm it?"**
Listen for: connection exhaustion against `max_connections` as the first hypothesis, and checking `DatabaseConnections` in CloudWatch (or `pg_stat_activity`/`SHOW PROCESSLIST`) to confirm before assuming something more exotic.

**"When would you reach for Aurora Serverless v2 instead of provisioned instances?"**
Listen for: spiky/unpredictable load or many small per-tenant databases as the trigger, awareness that it can mix with provisioned instances in the same cluster, and honesty that it carries a per-unit cost premium not automatically worth paying for steady, predictable load.

**"How would you design for a scenario where we need to safely test a risky schema migration against production-scale data?"**
Listen for: fast database cloning (copy-on-write, minutes regardless of size) as the specific Aurora capability suited to this, not a generic "take a snapshot and restore it" answer that ignores Aurora's faster mechanism.

## Red Flags

- "Aurora automatically handles multi-region writes" — indicates the candidate hasn't actually understood (or has confused Aurora with DynamoDB Global Tables)
- "We just open a new connection per request, RDS handles it" — indicates no real production experience with connection limits under load
- "We don't need read replicas, failover just works" — a writer-only cluster's failover is materially slower since a new instance must be provisioned; suggests the candidate hasn't hit an actual failover event
- "Aurora is basically the same as DynamoDB but with SQL" — conflates two fundamentally different architectures and consistency models; a sign of surface-level familiarity only
- "We've never tested our DR runbook, but Global Database handles it" — confuses replication (automatic) with failover (manual/deliberate) for cross-region scenarios

---

# Part 8 — Review: EM-Level Q&A

**Q: When should I push back on a proposal to use Aurora?**
Push back when: the data doesn't actually need relational integrity (a key-value workload reaching for Aurora out of familiarity rather than need), the workload is small/steady and cost-sensitive with no stated need for Aurora's HA/scale advantages over standard RDS, the team needs true multi-region active-active writes (Aurora is structurally single-writer), or multi-cloud/on-prem portability is a real requirement.

**Q: What is the single most common Aurora failure mode?**
Connection exhaustion from unpooled connections, especially from serverless/Lambda compute opening one connection per invocation. It's also the easiest to prevent (RDS Proxy or the Data API) and the easiest to miss until the first real load spike exposes it.

**Q: How do I know if our Aurora clusters are healthy?**
Four signals: (1) `DatabaseConnections` has meaningful headroom below `max_connections` on every instance; (2) `AuroraReplicaLag` is low and stable on every replica; (3) Performance Insights shows no persistently dominant slow query eating disproportionate load; (4) for Global Database, cross-region replication lag is stable and the failover runbook has actually been rehearsed, not just documented.

**Q: What does it mean when someone says "we need to add a read replica" mid-project?**
Usually one of two things: read traffic has outgrown what the writer (or existing replicas) can comfortably serve, or a new workload (reporting, a background job) needs isolation from the main application's read traffic via a custom endpoint. Either way it's a real compute-cost decision, not a free operation — size the new replica for its actual intended load, don't just copy the writer's instance class by default.

**Q: We're evaluating Aurora vs DynamoDB for a new service. What are the key deciding factors?**
Aurora: the domain genuinely needs relational integrity — foreign keys, multi-table transactions, joins, ad-hoc query flexibility via SQL. DynamoDB: access patterns are known, key-based, and the team wants to eliminate connection/instance management entirely. If the team is unsure whether the domain is "really" relational, that uncertainty itself is informative — a genuinely relational domain rarely stays ambiguous for long once real requirements land.

**Q: What's the real difference between Aurora's HA story and a self-managed MySQL/PostgreSQL HA setup?**
Self-managed HA (e.g., Patroni, manual streaming replication + a failover tool) requires the team to build and maintain failover automation, replica provisioning, and storage redundancy themselves — real, ongoing engineering investment. Aurora's storage/compute separation makes fast failover and low-lag replicas a built-in property of the platform rather than something the team assembles and maintains. The team still owns schema design, query performance, and capacity planning either way — Aurora doesn't remove relational-database expertise as a requirement, it removes the infrastructure-automation burden around HA specifically.

**Q: How should I think about Global Database's RPO/RTO commitments when a stakeholder asks about regional failure?**
Data-layer RPO is typically well under a second (replication lag at failure time). RTO for a *planned* failover (a DR drill or deliberate relocation) is typically under a minute. RTO for an *unplanned* full-region loss depends entirely on how fast your team detects the failure and executes the (manual) promotion — this is not automatic, and the honest answer to a stakeholder should always include whether that runbook has actually been tested recently, not just whether it exists on paper.

**Q: A team wants to skip capacity planning and "let it auto-scale." How do I respond?**
Serverless v2 genuinely reduces this burden for spiky/unpredictable workloads, but it isn't a substitute for understanding the workload's shape — a poorly-set min/max ACU range can still throttle under a real spike or waste money sitting idle at too-high a floor. For steady, predictable production load, provisioned instances sized deliberately are usually both cheaper and more predictable than defaulting to Serverless v2 out of a desire to avoid the sizing conversation entirely.

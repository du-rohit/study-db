# Amazon DynamoDB — Engineering Manager's Guide

> This document is written for EMs who need to make architectural decisions, review designs,
> lead incident reviews, size teams, and talk about DynamoDB trade-offs with engineers and stakeholders.
> It does not cover implementation internals — see `dynamodb.md` for that depth.

---

# Part 1 — The Decision Layer: When to Use (and Not Use) DynamoDB

## What Problem DynamoDB Solves

DynamoDB is optimized for one thing above all else: **eliminating operational burden for key-based access patterns at any scale, on AWS, with predictable per-request cost.**

The canonical use cases:
- **Serverless application backends** — Lambda + API Gateway + DynamoDB is AWS's flagship "no servers, no ops" stack
- **Session/user profile storage** — high read rate, known key lookup, low latency requirement
- **Shopping carts, order history, inventory counters** — e-commerce workloads with clear per-user or per-item access patterns
- **Gaming — leaderboards, player state, matchmaking** — bursty, unpredictable traffic that On-Demand handles gracefully
- **IoT device state and metadata** — high item count, simple key lookups, TTL-driven expiry

The common thread: **known access patterns, key-based lookups (not ad-hoc queries), and a strong preference for zero infrastructure management over query flexibility.**

---

## When DynamoDB Is a Strong Fit

| Question | Why it matters |
|---|---|
| Are your access patterns known and stable upfront? | DynamoDB requires designing the schema (and often GSIs) around specific queries — the same constraint as Cassandra, but with even less flexibility to "just add a query" later |
| Is your team AWS-committed? | DynamoDB is AWS-only — no self-hosting, no other cloud, no on-prem option |
| Do you want zero operational ownership? | No patching, no capacity servers, no version upgrades, no repair jobs — this is DynamoDB's core value proposition |
| Is traffic spiky or hard to forecast? | On-Demand billing absorbs this without a capacity-planning exercise |
| Do you need single-digit-millisecond latency at any scale? | DynamoDB's managed partitioning gives this without any manual sharding work from your team |
| Is your data model key-value/document shaped, not relational? | Natural fit; forcing a relational domain into DynamoDB is expensive later |

---

## When NOT to Use DynamoDB

**You need flexible or ad-hoc queries**
Every query pattern needs to be designed for in advance — usually via the base table key or a GSI. A team that regularly needs new report/analytics queries against live data will fight DynamoDB constantly. Feed a data warehouse (via Streams or S3 Export) instead of querying the live table for anything ad hoc.

**You need joins or a genuinely relational domain**
No joins, ever. A heavily relational domain (multi-entity referential integrity, complex many-to-many with rich attributes on the relationship itself) is expensive to force into single-table design. Use Aurora/PostgreSQL.

**You need complex transactions across many items routinely**
`TransactWriteItems` supports up to 100 items and costs 2× normal capacity — fine for occasional multi-item atomicity, a poor fit as the *primary* access pattern of a transaction-heavy domain (e.g., a general ledger with complex multi-leg postings). That's a relational database's job.

**Your team wants portability (multi-cloud, on-prem, avoid vendor lock-in)**
DynamoDB is AWS-proprietary — there is no self-hosted or other-cloud version. If multi-cloud portability is a stated architectural requirement, Cassandra (self-hosted or via a managed CQL-compatible service) is the equivalent capability without the lock-in.

**Your access patterns are still being discovered**
Because indexes are expensive to retrofit gracefully (a new GSI means a backfill and, for LSI, means recreating the table entirely), a product in a highly exploratory phase where query needs shift weekly will incur constant schema churn. A more flexible store (Postgres, or even a document DB with secondary indexes) may fit an unstable phase better, with a possible migration to DynamoDB once patterns stabilize.

**You need full-text search or complex filtering**
DynamoDB has no native full-text search, geo-query, or complex ad-hoc filtering. Pair it with OpenSearch (fed via Streams) if that's a real requirement — don't try to force it through `FilterExpression`, which reads (and bills for) every scanned item regardless of what's filtered out.

---

## DynamoDB vs Alternatives — Decision Framework

| Scenario | Recommendation | Why |
|---|---|---|
| AWS-native serverless app, key-based access | **DynamoDB** | Its core strength — zero ops, scales automatically |
| Same workload, but multi-cloud or on-prem requirement | **Cassandra** (managed or self-hosted) | Only realistic wide-column alternative with similar scaling model, cloud-portable |
| Relational domain, joins, complex transactions | **Aurora / PostgreSQL** | ACID, SQL, mature tooling |
| Flexible schema, ad-hoc queries, moderate scale | **MongoDB (Atlas)** | Richer query language, secondary indexes more forgiving |
| Full-text search / complex filtering | **OpenSearch** (often paired *with* DynamoDB via Streams) | Purpose-built for search, not a primary store replacement |
| Sub-millisecond caching layer | **DAX** (in front of DynamoDB) or **Redis/ElastiCache** | DAX if already on DynamoDB; Redis if you need richer data structures or aren't on DynamoDB |
| High-throughput time-series, multi-region active-active, non-AWS | **Cassandra / ScyllaDB** | Better suited to unbounded time-ordered writes and multi-cloud replication |
| Very small dataset, low traffic, team wants SQL simplicity | **RDS Postgres (single instance)** | DynamoDB's per-request billing and schema constraints aren't justified at trivial scale |

---

## Fully Managed Only — There Is No "Self-Hosted" Decision

Unlike Cassandra, there's no self-hosted-vs-managed decision to make — DynamoDB is *only* available as an AWS managed service. This removes an entire category of EM decision-making (team sizing for ops, upgrade planning, hardware sizing) but also removes the escape hatch: if AWS pricing or a specific missing feature becomes a blocker, there's no "just self-host it" fallback the way there is with Cassandra. Vendor lock-in is a structural property of choosing DynamoDB, not a configuration choice.

---

# Part 2 — Core Concepts an EM Must Own

## The Data Model Mental Model

DynamoDB requires access-pattern-driven schema design, just like Cassandra — but goes a step further with **single-table design**, where a single table often holds multiple unrelated entity types, distinguished by generic, overloaded key attributes (`PK`/`SK` populated differently per item type).

**What this means for your team:**
- Whiteboard the access patterns *before* the schema — "what queries will this feature need" is a design conversation that must happen before a single line of table-definition code is written, not after.
- A raw dump of a single-table design's items is close to unreadable to someone unfamiliar with the scheme (`PK: ORG#1, SK: USER#42` tells you nothing without the modeling doc). This is a real onboarding and debuggability cost — budget for good internal documentation of the key scheme, or a NoSQL Workbench model, as a first-class deliverable of the design.
- Adding a genuinely new access pattern later usually means a new Global Secondary Index and a backfill job — plan this as its own mini-project, not a quick config change.

**When reviewing a schema design, ask:**
- What are the actual access patterns this needs to serve, today and in the next 6–12 months?
- Is this single-table or one-table-per-entity? If single-table, is the added complexity actually justified by scale, or would a simpler multi-table design be easier to maintain at this team's current size?
- Which attributes will need a GSI, and is the GSI's own provisioned capacity accounted for?

## The Partition Key Is Still Everything

Same underlying physics as Cassandra: the partition key decides which physical partition (and therefore which throughput ceiling) an item's traffic lands on.

**Hot partitions in DynamoDB have a hard, documented ceiling:** 3,000 RCU or 1,000 WCU per partition, regardless of how much unused capacity the rest of the table has. "Adaptive capacity" (automatic, no config needed) softens uneven load *below* that ceiling, but a single key hot enough to exceed it will throttle no matter what the table's overall provisioned capacity looks like.

**When reviewing a design, ask:**
- Could any single partition key value receive disproportionate traffic (a celebrity user, a viral item, a global counter)?
- If yes, is there a write-sharding plan (adding random/calculated suffixes to spread that key across multiple partitions)?

## Consistency: What "Eventually Consistent by Default" Means for Your Product

- **Default reads are eventually consistent** — a read immediately after a write might not reflect it, though in practice this resolves within well under a second.
- **Strongly consistent reads** are available on demand (2× the read cost) for the base table and Local Secondary Indexes — but **never** for Global Secondary Indexes, which are always async-propagated.
- **Transactions** (`TransactWriteItems`/`TransactGetItems`) give full ACID guarantees across up to 100 items, at 2× capacity cost, for the cases that genuinely need it (e.g., a payment debit/credit pair).

**Explaining this to a non-engineer:** "By default, a read might lag a write by a fraction of a second. For anything where that's a problem — like a user immediately checking their own balance after an update — we ask for a strongly consistent read, which costs twice as much capacity but is always current. For fully atomic multi-step operations, like moving money between two accounts, we use transactions, which guarantee both steps happen together or not at all."

---

# Part 3 — Multi-Region and Reliability

## Global Tables: Multi-Active, Not Primary/Standby

Global Tables replicate a table to additional AWS regions with **every region able to accept both reads and writes** — there is no designated primary. Conflict resolution is **last-writer-wins** by internal timestamp, with no application-level merge logic exposed.

**Implication for your team:** if the same item can be legitimately written concurrently from two regions, one write silently wins and the other is lost — there's no conflict callback to intervene. Design write ownership so that, for any given item, one region is the practical "home" under normal operations, and reserve genuine multi-region concurrent writes to the same item for cases where "last update wins" is an acceptable business rule (e.g., a user profile edit, not a financial ledger).

## RPO and RTO

- **RPO** in a regional failure: effectively the cross-region replication lag at the moment of failure — typically sub-second to a few seconds.
- **RTO**: near-zero for the *data layer* itself (a surviving region is already serving traffic) — but **failover is not automatic**. Your application/client layer must detect the failure and redirect traffic (via Route 53 health checks, a multi-region client config, or similar). RTO in practice is bounded by how fast *your* failover mechanism reacts, not by DynamoDB.

## What "Managed" Does and Doesn't Give You

DynamoDB's managed nature eliminates entire categories of Cassandra-style operational risk (no repair jobs to miss, no compaction to tune, no JVM GC pauses, no node failures to replace) — but it does **not** eliminate:
- Data modeling mistakes (a bad partition key choice is still your team's problem, and expensive to fix after the fact)
- Cost surprises (an under-provisioned GSI, an unnecessary Scan on a hot path, or leaving On-Demand billing running on a much-larger-than-expected workload)
- The need for monitoring and incident response — throttling, hot keys, and replication lag are all real production incidents that happen on DynamoDB, they just don't require you to SSH into a node to diagnose them

---

# Part 4 — Operational Overhead and Team Implications

## What It Takes to Run DynamoDB in Production

Compared to Cassandra, the operational bar is dramatically lower — no dedicated DBA/SRE function is required purely to keep DynamoDB "up." What your team still owns:

| Responsibility | Detail |
|---|---|
| Schema/access-pattern design | The single highest-leverage (and highest-risk-if-wrong) skill — this doesn't go away just because the infrastructure is managed |
| Capacity mode decisions | Provisioned + Auto Scaling vs On-Demand, per table, revisited as traffic patterns mature |
| Cost monitoring | GSI sprawl, unnecessary Scans, and On-Demand cost on high, steady volume are the top silent cost leaks |
| IAM/access design | Fine-grained access control (`dynamodb:LeadingKeys`) for multi-tenant data isolation is an application-security responsibility, not something DynamoDB enforces for you by default |
| Backup/restore drills | PITR exists, but restoring creates a new table — your team needs a tested runbook for cutover, same as any other database |
| Monitoring dashboards/alerts | Throttling, hot-key detection (Contributor Insights), replication lag on Global Tables — none of this is automatic without someone setting up the CloudWatch alarms |

## Team Size and Skill Requirements

A team running DynamoDB well needs **strong data-modeling discipline**, not infrastructure/ops skills. The highest-leverage hire or training investment is someone (or several people) who deeply understands access-pattern-driven design and single-table modeling trade-offs — this is a design/architecture skill, closer to "senior backend engineer who's internalized DynamoDB's constraints" than "database administrator." No 24/7 DB-specific on-call rotation is typically needed purely for DynamoDB uptime — incidents tend to be application-level (bad key design, cost overruns, throttling from a traffic spike) rather than infrastructure-level.

---

## Common Failure Modes and Their Business Impact

### Hot Partition / Hot Key
A single partition key value receives disproportionate traffic and hits the 3,000 RCU/1,000 WCU per-partition ceiling. **Impact:** throttled requests for that specific key (e.g., one viral product page, one celebrity user) while the rest of the table is fine — a confusing, partial-outage-looking incident. **Fix:** write sharding at the data-model level; adaptive capacity helps but doesn't remove the hard ceiling.

### Under-Provisioned GSI Silently Throttling the Base Table
A GSI's own WCU is exhausted, which throttles writes to the *base table* for any item touching the indexed attribute — even though base-table capacity metrics look healthy. **Impact:** a confusing incident where "the table has plenty of capacity but writes are failing" — the fix requires knowing to check GSI-level metrics specifically, which teams new to DynamoDB often don't think to do.

### Scan on a Hot Path
A `Scan` (instead of `Query`) accidentally introduced on a request-serving path reads the entire table and bills accordingly. **Impact:** cost spike and/or throttling that scales with total table size, not request volume — can go unnoticed until the table grows large enough to make the Scan slow and expensive.

### On-Demand Cost Surprise
A table under On-Demand billing sees sustained high and steady traffic (not the spiky pattern On-Demand is priced for) — the bill is materially higher than provisioned capacity would have been for the same steady volume. **Impact:** a cost, not an outage, but a recurring one that compounds — the fix is revisiting capacity mode choice as traffic patterns mature past the initial "we don't know our traffic yet" phase.

### Global Tables Write Conflict
Two regions concurrently update the same item under a Global Tables setup; last-writer-wins silently drops one side. **Impact:** a data-loss-adjacent bug that's hard to detect because there's no error — the "losing" write simply appears to have never happened, and only shows up as a confused user report ("I updated this and it reverted").

### Retrofitting a New Access Pattern Late
A new product requirement needs a query the schema wasn't designed for. **Impact:** an engineering project (new GSI + backfill, or a table migration), not a quick change — the business-facing risk is under-communicating this cost when a PM assumes "just add a query" is a small ask.

## Incident Response Mental Model

1. **Check `ThrottledRequests` per table and per GSI** — distinguishes base-table capacity issues from index capacity issues immediately.
2. **Check Contributor Insights for the affected table** — identifies the specific hot key/partition without needing custom instrumentation.
3. **Check whether the issue correlates with a recent deploy** — a new access pattern, an accidental Scan, or a schema change is a more common root cause than DynamoDB itself misbehaving.
4. **For Global Tables, check `ReplicationLatency`** before assuming a cross-region data-consistency bug is application-level.
5. **DynamoDB itself very rarely is "down"** in the way a self-managed database can be — treat "DynamoDB is broken" as a low-prior hypothesis versus "our access pattern or capacity configuration is wrong."

---

# Part 5 — Cost Model

## What Drives Cost

| Driver | Detail |
|---|---|
| Capacity mode | On-Demand is simpler but ~5–7× more per-request than well-utilized Provisioned capacity — the right choice depends on how steady/predictable traffic actually is |
| GSI count and utilization | Every GSI has its own cost (capacity or per-request); unused or redundant GSIs are a common source of avoidable spend |
| Item size and access pattern efficiency | Larger items and inefficient Scans multiply cost linearly; single-table design's efficiency argument is fundamentally a cost argument |
| Global Tables region count | Each additional region roughly adds its own full write-side cost (replicated writes are billed, not free) |
| Backup retention | PITR's 35-day rolling window on a large, high-churn table is a real, recurring cost line — worth reviewing per table, not just enabling everywhere by default |
| Table class | Standard vs Standard-Infrequent-Access trades storage cost for throughput cost — worth applying to large, cold tables (e.g., long-tail audit logs) |

## Infrastructure Sizing Mental Model

There's no "how many nodes do we need" conversation — capacity is either a request-based bill (On-Demand) or an RCU/WCU number you set (Provisioned). The sizing exercise that *does* matter: estimating steady-state RCU/WCU needs per table from expected request volume and item sizes, to make an informed Provisioned-vs-On-Demand call, and to set sane Auto Scaling min/max bounds that won't either throttle under normal peaks or leave excessive idle capacity provisioned.

## Total Cost of Ownership vs Cassandra

DynamoDB's TCO advantage over self-hosted (or even managed) Cassandra is overwhelmingly in **engineering/ops headcount avoided**, not necessarily in raw infrastructure spend at very high, steady scale — a large, sustained-throughput Cassandra cluster can be cheaper in pure infrastructure dollars once amortized, if the team already has the operational expertise. DynamoDB wins clearly for teams that would otherwise need to build that expertise from scratch, or whose traffic is too spiky/unpredictable to size a self-hosted cluster confidently.

---

# Part 6 — Talking About DynamoDB With Stakeholders

## What Questions to Expect (and How to Answer Them)

**"Can we guarantee users always see their own latest update?"**

> "By default, a read might lag a write by a fraction of a second — that's the default 'eventually consistent' mode, which is cheaper and faster. For any place where a user needs to see their own change immediately, we request a strongly consistent read, which is guaranteed current but costs twice as much capacity. We apply that selectively, not everywhere."

**"Can we run a new report/analytics query against this data?"**

> "DynamoDB is built for known, fast key-based lookups, not ad-hoc queries — that's the trade we made for its scaling and reliability properties. For anything exploratory or reporting-shaped, we stream changes out to [a data warehouse / OpenSearch] rather than querying the live table directly, so we don't put unpredictable load on production traffic. What's the specific question this report needs to answer?"

**"What happens if we lose an AWS region?"**

> "If this table is on Global Tables across multiple regions, a surviving region keeps serving both reads and writes within seconds — the data lag at the moment of failure is typically well under a second's worth of writes. The part that isn't automatic is redirecting user traffic to the surviving region — that's on our failover configuration, and I can tell you how tested that path is if you want that detail."

**"We deleted some records by mistake — can we get them back?"**

> "Yes, if it's within the last 35 days, we can restore to the exact second before the mistake using Point-in-Time Recovery. That restore creates a new table, so there's a short cutover step, not an instant undo. Do we know precisely when the bad delete happened?"

**"Why did writes start failing even though you told me we have plenty of capacity?"**

> "Most likely one specific key or index got disproportionately busy and hit a per-partition ceiling that exists independent of the table's overall capacity — think of it like one specific product page or one specific customer's traffic maxing out its own lane, even though the highway overall has room. We're looking at which specific key or index is the bottleneck now."

---

## The Questions You Should Be Asking Your Team

**On schema design:**
- What are the actual access patterns this table needs to serve, and did we design the key schema around them explicitly?
- Is this single-table design, and if so, is the complexity justified by our current scale, or are we paying an onboarding/debuggability tax for a scaling problem we don't have yet?
- Which GSIs exist, and is each one still actually used by a live access pattern?

**On capacity and cost:**
- Is this table On-Demand or Provisioned, and was that decision revisited after we understood our actual traffic shape?
- Are we seeing any `ThrottledRequests`, on the base table or on any GSI?
- Do we have Contributor Insights enabled on tables where hot-key risk is plausible?

**On reliability:**
- For tables where staleness matters, are we using strongly consistent reads deliberately, or did we just take the default without thinking about it?
- If this table uses Global Tables, do we have a tested, documented failover path for the application layer — not just the data layer?
- Is PITR enabled on anything we couldn't tolerate losing back to only the last full backup?

**On security:**
- If end-user devices call DynamoDB directly, are we enforcing item-level access control via IAM condition keys (`dynamodb:LeadingKeys`), or relying solely on application-layer checks?

---

# Part 7 — Hiring: What Good DynamoDB Expertise Looks Like

## Interview Questions That Reveal Real Understanding

**"Walk me through how you'd design the schema for [use case]. What's the partition key, and would you use single-table design here?"**
Listen for: access-pattern-first thinking, an honest answer about *when single-table design is and isn't worth the complexity* (not "always single-table because that's the DynamoDB way" — that's a red flag of cargo-culting rather than understanding trade-offs).

**"A GSI's write capacity is throttling. What's the actual impact, and how would you diagnose it?"**
Listen for: understanding that GSI throttling backpressures the *base table's* writes too, and knowing to check GSI-specific CloudWatch metrics rather than just the base table's.

**"How would you handle a hot partition key that we can't just remove from the product?"**
Listen for: write sharding (adding calculated/random suffixes and fanning out reads), not just "increase provisioned capacity" (which doesn't help once a single key exceeds the fixed per-partition ceiling).

**"When would you reach for DynamoDB Transactions, and when is that overkill?"**
Listen for: multi-item/multi-table atomicity as the actual trigger, awareness of the 2× cost, and recognizing that a single item's `ConditionExpression` handles most "don't overwrite concurrent changes" needs without paying transaction overhead.

**"What are the limits of Global Tables' conflict resolution, and how would you design around them?"**
Listen for: last-writer-wins by timestamp, no application-level merge, and a concrete strategy (write ownership per region, or accepting LWW semantics only where business-appropriate).

## Red Flags

- "We always use single-table design, it's best practice" stated without any trade-off awareness — suggests cargo-culting a pattern rather than understanding when it's actually worth the complexity
- "We use Scan for that query" on anything that looks like a live request path
- "We didn't think about GSI capacity, we just let it auto-scale" without checking whether Auto Scaling's ramp lag is fast enough for the actual traffic shape
- "We assumed Global Tables would merge conflicting writes" — indicates the team hasn't actually read how conflict resolution works
- "We haven't looked at Contributor Insights, we just add capacity when we see throttling" — treating a data-modeling problem as a capacity problem

---

# Part 8 — Review: EM-Level Q&A

**Q: When should I push back on a proposal to use DynamoDB?**
Push back when: the team needs genuine ad-hoc query flexibility, the domain is heavily relational with real multi-entity transactions as the common case (not the exception), the team wants multi-cloud/on-prem portability, or access patterns are still actively being discovered and are likely to change significantly in the near term.

**Q: What is the single most common DynamoDB failure mode?**
Hot partition keys and under-designed access patterns — both are data-modeling problems, not infrastructure problems, and both are expensive to fix after the fact because fixing them usually means a new index plus a backfill, not a config change.

**Q: How do I know if our DynamoDB tables are healthy?**
Four signals: (1) `ThrottledRequests` is at/near zero on every table *and* every GSI; (2) Contributor Insights shows no persistently dominant hot key; (3) cost trend is explained by traffic growth, not silent waste (unused GSIs, unnecessary Scans, mismatched capacity mode); (4) for Global Tables, replication latency is stable and low.

**Q: What does it mean when someone says "we need a new GSI" mid-project?**
It means a new access pattern emerged that the original schema didn't anticipate. Concretely: adding the GSI itself is usually fast, but if existing items need the new index's key attributes populated, that's a backfill across the table — plan it as its own small project with a completion check, the same way you'd plan a Cassandra schema migration.

**Q: We're evaluating DynamoDB vs Cassandra for a new service. What are the key deciding factors?**
DynamoDB: zero ops, AWS-native, pay-per-request cost model, best when traffic is spiky/unknown and the team is fully AWS-committed. Cassandra (self-hosted or managed): more query expressiveness via CQL, works across clouds, potentially cheaper at very high sustained scale if the team already has the operational skill — but that skill is a real, ongoing cost if they don't. If the team is AWS-first and wants to minimize operational surface area, DynamoDB is the lower-risk default; if multi-cloud portability or CQL's relative query flexibility matters, Cassandra is worth the added operational commitment.

**Q: What is "eventually consistent" and when does it actually matter for users?**
By default, a read might not reflect a write that happened milliseconds earlier. For most product surfaces this is invisible. It matters when a user takes an action and immediately expects to see its result (e.g., submitting a form and being redirected to a page that reads the just-written data) — for those specific paths, use a strongly consistent read (available on the base table and LSIs, never on a GSI) rather than assuming the default is always fine.

**Q: What's the real cost of "vendor lock-in" with DynamoDB, and how should I factor it into a build decision?**
There is no self-hosted or other-cloud DynamoDB — a decision to build on it is a decision to be AWS-committed for that data, full stop. This isn't necessarily a bad trade (most teams already run their whole stack on one cloud), but it should be a named, explicit part of the architecture decision record, not an implicit consequence discovered later when a multi-cloud requirement shows up.

**Q: How should I think about Global Tables' RPO/RTO commitments when a stakeholder asks about regional failure?**
Data-layer RPO is typically sub-second to a few seconds (replication lag), and data-layer RTO is near-zero (a surviving region is already live). The caveat to always add: application-layer failover (redirecting traffic to the surviving region) is not automatic — the real RTO for *users* depends on how fast your DNS/routing failover mechanism reacts, which is a separate thing to have tested, not something DynamoDB guarantees for you.

**Q: A team wants to skip designing access patterns upfront and "figure out the schema as we go." How do I respond?**
This works fine for a genuinely exploratory prototype on a flexible store, but DynamoDB specifically punishes this approach — a wrong initial key schema means a GSI-plus-backfill project (at best) or a full table migration (at worst) to correct later, once data volume makes it expensive. If the team can't commit to known access patterns yet, that's itself useful information: either invest the design time now, or use a more schema-flexible store until patterns stabilize, and migrate to DynamoDB once they have.

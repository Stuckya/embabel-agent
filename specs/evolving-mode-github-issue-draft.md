# GitHub Issue Draft: Evolving Mode

> **Status: architectural rewrite, v5.**
>
> This version supersedes the graph-derived episode design captured at
> `f87a365166cd55ecd203848d1bbde685f8cb3383`. It preserves the product
> contract—repeatable and consumable episodes, child-process execution,
> STUCK recovery, internal and external evolution, and eventual Open
> Evolving—but moves every planning decision behind a planner-owned
> session interface.
>
> The planner may change. No new `PlannerType` is introduced.

Draft issue text for the post-1.0.0 Evolving Mode discussion.

Discussion context: https://github.com/embabel/embabel-agent/discussions/1725

## Title

Evolving Mode

## Body

Embabel Agent already replans as actions add typed objects to working
memory, and existing goals can become achievable during a running process.
What is missing is an explicit lifecycle for follow-up work that:

- completes without ending the root process;
- is handled once per published occurrence;
- can be handled again for a later occurrence;
- can remain STUCK until the world changes;
- executes only in an isolated child process; and
- delegates all reasoning about goals, actions, plans, blockage, and repair
  to the selected planner.

Evolving Mode is a process lifecycle and execution model. It is not a
planning strategy and therefore is not a `PlannerType`.

## Non-Negotiable Principles

This proposal is governed by six principles:

1. Episodes are repeatable.
2. Episodes are consumable.
3. Episodes can be `STUCK`, and evolving can be used to solve this.
4. `evolve()` is available both inside an action through `ctx.evolve()` and
   outside action execution through `process.evolve()`. Both have the same
   occurrence semantics. Open Evolving eventually uses the same ingress.
5. Episode work runs in a child process.
6. Evolving Mode never infers or recreates planner logic.

The sixth principle is architectural, not aspirational. The evolving
runtime must not inspect the goal graph and then attempt to make the same
decision a planner would make.

## Terminology

### Root process

The long-lived process that owns the root mission, the planner session,
the occurrence ledger, and any currently active child processes.

The root process is not an episode. This keeps the statement “episodes run
in child processes” literal.

### Occurrence

One successful call to `ctx.evolve(fact)` or `process.evolve(fact)`.
Every accepted call receives a new `OccurrenceId`, even if the same object
instance or an equal value was evolved previously.

The domain object does not need to carry an occurrence identifier.

### Episode

The persistent intention created for one occurrence. An episode may be
`PENDING`, `RUNNING`, `STUCK`, `COMPLETED`, or `CANCELLED`.

An episode can outlive an individual execution attempt. Every attempt to
perform episode work runs in a fresh child process.

### Root mission

The process’s committed objective. Only completion of the root mission
completes the root process. When no root mission is supplied, the evolving
process is intentionally long-lived and ends through cancellation or an
explicit caller-owned policy.

### Planner session

The planner-owned, process-lifetime interface through which the selected
planner observes the root mission, pending episodes, world revisions, and
execution outcomes and returns the next directive.

The planner session owns goal deliberation, occurrence routing, plan
construction, execution repair, and completion decisions.

### Child mission

An opaque planner-produced description of one episode attempt. The
evolving runtime can pass it to child-process creation but cannot inspect
it to derive actions, goals, costs, or dependencies.

### Standing state

State that intentionally survives an episode. Application-owned domain
state is standing state by nature. Embabel-managed state created inside a
child is child-local unless an action explicitly shares it with the root.

## Product Model

There are two forms of evolving behavior.

### Deterministic Evolving

A typed occurrence is submitted to the selected planner. The planner uses
the declared scope and its native semantics to decide whether and how the
occurrence can be handled.

The relationship can be known from declared actions and goals, but only
the planner may reason over those declarations. Evolving Mode does not
derive a parallel rule set.

### Open Evolving

When the selected planner reports an obstruction that the current scope
or objective policy cannot resolve, an `ObjectiveAuthor` may propose a
validated policy or process-local scope revision.

Open Evolving consumes planner-reported obstruction data. It does not ask
the evolving runtime to reverse-engineer missing facts or capabilities
from the action graph.

## Architectural Position

The central seam is between the evolving runtime and the selected planner.

The evolving runtime owns:

- occurrence identity and lineage;
- episode lifecycle records;
- child-process creation and disposal;
- world-revision notification;
- hard resource and action-budget enforcement;
- explicit state sharing;
- occurrence consumption;
- cooperative cancellation delivery; and
- lifecycle events.

The selected planner owns:

- binding an occurrence to a declared goal or objective;
- choosing between root work and episode work;
- choosing among competing episodes;
- plan construction and refinement;
- action and goal value semantics;
- costs, heuristics, preconditions, and effects;
- constructing the child mission;
- deciding whether an episode is currently plannable;
- declaring an episode `STUCK`;
- describing an obstruction when the planner can do so;
- deciding whether a failed attempt should retry, wait, or fail;
- repairing or replacing a plan after an execution outcome;
- deciding that an episode is complete; and
- deciding that the root mission is complete.

The generic execution machinery owns:

- invoking actions selected by the planner;
- collecting observed action and child-process outcomes;
- maintaining action history;
- reporting completion, failure, cancellation, and child `STUCK` status;
  and
- never turning an observed outcome into a planning decision.

## Planner Interface

The existing `Planner` interface must be extended so Evolving Mode does
not need to compensate for information the planner does not expose.

The exact Kotlin types are an interface-design task, but the required
shape is:

```kotlin
interface Planner {
    fun openSession(request: PlanningSessionRequest): PlanningSession
}

interface PlanningSession {
    fun next(turn: PlanningTurn): PlanningDirective
}
```

A planning turn includes facts the planner is entitled to know, not a
framework-derived interpretation of them:

```kotlin
data class PlanningTurn(
    val revision: Long,
    val rootMission: RootMission?,
    val rootState: RootStateView,
    val episodes: List<EpisodeView>,
    val outcomes: List<ExecutionOutcome>,
    val availableChildCapacity: Int,
)

data class EpisodeView(
    val id: EpisodeId,
    val occurrence: OccurrenceView,
    val state: EpisodeState,
    val attemptCount: Int,
)
```

The planner returns a directive:

```kotlin
sealed interface PlanningDirective {

    data class RunRoot(
        val execution: PlannerExecution,
    ) : PlanningDirective

    data class RunEpisode(
        val episodeId: EpisodeId,
        val mission: ChildMission,
    ) : PlanningDirective

    data class AwaitEpisode(
        val episodeId: EpisodeId,
        val interest: WorldInterest,
        val obstruction: PlanningObstruction?,
    ) : PlanningDirective

    data class CompleteEpisode(
        val episodeId: EpisodeId,
    ) : PlanningDirective

    data class CancelEpisode(
        val episodeId: EpisodeId,
        val reason: String,
    ) : PlanningDirective

    data object AwaitProcess : PlanningDirective

    data object CompleteProcess : PlanningDirective
}
```

`PlannerExecution`, `ChildMission`, and the implementation of
`PlanningObstruction` are planner-owned. The evolving runtime treats them
as opaque values.

This interface may evolve as implementation experience accumulates. Its
invariant may not:

> `PlanningSession.next()` is the only interface through which Evolving
> Mode obtains a decision about what work should run or what planning state
> means.

## No New `PlannerType`

Evolving Mode does not add `PlannerType.EVOLVING`.

`PlannerType` continues to select the planning strategy:

- `GOAP`
- `UTILITY`
- `HYBRID`

`withEvolving(...)` selects a process lifecycle and execution topology.
The two choices are orthogonal:

```text
GOAP + ordinary process      -> GOAP planning
GOAP + evolving process      -> GOAP planning session with episodes
UTILITY + evolving process   -> Utility planning session with episodes
HYBRID + evolving process    -> Hybrid planning session with episodes
```

Each built-in planner must implement the session interface through its
native planning machinery. A generic evolving adapter must not reconstruct
goal selection, regression, costs, values, relevance, or blocker analysis
from `planToGoal(...)`.

A third-party planner that does not implement the session contract fails
clearly when used with `withEvolving(...)`. There is no derived-rule
compatibility fallback.

## Public Interface

The phase-1 consumer-facing interface is deliberately small:

```java
var options = ProcessOptions.DEFAULT
    .withPlannerType(PlannerType.HYBRID)
    .withEvolving(GoalTarget.output(SamplesStored.class));

var process = agentPlatform.createAgentProcessFrom(
    agent,
    options,
    new CollectSamples("Zone A", 500));

OccurrenceId first = process.evolve(
    new SensorCalibrationRequested("sensor-7"));

agentPlatform.start(process);
```

Inside an action:

```java
@Action
ObservationResult observe(ActionContext ctx) {
    var request = observeCalibrationRequest();
    if (request != null) {
        ctx.evolve(request);
    }
    return new ObservationResult();
}
```

The two methods are the same operation:

```java
OccurrenceId ActionContext.evolve(Object fact);
OccurrenceId AgentProcess.evolve(Object fact);
```

There is no `process.publish()` alternative for occurrences.

### Unified evolve semantics

Both forms:

- create a new occurrence;
- assign a new `OccurrenceId`;
- append it to the same process-local occurrence ledger;
- make it a persistent episode intention;
- queue it for visibility at a planning tick;
- advance the process world revision;
- request a wake when the root process is eligible to run;
- emit the same acceptance and lifecycle events; and
- leave all goal binding and routing to the planner session.

`ctx.evolve()` additionally records the current episode, child process,
and publishing action as lineage.

`process.evolve()` records an external origin unless an overload supplies
more specific source and correlation metadata.

An evolve call made inside a child delegates to the nearest evolving
ancestor. The framework execution child itself does not become an evolving
root process.

### Acceptance and rejection

`evolve()` may synchronously reject only lifecycle and interface errors
that require no planning:

- the process was not created with `withEvolving(...)`;
- the process is already terminal;
- the supplied value violates a general object-ingress invariant; or
- the configured planner does not support planning sessions.

The call must not reject an occurrence as “unroutable” by walking the
goal or action graph. The occurrence is accepted, presented to the planner
at a planning tick, and may become `STUCK` with a planner-reported
obstruction.

This is a deliberate change from synchronous derived-rule validation.
Zero recreation of planner logic takes precedence over graph-derived
fail-fast behavior.

### Threading and ordering

`process.evolve()` is safe to call from outside the action execution
stack and from any thread supported by the process implementation.

It queues the occurrence rather than mutating the planner-visible world on
the publisher’s thread. Accepted occurrences become visible at a planning
tick in ledger order. Equal values and repeated publication of the same
object remain distinct occurrences.

The return value acknowledges occurrence ownership. It does not claim that
the planner has already routed or accepted the work.

## Episode Lifecycle

The lifecycle is per occurrence:

```text
evolve
  -> PENDING
  -> RUNNING
  -> COMPLETED

RUNNING
  -> STUCK
  -> RUNNING

RUNNING
  -> PENDING       after a retryable failed attempt

PENDING | RUNNING | STUCK
  -> CANCELLED
```

Only the planner can issue transitions whose meaning depends on planning:

- `PENDING` to `RUNNING` through `RunEpisode`;
- any active state to `STUCK` through `AwaitEpisode`;
- an active state to `COMPLETED` through `CompleteEpisode`; and
- retry, repair, or cancellation after an observed failure.

The runtime performs each requested transition mechanically and validates
only lifecycle legality.

## Repeatability

Repeatability follows from occurrence identity and fresh child execution:

- each successful `evolve()` call creates a new occurrence;
- each occurrence creates a distinct episode;
- each attempt runs in a fresh child process;
- child-local products from an earlier episode are absent;
- child action history is fresh;
- completion consumes only the selected occurrence; and
- a later equal occurrence is unaffected.

Episodes are independent, not idempotent. Publishing the same request
twice means handling two occurrences unless a future ingress adapter
explicitly supplies transport-level idempotency.

No runtime type set or equality comparison defines repeatability.

## Child-Process Execution

Every `RunEpisode` directive creates a fresh framework child process.
Episode actions never execute in the root process.

The child receives:

- the selected occurrence and its identity;
- the planner-produced `ChildMission`;
- a planner-authorized view of reusable root state;
- the selected `PlannerType`;
- inherited execution limits and context as defined by process options;
  and
- a fresh action history and child-local working memory.

The evolving runtime does not construct the child by:

- selecting a goal;
- deriving a chain;
- filtering the agent’s actions;
- discovering producers or consumers;
- copying only actions it considers relevant; or
- forcing a different planner.

The selected planner constructs the mission. The child opens a planning
session through the same planner implementation and executes that mission.

A framework child does not inherit `withEvolving(...)`. Its
`ctx.evolve(...)` calls delegate upward. A manually created child can be
declared evolving explicitly, allowing deliberate nesting without
accidental inheritance.

## Consumption and State Isolation

Consumption is structural rather than graph-derived.

An occurrence is active only in:

- the root occurrence ledger;
- the planner’s episode view; and
- the child attempt selected to handle it.

It is not added to the root blackboard as ordinary standing state.

On `CompleteEpisode`, the runtime atomically:

1. marks the occurrence consumed;
2. removes it from the planner’s active episode view;
3. disposes the child and all child-local working memory;
4. retains only explicitly shared standing state and separately evolved
   occurrences;
5. closes lifecycle bookkeeping that is not required for audit history;
   and
6. emits `EpisodeCompletedEvent`.

The runtime does not infer consumable types, intermediate products, or
chain membership.

### Child-local by default

Objects added normally during child execution remain in child working
memory. They are available to later actions in the same child attempt and
disappear when that child is disposed.

Persistent domain state should normally live in application-owned modules
and be exposed through action inputs and `@Condition`.

When an action intentionally needs to make Embabel-managed standing state
visible to the root process, it must use an explicit state-sharing
operation, illustratively:

```java
ctx.share(standingFact);
```

The final spelling of this separate interface can be decided with the
child-process state model. It must not be inferred from output types,
action membership, or goal relevance.

When an action intends to create another occurrence, it uses
`ctx.evolve(...)`, not state sharing.

## Occurrence Routing

Evolving Mode does not maintain derived episode rules.

At a planning tick, the planner receives:

- the declared scope;
- the root mission;
- root standing state;
- pending and STUCK occurrence-backed episodes;
- prior execution outcomes; and
- current resource capacity.

The selected planner decides:

- whether an occurrence can support any declared objective;
- which objective it supports;
- which occurrence wins when several compete;
- which objective wins when one occurrence could support several;
- whether root work should run instead;
- what child mission should be attempted; and
- whether an already satisfied world state constitutes completion for this
  occurrence.

Occurrence-aware grounding belongs in the planner. The runtime must not
hide same-type facts, create request windows, or alter action availability
to force a desired binding.

Values, costs, conditions, and heuristics retain their planner-defined
meaning. Evolving Mode introduces no priority lane.

## STUCK, Wake, and Repair

An episode becomes `STUCK` only when the planner returns
`AwaitEpisode`.

The planner may provide a precise interest:

```kotlin
WorldInterest.Facts(setOf(Permit::class))
```

or a conservative interest:

```kotlin
WorldInterest.AnyRevision
```

A planner that cannot identify a missing fact can still support correct
STUCK behavior by waiting for any world revision. The evolving runtime
must not improve that answer by performing its own regression or missing
input analysis.

The runtime records the planner’s obstruction verbatim when one is
provided. It does not synthesize a more detailed explanation.

A world revision may be caused by:

- `ctx.evolve(...)`;
- `process.evolve(...)`;
- explicitly shared standing state;
- completion or failure of root or child execution;
- approved process-local scope expansion;
- an application-owned state notification; or
- another explicit process signal.

On a relevant revision, the runtime includes the same persistent STUCK
episode in the next `PlanningTurn`. The planner decides whether the new
world enables it, whether another episode should run first, or whether it
should remain STUCK.

There is no runtime world fingerprint and no runtime rule saying that a
particular fact type solves a particular episode.

### Evolving to solve STUCK

One episode can solve another episode’s obstruction:

1. Episode A is retained as `STUCK`.
2. An internal or external caller evolves occurrence B.
3. The planner chooses an episode for B.
4. B’s child performs work and explicitly changes standing state or
   evolves another occurrence.
5. The world revision wakes planning.
6. The planner reconsiders A.

Open Evolving later uses the same loop. It changes the world or scoped
capabilities; it does not patch a runtime-derived chain.

## Failure and Retry

Child completion, child `STUCK`, action failure, cancellation, and process
failure are observed outcomes. They are not themselves retry policies.

After an attempt ends:

- the runtime reports the outcome to the planner session;
- the occurrence remains unconsumed unless the planner issues
  `CompleteEpisode` or `CancelEpisode`;
- failed child-local state is disposed;
- explicit external side effects remain governed by their own domain
  semantics; and
- the planner chooses retry, repair, wait, cancellation, or another piece
  of work.

Hard action and process budgets remain runtime constraints. The planner
cannot direct execution beyond them.

## Admission and Concurrency

The occurrence ledger preserves acceptance order, but acceptance order is
not a substitute for planner selection.

The selected planner chooses among ready occurrences using its native
semantics. The runtime enforces only declared capacity:

- phase 1 defaults to one active episode child per root process;
- later phases may permit multiple episode children;
- available capacity is included in `PlanningTurn`; and
- the runtime rejects an illegal over-capacity directive rather than
  silently choosing another episode.

There is no “one active episode per derived rule,” because no derived rule
exists. If a domain needs coalescing, aggregation, or keyed capacity, that
knowledge must be explicit at ingress or represented as planner-visible
domain data.

## Root Mission and Process Completion

`withEvolving(objective)` supplies the root mission to the planner.

```java
ProcessOptions.DEFAULT
    .withEvolving(GoalTarget.output(SamplesStored.class));
```

The root process completes only when the planner returns
`CompleteProcess`.

An episode completion never completes the root process. An incidental goal
achievement does not become terminal through Evolving Mode.

Without an objective, the root process is intentionally long-lived and
may return `AwaitProcess` when it has no runnable work.

Default-mode processes retain their existing completion behavior.

## Blackboard and Domain State

The blackboard is process-local working memory, not a general external
event bus or authoritative domain database.

High-frequency, reversible, or continuously changing state should remain
in application-owned modules and be exposed to planning through:

- action inputs;
- `@Condition`;
- declared scoped capabilities; and
- explicit process revision notifications where automatic wake is needed.

An evolved object is an occurrence, not root standing state and not a
planner-manufacturable action output.

For example, an observed request should enter through evolve:

```text
raw telemetry
  -> application interpretation
  -> SensorCalibrationRequested
  -> process.evolve(request)
```

Tray contents, inventory, connection state, and sensor snapshots usually
remain application-owned state.

## Relationship to `@Action(trigger = ...)`

`@Action(trigger = X.class)` remains an action-level reaction to a recent
result. It does not provide occurrence identity, persistent intention,
child isolation, consumption, or rearm.

Use `trigger` for a direct reaction with no episode lifecycle. Use
`evolve()` when a fact represents one occurrence of follow-up work that
the planner should deliberate over and complete nonterminally.

## Relationship to `ReplanRequestedException`

`ReplanRequestedException` remains the inside-the-action mechanism for
abandoning the current execution path and requesting replanning.

It is distinct from `ctx.evolve()`:

- `ctx.evolve()` creates a persistent occurrence;
- `ReplanRequestedException` changes control flow;
- an action may use both when it needs to publish work and yield; and
- neither bypasses the planner session.

`process.evolve()` is the outside-the-action form and cannot throw into an
executing action.

## Cooperative Interruption

Publishing an occurrence does not automatically interrupt the action
currently executing. It guarantees visibility at a future planning tick
and requests a wake for an eligible parked process.

A later phase may allow an occurrence to request cooperative cancellation
of the deepest active descendant. The request:

- uses the existing action-scoped cancellation machinery;
- never kills a thread;
- does not select the next action;
- does not imply priority or a planner lane;
- is ignored by non-cooperative action code until that action returns; and
- returns control to the planner session after the action yields.

Interruption metadata must be explicit at publication or in approved
policy. It must not be inferred from the goal graph.

## Open Evolving

Open Evolving adds an `ObjectiveAuthor` at a planning tick.

The consultation trigger is a planner directive or planner-reported
obstruction, such as:

- no objective can currently serve an occurrence;
- the selected episode has no viable plan;
- a required capability is outside the current scope; or
- the current root mission cannot progress.

The evolving runtime forwards that obstruction unchanged.

An author may propose:

- a typed runtime objective referring to declared goals;
- a revision to objective policy;
- dispatch to a declared but currently unscoped capability;
- process-local scope expansion; or
- an episode intended to change the world and unblock another episode.

Proposals:

- contain data and references to declared capabilities, never executable
  code;
- are validated against the resulting scope;
- may require approval;
- install only at a planning tick;
- are atomic when policy and scope change together;
- produce a new world revision; and
- return control to the same planner session, or a planner-owned revised
  session when the planner requires reopening.

The author is not invoked on every tick. It responds to named planner
obstructions and remains outside the execution hot loop.

## Process-Local Scope Expansion

Scope expansion adds already-declared `@Agent` or `@EmbabelComponent`
instances to one running root process.

It:

- never mutates global declarations;
- does not affect another process;
- validates collisions and declared metadata;
- installs atomically with any objective revision that depends on it;
- advances the world revision; and
- lets the selected planner reconsider pending and STUCK episodes.

The evolving runtime does not re-derive episode rules after expansion.
The planner session receives the revised scope and owns all new reasoning.

Removing capabilities and hot-loading executable code remain out of
scope.

## Recurring Goals

Recurring goals are distinct from occurrence-backed episodes. They
represent standing work the process chooses to pursue repeatedly without a
new evolved occurrence.

If added, recurrence must be an explicit lifecycle declaration presented
to the planner. The planner:

- chooses the recurring objective;
- constructs each child mission;
- decides between recurrence, root work, and occurrence-backed episodes;
- determines completion; and
- replans each recurrence from current state.

The evolving runtime may provide fresh child isolation and disposal but
must not derive a recurring chain or infer which outputs make recurrence
safe.

Recurring work is a later work stream and must not weaken the six
principles for occurrence-backed episodes.

## Observability

Evolving processes need events for:

- occurrence accepted or rejected;
- occurrence drained at a planning tick;
- episode created;
- planner selected root or episode work;
- child attempt started;
- child attempt completed, failed, cancelled, or became STUCK;
- planner declared an episode STUCK;
- episode reconsidered after a world revision;
- occurrence consumed;
- episode completed or cancelled;
- root process parked, woken, or completed;
- Open Evolving consultation proposed, approved, rejected, or installed;
  and
- process-local scope expansion.

Events should include, where applicable:

```text
process id
world revision
occurrence id
episode id
child process id
attempt number
planner type
planner directive
lineage
source/correlation metadata
planner-provided obstruction
timestamp
```

Observability must distinguish an observed child status from the planner’s
decision about that status.

## Motivating Example

The root mission:

```text
Collect samples in Zone A until 500 samples are stored.
```

The scoped capabilities include collection, navigation, storage, and
calibration.

`sampleTrayFull` is current state and remains a condition:

```java
@Condition(name = "sampleTrayFull")
boolean sampleTrayFull(SampleTraySnapshot tray) {
    return tray.count() >= tray.capacity();
}
```

It is not an occurrence merely because it changes.

An observed calibration request is follow-up work:

```java
ctx.evolve(new SensorCalibrationRequested("sensor-7"));
```

An external caller uses the identical operation:

```java
process.evolve(new SensorCalibrationRequested("sensor-7"));
```

At the next planning tick:

1. the planner sees the root mission and calibration occurrence;
2. the planner chooses between root work and the occurrence;
3. if it chooses calibration, it returns a child mission;
4. the runtime creates a child;
5. the selected planner plans and executes calibration in that child;
6. child-local calibration products disappear with the child;
7. the planner returns `CompleteEpisode`;
8. the runtime consumes that occurrence; and
9. a later calibration occurrence can run independently.

If calibration cannot proceed, the planner returns `AwaitEpisode`. The
episode remains STUCK. A later `ctx.evolve(...)` or
`process.evolve(...)` can create work whose result changes the world, after
which the planner reconsiders calibration.

## Proposed Work Streams

### 1. Planner session and repeatable child episodes

- extend `Planner` with the session interface;
- implement native sessions for GOAP, Utility, and Hybrid;
- add `withEvolving(...)`;
- add both `ctx.evolve(...)` and `process.evolve(...)`;
- implement occurrence identity and lineage;
- execute every episode attempt in a child;
- keep child writes local by default;
- consume occurrences on planner-directed completion; and
- preserve default-mode behavior.

### 2. STUCK, wake, and external scheduling

- implement `AwaitEpisode`;
- maintain world revisions;
- schedule parked processes after `process.evolve(...)`;
- support `AnyRevision` and planner-provided interests;
- retain STUCK occurrences without inferred blocker analysis; and
- add explicit application-state revision notification.

### 3. Observability

- expose occurrence, episode, attempt, child, revision, and directive
  events;
- preserve planner-provided obstruction data verbatim; and
- correlate wake/run cycles under one root process id.

### 4. Cooperative interruption

- allow explicit urgent-evolution policy;
- target the deepest active descendant;
- expose action-scoped cooperative cancellation; and
- return all post-yield selection to the planner.

### 5. Open Evolving

- consult an `ObjectiveAuthor` on planner-reported obstructions;
- validate and approve policy revisions;
- support authored typed objectives; and
- use the same `ctx.evolve()` / `process.evolve()` occurrence ingress.

### 6. Process-local scope expansion

- add declared capabilities to one running process;
- validate atomic scope and objective revisions; and
- notify or reopen the planner session without runtime derivation.

### 7. Recurring goals

- model standing recurrence explicitly;
- execute recurrences in fresh children; and
- leave recurrence selection and planning with the selected planner.

### 8. Example application

- show standing state modeled with conditions;
- evolve the same calibration request type at least twice;
- demonstrate both internal and external evolve;
- demonstrate STUCK followed by an evolution that resolves it; and
- complete only through the root mission.

## Acceptance Criteria

### Planner ownership

- No new `PlannerType` exists.
- GOAP, Utility, and Hybrid implement planner sessions natively.
- Evolving Mode does not walk action preconditions, effects, goals,
  producers, consumers, or cycles.
- Evolving Mode does not compare costs, values, plans, or goals.
- Evolving Mode does not filter an agent’s actions for a child.
- Evolving Mode does not infer missing facts or capabilities.
- Evolving Mode does not synthesize a retry or repair decision.
- A planner without session support fails clearly under
  `withEvolving(...)`.
- No generic derived-rule fallback exists.

### Repeatability and consumption

- Every accepted evolve call creates a distinct occurrence.
- Equal facts and repeated publication of the same instance remain
  distinct.
- Every episode attempt runs in a fresh child process.
- Episode actions never run in the root process.
- Child-local working memory never leaks into a later occurrence.
- Completion consumes exactly the selected occurrence.
- A later occurrence can be handled independently.
- No inferred consumable type set is maintained.
- Standing state crosses the child seam only explicitly.

### Unified evolve

- `ctx.evolve(fact)` and `process.evolve(fact)` use one ingress
  implementation and have the same occurrence semantics.
- Both return an occurrence identity.
- Both reach the same planner-session input.
- Internal evolve records action and episode lineage.
- External evolve is thread-safe and queues for a planning tick.
- There is no `process.publish()` occurrence path.
- An evolve call never performs goal-graph validation.

### STUCK and repair

- Only the planner can declare an episode STUCK.
- A STUCK episode retains its occurrence.
- A planner may wait for a precise interest or any revision.
- The runtime records planner obstruction data without augmenting it.
- A later evolve advances the world revision and wakes eligible planning.
- Another episode can change the world and enable the STUCK episode.
- Open Evolving consumes planner-reported obstruction data.

### Completion

- An episode completion never completes the root process.
- Only `CompleteProcess` from the planner completes an evolving root
  process.
- No objective means the process may wait indefinitely.
- Default-mode process completion remains unchanged.

### Architecture enforcement

Architecture tests should enforce that the evolving runtime:

- depends on planner interfaces, not concrete GOAP, Utility, or Hybrid
  implementations;
- cannot access action-graph traversal or condition-graph analysis;
- cannot inspect a `ChildMission` or `PlannerExecution`;
- cannot call goal-value or plan-cost comparison helpers; and
- can be tested entirely by feeding planner directives and asserting
  mechanical lifecycle effects.

Planner contract tests should separately verify each built-in planner’s
routing, selection, STUCK, repair, and completion behavior.

## Validation Rules

- `withEvolving(...)` and ephemeral-process options are incompatible when
  the process implementation cannot retain occurrence and planner-session
  state.
- A root objective reference is canonicalized against the declared scope
  using planner-owned or shared declaration validation, not episode graph
  derivation.
- `evolve()` on a non-evolving or terminal process fails immediately.
- Accepted occurrences remain owned until completed or cancelled.
- Lifecycle transitions must be legal and evented.
- A `RunEpisode` directive must reference an active episode.
- Every `RunEpisode` creates a child.
- A directive exceeding declared child capacity is an interface violation;
  the runtime does not choose a replacement.
- A child uses the configured `PlannerType`.
- Framework children do not inherit evolving mode.
- Explicitly created children may declare evolving mode themselves.
- Authored policy and scope revisions install only at planning ticks.
- No runtime-authored executable code is permitted.

## Non-Goals

- Do not introduce `PlannerType.EVOLVING`.
- Do not preserve an unchanged planner interface at the cost of planner
  reconstruction elsewhere.
- Do not derive episode rules from action or condition graphs.
- Do not expose graph-derived evolvable type sets, chains, consumables, or
  exclusions.
- Do not gate root actions using derived episode membership.
- Do not force GOAP for episode children.
- Do not add occurrence facts to root standing state.
- Do not infer persistence from output types.
- Do not treat Open/Supervisor mode as the event loop.
- Do not generate executable code at runtime.
- Do not mutate global agent declarations.
- Do not solve pub/sub fan-out or multi-process event routing in the first
  implementation.
- Do not require durable process rehydration in the first implementation,
  while keeping occurrence identity and the planner-session interface
  compatible with a later durable adapter.
- Do not suspend and restore a partially executed action. Cooperative
  interruption means cancel, observe the outcome, and let the planner
  decide again.

## Architectural Decision Summary

The decisive choices are:

1. An episode is a persistent occurrence-backed intention.
2. The root process is not an episode.
3. Every episode attempt runs in a fresh child process.
4. Child-local state is consumed structurally.
5. Standing state crosses the child seam explicitly.
6. Internal and external publication use `ctx.evolve()` and
   `process.evolve()` with identical semantics.
7. The selected planner owns every planning decision through a session.
8. Planner implementations may change.
9. No new `PlannerType` is introduced.
10. If the planner cannot answer a planning question, Evolving Mode waits
    or reports the limitation; it never invents the answer.

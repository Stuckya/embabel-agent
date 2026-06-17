# Evolving Mode Event-Driven Handoff

Draft handoff for the `codex/evolving-mode` work. This document records the
desired public shape after local dogfooding, while keeping the example domain
generic.

## Problem Statement

Event-driven applications need a boring, typed, deterministic way to let a
running Embabel process react to external state without repeatedly invoking Open
or Supervisor mode and without asking consumers to manage blackboard latches.

The public model should be:

```text
external event/state
  -> process fact ingress
  -> optional fact derivation pass
  -> published or derived domain facts
  -> evolution policy maps selected facts to runtime goals
  -> existing GOAP / Utility / Hybrid planner selects real actions
```

This preserves Embabel's existing action/fact/goal model while giving Evolving
Mode a first-class runtime mechanism.

## Core Principle

Declared agent and capability metadata should remain immutable.

A running process may add, remove, or reprioritize process-local runtime goals.
Those runtime goals are not mutations of `@Agent` declared goals. They are active
objectives for this process instance.

```text
@Agent / @EmbabelComponent metadata = stable capability declaration
RuntimeGoal = process-local active objective
EvolutionPolicy = maps facts to runtime goals
```

This is how Evolving Mode can satisfy the README direction of "add further
goals" without rewriting user declarations at runtime.

## Why `trigger` Was Limiting

`@Action(trigger = X.class)` currently means "this action is eligible when `X`
is the most recently added blackboard value."

That is useful, but too positional for long-lived event-driven processes:

- it depends on `lastResult`, so a prefix action can overwrite the trigger before
  the triggered action runs
- it does not compose cleanly inside forward GOAP plans
- it does not define process ingress, dedupe, wakeup, coalescing, lifecycle, or
  scope
- it pushed consumers toward activation keys, edge tracking, and manual rearming

Recommendation: keep `trigger` as a low-level reactive action feature, but do
not make it the main Evolving Mode event-driven API.

## Core Public Shape

The core Evolving Mode proposal can land as fact ingress, evolution policy, and
process-local runtime goals. The fact derivation layer below is a useful next
layer for better developer experience, but it should not be a prerequisite for
the core runtime-goal mechanism.

```java
EvolvingInvocation.on(agentPlatform)
    .withScope(AgentScopeBuilder.fromInstances(
        bufferCapabilities,
        navigationCapabilities,
        storageCapabilities,
        collectionCapabilities,
        hazardResponseCapabilities
    ))
    .withEventSource(domainEvents)  // Optional adapter over fact ingress
    .withEvolution(evolution -> evolution
        .onFact(BufferFlushNeeded.class)
            .addRuntimeGoal(BufferFlushed.class)
            .resumable()

        .onFact(HazardDetected.class)
            .addRuntimeGoal(HazardHandled.class)
            .interrupt()

        .onFact(TransientOpportunity.class)
            .addRuntimeGoal(OpportunityHandled.class)
            .resumable()
            .expires(Duration.ofSeconds(30))
    )
    .run(objective);
```

`.addRuntimeGoal(BufferFlushed.class)` compiles to a process-local agenda
entry wrapping a canonical goal from the active scope. Ambiguous output-type
matches should fail unless the rule names the declared goal explicitly.

Ingress should feel like normal fact publication:

```java
process.facts().publishState(new BufferSnapshot(...));
process.facts().publishEvent(new HazardDetected(...));
```

Suggested semantics:

- `publishState`: current truth, latest/coalesced by type or key
- `publishEvent`: this happened; process once unless repeated explicitly
- facts become visible at the next planning tick
- consumers should not manage activation keys or clear latches manually

Event identity must be explicit enough for repeat behavior to be deterministic:
use framework-generated occurrence identity, caller-supplied ids, or an explicit
"always fire" mode.

## Next Layer: Fact Derivation

Fact derivation is a separate layer on top of the core Evolving Mode proposal.
It is not required to add runtime goals from already-published facts, but it
removes a major source of consumer friction in event-driven systems.

Pure fact derivation should not be modeled as ordinary planner work.

These have different execution models:

```text
World-affecting action:
  navigate, store, collect, respond to hazards, call API, write file

Fact derivation:
  BufferSnapshot -> BufferFull
  Objective -> StorageDestination
  BufferFull + StorageDestination -> BufferFlushNeeded
```

A world-affecting action should be planner-selected.

A fact derivation should run before planning, cheaply and deterministically,
without competing with real work.

Nearby Embabel concepts are not quite enough:

- `@Condition` is planning-time and cheap, but boolean and should not mutate the
  blackboard
- `@Action(readOnly = true)` can derive objects, but is still planner-selected
- `@Action(trigger = X.class)` is reactive, but `lastResult`-based

Possible API:

```java
@DerivesFact
Optional<BufferFlushNeeded> bufferFlushNeeded(
    BufferSnapshot buffer,
    StorageDestination destination
) {
    return buffer.isFull()
        ? Optional.of(new BufferFlushNeeded(destination))
        : Optional.empty();
}
```

Framework-owned lifecycle:

- if the derivation returns a fact, publish or refresh that derived fact
- if the derivation returns empty, hide or retract the previous fact from that
  derivation
- run at planning ticks or when inputs change
- no external side effects
- does not compete with planner-selected actions

This removes sentinel facts such as `BufferFlushNeeded.none()` and manual lifecycle
APIs such as `clearActivationKey("buffer-full")`.

## Fact Derivation Is A Dataflow Graph

The examples are already chained:

```text
Objective -> StorageDestination
BufferSnapshot + StorageDestination -> BufferFlushNeeded
```

That means `@DerivesFact` is not just "run all derivations." It is a small
reactive dataflow graph. The implementation needs explicit rules for:

- ordering: topological sort or iterate to a fixpoint
- cycles: detect and reject, or define bounded iteration behavior
- provenance: track which derivation produced which fact
- retraction: retract only facts produced by the same derivation/input key
- conflicts: define precedence when a fact is both ingested and derivable
- multiplicity: define whether inputs are latest-only, keyed-by, explicit joins,
  or Cartesian products

Without this, multi-step derivations will have nondeterministic ordering and
unclear retraction semantics.

## Singleton And Keyed Facts

`Optional<T>` is a good shape for singleton level facts:

```text
BufferSnapshot(full) -> Optional<BufferFlushNeeded>
BufferSnapshot(notFull) -> Optional.empty()
```

But event-driven systems also need keyed set facts:

```text
TransientOpportunity(id=414)
TransientOpportunity(id=415)
```

The derivation model should support retracting one keyed fact without retracting
the whole set.

Possible shape:

```java
@DerivesFact
DerivedFacts<TransientOpportunity> transientOpportunities(ObservationSnapshot snapshot) {
    return DerivedFacts.keyedBy(TransientOpportunity::id, snapshot.opportunities());
}
```

If keyed/occurrence facts stay on a separate ingress path, document that
explicitly. The public model should not imply that singleton `Optional<T>`
derivations cover every event-driven use case.

## Capability Design Rule

Reusable capabilities should not depend on specific objective types.

Avoid:

```java
@DerivesFact
BufferFlushNeeded bufferFlushNeeded(BufferSnapshot buffer, CollectSamplesUntil objective) {
    return new BufferFlushNeeded(objective.storageDestination());
}
```

Prefer:

```java
@DerivesFact
StorageDestination storageDestination(CollectSamplesUntil objective) {
    return objective.storageDestination();
}

@DerivesFact
Optional<BufferFlushNeeded> bufferFlushNeeded(
    BufferSnapshot buffer,
    StorageDestination destination
) {
    return buffer.isFull()
        ? Optional.of(new BufferFlushNeeded(destination))
        : Optional.empty();
}
```

Generic module shape:

```text
Buffer / capacity capability:
  BufferSnapshot -> BufferFull

Objective facts:
  Objective -> StorageDestination

Need derivation:
  BufferFull + StorageDestination -> BufferFlushNeeded

Storage capability:
  BufferFlushNeeded -> ArrivedAt -> BufferFlushed
```

This keeps reusable capability modules objective-agnostic.

## Runtime Goals

Runtime goals should usually be created by evolution policy, not by ordinary
actions.

Preferred:

```java
.withEvolution(evolution -> evolution
    .onFact(BufferFlushNeeded.class)
        .addRuntimeGoal(BufferFlushed.class)
        .includeFact(BufferFlushNeeded.class)
        .resumable()
)
```

Avoid making normal capabilities return `GoalRequest` by default. That mixes
business capability logic with orchestration.

Action-returned goal requests can remain as an advanced escape hatch for
deliberative or discovery work:

```java
@Action
GoalRequest<ResearchCompleted> discoverAdditionalNeed(ResearchReport report) {
    ...
}
```

The normal pattern should be:

```text
ingress and derivations emit facts
evolution policy maps facts to runtime goals
planner satisfies runtime goals
```

## Runtime Goal Lifecycle

The lifecycle of runtime goals should be framework-owned.

When an evolution rule says `BufferFlushNeeded -> BufferFlushed runtime goal`,
the framework should define what happens when `BufferFlushNeeded` disappears.

Recommended default:

- when the source fact retracts, stop planning new work toward the runtime goal
- do not interrupt an active action by default
- interrupt only when the rule is marked `interrupt()` or explicitly configured
  to cancel on retraction

This cannot be left to consumer-side `none()` facts or manual clear calls.

## Execution-Level Interrupts

Plan-level selection is not enough for interrupting work.

If `HazardDetected` arrives while a long-running ordinary action is already
executing, it is not enough for the next plan to prefer `HazardHandled`. The
running action must have a way to yield or be interrupted at a cooperative
checkpoint.

The same issue applies when a runtime goal is retracted mid-action. For example,
`BufferFlushNeeded` might disappear while `navigateToDestination` is still
running.

Evolving Mode therefore needs an execution-level interrupt contract in addition
to runtime goal arbitration:

- long-running actions observe a cancellation/interrupt signal
- actions define safe yield checkpoints
- an `interrupt()` runtime goal trips that signal
- the process replans after the action exits at a checkpoint

Acceptance test to add:

```text
Given a long-running ordinary action is executing
When a hazard fact enters the process
Then the ordinary action exits within N ticks/checkpoints
And the interrupting runtime goal is planned next
```

Without this test, "hazards interrupt ordinary work" can pass at the planner level
while failing in real behavior.

## Runtime Goal Behavior Vocabulary

Avoid global numeric priority as the default. Evolving Mode should change the
effective planning system visible to GOAP, Utility, or Hybrid planners rather
than introduce a second planner-independent scoring model.

Use lifecycle and execution behavior instead:

```java
.resumable()
.terminal()
.interrupt()
.expires(Duration.ofSeconds(30))
```

Suggested meanings:

- `resumable()`: completion removes this runtime goal and the process
  re-arbitrates remaining work
- `terminal()`: completion can complete the process or parent objective
- `interrupt()`: this runtime goal may request cooperative interruption of
  in-flight work at checkpoints
- `expires(Duration)`: remove this runtime goal if it remains incomplete past
  the duration

Advanced planner-specific ranking or value policies can still exist below this
interface, but they should not be the default Evolving Mode vocabulary.

## Side-Effect Discipline

`@DerivesFact` must be side-effect-free.

Nothing in Java prevents a consumer from calling a world-affecting method inside
a derivation. The framework should provide at least strong documentation and,
where possible, lint/runtime checks.

Bad:

```java
@DerivesFact
ArrivedAt arrived(BufferFlushNeeded need) {
    return navigation.advanceToward(need.destination());
}
```

Good:

```java
@Action
NavigationProgress navigate(BufferFlushNeeded need) {
    return navigation.advanceToward(need.destination());
}
```

## What To Demote From Public API

These may still exist internally, but should not be the happy path:

- `activationKey`
- `clearActivationKey`
- consumer-authored edge detection
- TTL as normal consumer-facing lifecycle
- level/occurrence trigger taxonomy as primary DX
- normal actions returning `GoalRequest` for common state transitions

The public model should be facts and runtime goals, not latches.

## Acceptance Tests

1. State ingress coalesces
   - publish multiple `BufferSnapshot`s
   - only the latest snapshot is visible to derivations and planning

2. Event ingress processes once
   - publish `HazardDetected`
   - runtime goal is added once
   - duplicate behavior is explicit and tested

3. Fact derivation creates and retracts singleton facts
   - `BufferSnapshot(full)` derives `BufferFlushNeeded`
   - `BufferSnapshot(notFull)` retracts previous `BufferFlushNeeded`
   - no manual clear call

4. Fact derivation handles keyed sets
   - derive two `TransientOpportunity` facts
   - remove one source observation
   - only the matching keyed fact is retracted

5. Runtime goal follows fact lifecycle
   - `BufferFlushNeeded` creates `BufferFlushed` runtime goal
   - when `BufferFlushNeeded` disappears, the runtime goal follows the configured
     lifecycle rule

6. Derivations do not compete with actions
   - a collecting action can continue running
   - derivation still updates facts at planning ticks

7. Execution-level interrupt
   - a hazard fact arrives during a long-running ordinary action
   - the ordinary action exits within N ticks/checkpoints
   - the interrupting runtime goal is planned next

8. Planner composes across capabilities
   - collection capability emits or depends on generic facts
   - navigation/storage capabilities satisfy the runtime goal
   - no direct calls between capabilities

9. Objective-specific facts do not leak into reusable modules
   - reusable buffer/capacity module depends only on generic facts
   - objective module emits context facts like `StorageDestination`

## Final Recommended Shape

```text
EvolvingInvocation
  composes reusable capabilities

Fact ingress
  brings external state/events into the process

Optional fact derivations
  maintain current domain facts before planning as a next layer

Evolution policy
  maps selected facts to process-local runtime goals

Existing planner
  chooses real actions from the effective planning system

Execution interrupt contract
  lets safety/retraction affect in-flight long-running actions

Runtime goal lifecycle
  owned by framework, not consumers
```

This gives event-driven users a typed path that stays Embabel-native:

- facts are still facts
- actions still do work
- derivations keep facts current
- runtime goals are planner-visible
- GOAP / Utility / Hybrid remain the planners
- Evolving Mode changes effective runtime goals, not declared agent metadata

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
  -> fact derivation pass
  -> derived domain facts
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

## Public Shape

```java
EvolvingInvocation.on(agentPlatform)
    .withScope(AgentScopeBuilder.fromInstances(
        bufferCapabilities,
        navigationCapabilities,
        storageCapabilities,
        collectionCapabilities,
        hazardResponseCapabilities
    ))
    .withEventSource(domainEvents)
    .withEvolution(evolution -> evolution
        .onFact(StorageNeeded.class)
            .addRuntimeGoal(StorageCompleted.class)
            .lane(Lane.ECONOMIC)
            .resumable()

        .onFact(HazardDetected.class)
            .addRuntimeGoal(HazardHandled.class)
            .lane(Lane.SAFETY)
            .preemptive()
    )
    .run(objective);
```

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

## Missing Module: Fact Derivation

Pure fact derivation should not be modeled as ordinary planner work.

These have different execution models:

```text
World-affecting action:
  navigate, store, collect, respond to hazards, call API, write file

Fact derivation:
  BufferSnapshot -> BufferFull
  Objective -> StorageDestination
  BufferFull + StorageDestination -> StorageNeeded
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
Optional<StorageNeeded> storageNeeded(
    BufferSnapshot buffer,
    StorageDestination destination
) {
    return buffer.isFull()
        ? Optional.of(new StorageNeeded(destination))
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

This removes sentinel facts such as `StorageNeeded.none()` and manual lifecycle
APIs such as `clearActivationKey("buffer-full")`.

## Fact Derivation Is A Dataflow Graph

The examples are already chained:

```text
Objective -> StorageDestination
BufferSnapshot + StorageDestination -> StorageNeeded
```

That means `@DerivesFact` is not just "run all derivations." It is a small
reactive dataflow graph. The implementation needs explicit rules for:

- ordering: topological sort or iterate to a fixpoint
- cycles: detect and reject, or define bounded iteration behavior
- provenance: track which derivation produced which fact
- retraction: retract only facts produced by the same derivation/input key
- conflicts: define precedence when a fact is both ingested and derivable

Without this, multi-step derivations will have nondeterministic ordering and
unclear retraction semantics.

## Singleton And Keyed Facts

`Optional<T>` is a good shape for singleton level facts:

```text
BufferSnapshot(full) -> Optional<StorageNeeded>
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
StorageNeeded storageNeeded(BufferSnapshot buffer, CollectSamplesUntil objective) {
    return new StorageNeeded(objective.storageDestination());
}
```

Prefer:

```java
@DerivesFact
StorageDestination storageDestination(CollectSamplesUntil objective) {
    return objective.storageDestination();
}

@DerivesFact
Optional<StorageNeeded> storageNeeded(
    BufferSnapshot buffer,
    StorageDestination destination
) {
    return buffer.isFull()
        ? Optional.of(new StorageNeeded(destination))
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
  BufferFull + StorageDestination -> StorageNeeded

Storage capability:
  StorageNeeded -> ArrivedAt -> StorageCompleted
```

This keeps reusable capability modules objective-agnostic.

## Runtime Goals

Runtime goals should usually be created by evolution policy, not by ordinary
actions.

Preferred:

```java
.withEvolution(evolution -> evolution
    .onFact(StorageNeeded.class)
        .addRuntimeGoal(StorageCompleted.class)
        .includeFact(StorageNeeded.class)
        .lane(Lane.ECONOMIC)
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

When an evolution rule says `StorageNeeded -> StorageCompleted runtime goal`,
the framework should define what happens when `StorageNeeded` disappears.

Open decision:

- remove the runtime goal immediately
- mark the runtime goal inactive
- allow the active action to finish but prevent new planning toward it
- interrupt the active action if the goal is preempted or retracted

This cannot be left to consumer-side `none()` facts or manual clear calls.

## Execution-Level Preemption

Plan-level preemption is not enough.

If `HazardDetected` arrives while a long-running economic action is already
executing, it is not enough for the next plan to prefer the safety goal. The
running action must have a way to yield or be interrupted.

The same issue applies when a runtime goal is retracted mid-action. For example,
`StorageNeeded` might disappear while `navigateToStorage` is still running.

Evolving Mode therefore needs an execution-level interrupt contract in addition
to runtime goal arbitration:

- long-running actions observe a cancellation/preemption signal
- actions define safe yield checkpoints
- safety lane preemption trips that signal
- the process replans after the action exits at a checkpoint

Acceptance test to add:

```text
Given a long-running economic action is executing
When a safety fact enters the process
Then the economic action exits within N ticks/checkpoints
And the safety runtime goal is planned next
```

Without this test, "safety preempts economic work" can pass at the planner level
while failing in real behavior.

## Priority Model

Avoid global numeric priority as the default.

This becomes cross-skill calibration debt:

```java
priority(Priority.economic(60))
priority(Priority.opportunity(70))
```

Prefer lanes plus small local ordering:

```java
.lane(Lane.SAFETY)
.order(Order.URGENT)

.lane(Lane.ECONOMIC)
.order(Order.NORMAL)

.lane(Lane.OPPORTUNITY)
.order(Order.LOW)
```

Safety should be able to preempt economic work without relying on numeric tuning.

## Side-Effect Discipline

`@DerivesFact` must be side-effect-free.

Nothing in Java prevents a consumer from calling a world-affecting method inside
a derivation. The framework should provide at least strong documentation and,
where possible, lint/runtime checks.

Bad:

```java
@DerivesFact
ArrivedAt arrived(StorageNeeded need) {
    return navigation.advanceToward(need.destination());
}
```

Good:

```java
@Action
NavigationProgress navigate(StorageNeeded need) {
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
   - `BufferSnapshot(full)` derives `StorageNeeded`
   - `BufferSnapshot(notFull)` retracts previous `StorageNeeded`
   - no manual clear call

4. Fact derivation handles keyed sets
   - derive two `TransientOpportunity` facts
   - remove one source observation
   - only the matching keyed fact is retracted

5. Runtime goal follows fact lifecycle
   - `StorageNeeded` creates `StorageCompleted` runtime goal
   - when `StorageNeeded` disappears, the runtime goal follows the configured
     lifecycle rule

6. Derivations do not compete with actions
   - a collecting action can continue running
   - derivation still updates facts at planning ticks

7. Execution-level safety preemption
   - a safety fact arrives during a long-running economic action
   - the economic action exits within N ticks/checkpoints
   - the safety runtime goal is planned next

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

Fact derivations
  maintain current domain facts before planning

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

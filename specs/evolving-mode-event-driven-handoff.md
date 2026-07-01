# Evolving Mode Event-Driven Handoff

> **Status (2026-07-01):** this handoff served its purpose;
> `evolving-mode-github-issue-draft.md` is canonical. Notable deltas in the
> issue: interruption is a proposed rule semantic (`interruptCurrentAction()`,
> Sub-Issue 4) rather than "a later extension"; six work streams; goal targets
> spelled `GoalTarget.output(...)` / `GoalTarget.named(...)`.

Draft handoff for the `codex/evolving-mode` work. This document records the
desired public shape after local dogfooding, while keeping the example domain
generic.

## Problem Statement

Long-lived applications need a boring, typed, deterministic way to let a running
Embabel process remember and complete selected units of follow-up work without
repeatedly invoking Open or Supervisor mode and without asking consumers to
manage blackboard latches.

The public model should be:

```text
external events / sensors
  -> application-owned state modules
  -> @Condition / action inputs for current truth

occurrence facts
  -> EvolutionPolicy maps selected facts to runtime goals
  -> consume / rearm / resume

user or LLM objectives
  -> ObjectiveAuthor / ObjectivePlan
  -> process-local runtime goals
```

This preserves Embabel's existing action/fact/goal model while giving Evolving
Mode a first-class process-local objective mechanism.

## Core Principle

Declared agent and capability metadata should remain immutable.

A running process may add or remove process-local runtime goals. Those runtime
goals are not mutations of `@Agent` declared goals. They are active objectives
for this process instance; normal GOAP, Utility, or Hybrid arbitration decides
what runs.

```text
@Agent / @EmbabelComponent metadata = stable capability declaration
RuntimeGoal = process-local active objective
EvolutionPolicy = maps facts to runtime goals
```

This is how Evolving Mode can satisfy the README direction of "add further
goals" without rewriting user declarations at runtime.

## Maintainer Feedback Shape

The proposal should separate three concerns that were easy to blur together:

- declared capabilities: immutable `@Agent` and `@EmbabelComponent` actions,
  conditions, and declared goals
- application-owned state modules: consumer-owned current truth exposed through
  action inputs and `@Condition`
- runtime facts: selected process-local facts available at planning ticks
- runtime goals: process-local active objectives added or removed by an
  evolution policy

This keeps the "mutable" part of Evolving Mode inside the running process. The
effective planning system for that process may change, but Embabel does not
rewrite user-declared agent metadata.

Runtime facts are a substrate rather than the whole feature. Internally driven
evolution can start with facts produced by normal actions. Externally driven
evolution needs a sanctioned ingress path so a known running process can receive
selected facts from the consumer application. High-frequency or reversible state
should normally stay in application-owned state modules and be exposed through
`@Condition` or action inputs.

Normal Embabel planning should handle the main loop from current state. Evolving
Mode should be reserved for side work or newly discovered objectives that need
process-local lifecycle: track, retry, complete, consume, and resume.

Incremental delivery can therefore be framed as:

1. internal action-produced runtime facts: normal action outputs activate
   process-local runtime goals
2. selected external facts: facts published by the consumer application enter the
   same process safely at planning seams
3. observability support: each wake-up remains attached to the existing
   process/session, with clean turn boundaries and runtime-goal lifecycle events

Pub/sub fan-out is related but separate. This proposal only needs point-to-point
fact publication to one known running process.

## Practical Use Case

A concrete consumer scenario is:

```text
Collect samples in Zone A until 500 samples are stored.
```

The process runs over scoped capabilities such as:

- collection
- navigation
- storage
- hazard response
- buffer or workspace state

The collection capability should not directly call storage or navigation. It
should produce or consume domain facts, while Embabel composes the scoped
capabilities through the planner.

Example runtime facts:

- `BufferSnapshot`
- `BufferFull`
- `StorageNeeded`
- `ArrivedAtStorage`
- `StorageCompleted`
- `HazardDetected`
- `HazardHandled`

Example evolution rules:

```java
.onFact(StorageNeeded.class)
    .handleWithGoal(storageCompletedGoal)
    .resumable()

.onFact(HazardDetected.class)
    .handleWithGoal(hazardHandledGoal)
    .resumable()
```

These rules are for occurrence facts that should finish and consume their
rule-local activation. Memoryless level state such as "buffer is full" should usually
be modeled with `@Condition`, ordinary action values, and standing `NIRVANA`
utility work unless the consumer needs a remembered runtime goal.

The resulting loop is:

```text
external snapshot/event or action output
  -> runtime fact
  -> evolution policy
  -> process-local runtime goal
  -> existing GOAP / Utility / Hybrid planner
  -> declared action
```

The same engine should support:

- buffer full level -> storage actions become achievable or valuable through
  conditions and ordinary arbitration
- committed storage-needed occurrence -> storage runtime goal if the reaction
  should finish across ticks and consume its rule-local activation
- storage complete -> collection resumes
- hazard detected -> normal arbitration can select hazard response ahead of
  ordinary work when the handler goal is modeled accordingly
- target reached -> complete

## Why `trigger` Was Limiting

`@Action(trigger = X.class)` currently means "this action is eligible when `X`
is the most recently added blackboard value."

That is useful, but too positional for long-lived event-driven processes:

- it depends on `lastResult`, so a prefix action can overwrite the trigger before
  the triggered action runs
- it does not compose cleanly inside forward GOAP plans
- a trigger-gated chain can park `STUCK` before any action executes, because
  the planner cannot route through an event that is not already the latest
  result
- it does not define process ingress, dedupe, wakeup, coalescing, lifecycle, or
  scope
- it pushed consumers toward activation keys, edge tracking, and manual rearming

Recommendation: keep `trigger` as a low-level reactive action feature, but do
not make it the main Evolving Mode event-driven API.

## Core Public Shape

The core Evolving Mode proposal can land as an evolution policy over runtime
facts and process-local runtime goals. External fact ingress and fact derivation
are important developer-experience layers, but neither should obscure the core
runtime-goal mechanism.

```java
EvolvingInvocation.on(agentPlatform)
    .withScope(AgentScopeBuilder.fromInstances(
        bufferCapabilities,
        navigationCapabilities,
        storageCapabilities,
        collectionCapabilities,
        hazardResponseCapabilities
    ))
    .withEvolution(evolution -> evolution
        .onFact(StorageNeeded.class)
            .handleWithGoal(storageCompletedGoal)
            .resumable()

        .onFact(HazardDetected.class)
            .handleWithGoal(hazardHandledGoal)
            .resumable()
    )
    .run(objective);
```

`.handleWithGoal(storageCompletedGoal)` compiles to a process-local agenda
entry wrapping a canonical goal from the active scope. Earlier sketches used
`.handleWith(StorageCompleted.class)` as output-type shorthand; that should be
optional and should fail when the output type is ambiguous.

An optional event-source adapter can sit above fact ingress for consumer applications
that already have a domain event stream:

```java
EvolvingInvocation.on(agentPlatform)
    .withScope(scope)
    .withEventSource(domainEvents)  // Optional adapter over fact ingress
    .withEvolution(evolution)
    .run(objective);
```

External ingress should feel like normal fact publication:

```java
// Conservative spelling.
process.ingress().publish(new HazardDetected(...));

// Friendlier spelling if Embabel wants a facts facade.
process.facts().publish(new HazardDetected(...));
```

The local throwaway POC used this Java/Kotlin spelling:

```java
process.getIngress().publish(new HazardDetected(...));
```

```kotlin
process.ingress.publish(HazardDetected(...))
```

That POC spelling was backed by a local `BlackboardIngress` type. It is not an
upstream Embabel API and should not be PR'd as-is. The useful contract is a
sanctioned process-local fact ingress seam for selected external facts.
`process.ingress()` is probably the most conservative upstream spelling because
it names the seam without introducing a first-class `Facts` vocabulary.
`process.facts()` is a good DX option if maintainers want a friendlier facade
over blackboard object publication. Either way, it should be a wrapper over the
same ingress seam rather than a separate state channel. The mapping is:

- selected fact publication maps to occurrence-style or append-mode ingress with
  explicit duplicate behavior
- `.onFact(E).handleWithGoal(G)` compiles to an agenda entry wrapping a
  canonical scoped goal for `G`
- the local POC now includes a minimal `EvolutionPolicy` proving that visible
  process facts can activate process-local runtime goals without raw activation
  keys
- raw `activationKey`, manual `clearActivationKey(...)`, `ActivationTrigger`,
  and TTL/latch details should be hidden from the normal policy API

If `ActivationTrigger` survives, it should be internal, experimental, or a
low-level test hook. It should not be the main user model.

Do not bless direct blackboard mutation as the phase 2 happy path:

```java
process.blackboard().add(new HazardDetected(...));
```

Raw blackboard mutation is too shallow for external ingress. It does not carry
planning-tick timing, wake-up behavior, fact observation identity,
duplicate semantics, or observability.

Suggested semantics:

- publication means this fact should enter the running process; event/state
  semantics can be configured by policy, metadata, or optional publication
  options rather than by separate happy-path method names
- facts become visible at the next planning tick
- consumers should not manage activation keys or clear latches manually

Fact identity must be explicit enough for repeat behavior to be deterministic:
use framework-generated fact observation identity, caller-supplied ids, or an explicit
"always fire" mode.

## Blackboard And Ingress Boundary

The blackboard is process-local working memory for an `AgentProcess`. It should
not be treated as a mutable world-state database, pub/sub bus, or consumer-owned
latch system.

Action inputs are resolved from the blackboard. Action outputs are automatically
appended to it. Objects are ordered and append-only: the latest visible object
of a type is the default match, named bindings are available when type alone is
ambiguous, and hiding an object removes it from future planning/API visibility
without deleting process history.

Planning conditions are separate booleans, normally supplied by `@Condition`.
They are not ordinary blackboard objects and should not be reused as event
activation latches.

Selected external async facts should enter through a sanctioned process-local
ingress API. The local POC called this `BlackboardIngress`; upstream should treat
the name and exact shape as open. Ingress queues publication safely and drains at
planning ticks, where the facts become ordinary blackboard facts for planning.
Ingress is not a replacement for application-owned state modules.

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

If a consumer models mutable level state directly as a blackboard fact rather
than as a framework-owned derivation, the consumer owns that fact's lifecycle.
When the level falls false, hide or replace the visible fact explicitly through
blackboard hiding, latest/coalesced ingress, TTL, or another configured
lifecycle rule. Runtime-goal completion can consume its own source occurrence,
but it should not infer retraction for arbitrary level facts.

## Singleton And Keyed Facts

`Optional<T>` is a good shape for singleton level facts:

```text
BufferSnapshot(full) -> Optional<BufferFlushNeeded>
BufferSnapshot(notFull) -> Optional.empty()
```

But event-driven systems also need keyed set facts:

```text
HazardDetected(id=414)
HazardDetected(id=415)
```

The derivation model should support retracting one keyed fact without retracting
the whole set.

Possible shape:

```java
@DerivesFact
DerivedFacts<HazardDetected> hazards(ObservationSnapshot snapshot) {
    return DerivedFacts.keyedBy(HazardDetected::id, snapshot.hazards());
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

This keeps reusable `@Agent` or `@EmbabelComponent` instances objective-agnostic.

## Runtime Goals

Runtime goals should usually be created by evolution policy, not by ordinary
actions.

Preferred:

```java
.withEvolution(evolution -> evolution
    .onFact(StorageNeeded.class)
        .handleWithGoal(storageCompletedGoal)
        .resumable()
)
```

The core runtime goal is tied to the fact observation and rule-local
activation that fired the rule for dedupe, consume-on-completion, and rearm. It
does not need a payload-binding API in the first slice; handlers should re-sense
current state or read normal objective/context facts.

Avoid making normal capabilities return `GoalRequest` by default. That mixes
business capability logic with orchestration.

Handlers selected by `.onFact(...).handleWithGoal(...)` must be goal producers in
the active scope, for example by producing the goal's satisfied-by type and, in
annotation style, using `@AchievesGoal` where appropriate. Standing level
reactions should usually remain plain value-selected actions under `NIRVANA`;
marking them as achieved goals can accidentally turn normal utility work into
process completion or agenda completion.

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

## Base Policy And Objective Policy

Separate universal consumer-application policy from objective-authored policy.

Base policy is installed by the consumer application for every relevant process.
It should cover events the `ObjectiveAuthor` should not have to remember, such
as hazards, blocking prompts, and session recovery.

Objective policy is produced for a particular run. It should cover objective
completion, objective-specific event rules, scheduled interruptions, utility
defaults, and initial facts.

At launch, the framework should merge base policy and objective policy, validate
all runtime-goal targets against the active scope, and fail fast if any handler
has no scoped producing capability.

## Runtime Goal Lifecycle

The lifecycle of runtime goals should be framework-owned.

When an evolution rule says `BufferFlushNeeded -> BufferFlushed runtime goal`,
the framework should define what happens when `BufferFlushNeeded` disappears.

Recommended default:

- when the source event retracts, stop planning new work toward the runtime goal
- do not interrupt an active action by default
- interrupt only when explicitly configured to cancel on retraction
- for the internal-facts-only phase, a successful `resumable()` runtime goal
  consumes the rule-local activation that created it

This cannot be left to consumer-side `none()` facts or manual clear calls.

## Action Granularity And Replanning

The core Evolving Mode contract should be GOAP-native: adding a runtime goal
requests replanning on the next planning tick, and normal Embabel goal
arbitration decides whether that runtime goal runs before ordinary work.

For responsive behavior, long-running activities should be decomposed into
small, resumable actions whose preconditions are rechecked between steps. If
`HazardDetected` arrives while ordinary work is between action boundaries, the
next planning seam can select `HazardHandled` if that goal is modeled to win
normal arbitration.

Execution-level cancellation for irreducibly blocking actions is a later
extension, not part of the core event-goal primitive.

Hard interruption should be expressed as availability, not as an Evolving-specific
priority mechanism. Existing Embabel primitives already cover this: use
`@Condition` and `@Action(pre = ...)` so ordinary work is not achievable while a
domain condition holds, and make the recovery or hazard handler the achievable
path. Goal and action values remain useful for soft ordering when more than one
path is available.

Acceptance test to add:

```text
Given ordinary work is decomposed into resumable actions
When a hazard event enters the process
Then the runtime goal is added
And the next planning seam uses normal Embabel arbitration
And a correctly modeled hazard goal can be selected before ordinary work
```

Add a parallel hard-availability acceptance test:

```text
Given ordinary work is gated by an existing Embabel condition such as
  @Action(pre = "... && !hazardActive")
When hazardActive holds
Then ordinary work is unachievable
And the hazard or recovery handler is the achievable path
```

Without these tests, "hazards interrupt ordinary work" can accidentally become a
second Evolving-specific priority model rather than ordinary availability plus
GOAP/Utility/Hybrid arbitration.

## Observability Expectations

Evolving Mode should use the existing process/session observability model. A
process woken by ingress is not a fresh process; it is the same long-lived
process beginning another unit of work.

The observability contract should include:

- wake-ups continue the existing process and reuse the stable session id
- each wake-up has a clean turn boundary with start, end, and error handling
- ingress facts record their source and correlation metadata as span attributes
- runtime goals emit lifecycle events when added, completed, retracted,
  suppressed, or rejected
- per-turn runtime state is cleaned at turn end, not only at process termination

Additional trace linking for fan-out or producer/consumer processes can be
handled separately if a pub/sub use case appears.

## Runtime Goal Behavior Vocabulary

Avoid global numeric priority as the default. Evolving Mode should change which
runtime goals are active, then let GOAP, Utility, or Hybrid planners arbitrate
normally.

Use a small runtime-goal vocabulary:

```java
.resumable()
```

Suggested meanings:

- `resumable()`: completion removes this runtime goal and the process
  re-arbitrates remaining work

Do not use numeric priority as the core Evolving Mode vocabulary. Long-running
objective completion remains `CompletionPolicy`; event expiry/TTL and
event-payload binding are later extensions.

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
- forcing consumers to choose level/occurrence trigger taxonomy before they can
  express simple `.onFact(...).handleWithGoal(...)` policies
- normal actions returning `GoalRequest` for common state transitions

The public model should be facts and runtime goals, not latches.

## Acceptance Tests

1. Internal runtime fact creates runtime goal
   - action produces `StorageNeeded`
   - evolution policy adds `StorageCompleted` as a runtime goal
   - planner satisfies it through declared scoped capabilities

2. State ingress coalesces
   - publish multiple `BufferSnapshot`s
   - only the latest snapshot is visible to derivations and planning

3. Event ingress processes once
   - publish `HazardDetected`
   - runtime goal is added once
   - duplicate behavior is explicit and tested

4. Fact derivation creates and retracts singleton facts
   - `BufferSnapshot(full)` derives `BufferFlushNeeded`
   - `BufferSnapshot(notFull)` retracts previous `BufferFlushNeeded`
   - no manual clear call

5. Fact derivation handles keyed sets
   - derive two `HazardDetected` facts
   - remove one source observation
   - only the matching keyed fact is retracted

6. Runtime goal follows fact lifecycle
   - `BufferFlushNeeded` creates `BufferFlushed` runtime goal
   - when `BufferFlushNeeded` disappears, the runtime goal follows the configured
     lifecycle rule

7. Derivations do not compete with actions
   - a collecting action can continue running
   - derivation still updates facts at planning ticks

8. Replanning uses normal arbitration
   - a hazard event enters the process
   - the hazard runtime goal is added
   - the next planning seam uses normal Embabel arbitration
   - a correctly modeled hazard goal can be selected before ordinary work

9. Planner composes across capabilities
   - collection capability emits or depends on generic facts
   - navigation/storage capabilities satisfy the runtime goal
   - no direct calls between capabilities

10. Objective-specific facts do not leak into reusable modules
   - reusable buffer/capacity module depends only on generic facts
   - objective module emits context facts like `StorageDestination`

11. Observability preserves process/session continuity
   - ingress wake-up starts a new turn on the existing process/session
   - the turn closes cleanly on success, error, or no-op wake-up
   - runtime-goal lifecycle events are visible

## Final Recommended Shape

```text
EvolvingInvocation
  composes reusable capabilities

Fact ingress
  brings selected external facts into the process

Optional fact derivations
  maintain current domain facts before planning as a next layer

Evolution policy
  maps selected facts to process-local runtime goals

Existing planner
  chooses real actions from the effective planning system

Action granularity and replanning
  keeps the core GOAP-native; execution-level cancellation is a later extension

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

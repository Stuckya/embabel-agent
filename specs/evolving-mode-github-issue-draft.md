# GitHub Issue Draft: Evolving Mode Essentials

Draft issue text for the post-1.0.0 Evolving Mode discussion.

Discussion context: https://github.com/embabel/embabel-agent/discussions/1725

## Reply Draft For The Practical Use-Case Question

This is a short reply to the latest discussion question before opening the issue.

> @simeshev, happy to.
>
> The practical shape is a long-lived event-driven process with one stable objective and reactive replanning at planning boundaries.
>
> My real domain is a long-lived agent driving a live external system. I am abstracting it here as sample collection so the discussion stays generic.
>
> Example: start a process with the objective "collect samples in Zone A until 500 samples are stored." The process has scoped capabilities for collection, navigation, storage, hazard response, and buffer/workspace state.
>
> While it is running:
>
> - when the local workspace becomes full, storage actions become achievable or valuable through conditions and normal planner action selection
> - when storage completes, collection resumes
> - when a hazard appears, normal planner action selection can choose hazard response ahead of ordinary work if the handler goal is modeled accordingly
> - when one-time follow-up work needs handling, evolution policy can install a runtime goal that finishes once and marks its rule-local activation consumed
> - when 500 samples are stored, the process completes
>
> I do not want the collection capability to call the storage or navigation capability directly. I want capabilities to publish or consume typed facts and let Embabel compose the scoped capabilities through GOAP/Utility/Hybrid.
>
> So the loop I am looking for is:
>
> ```text
> action output or external ingress
>   -> runtime fact
>   -> evolution policy
>   -> process-local runtime goal
>   -> existing planner
>   -> declared action
> ```
>
> I see the incremental path as:
>
> 1. internally produced facts drive runtime goals
> 2. externally published facts drive the same runtime-goal engine
> 3. observability keeps each wake-up attached to the existing process/session
>
> Pub/sub fan-out is not required for my current use case. I only need to publish facts to one known running process.

## Parent Issue

### Title

Evolving Mode Essentials: process-local runtime goals for long-lived processes

### Body

I'm proposing this as an epic for Evolving Mode. A way for a long-lived process to change which goals are active while it runs, without editing its declared agents. An action — and later an external event — produces a typed fact, an evolution policy turns selected facts into process-local runtime goals, and the existing planner achieves them with already-declared actions.

The work is broken down into phases (internal facts, external ingress, observability) and I'll open a sub-issue per work stream discussed in #1725.

### Vocabulary

This issue leans on a handful of terms throughout. They are defined once here:

- **Declared capability** - immutable `@Agent` and `@EmbabelComponent` actions, conditions, and goals.
- **Consumer application** - the application using Embabel, configuring invocation, providing scoped capabilities, and publishing external facts.
- **Runtime fact** - typed domain object on the blackboard, selected by an evolution policy. Not a new public `Fact` API.
- **Runtime goal** - a process-local objective added or removed by an evolution policy.
- **Evolution rule** - an `onFact(X).handleWithGoal(G)`-style rule: when fact `X` is observed, add one runtime goal `G` that the current process scope knows how to achieve.
- **Observed fact occurrence** - internal API, one record created per fact addition or ingress drain (_not per planning tick_). Its identity is framework-owned.
- **Rule-local activation** - the per-rule check that ensures a rule fires once per observed fact occurrence, with a managed lifecycle.
- **Modeling a fact** - if a fact describes state that remains true, like "sample tray is full," model it with `@Condition` and recheck it each planning tick. If it describes one unit of work to handle, like "store this batch," model it with `onFact(...).resumable()`.

### Motivation

The README frames Evolving Mode as the platform working with multiple goals in the same process, and modifying a running process to add further goals and agents when new needs are discovered.

I see Evolving Mode as primarily a process-lifecycle feature. The planner already knows how to choose among goals. What's missing is a way to change the effective runtime goals and completion behavior while the process runs. The slice I want to prove first is process-local runtime goal evolution. An action returns a typed fact, an evolution policy turns that fact into a runtime goal, and the existing planner chooses declared actions from the active scope.

Embabel already has strong invocation-time composition through Autonomy and scoped invocation through `UtilityInvocation` and `SupervisorInvocation`; this proposal is about the runtime gap after a process has started.

The core loop is:

```text
action output
  -> runtime fact
  -> evolution policy
  -> process-local runtime goal
  -> existing GOAP / Utility / Hybrid planner
  -> declared action
```

A normal Embabel action might return `StorageNeeded`. The evolving process observes that fact and applies an `onFact(StorageNeeded.class)` rule, adding a process-local runtime goal that the current process scope knows how to achieve. The planner then composes scoped storage and navigation actions to satisfy it.

I want external ingress to feed the same loop too, which matters for event-driven applications. As agreed in #1725 I'd like to prove the action-produced fact path first.

Evolving Mode reuses the existing planner rather than adding a new `PlannerType`. It changes the process-local set of active runtime goals and lets the planner choose declared actions from the active scope. I'm keeping the immutable-declared interpretation of the README's Evolving Mode: declared capabilities stay fixed, while runtime facts and runtime goals are the moving parts.

I'm addressing the runtime-goals half of "add further goals and agents" first. I'd make the other half, runtime mutation of the agent/capability scope, a separate follow-up.

For the agent/capability half, I'd treat that as process-local expansion, not mutation of global declarations. By expansion, I mean adding more scoped `@Agent` or `@EmbabelComponent` instances to the process at runtime, so their declared actions and goals become visible to the planner on the next planning tick. That seems like a larger initiative than runtime goals, so I'd prefer to prove the goals half first.

### Proposed Work Streams

As discussed in #1725, I've split this into a few core work streams. They do not have to be strictly sequential, but the first is the smallest green slice and should stand on its own.

1. Internal action-produced runtime facts
   - normal action outputs activate process-local runtime goals
   - no external ingress required
   - proves the core Evolving Mode engine and the README "action discovers additional goals" scenario

2. External process fact ingress
   - consumer applications can publish typed facts into one known running process
   - facts become visible at planning ticks
   - one-time work facts can feed the same evolution engine as internal runtime facts

3. Observability support
   - each wake-up continues the existing process/session
   - wake-ups have clean turn boundaries
   - runtime goals have observable lifecycle events

4. Optional future work stream: Process-local scope expansion
   - allow a running process to add more scoped `@Agent` or `@EmbabelComponent` instances
   - make their declared actions and goals visible to the planner on a later planning tick
   - validate and approve scope changes before they affect planning
   - fulfills the "add further agents" half of the README definition

I think a separate pub/sub discussion makes sense if one event needs to wake many processes, or if the publisher does not know the consuming process. I'm keeping that out of scope for Evolving Mode.

### Motivating Example

A concrete event-driven process might have a long-running objective such as:

```text
As a robot, collect samples in Zone A until 500 samples are stored.
```

The process runs over scoped capabilities / agents:

- sample collection
- robot navigation
- sample storage
- hazard response
- buffer/workspace state

I don't want the collection capability to directly call storage or navigation. It should produce or consume typed facts and let Embabel compose the scoped capabilities through the planner.

The traditional Embabel pieces:

- declared actions handle collection, navigation, storage, and hazard response
- declared goals are satisfied by normal action outputs such as `SamplesStored` and `HazardHandled`
- conditions and values still control availability and action selection

For example, `sampleTrayFull` is ongoing state: it stays true while the robot's sample tray is full and becomes false after the samples are stored. It should be modeled with `@Condition` and action preconditions, not as a runtime goal:

```java
@Condition(name = "sampleTrayFull")
boolean sampleTrayFull(SampleTraySnapshot tray) {
    return tray.count() >= tray.capacity();
}

@Action(pre = "sampleTrayFull")
AtStorage navigateToStorage(CurrentLocation current, StorageLocation storage) {
    // navigate to the storage area
}
```

In this example, `sampleTrayFull` gates whether storage/navigation actions are available. `StorageNeeded` represents a committed storage cycle that should finish once and rearm.

Evolving Mode takes over when selected typed facts should create process-local runtime goals:

- `SampleCollectionNeeded`
- `StorageNeeded`
- `HazardDetected`

Other progress facts can remain ordinary blackboard facts that completion policies, conditions, or actions read:

- `SampleCollected`
- `SamplesStored`
- `HazardHandled`

### Runtime Semantics

The higher-level policy API should compile down to a small runtime substrate, with the lifecycle details hidden from consumers:

```text
consumer policy API
  -> observed fact occurrence + runtime-goal lifecycle
  -> process-local agenda + ingress runtime
  -> existing planner
```

- Visible process facts, especially action-produced facts, feed the evolution policy. Fact ingress goes through a sanctioned process-local facade, never a sibling state channel or direct `blackboard.add(...)`.
- Adding a runtime goal requests replanning on the next planning tick. Normal Embabel planning then chooses among active runtime goals and ordinary goals.
- Consumers should not have to reason about raw activation ids, manual clearing APIs, TTLs, or activation lifecycle.

This follows Embabel's existing type, condition, binding, and planner model. It does not introduce keyed goal-instance semantics. A domain that needs key-specific matching should use existing primitives. For example: distinct types, `@Condition`, `@Action(pre = ...)`, SpEL conditions, named bindings, or coalescing facts so only one relevant source is active at a time.

I prefer `onFact(...)` over `onEvent(...)` because the mechanism is driven by ordinary action-produced runtime facts; external events become runtime facts first.

### Blackboard Model

Embabel's documentation treats the blackboard as process-local working memory for an `AgentProcess`. Not a mutable world-state database, not a pub/sub bus, not an external-event ingestion mechanism.

Most user code should not write it directly.

Blackboard objects are ordered and append-only. The latest visible object of a type is the default match; named binding is available when type alone is ambiguous. Hiding an object removes it from future planning and API visibility, but does not delete it from process history.

Conditions are separate planning booleans, normally supplied by `@Condition`. They are not ordinary blackboard objects and should not be used as the external-event ingestion mechanism.

If mutable ongoing state is modeled as a blackboard fact, the consumer owns that fact's lifecycle unless explicit ingress lifecycle options are configured. When that state is no longer true, hide or replace the visible fact. Completing a runtime goal consumes only its own rule-local activation. It does not retract other state facts or blackboard objects.

External facts from the consumer application should enter a running process through a sanctioned process-local ingress API that queues publication for a planning tick. I want Evolving Mode to preserve the boundary: external events enter through ingress, then become ordinary typed facts visible to planning.

### Ingress Naming Options

I care more about the interface than the exact name. Reasonable spellings are:

```java
process.ingress().publish(new HazardDetected(...));
```

`process.ingress()` implies that external input enters a known running process, queues safely, drains at planning ticks, and can wake the process. A public type named `ProcessIngress` or `ProcessFactIngress` could sit behind this spelling. This keeps the blackboard as process working memory rather than making it sound like the caller's mutation surface.

```java
process.facts().publish(new HazardDetected(...));
```

`process.facts()` is a friendlier consumer option. It says "publish a typed fact to this running process," which matches the action input/output mental model. The cost is that embabel-agent does not currently have a first-class `Fact` or `Facts` module; it has blackboard objects. This spelling introduces new vocabulary to the codebase.

**I wouldn't bless this as the phase 2 happy path:**

```java
process.blackboard().add(new HazardDetected(...));
```

Raw blackboard mutation is too shallow for external ingress. It does not carry planning-tick timing, wake-up behavior, observed fact occurrence identity, duplicate semantics, or observability.

### Minimal API Shape

At the parent-issue level, I'd keep the API shape intentionally small. Fuller builder and option sketches belong in the sub-issues.

```java
.withEvolution(evolution -> evolution
    .onFact(StorageNeeded.class)
        .handleWithGoal(storageCompletedGoal)
        .resumable()
);

process.ingress().publish(new HazardDetected(...));
```

Runtime goal rules should compile to process-local agenda entries. Conceptually, each entry needs:

```text
one resolved goal from the current process scope
observed fact occurrence identity
resumable completion behavior
```

A runtime rule identifies the declared goal it wants the process to achieve. The implementation canonicalizes that target against the active scope, rejects ambiguous matches, and prevents forged or lookalike goals from changing value, preconditions, or metadata.

I would start with handlers re-sensing current state or reading objective/context facts rather than relying on fact-payload delivery. Observed fact occurrence identity exists for dedupe, rule-local activation consumption, and rearm.

The core excludes `terminal()` and `expires(...)` runtime goals; long-running objectives complete through `CompletionPolicy`. Source-disappearance handling and execution-level cancellation are deferred. Small resumable actions and normal replanning cover the cases I have in mind.

This draft uses `handleWithGoal(...)` to emphasize that a rule targets a goal, not a new action. Embabel already uses `Action` for declared executable operations, and this primitive does not create one. (_Naming and the goal-target shape are open questions, below._)

### Relationship To `@Action(trigger = ...)`

I'd keep `@Action(trigger = X.class)` as an action-level reactive feature. It is useful when `X` is the latest result and the triggered action can run directly.

I don't think it should be the Evolving Mode mechanism. `trigger` is based on `lastResult`, so it is fragile as a step inside a forward GOAP plan:

- a prefix action can overwrite `lastResult` before the triggered action runs
- a planner that needs a complete static path cannot route through a trigger action whose event is not already the latest result

The boundary should be:

- use `@Action(trigger = X.class)` for simple reactive handlers where no planner-visible lifecycle is needed
- use `onFact(X).handleWithGoal(G).resumable()` when a one-time work fact should install a process-local goal, replan, complete, mark its rule-local activation consumed, and resume normal planner action selection

### Fact Matching and Rearm Semantics

This is the lifecycle piece I think Evolving Mode needs to own, not something every consumer should hand-roll.

For example, suppose an action outputs:

```text
StorageNeeded
```

and the policy says:

```java
.onFact(StorageNeeded.class)
    .handleWithGoal(storageCompletedGoal)
    .resumable()
```

The behavior I'd expect:

- the first `StorageNeeded` adds one `StorageCompleted` runtime goal.
- re-scanning the same observed `StorageNeeded` occurrence does not add another runtime goal while its activation is active or already consumed.
- when `StorageCompleted` is produced, the runtime goal completes and the rule-local activation is marked consumed.
- if `StorageNeeded` appears again later, it can fire again because the earlier activation was consumed.

Without observed fact occurrence identity and rule-local consume/rearm behavior, two bad outcomes are easy to create:

- the same observed fact occurrence keeps adding duplicate runtime goals.
- the same observed fact occurrence re-fires after completion just because the underlying blackboard object is still visible.

So the default should be simple:

- a rule fires once per observed fact occurrence.
- the same observed fact occurrence does not activate the same rule twice.
- a `resumable()` runtime goal that completes successfully marks the activation that created it consumed for that rule.
- a later matching source can fire again after the previous activation has been consumed.

Observed fact occurrence should be framework-owned: an internal process-local observation record created once per fact addition or ingress drain, not once per planning tick. "Same occurrence" means the same observation record id, not object equality, domain equality, payload value, or keyed goal satisfaction. Re-scanning the same visible blackboard object must resolve to the same observation record. A later action output or ingress publication creates a new one and can activate the rule again.

The activation bookkeeping should be internal. Consumers should not manage activation ids, consume records, or internal activation state.

Completion consumes the rule-local activation, not necessarily the underlying blackboard object. The same observed fact occurrence must not re-fire the same rule after completion. Hiding or replacing the blackboard object remains an explicit lifecycle concern.

Source disappearance before completion is real, but I wouldn't put it in the first public API. It can be a later lifecycle policy once the core activation, dedupe, completion, and rearm behavior is proven. A consumer should not need to emit sentinel `none()` facts or manually clear internal activation state, in my opinion.

### Completion Bookend

The long-running objective needs an explicit completion rule. For example, "collect samples until 500 samples are stored" can be represented by a completion policy or a higher-level objective completion helper that compiles to one.

Illustrative pseudocode:

```java
.withCompletionPolicy(process -> {
    var stored = process.blackboard().last(SamplesStored.class);
    return stored != null && stored.count() >= 500
        ? ProcessOutcome.completed("target reached")
        : ProcessOutcome.continueProcess();
})
```

or, at the higher-level objective API:

```java
.completeWhen(SamplesStored.atLeast(500))
```

The terminal condition is part of the contract.

In an evolving invocation, completing a runtime goal or incidental declared goal should not complete the long-running process unless `CompletionPolicy` or an explicit terminal objective says the process is complete.

If the long-running objective is not complete and no action is currently selectable, the process should enter `WAITING` rather than complete or keep invoking Open/Supervisor mode.

Completing a runtime goal does not end the long-running process. Phase 1 can prove that through internal discovery: an action produces a runtime fact, the process adds and completes a runtime goal, and normal run/tick mechanics keep the process alive. External ingress supplies the main wake-up path for the consumer application later.

### Objective Author Relationship

I see two useful evolving invocation shapes:

- deterministic evolving: `withEvolution(...)` supplies the runtime-goal policy directly
- open evolving: `withObjectiveAuthor(...)` uses Open-style deliberation to author an `ObjectivePlan`

This should follow the same safety shape as Open mode: LLM-backed authoring can rank or select among declared scoped goals and propose objective-specific policy, but execution only uses validated scope objects.

An `ObjectiveAuthor` should author typed objectives or objective plans. Those plans may include initial facts, completion rules, and evolution rules or already-compiled agenda entries. The framework validates the authored plan against the active scope before the process starts.

If both inputs are allowed, they could combine into one validated plan:

```text
ObjectiveAuthor plan
  + explicit withEvolution policy
  -> validated merged ObjectivePlan
```

The important point is that Open-style authoring stays outside the hot loop. Runtime actions should normally emit typed facts rather than directly inventing new goals. If a runtime fact exposes an objective the current policy cannot handle, a later phase can support re-authoring at a planning tick, with validation and approval before new policy is installed.

### Policy Sources

Policy reaches a process from two places. Base policy is installed by the consumer application and applies to every process of a class:

- hazard handling
- blocking prompt recovery
- disconnect/session recovery

Objective policy is authored for a particular run:

- periodic side task such as a sensor calibration check
- objective-specific event rules
- objective completion rules
- utility defaults and initial facts

The `ObjectiveAuthor` should not need to remember universal safety and recovery rules. A launch-time validation step should merge base policy and objective policy, canonicalize all runtime-goal targets against scope, and reject the plan if any required handler has no scoped producing capability.

### Future Extension: Fact Derivation

I see fact derivation as a related future layer, not a prerequisite for the core runtime-goal engine. A later layer may derive runtime facts from current blackboard state before planning; for example, a process might derive `StorageNeeded` from a `BufferSnapshot` plus objective context.

Core Evolving Mode can begin with runtime facts already present on the blackboard:

- action-produced runtime facts
- initial objective facts
- externally ingested facts once the phase 2 ingress seam exists

That layer needs its own contracts for ordering, provenance, identity, retraction, and side-effect discipline. It should not block phase 1 or phase 2; both can feed the same evolution policy without introducing a derivation API.

### Validation Rules

- Runtime goals MUST be canonicalized against the active scope.
- Runtime-goal rules SHOULD preferably target an explicit declared scoped goal.
- Output-type matching MAY be offered as shorthand only when it resolves to exactly one scoped declared goal.
- A target runtime goal with no producing scoped capability MUST be rejected fast.
- A runtime goal that has a scoped producer but is currently blocked by missing facts or preconditions SHOULD remain active and allow the process to wait until a plan becomes available.
- Ambiguous output-type matches MUST fail unless the rule identifies the declared goal by stable goal identity or explicit goal reference.
- Forged lookalike goals MUST NOT be able to smuggle different value, preconditions, or metadata through the runtime-goal API.
- Hard preemption at planning boundaries SHOULD be modeled with existing planning availability primitives: consumer-authored `@Condition` methods and `@Action(pre = ...)` preconditions make ordinary work unavailable while a domain condition holds. Goal and action values remain ordinary planner inputs, not an Evolving-specific priority or lane mechanism.
- Memoryless ongoing-state reactions SHOULD usually remain plain value-selected actions under the `NIRVANA` planner mode (no explicit goal needed). Runtime-goal handlers selected by `.onFact(...).handleWithGoal(...)` MUST be goal producers in the active scope, for example by producing the goal's satisfied-by type and, in annotation style, using `@AchievesGoal` where appropriate.
- Observed fact occurrence identity MUST be carried internally for dedupe, rule-local activation consumption, and rearm. For phase 1, this is framework-owned activation identity, not domain equality.
- Adding a runtime goal SHOULD request replanning on the next planning tick. Normal Embabel planning MUST choose among active runtime goals and ordinary goals.

### Non-Goals

- Do not introduce a new `PlannerType`.
- Do not repeatedly invoke Open/Supervisor mode as the event loop.
- Do not mutate user-declared `@Agent` or `@EmbabelComponent` metadata.
- Do not add agents/actions/capabilities to an already-running process in this issue; treat process-local scope expansion as a later phase.
- Do not implement the runtime-agent-addition half of the README in this issue; this issue realizes the runtime-goals half first.
- Do not solve pub/sub fan-out in this issue.
- Do not require fact derivation to land before the runtime-goal engine.

### Acceptance Direction

The Validation Rules above are the contract; these are the observable behaviors that demonstrate it.

- A process can add a process-local runtime goal when a selected one-time work fact appears, whether the fact was produced by an action or ingressed externally.
- An action-produced runtime fact can activate a runtime goal without external ingress.
- Runtime goals with no satisfying scoped capability are rejected fast; runtime goals blocked by currently missing facts can remain active until a plan becomes available.
- Existing GOAP, Utility, and Hybrid planners choose declared actions from the scoped capabilities.
- The same observed fact occurrence does not repeatedly add the same runtime goal.
- Resumable completion marks the rule-local activation that created the runtime goal consumed, and the process returns to ordinary work.
- Process completion is handled by `CompletionPolicy` or an explicit terminal objective. If it returns continue and no action is selectable, the process remains alive and enters `WAITING`.
- Observability keeps all wake-ups attached to the same process/session.

### Open questions

Maintainer input would help on these decisions; the rest of the proposal takes a position.

- **Method names.** The spellings in this draft (`onFact`, `handleWithGoal`, `resumable`, `ingress()` vs `facts()`, `completeWhen`) are placeholders. What matters is that a rule reads as targeting a goal rather than declaring a new action, and that ingress reads as entering a running process.
- **Goal targets.** Whether a rule names its declared goal by direct reference, by an output type that resolves to exactly one scoped goal, or both. Either way the target is canonicalized against the active scope.
- **Payload binding.** How much fact-payload binding, if any, belongs in v1. The core primitive carries occurrence identity for dedupe and rearm; payload delivery can layer on later.
- **Combining authoring inputs.** Whether `withObjectiveAuthor(...)` and an explicit `withEvolution(...)` policy can be supplied together and merged into one validated `ObjectivePlan`, or whether supplying both is rejected.

## Sub-Issue 1

### Title

Evolving Mode phase 1: internal runtime facts activate process-local runtime goals

### Body

Implement the core Evolving Mode engine for internally produced runtime facts.

This phase does not require external ingress. It should be dogfooded through internal discovery: a normal action discovers a follow-up need, returns a typed runtime fact, and the evolution policy turns that fact into a process-local runtime goal over the existing scope. Existing GOAP, Utility, or Hybrid planning decides what runs.

This should be deliberately framework-native: runtime facts are ordinary typed action outputs on the blackboard, and runtime goals use Embabel's existing type, condition, binding, and planner semantics. They do not introduce keyed goal-instance satisfaction. Actions that should handle repeated runtime goals should use existing repeatability mechanisms such as `canRerun = true`, or existing state-clearing/replacement patterns.

Here, `resumable()` means the runtime goal is removed after completion, its rule-local activation is marked consumed, and the process returns to normal planner action selection — not the restoration of a suspended plan stack.

Implementation likely needs a small internal activation record, not a public consumer-facing API. Conceptually:

```text
FactObservation:
  id
  fact
  fact type

RuleActivationRecord:
  rule id
  fact observation id
  runtime goal id
  status: active | consumed
```

The public contract is simpler: consumers produce typed facts; Evolving Mode records which observed fact occurrences have activated which rules and handles duplicate suppression plus resumable consume/rearm internally.

This also covers the long-lived process bookend: runtime-goal completion is not process completion. When completion policy says to continue but no action is selectable, the process moves to `WAITING`.

Example:

```text
action output: StorageNeeded
  -> evolution policy adds runtime goal StorageCompleted
  -> planner chooses declared storage/navigation actions
  -> StorageCompleted is produced
  -> runtime goal completes and ordinary work resumes
```

Suggested API shape:

```java
.withEvolution(evolution -> evolution
    .onFact(StorageNeeded.class)
        .handleWithGoal(storageCompletedGoal)
        .resumable()

    .onFact(HazardDetected.class)
        .handleWithGoal(hazardHandledGoal)
        .resumable()
)
```

A runtime rule identifies the declared goal it targets, and that target is canonicalized against the active scope before planning. Ambiguous matches fail with a helpful message.

Acceptance criteria:

- declared capabilities remain immutable
- runtime goals are process-local
- runtime goals are canonicalized/validated against the active scope
- runtime goals with no producing scoped capability are rejected fast
- runtime goals with a scoped producer are not rejected merely because they are currently blocked by missing facts or preconditions
- adding a runtime goal does not mutate `Agent.goals`
- observed fact occurrence identity and duplicate suppression are deterministic; that identity is framework-owned, not domain equality or consumer-managed ids
- resumable completion marks the triggering rule-local activation consumed, demonstrating rearm without external ingress or derivation
- resumable runtime goals complete and the process returns to normal planner action selection
- completing a runtime goal or incidental declared goal does not complete the process unless `CompletionPolicy` or an explicit terminal objective says it is complete
- when `CompletionPolicy` returns continue and no action is selectable, the process enters `WAITING` rather than completing
- base GOAP, Utility, and Hybrid planners remain the execution planners

Out of scope:

- external facts from the consumer application
- pub/sub fan-out
- adding new agents/actions/capabilities to an already-running process
- fact derivation
- keyed goal-instance satisfaction
- source-disappearance policy for a runtime goal that has not completed yet

Example acceptance test:

```text
Given a process with onFact(StorageNeeded).handleWithGoal(storageCompletedGoal).resumable()
When an action outputs StorageNeeded
Then exactly one StorageCompleted runtime goal is active
And the runtime goal has a rule-local activation for that StorageNeeded fact

When StorageCompleted is produced
Then the runtime goal completes
And that rule-local activation is marked consumed

When an action outputs a later StorageNeeded
Then exactly one new StorageCompleted runtime goal is active
And the new runtime goal has its own rule-local activation
```

## Sub-Issue 2

### Title

Evolving Mode phase 2: external process fact ingress for a known running process

### Body

Add a sanctioned way for a consumer application to publish typed facts into one known running process.

This is separate from the core internal path because internally produced facts are enough to prove the evolution engine. External ingress is needed for event-driven applications where facts change outside the Embabel action loop.

Desired contract:

```java
// Conservative spelling.
process.ingress().publish(new HazardDetected(...));

// Friendlier spelling if Embabel wants a facts facade.
process.facts().publish(new HazardDetected(...));
```

Published facts should become visible at planning ticks, not by mutating the blackboard directly from arbitrary consumer application threads.

Suggested semantics:

- `publish`: enqueue this typed fact for the running process
- one-time work publication is the first target: publish a fact that means "this happened; handle it once unless repeated explicitly"
- latest/state-style publication can come later, but only with explicit lifecycle semantics for replacement, coalescing, and active runtime goals
- external facts feed the same evolution policy as action-produced facts when a rule maps them to runtime goals
- wake-up should move a waiting/stuck/paused process back to runnable state when appropriate
- duplicate behavior should be explicit and deterministic
- external ingress should reuse the same observed fact occurrence identity and rearm semantics as internal facts

Acceptance criteria:

- consumer application code can publish facts to a known running process
- publication is thread-safe and does not mutate the blackboard directly
- facts drain at planning ticks
- one-time work facts can be deduped or repeated by explicit observed-fact identity
- external ingress can wake the process without starting a new process
- external ingress can activate the same runtime-goal rules as action-produced facts

Out of scope:

- latest/state replacement or coalescing unless explicit lifecycle semantics are defined
- one event waking many processes
- general pub/sub routing
- trace linking across producer/consumer processes beyond the 1-to-1 case

## Sub-Issue 3

### Title

Evolving Mode phase 3: observability for ingress wake-ups and runtime goals

### Body

Add observability support for long-lived evolving processes that enter `WAITING` and wake again.

The observability model should reuse Embabel's existing session, turn, and idle machinery. An ingress wake-up should be a new unit of work on the existing process/session, not a fresh disconnected process.

Requirements:

- a wake-up continues the existing process and reuses its stable session id
- each wake-up has a clean turn boundary
- turn boundaries close cleanly on success, error, no-op wake-up, cancellation, or exhaustion
- ingress facts record source/correlation metadata where available
- runtime goals have lifecycle events

Suggested event shape:

```text
RuntimeGoalLifecycleEvent =
  process id
  turn id
  runtime goal id
  source event type/id
  declared goal name/output type
  lifecycle transition
  reason
  timestamp
```

Useful lifecycle transitions:

- runtime goal proposed
- runtime goal installed
- runtime goal rejected, for example because validation against the current process scope failed
- runtime goal completed
- runtime goal removed
- runtime goal suppressed or deduped
- plan selected after runtime-goal planning

Acceptance criteria:

- a process woken by ingress appears under the existing process/session
- the wake-up is represented as a distinct turn/unit of work
- runtime-goal lifecycle events are observable
- per-turn runtime state is cleaned up at turn end, not only at process termination
- plain 1-to-1 ingress does not require a pub/sub tracing model

Out of scope:

- fan-out tracing for one event waking many processes
- linking independent producer and consumer traces in a pub/sub system

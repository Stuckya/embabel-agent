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
> - when a committed occurrence needs handling, evolution policy can install a runtime goal that finishes once and marks its rule-local activation consumed
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

I'm treating this issue as an epic for Evolving Mode. This proposal is an incremental path for Evolving Mode, and I will open sub-issues to track the different work streams discussed in #1725.

### Motivation

The README frames Evolving Mode as the platform working with multiple goals in the same process and modifying a running process to add further goals and agents when new needs are discovered.

Evolving Mode is primarily a process-lifecycle feature: the planner already knows how to choose among goals, but the process needs a sanctioned way to change the effective runtime goals and completion behavior while it is running.

I think the smallest useful slice of that direction is process-local runtime goal evolution: an action returns a typed fact, an evolution policy turns that fact into a process-local runtime goal, and the existing planner chooses declared actions from the active scope.

Embabel already has strong invocation-time composition through Autonomy and scoped invocation through `UtilityInvocation` and `SupervisorInvocation`; this proposal is about the runtime gap after a process has started.

The phase 1 loop is:

```text
action output
  -> runtime fact
  -> evolution policy
  -> process-local runtime goal
  -> existing GOAP / Utility / Hybrid planner
  -> declared action
```

A normal Embabel action might return `StorageNeeded`. The evolving process observes that fact after the action, applies an `onFact(StorageNeeded.class)` rule targeting one goal that the current scoped capabilities know how to achieve, adds a process-local runtime goal, and the normal planner composes scoped storage/navigation actions to satisfy it.

External ingress should feed the same loop in phase 2. That is important for event-driven applications. Phase 1 proves the action-produced fact path first.

This is not a proposal for a new `PlannerType`. Evolving Mode changes the process-local set of active runtime goals and lets the existing planner choose declared actions from the active scope.

This separates three concepts:

- declared capabilities: immutable `@Agent` and `@EmbabelComponent` actions, conditions, and declared goals
- runtime facts: ordinary typed domain objects visible on the process blackboard and selected by an evolution policy; this is not a new public `Fact` wrapper type
- runtime goals: process-local active objectives added or removed by an evolution policy

This issue intentionally chooses the immutable-declared interpretation of the README's Evolving Mode. It addresses the runtime-goals half of "add further goals and agents" first. The other half, runtime mutation of the agent/capability scope, should be a separate follow-up.

For the agent/capability half, I would treat that as process-local expansion, not mutation of global declarations. By expansion, I mean adding more scoped `@Agent` or `@EmbabelComponent` instances to the process at runtime, so their declared actions and goals become visible to the planner on the next planning tick. That seems like a larger initiative than runtime goals, so I would prefer to prove the goals half first.

If the process can simply reconsider something on every planning tick, use conditions and Utility/Hybrid selection. Use `onFact(...).handleWithGoal(...).resumable()` when a fact should start work that is expected to finish, mark its rule-local activation consumed, and then resume ordinary execution.

Another way to draw that line: if state should be reconsidered every planning tick, model it with conditions, values, and ordinary planning. If a fact should commit one unit of follow-up work that finishes and rearms, model it with an `onFact(...).resumable()` rule.

### Proposed Work Streams

As discussed in #1725, this can be split into a few core work streams. They do not have to be strictly sequential, but phase 1 is the smallest green slice and should stand on its own.

1. Internal action-produced runtime facts
   - normal action outputs activate process-local runtime goals
   - no external ingress required
   - proves the core Evolving Mode engine and the README "action discovers additional goals" scenario

2. External process fact ingress
   - host applications can publish typed facts into one known running process
   - facts become visible at planning ticks
   - occurrence-style facts can feed the same evolution engine as internal runtime facts

3. Observability support
   - each wake-up continues the existing process/session
   - wake-ups have clean turn boundaries
   - runtime goals have observable lifecycle events

4. Optional: Process-local scope expansion
   - allow a running process to add more scoped `@Agent` or `@EmbabelComponent` instances
   - make their declared actions and goals visible to the planner on a later planning tick
   - validate and approve scope changes before they affect planning
   - fulfills the "add further agents" half of the README definition

A separate pub/sub discussion might make sense if one event needs to wake many processes, or if the publisher does not know the consuming process. This is currently out of scope for Evolving Mode.

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

The collection capability should not directly call storage or navigation. It should produce or consume typed facts. Embabel should compose the scoped capabilities through the planner.

The traditional Embabel pieces stay traditional:

- declared actions handle collection, navigation, storage, and hazard response
- declared goals are satisfied by normal action outputs such as `SamplesStored` and `HazardHandled`
- conditions and values still control ordinary availability and action selection

For example, `sampleTrayFull` is a level: it stays true while the robot's sample tray is full and becomes false after the samples are stored. That should usually be modeled with `@Condition` and action preconditions, not as a runtime goal:

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

Proposed higher-level APIs should compile down to a runtime substrate with these semantics:

- Visible process facts, especially action-produced facts in phase 1, can feed the evolution policy.
- A public API should expose a sanctioned process-local fact ingress facade, not a sibling state channel and not direct `blackboard.add(...)`.
- Event-style publication maps to occurrence-style fact publication or append-style fact publication with explicit duplicate semantics.
- An `.onFact(E).handleWithGoal(G)` style rule says: when a matching fact is observed, add one process-local runtime goal that the current process scope knows how to achieve.
- External ingress may still use observed fact identity, duplicate handling, and runtime-goal lifecycle semantics underneath, but the higher-level policy API should hide those details from normal consumers.
- Adding a runtime goal should request replanning on the next planning tick. Normal Embabel planning chooses among active runtime goals and ordinary goals.

Phase 1 intentionally follows Embabel's existing type, condition, binding, and planner model. It does not introduce keyed goal-instance semantics. If a domain needs key-specific matching, it should model that with existing Embabel primitives such as distinct types, `@Condition`, `@Action(pre = ...)`, SpEL conditions, named bindings, or by coalescing facts so only one relevant source is active at a time.

I prefer `onFact(...)` over `onEvent(...)` because phase 1 is driven by ordinary action-produced runtime facts. External events in phase 2 should enter the process by becoming runtime facts first.

In other words:

```text
consumer policy API
  -> observed fact identity and runtime-goal lifecycle semantics
  -> process-local agenda and fact-ingress runtime
  -> existing planner
```

Consumers should not need to reason about raw activation identifiers, manual clearing APIs, TTLs, and activation lifecycle details.

### Blackboard Model

Current Embabel documentation treats the blackboard as process-local working memory for an `AgentProcess`, not as a mutable world-state database, pub/sub bus, or external-event ingestion mechanism.

Most user code should not write it directly. Action inputs are resolved from the blackboard, action outputs are automatically appended to the blackboard, and planning conditions are evaluated from the blackboard.

Blackboard objects are ordered and append-only. The latest visible object of a type is the default match; named binding is available when type alone is ambiguous. Hiding an object removes it from future planning and API visibility, but does not delete it from process history.

Conditions are separate planning booleans, normally supplied by `@Condition`. They are not ordinary blackboard objects and should not be used as the external-event ingestion mechanism.

If a mutable level is modeled as a blackboard fact, the consumer owns that fact's lifecycle unless explicit ingress lifecycle options are configured. When the level is no longer true, hide or replace the visible fact. Runtime-goal completion can consume the rule-local activation for its own occurrence-like runtime fact; it should not imply retraction for arbitrary level facts or arbitrary blackboard objects.

External host facts should enter a running process through a sanctioned process-local ingress API that queues publication for a planning tick. Upstream should treat the name and exact shape as open. Evolving Mode should preserve the boundary: external events enter through ingress, then become ordinary typed facts visible to planning.

### Ingress Naming Options

The exact name is less important than the seam. Reasonable spellings are:

```java
process.ingress().publish(new HazardDetected(...));
```

`process.ingress()` implies that external input enters a known running process, queues safely, drains at planning ticks, and can wake the process. A public type named `ProcessIngress` or `ProcessFactIngress` could sit behind this spelling. This keeps the blackboard as process working memory rather than making it sound like the caller's mutation surface.

```java
process.facts().publish(new HazardDetected(...));
```

`process.facts()` is the friendlier consumer option. It says "publish a typed fact to this running process," which matches the action input/output mental model. The cost is that upstream Embabel does not currently have a first-class `Fact` or `Facts` module; it has blackboard objects. This spelling introduces new vocabulary.

Do not bless this as the phase 2 happy path:

```java
process.blackboard().add(new HazardDetected(...));
```

Raw blackboard mutation is too shallow for external ingress. It does not carry planning-tick timing, wake-up behavior, observed fact identity, duplicate semantics, or observability.

### Minimal API Shape

At the parent-issue level, I would keep the API shape intentionally small. Fuller builder and option sketches belong in the sub-issues.

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

A runtime rule should identify the declared goal it wants the process to achieve. The final API may do that directly with a goal-like input or indirectly with an output type that resolves to one goal in the current process scope. In either case, the implementation must canonicalize against the active scope, reject ambiguous matches, and prevent forged or lookalike goals from changing value, preconditions, or metadata.

Core handlers should re-sense current state or read objective/context facts rather than relying on fact-payload delivery. Observed fact occurrence identity is for dedupe, rule-local activation consumption, and rearm. Payload binding can be added later if a consumer needs it, but it is not part of the core fact primitive.

The core does not include `terminal()` or `expires(...)` runtime goals. Long-running objectives complete through `CompletionPolicy`. If a source fact disappears before handling completes, that lifecycle choice can be added later. Execution-level cancellation can also be added later if a use case cannot be modeled with small resumable actions and normal replanning.

Naming note: the final method name and argument shape are open. This draft uses `handleWithGoal(...)` in examples to emphasize that the rule targets a goal, not a new action. Embabel already uses `Action` for declared executable operations, and this primitive does not create a new action. If the project prefers a different name or a typed output target, choose the shape that keeps that distinction clear while still resolving to exactly one goal in scope. Maintainer input would be useful on whether the first API should take a goal-like target, an output-type target, or support both.

### Relationship To `@Action(trigger = ...)`

`@Action(trigger = X.class)` should remain an action-level reactive feature. It is useful when `X` is the latest result and the triggered action can run directly.

It should not be the Evolving Mode mechanism. `trigger` is based on `lastResult`, so it is fragile as a step inside a forward GOAP plan:

- a prefix action can overwrite `lastResult` before the triggered action runs
- a planner that needs a complete static path cannot route through a trigger action whose event is not already the latest result

The boundary should be:

- use `@Action(trigger = X.class)` for simple reactive handlers where no planner-visible lifecycle is needed
- use `onFact(X).handleWithGoal(G).resumable()` when an occurrence-like runtime fact should install a process-local goal, replan, complete, mark its rule-local activation consumed, and resume normal planner action selection

### Fact Matching And Rearm Semantics

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

The behavior I would expect:

- the first `StorageNeeded` adds one `StorageCompleted` runtime goal
- re-scanning the same observed `StorageNeeded` occurrence does not add another runtime goal while its activation is active or already consumed
- when `StorageCompleted` is produced, the runtime goal completes and the rule-local activation is marked consumed
- if `StorageNeeded` appears again later, it can fire again because the earlier activation was consumed

Without observed fact occurrence identity and rule-local consume/rearm behavior, two bad outcomes are easy to create:

- the same observed fact occurrence keeps adding duplicate runtime goals
- the same observed fact occurrence re-fires after completion just because the underlying blackboard object is still visible

So the default should be simple:

- a rule fires once per observed fact occurrence
- the same observed fact occurrence does not activate the same rule twice
- a `resumable()` runtime goal that completes successfully marks the activation that created it consumed for that rule
- a later matching source can fire again after the previous activation has been consumed

For phase 1, observed fact occurrence identity should be framework-owned. It should not depend on object equality, domain equality, or keyed goal satisfaction.

An observed fact occurrence is an internal process-local observation record created once per fact addition or ingress drain. It is not created once per planning scan. Re-scanning the same visible blackboard object must resolve to the same observation record, so a consumed activation cannot re-fire just because the source object remains visible.

"Same occurrence" means the same observation record id, not object equality, domain equality, or payload value. A later action output or ingress publication creates a new observation record and can activate the rule again.

The activation bookkeeping should be internal. Consumers should not manage activation ids, consume records, or internal activation state.

Completion consumes the rule-local activation record, not necessarily the underlying blackboard object. The same observed occurrence must not re-fire the same rule after completion. Hiding or replacing the blackboard object remains an explicit lifecycle concern.

Source disappearance before completion is real, but I would not put it in the first public API. It can be a later lifecycle policy once the core activation, dedupe, completion, and rearm behavior is proven. A consumer should not need to emit sentinel `none()` facts or manually clear internal activation state.

### Completion Bookend

The long-running objective needs an explicit completion rule. For example, "collect samples until 500 samples are stored" can be represented by a completion policy or a higher-level objective completion helper that compiles to one.

Illustrative pseudocode:

```java
// Final Java API spelling may differ.
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

The issue does not need to settle the final spelling, but it should make the terminal condition part of the contract.

In an evolving invocation, completing a runtime goal or incidental declared goal should not complete the long-running process unless `CompletionPolicy` or an explicit terminal objective says the process is complete.

If the long-running objective is not complete and no action is currently selectable, the process should enter `WAITING` rather than complete or keep invoking Open/Supervisor mode.

Phase 1 should prove that runtime-goal completion does not complete the long-running process. Phase 2 ingress supplies the main external wake-up path; phase 1 can still be driven by existing run, tick, or resume mechanics in tests or hosts.

### Objective Author Relationship

There are two useful Evolving invocation shapes:

- deterministic evolving: `withEvolution(...)` supplies the runtime-goal policy directly
- open evolving: `withObjectiveAuthor(...)` uses Open-style deliberation to author an `ObjectivePlan`

This should follow the same safety shape as Open mode: LLM-backed authoring can rank or select among declared scoped goals and propose objective-specific policy, but execution only uses validated scope objects.

An `ObjectiveAuthor` should author typed objectives or objective plans. Those plans may include initial facts, completion rules, and evolution rules or already-compiled agenda entries. The framework validates the authored plan against the active scope before the process starts.

For an MVP, combining both inputs could either be rejected or defined as:

```text
ObjectiveAuthor plan
  + explicit withEvolution policy
  -> validated merged ObjectivePlan
```

The important point is that Open-style authoring stays outside the hot loop. Runtime actions should normally emit typed facts rather than directly inventing new goals. If a runtime fact exposes an objective the current policy cannot handle, a later phase can support re-authoring at a planning tick, with validation and approval before new policy is installed.

### Base Policy And Objective Policy

Evolving Mode should distinguish policy that is always installed by the host from policy authored for a specific objective.

Base policy is host-installed and universal for a class of processes. Examples:

- hazard handling
- blocking prompt recovery
- disconnect/session recovery

Objective policy is authored for a particular run. Examples:

- scheduled interruption such as an hourly maintenance activity
- objective-specific event rules
- objective completion rules
- utility defaults and initial facts

The `ObjectiveAuthor` should not need to remember universal safety and recovery rules. A launch-time validation step should merge base policy and objective policy, canonicalize all runtime-goal targets against scope, and reject the plan if any required handler has no scoped producing capability.

### Future Extension: Fact Derivation

Fact derivation is a related next layer, not a prerequisite for the core runtime-goal engine. A later layer may derive runtime facts from current blackboard state before planning; for example, a process might derive `StorageNeeded` from a `BufferSnapshot` plus objective context.

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
- Hard interruption SHOULD be modeled with existing planning availability primitives: consumer-authored `@Condition` methods and `@Action(pre = ...)` preconditions make ordinary work unavailable while a domain condition holds. Goal and action values remain ordinary planner inputs, not an Evolving-specific priority or lane mechanism.
- Memoryless level reactions SHOULD usually remain plain value-selected actions under `NIRVANA`. Runtime-goal handlers selected by `.onFact(...).handleWithGoal(...)` MUST be goal producers in the active scope, for example by producing the goal's satisfied-by type and, in annotation style, using `@AchievesGoal` where appropriate.
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

- A process can add a process-local runtime goal when a selected occurrence-like runtime fact appears, whether the fact was produced by an action or ingressed externally.
- An action-produced runtime fact can activate a runtime goal without external ingress.
- Runtime goals are validated against the active scope and projected into the effective planning system.
- Runtime goals with no satisfying scoped capability are rejected fast; runtime goals blocked by currently missing facts can remain active until a plan becomes available.
- Existing GOAP, Utility, and Hybrid planners choose declared actions from the scoped capabilities.
- The same observed fact occurrence does not repeatedly add the same runtime goal.
- Resumable completion marks the rule-local activation that created the runtime goal consumed.
- A resumable runtime goal can complete and allow the process to return to ordinary work.
- Adding a runtime goal requests replanning; normal Embabel planning decides whether that goal runs before ordinary work.
- Process completion is handled by `CompletionPolicy` or an explicit terminal objective.
- If `CompletionPolicy` returns continue and no action is selectable, the process remains alive and enters `WAITING`.
- Observability keeps all wake-ups attached to the same process/session.

## Sub-Issue 1

### Title

Evolving Mode phase 1: internal runtime facts activate process-local runtime goals

### Body

Implement the core Evolving Mode engine for internally produced runtime facts.

This phase does not require external ingress. It should prove that an action-produced runtime fact can cause the running process to add or remove process-local runtime goals over the existing scope. Existing GOAP, Utility, or Hybrid planning decides what runs.

Phase 1 should be deliberately framework-native: runtime facts are ordinary typed action outputs on the blackboard, and runtime goals use Embabel's existing type, condition, binding, and planner semantics. They do not introduce keyed goal-instance satisfaction. Actions that should handle repeated runtime goals should use existing repeatability mechanisms such as `canRerun = true`, or existing state-clearing/replacement patterns.

Here, `resumable()` does not mean restoring a suspended plan stack. It means the runtime goal is removed after completion, its rule-local activation is marked consumed, and the process returns to normal planner action selection.

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

Phase 1 also includes the long-lived process bookend: runtime-goal completion is not process completion. When completion policy says to continue, no selectable action moves the process to `WAITING`.

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

The exact final spelling can differ. The important part is that a runtime rule identifies the declared goal it wants the process to achieve. The API may take a goal-like input or an output type, but it must resolve to one goal in the current process scope before planning. Ambiguous output-type matches should fail with a helpful message.

Acceptance criteria:

- declared capabilities remain immutable
- runtime goals are process-local
- runtime goals are canonicalized/validated against the active scope
- runtime goals with no producing scoped capability are rejected fast
- runtime goals with a scoped producer are not rejected merely because they are currently blocked by missing facts or preconditions
- adding a runtime goal does not mutate `Agent.goals`
- observed fact occurrence identity and duplicate suppression are deterministic; that identity is framework-owned, not domain equality or consumer-managed ids
- resumable completion marks the triggering rule-local activation consumed, so phase 1 can demonstrate rearm without external ingress or derivation
- resumable runtime goals complete and the process returns to normal planner action selection
- completing a runtime goal or incidental declared goal does not complete the process unless `CompletionPolicy` or an explicit terminal objective says it is complete
- when `CompletionPolicy` returns continue and no action is selectable, the process enters `WAITING` rather than completing
- base GOAP, Utility, and Hybrid planners remain the execution planners

Out of scope:

- host-published external facts
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

Add a sanctioned way for a host application to publish typed facts into one known running process.

This is separate from the core internal path because internally produced facts are enough to prove the evolution engine. External ingress is needed for event-driven applications where facts change outside the Embabel action loop.

Desired contract:

```java
// Conservative spelling.
process.ingress().publish(new HazardDetected(...));

// Friendlier spelling if Embabel wants a facts facade.
process.facts().publish(new HazardDetected(...));
```

Published facts should become visible at planning ticks, not by mutating the blackboard directly from arbitrary host threads.

Suggested semantics:

- `publish`: enqueue this typed fact for the running process
- occurrence-style publication is the first target: "this happened; process once unless repeated explicitly"
- latest/state-style publication can come later, but only with explicit lifecycle semantics for replacement, coalescing, and active runtime goals
- external facts feed the same evolution policy as action-produced facts when a rule maps them to runtime goals
- wake-up should move a waiting/stuck/paused process back to runnable state when appropriate
- duplicate behavior should be explicit and deterministic
- external ingress should reuse the same observed fact occurrence identity and rearm semantics as internal facts

Acceptance criteria:

- host code can publish facts to a known running process
- publication is thread-safe and does not mutate the blackboard directly
- facts drain at planning ticks
- occurrence-style facts can be deduped or repeated by explicit occurrence identity
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

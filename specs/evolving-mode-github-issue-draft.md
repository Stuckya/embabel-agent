# GitHub Issue Draft: Evolving Mode Essentials

Draft issue text for the post-1.0.0 Evolving Mode discussion.

Discussion context: https://github.com/embabel/embabel-agent/discussions/1725

### Title

Evolving Mode Essentials: process-local runtime goals for long-lived processes

### Body

I'm proposing this as an epic for Evolving Mode: a way for a long-lived process to add, complete, consume, and resume selected process-local runtime goals while it runs, without editing its declared agents.

The work is broken down into phases (internal facts, selected external ingress, observability) and I'll open a sub-issue per work stream discussed in #1725. A fourth stream, process-local scope expansion, is listed as deferred future work.

### Motivation

The README frames Evolving Mode as the platform working with multiple goals in the same process, and modifying a running process to add further goals and agents when new needs are discovered.

I see Evolving Mode as a process-local objective feature, not the general event-driven state mechanism. The planner already knows how to choose among goals. What's missing is a way to change the effective runtime goals and completion behavior while the process runs. The slice I want to prove first is process-local runtime goal evolution. An action returns a typed fact, an evolution policy turns that fact into a runtime goal, and the existing planner chooses declared actions from the active scope.

Embabel already has strong invocation-time composition through Autonomy and scoped invocation through `UtilityInvocation` and `SupervisorInvocation`; this proposal is about the runtime gap after a process has started.

There are three layers I want to keep separate:

```text
external events / sensors
  -> application-owned state modules
  -> @Condition / action inputs for current truth

occurrence facts
  -> Evolving runtime goals
  -> consume / rearm / resume

user or LLM objectives
  -> ObjectiveAuthor / ObjectivePlan
  -> process-local runtime goals
```

A normal Embabel action might return `CalibrationRequested`. The evolving process observes that fact and applies an `onFact(CalibrationRequested.class)` rule, adding a process-local runtime goal that the current process scope knows how to achieve. The planner then composes declared actions to satisfy it.

I want selected external facts to feed the same loop too, which matters for event-driven applications. As agreed in #1725 I'd like to prove the action-produced fact path first.

Evolving Mode reuses the existing planner rather than adding a new `PlannerType`. It changes the process-local set of active runtime goals and lets the planner choose declared actions from the active scope. I'm keeping the immutable-declared interpretation of the README's Evolving Mode: declared capabilities stay fixed, while runtime facts and runtime goals are the moving parts.

I'm addressing the runtime-goals half of "add further goals and agents" first. I'd make the other half, runtime mutation of the agent/capability scope, a separate follow-up. For the agent/capability half, I'd treat that as process-local expansion, not mutation of global declarations. By process-local expansion, I mean adding more scoped `@Agent` or `@EmbabelComponent` instances to the process at runtime, so their declared actions and goals become visible to the planner on the next planning tick. That seems like a larger initiative than runtime goals, so I'd prefer to prove the goals half first.

### Vocabulary Key

This issue leans on a handful of terms throughout. They are defined once here:

- **Declared capability** - immutable `@Agent` and `@EmbabelComponent` actions, conditions, and goals.
- **Consumer application** - the application using Embabel, configuring invocation, providing scoped capabilities, owning domain state modules, and publishing selected external facts.
- **Runtime fact** - typed domain object on the blackboard, selected by an evolution policy. Not a new public `Fact` API.
- **Occurrence fact** - a runtime fact that represents one unit of side work or newly discovered objective to handle once, such as `CalibrationRequested` or `HazardDetected`.
- **Runtime goal** - a process-local objective added or removed by an evolution policy.
- **Evolution rule** - an `onFact(X).handleWithGoal(G)`-style rule: when fact `X` is observed, add one runtime goal `G` that the current process scope knows how to achieve.
- **Fact observation** - internal record created once per fact addition or ingress drain (_not per planning tick_). Its identity is framework-owned.
- **Rule-local activation** - the per-rule check that ensures a rule fires once per fact observation, with a managed lifecycle.
- **Application-owned state module** - consumer-owned state such as sensor snapshots, tray contents, inventory, or connection/session state. Embabel should consume this through action inputs, `@Condition`, scoped capabilities, and selected runtime facts, not own the whole state model.
- **Modeling a fact** - see the Decision Guide below. Default to normal Embabel planning; use Evolving when a fact should create side work or a newly discovered objective with lifecycle.

### Decision Guide

| Situation | Prefer | Example |
| --- | --- | --- |
| The planner needs current truth. | `@Condition` or action input | `sampleTrayFull`, `doorOpen`, `hazardVisible` |
| An action should only run while current truth holds. | `@Condition` + `@Action(pre = ...)` | `navigateToStorage` only when `sampleTrayFull` |
| A fact means "do this specific task and keep trying until it is handled." | Evolving runtime goal | `CalibrationRequested -> CalibrationCompleted` |
| A task should be remembered, but its steps depend on current truth. | Evolving runtime goal + `@Condition` | `HazardDetected -> HazardHandled`, with actions gated by `hazardVisible` or `safePathAvailable` |
| A user or LLM adds a new objective to this run. | `ObjectiveAuthor` / `ObjectivePlan` -> runtime goals | "Run a sensor calibration check before finishing" at launch, or later at a validated planning tick |
| The only reason is "an event happened." | Usually not enough for Evolving | Project current truth into application state, then expose it through `@Condition` or action inputs |

### Proposed Work Streams

As discussed in #1725, I've split this into a few core work streams. They do not have to be strictly sequential, but the first is the smallest working slice and should stand on its own.

1. Internal action-produced runtime facts
2. Selected external process fact ingress
3. Observability support
4. Optional future work: Process-local scope expansion

The sub-issues below cover the first three. Scope expansion is listed so it is not lost, but I would not include it in the first implementation pass.

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

In this example, `sampleTrayFull` only gates whether storage/navigation actions are available. Evolving Mode is not involved merely because the tray is full.

For event-driven consumer applications, external observations should usually update application-owned state modules first. Embabel can then read the current truth through action inputs and `@Condition`. The Evolving case is side work or a newly discovered objective, such as `HazardDetected`, that should be handled once and then marked done.

Evolving Mode takes over when selected typed facts should create process-local runtime goals:

- `CalibrationRequested`
- `HazardDetected`

Other progress facts can remain ordinary blackboard facts that completion policies, conditions, or actions read:

- `SampleCollected`
- `SamplesStored`
- `HazardHandled`

### Runtime Semantics

The higher-level policy API should compile down to a small runtime substrate, with the lifecycle details hidden from consumers:

```text
consumer policy API
  -> fact observation + runtime-goal lifecycle
  -> process-local agenda + ingress runtime
  -> existing planner
```

- Selected process facts, especially action-produced occurrence facts, feed the evolution policy. Fact ingress goes through a sanctioned process-local facade for selected external facts, never a sibling state channel or direct `blackboard.add(...)`.
- Adding a runtime goal requests replanning on the next planning tick. Normal Embabel planning then chooses among active runtime goals and ordinary goals.

This follows Embabel's existing type, condition, binding, and planner model. It does not introduce keyed goal-instance semantics. A domain that needs key-specific matching should use existing primitives. For example: distinct types, `@Condition`, `@Action(pre = ...)`, SpEL conditions, named bindings, or coalescing facts so only one relevant source is active at a time.

I prefer `onFact(...)` over `onEvent(...)` because the mechanism is driven by ordinary action-produced runtime facts; selected external events become runtime facts first.

### Blackboard Model

Embabel's documentation treats the blackboard as process-local working memory for an `AgentProcess`. Not a mutable world-state database, not a pub/sub bus, not an external-event ingestion mechanism.

Embabel should not own domain state like tray contents, inventory, or sensor snapshots. The consumer application owns those modules. Embabel consumes `@Condition`, action inputs, scoped capabilities, selected facts, and runtime-goal policy.

Most user code should not write to it directly. Selected external facts from the consumer application should enter a running process through a sanctioned process-local ingress API that queues publication for a planning tick.

Ingress is not a replacement for application-owned state modules. High-frequency or reversible state should normally stay outside Evolving Mode and be exposed through conditions or action inputs.

### Ingress Naming Options

I care more about the interface than the exact name. Reasonable spellings are:

```java
process.ingress().publish(new CalibrationRequested(...));
```

`process.ingress()` implies that selected external input enters a known running process, queues safely, drains at planning ticks, and can wake the process. A public type named `ProcessIngress` or `ProcessFactIngress` could sit behind this spelling. This keeps the blackboard as process working memory rather than making it sound like the caller's mutation surface.

```java
process.facts().publish(new CalibrationRequested(...));
```

`process.facts()` is a friendlier consumer option. It says "publish a typed fact to this running process," which matches the action input/output mental model. The cost is that embabel-agent does not currently have a first-class `Fact` or `Facts` module; it has blackboard objects. This spelling introduces new vocabulary to the codebase.

**I wouldn't bless this as the phase 2 happy path:**

```java
process.blackboard().add(new HazardDetected(...));
```

Raw blackboard mutation is too shallow for external ingress. It does not carry planning-tick timing, wake-up behavior, fact observation identity, duplicate semantics, or observability.

### Minimal API Shape

At the parent-issue level, I'd keep the API shape intentionally small. Fuller builder and option sketches belong in the sub-issues.

```java
.withEvolution(evolution -> evolution
    .onFact(CalibrationRequested.class)
        .handleWithGoal(calibrationCompletedGoal)
        .resumable()
);

process.ingress().publish(new CalibrationRequested(...));
```

Runtime goal rules should compile to process-local agenda entries with:

```text
one resolved goal from the current process scope
fact observation identity
resumable completion behavior
```

A runtime rule identifies the declared goal it wants the process to achieve. The validation rules below cover scope canonicalization and ambiguous matches.

Here `resumable()` means the runtime goal is removed after completion, its rule-local activation is marked consumed, and the process returns to normal planner action selection. It does not mean restoring a suspended plan stack.

The fluent API above is only one authoring style. The underlying contract should be typed data, not generated code:

```text
fluent API, config, or ObjectiveAuthor
  -> runtime-goal rule data
  -> validation against scope
  -> process-local runtime goals
```

That keeps the deterministic path ergonomic while still allowing an `ObjectiveAuthor` to propose the same connections as data. For example, an LLM-backed author should be able to propose "when `CalibrationRequested` appears, pursue `CalibrationCompleted` as resumable work" without writing Java or Kotlin code.

Occurrence facts may carry payloads for logging, correlation, or later API layers. For v1, I would start with handlers re-sensing current state or reading objective/context facts rather than relying on payload-to-goal binding. Fact observation identity exists for dedupe, rule-local activation consumption, and rearm.

The core excludes `terminal()` and `expires(...)` runtime goals; long-running objectives complete through `CompletionPolicy` as described in the Completion Bookend below. Source-disappearance handling and execution-level cancellation are deferred. Small resumable actions and normal replanning cover the cases I have in mind.

This draft uses `handleWithGoal(...)` to emphasize that a rule targets a goal, not a new action. Embabel already uses `Action` for declared executable operations, and this primitive does not create one. (_Naming and the goal-target shape are open questions, below._)

### Relationship To `@Action(trigger = ...)`

I'd keep `@Action(trigger = X.class)` as an action-level reactive feature. It is useful when `X` is the latest result and the triggered action can run directly.

I don't think it should be the Evolving Mode mechanism. `trigger` is based on `lastResult`, so it is fragile as a step inside a forward GOAP plan:

- a prefix action can overwrite `lastResult` before the triggered action runs.
- a planner that needs a complete static path cannot route through a trigger action whose event is not already the latest result.

The boundary should be:

- use `@Action(trigger = X.class)` for simple reactive handlers where no planner-visible lifecycle is needed.
- use `onFact(X).handleWithGoal(G).resumable()` when an occurrence fact should install a process-local goal, replan, complete, mark its rule-local activation consumed, and resume normal planner action selection.

### Fact Matching and Rearm Semantics

This is the lifecycle piece I think Evolving Mode needs to own, not something every consumer should hand-roll.

- a rule fires once per fact observation.
- the same fact observation does not activate the same rule twice.
- `resumable()` completion marks the rule-local activation that created the runtime goal consumed.
- completing a runtime goal consumes the rule-local activation, not necessarily the underlying blackboard object.
- after completion, the same fact observation should not re-fire the same rule or keep selecting the completed handler path only because the source object is still visible.
- a later matching source can fire again after the previous activation has been consumed.

The activation bookkeeping should be internal. Consumers should not manage activation ids, consume records, or internal activation state. Sub-Issue 1 carries the detailed acceptance behavior.

Source disappearance before completion is real, but I wouldn't put it in the first public API. It can be a later lifecycle policy once the core activation, dedupe, completion, and rearm behavior is proven. A consumer should not need to emit sentinel `none()` facts or manually clear internal activation state, in my opinion.

### Completion Bookend

The long-running objective needs an explicit completion rule. For example, "collect samples until 500 samples are stored" can be represented by a completion policy or a higher-level objective completion helper that compiles to one. The count can come from the latest visible progress fact or from consumer-owned state read by the completion policy. Reading from the process blackboard is fine; the warning above is about direct external mutation.

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

An `ObjectiveAuthor` should author typed objectives or objective plans. Those plans may include initial facts, completion rules, and runtime-goal rule data, such as "on this fact, pursue that declared goal as resumable work." The framework validates the authored plan against the active scope before the process starts.

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
- Memoryless ongoing-state reactions SHOULD usually remain plain value-selected actions, such as standing `NIRVANA` utility work under the Hybrid planner.
- Runtime-goal handlers selected by `.onFact(...).handleWithGoal(...)` MUST be goal producers in the active scope, for example by producing the goal's satisfied-by type and, in annotation style, using `@AchievesGoal` where appropriate.
- Fact observation identity MUST be carried internally for dedupe, rule-local activation consumption, and rearm. For phase 1, this is framework-owned activation identity, not domain equality.
- Adding a runtime goal SHOULD request replanning on the next planning tick. Normal Embabel planning MUST choose among active runtime goals and ordinary goals.

### Non-Goals

- Do not introduce a new `PlannerType`.
- Do not repeatedly invoke Open/Supervisor mode as the event loop.
- Do not mutate user-declared `@Agent` or `@EmbabelComponent` metadata.
- Do not add agents/actions/capabilities to an already-running process in this issue; this issue realizes the runtime-goals half first and treats process-local scope expansion as a later phase.
- Do not solve pub/sub fan-out in this issue.
- Do not require fact derivation to land before the runtime-goal engine.

### Acceptance Direction

The Validation Rules above are the contract; these are the observable behaviors that demonstrate it.

- Phase 1: an action-produced runtime fact can activate a process-local runtime goal without external ingress.
- Phase 2: a selected externally ingressed fact can activate the same runtime-goal rule path.
- Runtime goals with no satisfying scoped capability are rejected fast; runtime goals blocked by currently missing facts can remain active until a plan becomes available.
- Existing GOAP, Utility, and Hybrid planners choose declared actions from the scoped capabilities.
- The same fact observation does not repeatedly add the same runtime goal.
- Resumable completion marks the rule-local activation that created the runtime goal consumed, and the process returns to ordinary work.
- Process completion remains governed by `CompletionPolicy` or an explicit terminal objective.
- Observability keeps all wake-ups attached to the same process/session.

### Open questions

Maintainer input would help on these decisions; the rest of the proposal takes a position.

- **Method names.** The spellings in this draft (`onFact`, `handleWithGoal`, `resumable`, `ingress()` vs `facts()`, `completeWhen`) are placeholders. What matters is that a rule reads as targeting a goal rather than declaring a new action, and that ingress reads as entering a running process.
- **Goal targets.** Whether a rule names its declared goal by direct reference, by an output type that resolves to exactly one scoped goal, or both. Either way the target is canonicalized against the active scope.
- **Payload binding.** How much fact-payload binding, if any, belongs in v1. The core primitive carries fact observation identity for dedupe and rearm; payload delivery can layer on later.
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

The public contract is simpler: consumers produce typed facts; Evolving Mode records which fact observations have activated which rules and handles duplicate suppression plus resumable consume/rearm internally.

Detailed lifecycle expectation:

- the first `StorageNeeded` fact observation adds one `StorageCompleted` runtime goal.
- re-scanning the same `StorageNeeded` fact observation does not add another runtime goal while its activation is active or already consumed.
- when `StorageCompleted` is produced, the runtime goal completes and the rule-local activation is marked consumed.
- the same visible `StorageNeeded` object does not re-fire after completion just because the blackboard object is still visible.
- if `StorageNeeded` appears again later as a new action output, it can fire again because it creates a new fact observation.

Fact observation should be framework-owned: an internal process-local record created once per fact addition, not once per planning tick. "Same fact observation" means the same observation record id, not object equality, domain equality, payload value, or keyed goal satisfaction. Re-scanning the same visible blackboard object must resolve to the same observation record.

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
- fact observation identity and duplicate suppression are deterministic; that identity is framework-owned, not domain equality or consumer-managed ids
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
And the same visible StorageNeeded occurrence does not run the storage handler again as ordinary planner-selected utility work

When an action outputs a later StorageNeeded
Then exactly one new StorageCompleted runtime goal is active
And the new runtime goal has its own rule-local activation
```

## Sub-Issue 2

### Title

Evolving Mode phase 2: selected external process fact ingress for a known running process

### Body

Add a sanctioned way for a consumer application to publish selected typed facts into one known running process.

This is separate from the core internal path because internally produced facts are enough to prove the evolution engine. External ingress is needed when a consumer application wants selected external facts to become visible to one running Embabel process.

Ingress is not a replacement for application-owned state modules. High-frequency or reversible state, such as current sensor snapshots or "tray is full", should normally stay in the consumer application and be exposed through `@Condition` or action inputs. Phase 2 ingress is for selected facts that should enter the process at planning ticks.

Desired contract:

```java
// Conservative spelling.
process.ingress().publish(new CalibrationRequested("cal-123"));

// Friendlier spelling if Embabel wants a facts facade.
process.facts().publish(new CalibrationRequested("cal-123"));
```

Published facts should become visible at planning ticks, not by mutating the blackboard directly from arbitrary consumer application threads.
The sample id above is useful for correlation or future payload-binding work; v1 should not require payload-to-goal binding.

Blackboard details that matter for ingress:

- blackboard objects are ordered and append-only.
- the latest visible object of a type is the default match.
- named binding is available when type alone is ambiguous.
- hiding an object removes it from future planning and API visibility, but does not delete it from process history.
- conditions are separate planning booleans, normally supplied by `@Condition`; they are not ordinary blackboard objects and should not be used as the external-ingress mechanism.
- completing a runtime goal consumes only its own rule-local activation. It does not retract other state facts or blackboard objects.

Suggested semantics:

- `publish`: enqueue this selected typed fact for the running process
- occurrence fact publication is the first target: publish a fact that means "this happened; handle it once unless repeated explicitly"
- latest/state-style publication can come later, but only with explicit lifecycle semantics for replacement, coalescing, and active runtime goals
- external facts feed the same evolution policy as action-produced facts when a rule maps them to runtime goals
- wake-up should move a waiting/stuck/paused process back to runnable state when appropriate
- duplicate behavior should be explicit and deterministic
- external ingress should reuse the same fact observation identity and rearm semantics as internal facts

Acceptance criteria:

- consumer application code can publish selected facts to a known running process
- publication is thread-safe and does not mutate the blackboard directly
- facts drain at planning ticks
- occurrence facts can be deduped or repeated by explicit observed-fact identity
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

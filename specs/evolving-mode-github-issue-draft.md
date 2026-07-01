# GitHub Issue Draft: Evolving Mode Essentials

Draft issue text for the post-1.0.0 Evolving Mode discussion.

Discussion context: https://github.com/embabel/embabel-agent/discussions/1725

### Title

Evolving Mode Essentials: process-local runtime goals for long-lived processes

### Body

I'm proposing this as an epic for Evolving Mode: a way for a long-lived process to add, complete, consume, and resume selected process-local runtime goals while it runs, without editing its declared agents.

The work is broken down into phases (internal facts, selected external ingress, observability, cooperative interruption, open re-authoring, scope expansion) and I'll open a sub-issue per work stream.

The whole shape, up front:

```text
action output or selected external fact
  -> runtime fact
  -> evolution policy
  -> process-local runtime goal
  -> existing planner
  -> declared actions
```

- declared capabilities stay immutable; existing GOAP/Utility/Hybrid planners are reused unchanged
- Evolving Mode owns remembered one-time work — observed once, completed once, rearmed for the next occurrence; ongoing state stays with `@Condition`
- phase 1 (internal facts activate runtime goals) is the smallest working slice and stands alone

### Motivation

The README frames Evolving Mode as the platform working with multiple goals in the same process, and modifying a running process to add further goals and agents when new needs are discovered.

The README's own example — an action can realize that it has become important to achieve additional goals — is this proposal's phase 1: an action output becomes a runtime fact, and an evolution rule installs the corresponding runtime goal. The core here implements the conservative reading of that sentence (declared capabilities stay immutable; evolution is process-local); the more emergent reading — mid-run discovery of objectives nobody pre-wired — goes through the same validation later, as Open Evolving re-authoring at a planning tick.

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

The property that separates the middle layer from the first is memory. A `@Condition` is memoryless by design: current truth is re-derived at each planning tick. That is the right model when the world itself holds the memory: the state stays observable while it matters and stops being true once handled. Evolving Mode is for selected facts the world will not track for you: one unit of work that should be remembered, pursued to completion once, and rearmed for the next occurrence. That bookkeeping — fact observation identity, dedupe, consume, rearm — is the part I think the framework must own, because consumers end up hand-rolling it with flags, queues, and edge-latches and getting it subtly wrong.

A normal Embabel action might return `CalibrationRequested`. The evolving process observes that fact and applies an `onFact(CalibrationRequested.class)` rule, adding a process-local runtime goal that the current process scope knows how to achieve. The planner then composes declared actions to satisfy it.

I want selected external facts to feed the same loop too, which matters for event-driven applications. As agreed in #1725 I'd like to prove the action-produced fact path first.

Evolving Mode reuses the existing planner rather than adding a new `PlannerType`. It changes the process-local set of active runtime goals and lets the planner choose declared actions from the active scope. I'm keeping the immutable-declared interpretation of the README's Evolving Mode: declared capabilities stay fixed, while runtime facts and runtime goals are the moving parts.

I'm addressing the runtime-goals half of "add further goals and agents" first. I'd make the other half, runtime mutation of the agent/capability scope, a separate follow-up. For the agent/capability half, I'd treat that as process-local expansion, not mutation of global declarations. By process-local expansion, I mean adding more scoped `@Agent` or `@EmbabelComponent` instances to the process at runtime, so their declared actions and goals become visible to the planner on the next planning tick. That seems like a larger initiative than runtime goals, so I'd prefer to prove the goals half first.

### Vocabulary Key

This issue leans on a handful of terms throughout. They are defined once here:

- **Declared capability** - immutable `@Agent` and `@EmbabelComponent` actions, conditions, and goals.
- **Consumer application** - the application using Embabel, configuring invocation, providing scoped capabilities, owning domain state modules, and publishing selected external facts.
- **Runtime fact** - typed domain object on the blackboard, selected by an evolution policy. Not a new public `Fact` API.
- **Occurrence fact** - a runtime fact that reveals known follow-up work this run should remember and complete once, such as `CalibrationRequested` or `HazardDetected`. "Newly discovered objective" is reserved for the Open Evolving path (`ObjectiveAuthor`), not the fluent policy path.
- **Runtime goal** - a process-local objective added or removed by an evolution policy.
- **Evolution rule** - an `onFact(X).handleWithGoal(G)`-style rule: when fact `X` is observed, add one runtime goal `G` that the current process scope knows how to achieve.
- **Fact observation** - internal record created once per fact addition or ingress drain (_not per planning tick_). Its identity is framework-owned.
- **Rule-local activation** - the per-rule check that ensures a rule fires once per fact observation, with a managed lifecycle.
- **Application-owned state module** - consumer-owned state such as sensor snapshots, tray contents, inventory, or connection/session state. Embabel should consume this through action inputs, `@Condition`, scoped capabilities, and selected occurrence facts, not own the whole state model.
- **Modeling a fact** - see the Decision Guide below. Default to normal Embabel planning; use deterministic Evolving when a fact reveals known follow-up work with lifecycle; use Open Evolving (`ObjectiveAuthor`) when the run discovers something no predefined rule covers.

### Decision Guide

| Situation | Prefer | Example |
| --- | --- | --- |
| The planner needs current truth. | `@Condition` or action input | `sampleTrayFull`, `doorOpen`, `hazardVisible` |
| An action should only run while current truth holds. | `@Condition` + `@Action(pre = ...)` | `navigateToStorage` only when `sampleTrayFull` |
| A fact reveals known follow-up work this run should remember and complete. | Evolving runtime goal via predefined policy | `CalibrationRequested -> CalibrationCompleted` |
| A task should be remembered, but its steps depend on current truth. | Evolving runtime goal + `@Condition` | `HazardDetected -> HazardHandled`, with actions gated by `hazardVisible` or `safePathAvailable` |
| Known follow-up work is urgent and the current action should yield. | Evolving runtime goal + `interruptCurrentAction()` (cooperative cancellation) | `UrgentHazardDetected -> HazardHandled`, cancelling an in-flight navigation action |
| A user asks for a broad objective before the process starts. | `ObjectiveAuthor` / `ObjectivePlan` creates the initial plan | "Collect samples in Zone A until 500 are stored, and run a calibration check every hour" |
| The run discovers an unknown blocker or has no viable plan. | Open Evolving: `ObjectiveAuthor` re-authoring at a validated planning tick | `ZoneAccessBlocked(missingRequirement = Permit("P-42"))` -> propose runtime goal `PermitObtained` with a typed target |
| The only reason is "an event happened." | Usually not enough for Evolving | Project current truth into application state, then expose it through `@Condition` or action inputs |

### Proposed Work Streams

As discussed in #1725, I've split this into work streams. They do not have to be strictly sequential, but the first is the smallest working slice and should stand on its own.

1. Internal action-produced runtime facts
2. Selected external process fact ingress
3. Observability support
4. Cooperative interruption of the running action
5. Open Evolving: `ObjectiveAuthor` re-authoring at a planning tick
6. Process-local scope expansion

The sub-issues below cover all six. Streams 4-6 capture work uncovered while validating the earlier drafts against a live event-driven consumer application; I would not include 5 or 6 in the first implementation pass.

### Motivating Example

The design has been validated end-to-end in a live event-driven consumer application; that dogfooding drove the current-truth-vs-occurrence split, the rearm semantics, and the cooperative-interruption position in this proposal.

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

For event-driven consumer applications, external observations should usually update application-owned state modules first. Embabel can then read the current truth through action inputs and `@Condition`. The Evolving case is known follow-up work, such as `HazardDetected`, that this run should remember, handle once, and mark done.

Evolving Mode takes over when selected typed facts should create process-local runtime goals:

- `CalibrationRequested`
- `UrgentHazardDetected`

Other progress facts can remain ordinary blackboard facts that completion policies, conditions, or actions read:

- `SampleCollected`
- `SamplesStored`
- `HazardHandled`

Putting the pieces together, the whole consumer surface is one invocation plus selected publishes. Names are placeholders (see Open Questions); the shape is the point:

```java
var process = EvolvingInvocation.on(agentPlatform)
    .withScope(AgentScopeBuilder.fromInstances(
        new SampleCollection(), new Navigation(), new SampleStorage(), new HazardResponse()))
    .withEvolution(evolution -> evolution
        .onFact(CalibrationRequested.class)
            .handleWithGoal(GoalTarget.output(CalibrationCompleted.class))
            .resumable()
        .onFact(UrgentHazardDetected.class)
            .handleWithGoal(GoalTarget.output(HazardHandled.class))
            .interruptCurrentAction()   // Sub-Issue 4
            .resumable())
    .withCompletionPolicy(process -> {
        var stored = process.blackboard().last(SamplesStored.class);
        return stored != null && stored.count() >= 500
            ? ProcessOutcome.completed("target reached")
            : ProcessOutcome.continueProcess();
    })
    .createProcess(new CollectSamples("Zone A", 500));

agentPlatform.start(process);

// later, from the consumer application (phase 2 ingress):
process.ingress().publish(new CalibrationRequested("cal-123"));
```

`GoalTarget` is illustrative: `output(...)` resolves a declared goal by unique output type in scope; `named(...)` would reference one by stable identity. The exact target shape is an open question below.

### Runtime Semantics

The higher-level policy API should compile down to a small runtime substrate, with the lifecycle details hidden from consumers:

```text
consumer policy API
  -> fact observation + runtime-goal lifecycle
  -> process-local runtime-goal records + ingress runtime
  -> existing planner
```

- Selected process facts, especially action-produced occurrence facts, feed the evolution policy. Fact ingress goes through a sanctioned process-local facade for selected external facts, never a sibling state channel or direct `blackboard.add(...)`.
- Adding a runtime goal requests replanning on the next planning tick. Normal Embabel planning then chooses among the active runtime goals, executing declared actions from the scoped capabilities.

This follows Embabel's existing type, condition, binding, and planner model. It does not introduce keyed goal-instance semantics. A domain that needs key-specific matching should use existing primitives. For example: distinct types, `@Condition`, `@Action(pre = ...)`, SpEL conditions, named bindings, or coalescing facts so only one relevant source is active at a time.

I prefer `onFact(...)` over `onEvent(...)` because the mechanism is driven by ordinary action-produced runtime facts; selected external events become runtime facts first.

Occurrence facts should be semantic conclusions, not raw telemetry. A domain should publish `UrgentHazardDetected` — a decision that intervention is needed now — rather than a raw sensor reading like `ProximityAlarm`; an intentional hazard-handling mode would not emit it, and would expose its state through `@Condition` instead.

### Blackboard Model

I'd frame the blackboard as process-local working memory for an `AgentProcess` — not a mutable world-state database, not a pub/sub bus, not an external-event ingestion mechanism. (To be transparent: upstream docs describe it as "the shared memory system that maintains state throughout the agent process execution" and show direct `blackboard.add(...)` in examples. The framing here is the position this proposal takes on what external ingress must preserve, not a quote of existing documentation.)

Embabel should not own domain state like tray contents, inventory, or sensor snapshots. The consumer application owns those modules. Embabel consumes `@Condition`, action inputs, scoped capabilities, selected facts, and runtime-goal policy. A `@Condition` can read current truth from those modules directly; current state does not need to be republished to the blackboard as facts.

Most user code should not write to it directly. Selected external facts from the consumer application should enter a running process through a sanctioned process-local ingress API that queues publication for a planning tick.

Ingress is not a replacement for application-owned state modules. High-frequency or reversible state should normally stay outside Evolving Mode and be exposed through conditions or action inputs.

### Ingress Naming Options

I care more about the interface than the exact name: `process.ingress().publish(...)` is the conservative spelling, `process.facts().publish(...)` the friendlier one, and raw `process.blackboard().add(...)` is not a sanctioned path for external input. The tradeoffs live in Sub-Issue 2.

### Minimal API Shape

At the parent-issue level, I'd keep the API shape intentionally small — the rule + publish pair in the Motivating Example is the whole consumer surface. Fuller builder and option sketches belong in the sub-issues.

A runtime rule identifies the declared goal it wants the process to achieve; the validation rules below cover scope canonicalization and ambiguous matches, and Sub-Issue 1 carries the record shape rules compile to.

Here `resumable()` means the runtime goal is removed after completion, its rule-local activation is marked consumed, and the process returns to normal planner action selection. It does not mean restoring a suspended plan stack.

The fluent API above is only one authoring style. The underlying contract should be typed data, not generated code:

```text
fluent API, config, or ObjectiveAuthor
  -> runtime-goal rule data
  -> validation against scope
  -> process-local runtime goals
```

That keeps the deterministic path ergonomic while still allowing an `ObjectiveAuthor` to propose the same connections as data. For example, an LLM-backed author should be able to propose "when `CalibrationRequested` appears, pursue `CalibrationCompleted` as resumable work" without writing Java or Kotlin code.

The core excludes `terminal()` and `expires(...)` runtime goals; long-running objectives complete through `CompletionPolicy` as described in the Completion Bookend below. Source-disappearance handling is deferred. Interrupting an action that is already executing is covered in Cooperative Interruption below — replanning alone only takes effect at the next planning tick.

This draft uses `handleWithGoal(...)` to emphasize that a rule targets a goal, not a new action. Embabel already uses `Action` for declared executable operations, and this primitive does not create one. (_Naming and the goal-target shape are open questions, below._)

### Relationship To `@Action(trigger = ...)`

I'd keep `@Action(trigger = X.class)` as an action-level reactive feature. It is useful when `X` is the latest result and the triggered action can run directly.

I don't think it should be the Evolving Mode mechanism. `trigger` is based on `lastResult`, so it is fragile as a step inside a forward GOAP plan:

- a prefix action can overwrite `lastResult` before the triggered action runs.
- a planner that needs a complete static path cannot route through a trigger action whose event is not already the latest result.

The boundary should be:

- use `@Action(trigger = X.class)` for simple reactive handlers where no planner-visible lifecycle is needed.
- use `onFact(X).handleWithGoal(G).resumable()` when an occurrence fact should install a process-local goal, replan, complete, mark its rule-local activation consumed, and resume normal planner action selection.

### Cooperative Interruption

Adding a runtime goal changes what the planner selects at the next planning tick; it does not interrupt an action that is already executing, and consumers with long or blocking actions should not have to split them into planning-tick-sized steps to stay responsive to urgent facts.

For urgent occurrence facts I'd add an optional rule semantic, `.interruptCurrentAction()`: it installs the runtime goal as usual and requests graceful interruption of the running action through cooperative cancellation. No threads are killed, no plan skips normal selection, and an action that never observes its cancellation signal runs to completion. Sub-Issue 4 carries the motivation, API shape, full semantics, and acceptance criteria.

I prefer `interruptCurrentAction` over spellings like `preemptive`, which reads like a priority or lane system this proposal does not introduce.

### Fact Matching and Rearm Semantics

This is the lifecycle piece I think Evolving Mode needs to own, not something every consumer should hand-roll: a rule fires once per fact observation; `resumable()` completion consumes that rule-local activation without retracting the underlying blackboard object; the same observation never re-fires the rule or keeps selecting the completed handler, and a later matching observation rearms it.

Multiple simultaneously active observations for the same rule also need deterministic v1 behavior: queue one at a time, coalesce activations by policy, or fail fast — and the default must be documented. Consumers should not infer keyed goal-instance semantics from payload fields.

The activation bookkeeping should be internal. Consumers should not manage activation ids, consume records, or internal activation state. Sub-Issue 1 carries the detailed lifecycle and acceptance behavior.

Source disappearance before completion is real, but I wouldn't put it in the first public API. It can be a later lifecycle policy once the core activation, dedupe, completion, and rearm behavior is proven. A consumer should not need to emit sentinel `none()` facts or manually clear internal activation state, in my opinion.

### Completion Bookend

The long-running objective needs an explicit completion rule. For example, "collect samples until 500 samples are stored" can be represented by a completion policy or a higher-level objective completion helper that compiles to one. The count can come from the latest visible progress fact or from consumer-owned state read by the completion policy. Reading from the process blackboard is fine; the Blackboard Model's warning is about direct external mutation.

The Motivating Example shows the completion policy inline; a higher-level objective API could compile to the same thing:

```java
.completeWhen(SamplesStored.atLeast(500))
```

The terminal condition is part of the contract.

In an evolving invocation, completing a runtime goal or incidental declared goal should not complete the long-running process unless `CompletionPolicy` or an explicit terminal objective says the process is complete. The mechanism that guarantees this — and why the guarantee belongs to the invocation rather than to `AgentProcess` — is covered in Sub-Issue 1.

If the long-running objective is not complete and no action is currently selectable, the process should enter `WAITING` rather than complete or keep invoking Open/Supervisor mode. This is an explicit engine change, not a description of current behavior: today plan-not-found leads to `STUCK`, and completing the last active runtime goal terminates the process as `EXHAUSTED`. Sub-Issue 1 scopes the change.

Completing a runtime goal does not end the long-running process. Phase 1 can prove that through internal discovery: an action produces a runtime fact, the process adds and completes a runtime goal, and normal run/tick mechanics keep the process alive. External ingress supplies the main wake-up path for the consumer application later.

### Objective Author Relationship

I see two useful evolving invocation shapes:

- deterministic evolving: `withEvolution(...)` supplies the runtime-goal policy directly
- open evolving: `withObjectiveAuthor(...)` uses Open-style deliberation to author an `ObjectivePlan`

This should follow the same safety shape as Open mode: LLM-backed authoring can rank or select among declared scoped goals and propose objective-specific policy, but execution only uses validated scope objects.

An `ObjectiveAuthor` should author typed objectives or objective plans. Those plans may include initial facts, completion rules, and runtime-goal rule data, such as "on this fact, pursue that declared goal as resumable work." The framework validates the authored plan against the active scope before installation — at launch for initial plans, at the planning tick for Open Evolving revisions (Sub-Issue 5).

The two shapes divide the work cleanly:

- **Deterministic evolving** covers known fact types mapping to known declared goals — the fluent policy path. If a fact type is known enough to predeclare, it belongs here.
- **Open evolving** is the generic re-authoring mechanism for what no predefined rule covers: an unknown blocker, no viable plan, or unresolved runtime facts at a planning tick. It should not require predeclared per-type hooks like `.onUnhandledFact(X.class)` — that would just be deterministic evolving with extra steps.

Authored plans should reference **generic declared goals with typed targets**, not one declared goal per concrete instance. A scope declares `PermitObtained`; the authored plan binds it to `Permit("P-42")` as a typed target fact. The LLM authors objectives and plans; it never authors executable code, and the hot loop stays deterministic. Sub-Issue 5 carries the mid-run re-authoring flow — a worked example, trigger points, and acceptance criteria; phases 1-3 prove the deterministic engine it rides on.

If both inputs are allowed, they could combine into one validated plan:

```text
ObjectiveAuthor plan
  + explicit withEvolution policy
  -> validated merged ObjectivePlan
```

The important point is that Open-style authoring stays outside the hot loop: runtime actions emit typed facts rather than inventing new goals.

### Policy Sources

Policy reaches a process from two places:

- **Base policy** — installed by the consumer application for every process of a class: hazard handling, blocking prompt recovery, disconnect/session recovery.
- **Objective policy** — authored for a particular run: periodic side tasks such as a sensor calibration check, objective-specific event rules, completion rules, utility defaults, and initial facts.

The `ObjectiveAuthor` should not need to remember universal safety and recovery rules. A launch-time validation step should merge base policy and objective policy, canonicalize all runtime-goal targets against scope, and reject the plan if any required handler has no scoped producing capability.

### Future Extension: Fact Derivation

I see fact derivation as a related future layer, not a prerequisite for the core runtime-goal engine. A later layer may derive runtime facts from current blackboard state before planning; for example, a process might derive `StorageNeeded` from a `BufferSnapshot` plus objective context.

Core Evolving Mode can begin with runtime facts already present on the blackboard: action-produced facts, initial objective facts, and externally ingested facts once the phase 2 ingress API exists.

That layer needs its own contracts for ordering, provenance, identity, retraction, and side-effect discipline. It should not block phase 1 or phase 2; both can feed the same evolution policy without introducing a derivation API.

### Validation Rules

- Runtime goals MUST be canonicalized against the active scope.
- Runtime-goal rules MUST identify their declared goal unambiguously. Both spellings are explicit targets: stable goal identity (`GoalTarget.named(...)`) and an output type that resolves to exactly one scoped declared goal (`GoalTarget.output(...)`, used in the examples).
- A target runtime goal with no producing scoped capability MUST be rejected fast.
- A runtime goal that has a scoped producer but is currently blocked by missing facts or preconditions SHOULD remain active and allow the process to wait until a plan becomes available.
- Ambiguous output-type matches MUST fail unless the rule identifies the declared goal by stable goal identity or explicit goal reference.
- Forged lookalike goals MUST NOT be able to smuggle different value, preconditions, or metadata through the runtime-goal API.
- Making ordinary work yield at planning ticks SHOULD be modeled with existing planning availability primitives: consumer-authored `@Condition` methods and `@Action(pre = ...)` preconditions make ordinary work unavailable while a domain condition holds. Goal and action values remain ordinary planner inputs, not an Evolving-specific priority or lane mechanism. Availability governs what is selected at the next planning tick; interrupting an action that is already executing is the cooperative-interruption concern (`interruptCurrentAction()`), never a priority mechanism.
- `interruptCurrentAction()` MUST be cooperative only: it requests cancellation through the action-scoped token, never kills threads, never bypasses planning, and non-cooperative actions run to completion.
- Authored plans MUST reference declared goals with typed targets. Authoring MUST NOT introduce executable code, and per-instance declared goals (one goal per concrete target) SHOULD NOT be required where a generic goal plus typed target works.
- Memoryless ongoing-state reactions SHOULD usually remain plain value-selected actions under Utility/Hybrid planning.
- Runtime-goal handlers selected by `.onFact(...).handleWithGoal(...)` MUST be goal producers in the active scope, for example by producing the goal's satisfied-by type and, in annotation style, using `@AchievesGoal` where appropriate.
- Fact observation identity MUST be carried internally for dedupe, rule-local activation consumption, and rearm. For phase 1, this is framework-owned activation identity, not domain equality.
- Adding a runtime goal SHOULD request replanning on the next planning tick. Normal Embabel planning MUST choose among the active runtime goals using ordinary goal and value arbitration; declared actions from the scoped capabilities remain the only executable steps.

### Non-Goals

- Do not introduce a new `PlannerType`.
- Do not repeatedly invoke Open/Supervisor mode as the event loop.
- Do not mutate user-declared `@Agent` or `@EmbabelComponent` metadata.
- Do not add agents/actions/capabilities to an already-running process in the first implementation pass; this epic realizes the runtime-goals half first and treats process-local scope expansion as a later phase (Sub-Issue 6).
- Do not solve pub/sub fan-out in this issue.
- Do not require fact derivation to land before the runtime-goal engine.
- Do not generate executable code at runtime: `ObjectiveAuthor` proposes references to declared goals with typed targets; execution only ever runs validated scope objects.
- Do not add per-type hooks for the open path (no `.onUnhandledFact(...)`): known fact types belong in deterministic evolution policy; Open Evolving is the generic re-authoring mechanism.
- Do not provide persistence or rehydration in this epic: an evolving process is JVM-resident and single-instance. Fact-observation and hidden-object bookkeeping are identity-based and do not survive serialization; a durable, restartable long-lived-process story is separate future work.

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
- A standing value-selected activity and a resumable runtime goal compose: the occurrence handler wins ordinary arbitration, runs once, and standing work resumes.
- The phase 1 demonstration should be occurrence-shaped (discover -> handle once -> consume -> rearm, like `StorageNeeded`), not a steady-state collection loop alone. Dogfooding a complete collect/store/hazard loop showed it runs on conditions, values, and completion policy alone — a loop-shaped demo would exercise none of the runtime-goal machinery.

### Open questions

Maintainer input would help on these decisions; the rest of the proposal takes a position.

- **Does the immutable-declared interpretation match the README's intent?** This proposal reads "modify a running process to add further goals" as process-local runtime goals over immutable declared capabilities — never mutation of declared agent metadata. #1725 showed the sentence is ambiguous (an actions-mutate-the-action-list reading was proposed in-thread before converging here). This is the proposal's largest interpretive commitment and the one only the README's author can ratify.
- **Reuse existing planners, or a new planning mode?** The proposal changes the effective goal set per planning tick and reuses GOAP/Utility/Hybrid unchanged, rather than adding a dedicated evolving planner. Confirm this is the preferred direction.
- **Method names.** The spellings in this draft (`onFact`, `handleWithGoal`, `resumable`, `ingress()` vs `facts()`, `completeWhen`) are placeholders. What matters is that a rule reads as targeting a goal rather than declaring a new action, and that ingress reads as entering a running process. `resumable` is the placeholder most likely to mislead — it can read as restoring a suspended plan stack, which it never does; alternatives like `consumeOnCompletion()` or `completeAndRearm()` may say it better.
- **Goal targets.** Whether a rule names its declared goal by direct reference, by an output type that resolves to exactly one scoped goal, or both. The examples use an illustrative `GoalTarget.output(CalibrationCompleted.class)` shape; `GoalTarget.named("calibrationCompleted")` would be the explicit-reference escape hatch when an output type is ambiguous in scope. Either way the target is canonicalized against the active scope.
- **Payload binding.** How much fact-payload binding, if any, belongs in v1. The core primitive carries fact observation identity for dedupe and rearm; payload delivery can layer on later. For v1 I'd have handlers re-sense current state or read objective/context facts rather than rely on payload-to-goal binding.
- **Combining authoring inputs.** Whether `withObjectiveAuthor(...)` and an explicit `withEvolution(...)` policy can be supplied together and merged into one validated `ObjectivePlan`, or whether supplying both is rejected.
- **`interruptCurrentAction()` sequencing.** Cooperative interruption is specified as Sub-Issue 4. Should it land immediately after phase 1 (consumers with long blocking actions need it early), or wait for phase 2 ingress, where urgent external facts make it most visible?

## Sub-Issue 1

### Title

Evolving Mode phase 1: internal runtime facts activate process-local runtime goals

### Body

Implement the core Evolving Mode engine for internally produced runtime facts.

This phase does not require external ingress. It should be dogfooded through internal discovery: a normal action discovers a follow-up need, returns a typed runtime fact, and the evolution policy turns that fact into a process-local runtime goal over the existing scope. Existing GOAP, Utility, or Hybrid planning decides what runs.

Note the distinction from the parent issue's `sampleTrayFull` example: `StorageNeeded` here is a discovered unit of work ("store this batch"), not the ongoing tray-full state — the ongoing state remains a `@Condition`. The occurrence is what the run must remember; the condition is what the world keeps showing.

This should be deliberately framework-native: runtime facts are ordinary typed action outputs on the blackboard, and runtime goals use Embabel's existing type, condition, binding, and planner semantics. They do not introduce keyed goal-instance satisfaction. Actions that should handle repeated runtime goals should use existing repeatability mechanisms such as `canRerun = true`, or existing state-clearing/replacement patterns.

Here, `resumable()` means the runtime goal is removed after completion, its rule-local activation is marked consumed, and the process returns to normal planner action selection — not the restoration of a suspended plan stack.

Rules compile to process-local runtime-goal records with:

```text
one resolved goal from the current process scope
fact observation identity
resumable completion behavior
```

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

The "does not run the handler again as ordinary utility work" guarantee is the subtlest part of the engine: under GOAP it falls out of goal-path pruning, but Utility/Hybrid value selection will re-select the handler while the source fact stays visible. The concrete mechanisms belong in implementation notes; the guarantee belongs under the acceptance test.

This also covers the long-lived process bookend: runtime-goal completion is not process completion. The incidental-goal guarantee is mechanical: the evolving invocation constructs its process agent with an empty declared-goal set, so runtime goals are the only source of process completion; incidental declared goals cannot complete a process that never carries them as its own. Standing value-selected work needs no goal path, so an empty goal set does not idle the process. Direct `AgentProcess` use without that construction keeps traditional complete-on-goal semantics — the guarantee belongs to the invocation.

When completion policy says to continue but no action is selectable, the process moves to `WAITING`.

The idle semantics are new engine work scoped to this phase. In the current engine, plan-not-found sets `STUCK`, and completing the last active runtime goal terminates the process as `EXHAUSTED` — an evolving process would die on the next tick instead of parking for the next fact. Phase 1 changes that for evolving processes: `CompletionPolicy` continue plus no selectable action parks the process `WAITING`, and running out of active runtime goals alone does not terminate.

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
        .handleWithGoal(GoalTarget.output(StorageCompleted.class))
        .resumable()

    .onFact(HazardDetected.class)
        .handleWithGoal(GoalTarget.named("hazardHandled"))
        .resumable()
)
```

A runtime rule identifies the declared goal it targets, and that target is canonicalized against the active scope before planning. Ambiguous matches fail with a helpful message. The example shows both target spellings: unique output type and stable goal identity.

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
- completing all active runtime goals alone does not terminate an evolving process (today's `EXHAUSTED` path is bypassed while `CompletionPolicy` says continue)
- standing value-selected work continues under Utility/Hybrid planning; when an occurrence fact activates a resumable runtime goal, the handler wins ordinary arbitration, runs once, consumes its rule-local activation, and standing work resumes without the completed handler re-running as ordinary utility work
- concurrent same-type observations follow the documented default (queue, coalesce, or fail fast) deterministically
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
Given a process with onFact(StorageNeeded).handleWithGoal(GoalTarget.output(StorageCompleted)).resumable()
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

`process.ingress()` implies that selected external input enters a known running process, queues safely, drains at planning ticks, and can wake the process. A public type named `ProcessIngress` or `ProcessFactIngress` could sit behind this spelling, and it keeps the blackboard as process working memory rather than making it sound like the caller's mutation surface. `process.facts()` is the friendlier option — "publish a typed fact to this running process" matches the action input/output mental model — but embabel-agent has blackboard objects today, not a first-class `Fact` module, so this spelling introduces new vocabulary.

I wouldn't bless raw `process.blackboard().add(...)` as the happy path: it carries no planning-tick timing, wake-up behavior, fact observation identity, duplicate semantics, or observability.

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
- only `publish` is safe from foreign threads; draining, rule matching, completion, approvers, and runtime-goal bookkeeping run on the tick thread and stay thread-confined. Ordering between concurrent action outputs and ingress drains (relevant when actions run in parallel) should be stated explicitly.

Run-loop ownership after wake needs an explicit decision. Waking a `WAITING` process is a status flip; something must then re-drive it to an actual planning tick. Two models exist: consumer-driven — the consumer application owns a driver loop and calls `tick()`/`run()` (the model the validating consumer application uses today) — or platform-driven, where a platform executor re-dispatches woken processes. Phase 2 should pick one, or support both, explicitly.

Acceptance criteria:

- consumer application code can publish selected facts to a known running process
- publication is thread-safe and does not mutate the blackboard directly
- facts drain at planning ticks
- occurrence facts can be deduped or repeated by fact observation identity
- external ingress can wake the process without starting a new process
- publishing to a stopped, completed, or detached process returns a deterministic rejected/no-op result and never rebinds the fact to a later process
- run-loop ownership after wake is specified and tested: a woken process is re-driven to an actual planning tick (by the consumer driver or a platform executor), not merely flipped back to a runnable status
- external ingress can activate the same runtime-goal rules as action-produced facts

Out of scope:

- latest/state publication replacement or coalescing unless explicit lifecycle semantics are defined (distinct from the rule-activation default in Fact Matching)
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

## Sub-Issue 4

### Title

Evolving Mode phase 4: cooperative interruption of the running action (`interruptCurrentAction()`)

### Body

Add an optional rule semantic that couples an urgent runtime goal to graceful interruption of the currently running action.

Adding a runtime goal requests replanning at the next planning tick — it does not interrupt an action that is already executing. Consumer applications routinely do long-running or blocking work inside a single action — navigation, tool calls, network calls, database queries, LLM calls — and should not have to split those into artificial planning-tick-sized steps just to stay responsive to urgent facts.

Suggested API shape:

```java
.withEvolution(evolution -> evolution
    .onFact(UrgentHazardDetected.class)
        .handleWithGoal(GoalTarget.output(HazardHandled.class))
        .interruptCurrentAction()
        .resumable()
)
```

Semantics:

- installs the runtime goal from the observed fact as usual
- requests graceful interruption of the currently running action through cooperative cancellation — an action-scoped cancellation signal exposed to action code
- does not kill threads
- does not bypass normal planning: the planner reselects when the interrupted action yields, and ordinary goal/value arbitration decides what runs next
- non-cooperative actions are not forcibly stopped; an action that never observes its token simply runs to completion
- `resumable()` keeps its meaning: after the runtime goal completes, its rule-local activation is consumed and the process returns to normal planner action selection

Embabel already has action-scoped graceful termination machinery in `AgentProcess.terminateAction` / `TerminationScope.ACTION`; this work defines how urgent runtime-goal rules request it and how cooperative cancellation is exposed to action code. Blocking action code observes cancellation through the action-scoped signal on its context and can pass it to a client's native cancel (an HTTP call, a statement, a navigation step loop). The naming avoids `preemptive`-style vocabulary, which would imply a priority or lane system this epic does not introduce.

Acceptance criteria:

- a rule with `interruptCurrentAction()` installs its runtime goal and requests cancellation of the in-flight action via the action-scoped token
- a cooperative blocking action observes the token, yields early, and the next planning tick can select the handler path through normal arbitration
- a non-cooperative action runs to completion; the process stays consistent and replans afterward
- rules without `interruptCurrentAction()` never interrupt the running action
- no threads are killed; no action runs without normal planner selection
- rules can be triggered by action-produced facts, and by ingressed facts once phase 2 lands; interruption has an effect only when a target action is currently running

Out of scope:

- forced or timeout-based termination of non-cooperative actions
- suspending and restoring a partially executed action (interrupt means cancel and replan, not pause and resume)
- priority or lane semantics

## Sub-Issue 5

### Title

Evolving Mode phase 5: Open Evolving — `ObjectiveAuthor` re-authoring at a planning tick

### Body

Support revising the `ObjectivePlan` mid-run when the process encounters something no predefined rule covers: an unknown blocker, no viable plan, or unresolved runtime facts.

Deterministic evolving covers known fact types mapping to known declared goals — the fluent policy path. Open Evolving is the generic re-authoring mechanism. It should not require predeclared per-type hooks like `.onUnhandledFact(X.class)`; if a fact type is known enough to predeclare, it belongs in deterministic policy.

Illustrative flow:

```text
current objective:  collect samples in Zone A until 500 are stored
discovered fact:    ZoneAccessBlocked(zone = A, missingRequirement = Permit("P-42"))
                    (no predefined rule maps this)

Open Evolving at a planning tick:
  ObjectiveAuthor receives the current objective, active facts, recent failures, and scope
  proposes: runtime goal PermitObtained with typed target Permit("P-42")
            (and, with Sub-Issue 6, adding a declared PermitCapability to the
             process-local scope if it is not already present)

framework:  validates PermitObtained against the active scope (the expanded scope when the
            proposal pairs it with a capability addition), validates the typed target,
            optionally asks an approver, installs the approved runtime goal
planner:    executes declared permit actions deterministically
completion: the runtime goal is consumed; sample collection resumes
```

Authored proposals reference generic declared goals with typed targets (`PermitObtained` plus `Permit("P-42")`), never one declared goal per concrete instance, and never executable code. This mirrors Open mode's validated-goal-choice discipline: ranking and selection over declared scope, with approval before anything is installed.

Consultation triggers need definition; candidates:

- an action emits a fact no policy rule maps, and re-authoring is configured for the process
- plan-not-found while `CompletionPolicy` says continue — consult the author before parking `WAITING`

Acceptance criteria:

- re-authoring happens only at a planning tick, never during action execution
- authored proposals are validated with the same rules as launch-time plans (canonicalization, no-producer rejection, ambiguity rejection) — against the active scope, or atomically against the proposed expanded scope when a proposal pairs a goal with a capability addition (Sub-Issue 6)
- an approver can veto a proposed revision; a vetoed revision leaves the process in its prior state
- proposals reference declared goals with typed targets; no executable code is installed
- the author stays out of the hot loop: consultation happens on defined triggers, not per tick
- an installed revision composes with the existing activation/consume/rearm lifecycle

Out of scope:

- per-type open hooks (`.onUnhandledFact(...)`)
- authoring new executable actions or goals (scope expansion is Sub-Issue 6; code generation is a non-goal of the epic)
- multi-process coordination

## Sub-Issue 6

### Title

Evolving Mode phase 6: process-local scope expansion (the "add further agents" half)

### Body

Realize the second half of the README sentence: adding more scoped `@Agent` / `@EmbabelComponent` instances to a running process, so their declared actions, conditions, and goals become visible to the planner on the next planning tick.

This is process-local expansion, not mutation of global declarations. The instances already exist as declared capabilities; expansion changes which of them are in this process's scope. Global agent metadata is never touched, and other processes are unaffected.

It composes naturally with Sub-Issue 5: an Open Evolving revision may propose both a runtime goal and the capability that produces it — the permit example adds `PermitCapability` to the process-local scope, then installs `PermitObtained` with `Permit("P-42")`.

Design points:

- expansion source: a consumer-facing API on the running process, `ObjectiveAuthor` proposals, or both
- validation: expanded capabilities go through the same metadata reading and scope canonicalization as launch-time scope
- atomic proposals: a runtime goal proposed together with its producing capability validates as one unit against the *expanded* scope, and neither installs unless both pass. This does not contradict phase 1's reject-fast rule: phase 1 rejects a no-producer goal against the *current* scope; expansion changes the scope the rule evaluates against before the goal is installed
- name/type collisions with the existing scope fail fast
- contraction (removing capabilities mid-run) is a separate concern and likely out of scope for the first pass

Acceptance criteria:

- a declared capability instance can be added to a running process's scope at a planning tick
- its actions, conditions, and goals are visible to the planner on the next tick
- global agent metadata is never mutated; other processes are unaffected
- a runtime goal proposed with its producing capability installs atomically: validated against the expanded scope, installed only after both pass
- expansion is validated and rejectable through the same approval step as authored plans

Out of scope:

- removing or contracting scope mid-run
- hot-loading new code; expansion only references already-declared capabilities
- cross-process scope sharing

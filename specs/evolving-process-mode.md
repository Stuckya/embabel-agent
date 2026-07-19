# Evolving Process Mode

> **Status: historical throwaway-POC implementation notes.** `GoalAgenda`,
> activation keys, agenda projection, `CompletionPolicy`, and the other types
> below describe the local feasibility branch, not the current upstream
> proposal. Baseline tests and maintainer discussion in issue #1756 narrowed
> deterministic phase 1 to lifecycle around existing declared goals: complete
> an episode without ending the process, hide its request and satisfying output,
> and allow a later request to run it again. The current Phase 1 proposal puts
> that episode policy on `ProcessOptions` and does not require
> `EvolvingInvocation`. See
> `evolving-mode-github-issue-draft.md` for the canonical design.

## Overview

Embabel already supports invocation-time agent and goal selection through Autonomy
and scoped invocation through `UtilityInvocation` and `SupervisorInvocation`.
Event-driven domains need the same kind of public invocation shape for
long-lived processes whose objectives can change while running.

Evolving Process Mode fills that runtime seam without introducing a new
`PlannerType` or repeatedly invoking Open/Supervisor mode. It lets a running
agent process add and remove or expire active runtime goals while preserving
Embabel's existing planner arbitration model.

Evolving mode composes with GOAP, Utility, and Hybrid planners by changing the
effective planning system available to a process at OODA seams. Agenda
projection does not mutate `Agent.goals`.

The current implementation is a local throwaway POC for the roadmap shape
described in the README: a process can work with multiple goals and modify the
running process as new facts make additional agenda goals relevant. The POC code
and names are not intended to be PR'd as-is; the upstream contribution should be
based on the contracts and acceptance tests that survive dogfooding.

V1 evolves agenda entries over the `AgentScope` created for the process. The
current POC runs `ObjectiveAuthor` after that scope has been created. Choosing
or assembling scope based on the objective before launch is future work, and
adding new scoped `@Agent` or `@EmbabelComponent` instances to an already-running process
is out of scope for this POC.

The upstream proposal should be incremental:

1. internal action-produced runtime facts: normal action outputs activate
   process-local runtime goals
2. external triggers/events: facts published by the consumer application enter a
   known running process through sanctioned ingress and feed the same evolution engine
3. observability support: each wake-up remains attached to the existing
   process/session with clean turn boundaries and runtime-goal lifecycle events

Pub/sub fan-out is a related but separate problem. Evolving Mode only requires a
single running process to evolve its own runtime goals over its configured
scope.

## Problem Statement

Embabel can compose a plan at process start, but long-lived domains need a
running process to remember and complete selected follow-up work as facts appear
without turning the runtime loop over to an LLM or forcing each agent to
manually orchestrate other agents.

For example, a consumer objective like "Collect samples in Zone A until 500
samples are stored" must react while running:

- workspace becomes full -> application state changes and storage actions become
  achievable or valuable through conditions
- session reaches storage -> deposit samples
- workspace becomes empty -> return to the collection zone
- nearby item appears -> collect it
- hazard appears -> respond safely
- 500 samples stored -> complete

Without a runtime seam, reusable agents are forced into awkward shapes:

- one giant agent that owns navigation, storage, collection, safety, and state
  tracking
- direct agent-to-agent calls that bypass planner composition
- Utility AI loops that become hand-maintained finite-state machines
- repeated Open/Supervisor/LLM orchestration, which is too nondeterministic and
  expensive for a hot loop
- continuous activity encoded as normal GOAP goals, which stop driving work once
  satisfied

The missing seam is:

```text
selected fact -> approved runtime objective -> planner-visible goal -> deterministic action
```

Evolving Process Mode provides that seam so purpose-built capabilities such as
navigation, storage, collection, hazard response, procurement, and reference
lookup can remain reusable while the process composes them in response to live
session state.

## Terminology

- Objective: domain-level desired outcome or request passed to
  `EvolvingInvocation`, such as `CollectSamplesUntil`. This is not an Embabel
  planner `Goal`.
- ObjectivePlan: validated proposal for satisfying an `Objective`. It may
  contain initial facts, agenda entry specs, activation rules, and completion
  rules.
- ObjectiveAuthor: deterministic or LLM-backed authoring seam that converts
  user input or an `Objective` into typed `Objective`s or `ObjectivePlan`s. It
  does not execute runtime actions and does not mutate the agenda directly.
- Goal: Embabel planner goal.
- EvolutionPolicy: process-local policy that maps selected runtime facts to
  runtime goals over the active scope.
- AgendaEntry: runtime wrapper that projects a `Goal` into the active process
  with bindings, completion mode, activation key, and source context.
- ActivationTrigger: local POC primitive for typed ingress latches. It is not
  the intended happy-path upstream API; normal consumers should use
  `EvolutionPolicy` rules over facts rather than manage trigger taxonomy.

## Public Interface: EvolvingInvocation

Status: Implemented POC public interface. The low-level runtime substrate starts
below in "Implemented POC Surface."

`EvolvingInvocation` is the Embabel-shaped public interface for this mode,
parallel to `UtilityInvocation` and `SupervisorInvocation`. It implements the
existing `ScopedInvocation` and `BaseInvocation` contracts, so it inherits
`withScope`, `withProcessOptions`, `withAgentName`, `run`, and `runAsync` rather
than inventing a parallel invocation surface.

The user-facing mental model should be "run this objective over this scope of
capabilities," not "manually mutate agenda entries." `GoalAgenda` remains the
runtime mechanism underneath the invocation.

The public stack is:

- `EvolvingInvocation`: public invocation interface
- `ObjectiveAuthor`: optional LLM or deterministic objective authoring seam
- `GoalAgenda`: runtime mechanism
- `AgendaEntryApprover`: authorization seam
- Planner: deterministic action selection over declared actions

Target LLM-assisted shape:

```java
EvolvingInvocation.on(agentPlatform)
    .withScope(AgentScopeBuilder.fromInstances(
        navigationCapabilities,
        storageCapabilities,
        collectionCapabilities,
        hazardResponseCapabilities
    ))
    .withObjectiveAuthor(llmObjectiveAuthor)
    .run(new UserInput("Collect samples in Zone A until 500 samples are stored"));
```

Target deterministic shape:

```java
EvolvingInvocation.on(agentPlatform)
    .withScope(scope)
    .withEvolution(collectSamplesUntilEvolution)
    .run(new CollectSamplesUntil(
        Zone.A,
        SamplesStored.atLeast(500)
    ));
```

`withObjectiveAuthor(...)` is the intended high-level path for objective-driven
use. `withEvolution(...)` is the low-level escape hatch backed by
`EvolutionOptions`; it is useful for tests, explicit control, and advanced
callers.
`run(...)` and `runAsync(...)` create and start a process. Reactive systems that
need to publish ingress before the first tick, or drive `tick()`/`run()` from an
external loop, can call `createProcess(...)` to receive a configured but
unstarted `AgentProcess`.
Scope instances may be `@EmbabelComponent` or `@Agent` instances, including
domain capabilities packaged with those annotations.

Manual-drive shape, matching the local throwaway POC test fixture:

```kotlin
val process = EvolvingInvocation.on(agentPlatform)
    .withScope(CollectionCapabilitiesAgent)
    .withObjectiveAuthor(objectiveAuthor)
    .createProcess(CollectSamplesUntil(zone = "zone-a", target = 1))

process.ingress.publish(
    StorageNeeded("zone-a"),
)
process.tick()
```

## LLM Objective Authoring

Status: `ObjectiveAuthor`, `ObjectiveAuthorRequest`, and `ObjectivePlan` are
implemented as a deterministic authoring seam. LLM-backed author
implementations and Autonomy adapters remain follow-up work.

An `ObjectiveAuthor`, possibly using Open-style LLM deliberation, authors typed
`Objective`s or `ObjectivePlan`s. That keeps LLM use at the objective-authoring
seam described in the Deterministic Runtime Contract rather than in the hot
runtime loop.

An LLM can help translate natural language into typed `Objective`s or decompose
a long-running objective into proposed sub-objectives. Objective-dependent scope
assembly before process launch is a future extension; the current POC provides
the configured `AgentScope` to `ObjectiveAuthor`. It does not directly drive
each tick.

An `ObjectiveAuthor` can be LLM-backed or deterministic. In both cases, its
output should be typed, validated against the active `AgentScope` produced by
the configured `AgentScopeBuilder`, and converted into agenda entries before
process launch. Entry activation is still authorized by `AgendaEntryApprover`.

LLM-backed objective authoring should reuse Autonomy, `Ranker`,
`GoalSelectionOptions`, and `GoalChoiceApprover` where applicable. Current
Embabel Open mode ranks existing goals and assembles a goal agent; it does not
author `ObjectivePlan`s, so an Open/Autonomy-backed `ObjectiveAuthor` remains a
follow-up rather than existing Open mode behavior.

## Deterministic Runtime Contract

- `ObjectiveAuthor` proposes typed `Objective`s or `ObjectivePlan`s.
- The framework validates them against the active `AgentScope`.
- `ObjectivePlan`s are compiled into agenda entries and/or `EvolutionPolicy`
  rules. The POC `ObjectivePlan` can carry agenda entries and policy directly;
  richer objective-to-entry compilation remains a Remaining POC Gap.
- `AgendaEntryApprover` authorizes entry activation.
- The evolving process projects approved entries into planner-visible agenda
  goals.
- The planner executes declared actions only.
- LLMs do not run the hot loop.
- LLMs do not directly mutate the agenda.
- Runtime facts enter through ingress.
- Hard interruption is modeled with existing planning availability primitives:
  consumer-authored `@Condition` methods and `@Action(pre = ...)` preconditions
  can make ordinary work unavailable while a domain condition holds.

This contract is the core boundary between flexible objective authoring and
deterministic execution.

## Crew/CrewAI Mental Model Mapping

Event-driven applications often want a CrewAI-like mental model without
importing CrewAI's runtime semantics into Embabel.

The mapping is:

- Crew/capability set -> `AgentScopeBuilder`
- Purpose-built agents -> reusable Embabel `@Agent` or `@EmbabelComponent` instances
- Task/objective -> typed `Objective` or `ObjectivePlan`
- Manager/dispatcher -> `EvolvingInvocation`
- Guardrails, in CrewAI's sense of task authorization -> `AgendaEntryApprover`
- Execution -> GOAP, Utility, or Hybrid planner over declared actions

The important distinction is that the evolving process does not become a
chatty supervisor. It keeps Embabel's typed blackboard, planner, and action
model as the execution substrate.

## Consumer Validation Scenario

A consumer should be able to express a long-running objective such as "Collect
samples in Zone A until 500 samples are stored" over scoped navigation,
collection, storage, and hazard-response capabilities. Current state such as
workspace contents, location, hazards, nearby items, and progress remains owned
by the consumer application and is exposed through action inputs and
`@Condition`. Selected facts that represent process-local work items can enter
through ingress; approved runtime objectives become planner-visible agenda goals.

Normal Embabel planning handles the main loop from current state. Evolving Mode
is for side work or newly discovered objectives that should be tracked until
handled, such as a calibration request, a scheduled maintenance task, or a hazard
incident that should complete as `HazardHandled`.

The important target is composition without manual orchestration:
the collection capability should not need to directly call storage or
navigation capabilities, whether those capabilities are packaged as agents or
`@EmbabelComponent` modules. The hot loop should not become repeated
Supervisor/Open mode.

## Open Mode Relationship

Open/Supervisor mode is still valuable for deliberation, interpretation, and
objective discovery. It should not be repeatedly invoked as the live control
loop for event-driven domains.

Open-style deliberation can inform an `ObjectiveAuthor`, but the Deterministic
Runtime Contract still applies: proposals are typed, validated, approved, and
then executed through normal planner action selection.

This preserves the useful part of Open mode without making nondeterministic,
expensive LLM orchestration the response to every state update or work item.

## Implemented POC Surface

Status: Current low-level implementation. This is the runtime mechanism under
the proposed public interface above.

### EvolutionOptions

`ProcessOptions.evolution` carries an `EvolutionOptions` value with:

- `agendaCatalog`: an activatable catalog of agenda entries
- `policy`: process-local rules that map selected facts to runtime goals
- `agendaEntryApprover`: an approval seam for catalog activation and runtime
  entry proposals
- `completionPolicy`: a process outcome policy

`ProcessOptions.withEvolution` installs an `EvolutionOptions` value.
`EvolutionOptions.initialAgenda` remains as a deprecated alias for
`agendaCatalog`; entries here are a catalog, not active initial state.
`agendaCatalog` is part of the low-level POC surface and should become an
escape hatch beneath `EvolvingInvocation`, not the normal user entry point.

### EvolutionPolicy

`EvolutionPolicy` is the refined POC path for runtime goals.
It maps selected visible process facts to process-local agenda entries. A rule
such as `StorageNeeded -> StorageCompleted` is canonicalized against
the active `AgentScope` before launch, so runtime rules cannot smuggle altered
goal preconditions, values, or metadata through lookalike goals.

At each planning tick, matching visible facts propose runtime agenda entries.
Those entries use the same `AgendaEntryApprover`, planner projection, and
completion behavior as lower-level agenda entries. `RESUMABLE` policy-created
entries consume both stale satisfying outputs and the source fact that fired the
rule, so the same fact does not repeatedly retrigger the same runtime goal.

Actions that handle policy-created runtime goals must be declared as goal
producers in the active scope, for example by producing the goal's satisfied-by
type and, in annotation style, using `@AchievesGoal` where appropriate. Standing
level reactions should usually stay plain value-selected actions under
`NIRVANA`; making a level reaction an achieved goal can accidentally turn normal
utility work into process completion or agenda completion.

This is the behavior the throwaway branch now uses to prove phase 1:
action-produced facts can activate process-local runtime goals without manual
activation-key clearing.

### Local POC Fact Ingress

The local throwaway POC calls its fact-ingress seam `BlackboardIngress` and
exposes it from both `AgentProcess` and `ProcessContext`. This name and exact
shape are not upstream Embabel API.
`publish` queues a typed fact for the next planning tick and returns an
`IngressReceipt`.
`IngressReceipt` acknowledges publication and provides a correlation id for
events, logging, and tests. It does not mean the fact has already been drained
into the blackboard.

The blackboard itself remains Embabel's process-local typed working memory. It
is not a mutable world-state database, pub/sub bus, or agenda latch system.
Action inputs are resolved from the blackboard, action outputs are automatically
appended, and planning conditions are separate booleans normally supplied by
`@Condition`.

Blackboard objects are ordered and append-only. The latest visible object of a
type is the default match; named binding is available when type alone is
ambiguous. Hiding removes an object from future planning and API visibility
without deleting process history. Selected external async facts should therefore
enter through a sanctioned process-local ingress seam and drain at planning ticks
rather than mutating the blackboard directly from consumer application threads.

If a mutable level is modeled as a blackboard fact, the consumer owns that
fact's lifecycle unless explicit ingress lifecycle options are configured. When
the level is no longer true, hide or replace the visible fact, for example with
`Blackboard.hide(...)`, `IngressMode.LATEST`, coalescing, TTL, or a configured
level trigger. Policy-created runtime goals consume their internal rule-local
activation on `RESUMABLE` completion; they do not infer that an arbitrary level
fact should retract just because a reaction ran.

`IngressOptions` describes one publish operation:

- `mode`: `APPEND` or `LATEST`
- `wake`: `NONE` or `WAKE`
- `coalesceKey`: replaces pending ingress with the same key before drain
- `activationKey`: activates matching catalog entries at drain time
- `ttl`: hides the drained fact after the duration expires

`ActivationTrigger` is a local POC primitive for low-level ingress-to-agenda
latches. It is not the desired upstream happy path. A future
`.onFact(...).handleWithGoal(...)` style policy API should sit above any typed
trigger/latch mechanics, so consumers publish domain facts and the framework
owns fact observation identity, rule-local activation, and rearm
behavior.
`ActivationTrigger.level(K, Fact.class)` can be used with
`BlackboardIngress.update(trigger, active, supplier)` for explicit low-level
tests: `false -> true` publishes a fact and activates matching entries,
`true -> true` remains idempotent, `true -> false` rearms the process-private
latch, and `false -> false` is a no-op. Level triggers may opt into
`hideOnInactive()`, which hides the visible level fact at the next planning tick
when the trigger falls false.
`ActivationTrigger.occurrence(K, Fact.class).occurrenceId(...)` can be used with
`BlackboardIngress.occurred(trigger, fact)` for low-level occurrence tests.
Both trigger styles project to the lower-level `activationKey` internally.

`activationKey` bridges ingress to agenda activation: when a fact drains with
activation key `K`, catalog entries with `activationKey == K` are proposed
through the approver if process-private key `K` is not already active. This
makes activation key ingress edge-triggered without using the planning condition
namespace: duplicate publishes while `K` is active are idempotent, and a later
occurrence can rearm through `BlackboardIngress.clearActivationKey(K)` before
the next matching publish. Raw keys and `clearActivationKey` remain low-level
escape hatches for tests, explicit control, and migration.

`BlackboardIngress.publish` is safe to call from non-process threads. It queues
pending ingress under lock and does not mutate the blackboard directly. Normal
blackboard writes happen at planning ticks. Cooperative action cancellation
remains available through `AgentProcess.terminateAction`, but it is not a
separate Evolving-ingress wake mode in the simplified POC.

`BlackboardIngress` is distinct from `ReplanRequestedException`.
`ReplanRequestedException` is initiated by an action or tool loop that is already
running inside the process. Ingress is selected external async fact publication
into a running process. It is not reinventing replanning; it covers the opposite
direction of state change.

Consumer dogfooding found that append-only blackboard facts are a poor fit for
mutable world-state booleans unless hiding, coalescing, or TTL semantics are
explicit. Prefer application-owned state modules plus `@Condition` or action
inputs for high-frequency or reversible current state. Use the POC ingress
lifecycle options only when the fact should become process-visible:

- use `EvolutionPolicy` for facts that should activate process-local runtime
  goals
- use `IngressMode.LATEST`, `coalesceKey`, and `ttl` for external facts whose
  visible state should replace or expire older facts
- explicitly hide or replace consumer-owned level facts when the external level
  falls false
- reserve typed `ActivationTrigger`s for low-level POC tests and explicit latch
  control
- avoid modeling toggled state as permanent append-only facts unless the
  corresponding hide/coalesce rule is part of the design

### GoalAgenda

`GoalAgenda` is an immutable overlay of agenda entries that can be projected
into planning. It exposes `withEntry`, `withoutEntry`, and `expire`, all of
which return a new agenda instance.

The active agenda is visible as `AgentProcess.goalAgenda`. This is useful for
inspection and tests, but normal users should not need to orchestrate by reading
or mutating agenda state directly.

### AgendaEntry

An `AgendaEntry` references a known goal and carries runtime context:

- `id`
- `goal`
- `bindings`
- `source`
- `completionMode`
- `activationKey`
- `ttl`
- `createdAt`
- `completionPredicate`

Completion modes are intentionally small in the first POC:

- `TERMINAL`: satisfying the entry completes the process
- `RESUMABLE`: satisfying the entry removes it, consumes visible outputs that
  satisfy the entry goal, and re-arbitrates
- `COMPOSITE_TERMINAL`: completes only when its completion predicate is true;
  if the child goal is satisfied before the predicate is true, the process
  waits instead of spinning on the already-satisfied goal

`AgendaCompletionPredicate` overlaps with Embabel's existing `Condition`
composition model. `Condition` evaluates through `OperationContext`, while the
POC predicate currently needs `AgentProcess`, `AgendaEntry`, and `GoalAgenda`.
Future completion rules should prefer existing `Condition` composition if an
adapter is enough, or keep `AgendaCompletionPredicate` internal until the public
`ObjectivePlan` shape proves it needs agenda-specific context.

`KEEP_ALIVE` and `RECURRING` are intentionally not part of the POC. Continuous
background utility work should be modeled with the existing `NIRVANA` goal under
the Hybrid planner. Agenda projection preserves the wrapped goal name so
agenda-wrapped `NIRVANA` still reaches Hybrid utility planning.

### AgendaEntryApprover

`AgendaEntryApprover` approves or rejects agenda entry activation. The request
includes the proposed entry, source fact, source type, bindings, current
agenda, and agent process.

The approver is used for both catalog activation and direct runtime proposals
through `AgentProcess.addAgendaEntry`.

The implemented approver is entry-level. Multi-entry `ObjectivePlan`s still need
an owning plan identity and either plan-level validation/approval or a defined
compilation rule, such as individually approved entries plus a
`COMPOSITE_TERMINAL` entry for the objective.

`AgendaEntryApprover` intentionally has a different request payload than
`GoalChoiceApprover`, but it duplicates the same approved/not-approved response
protocol shape. Before promoting it as a stable public API, prefer extracting or
sharing approval response types.

### CompletionPolicy

`CompletionPolicy` evaluates the process and current agenda and returns a
`ProcessOutcome`.

Outcome codes are:

- `CONTINUE`: keep running normal process logic
- `COMPLETED`: mark the process completed
- `EXHAUSTED`: terminate the process as a non-success exhausted outcome
- `CANCELLED`: terminate the process as a cancellation outcome

The current POC maps `ProcessOutcomeCode` to `AgentProcessStatusCode` as
follows:

- `CONTINUE`: no terminal status transition
- `COMPLETED`: `AgentProcessStatusCode.COMPLETED`
- `EXHAUSTED`: `AgentProcessStatusCode.TERMINATED`, with the outcome recorded
  as failure information
- `CANCELLED`: `AgentProcessStatusCode.TERMINATED`, with the outcome recorded
  as failure information

`failureInfo` on a terminated process may therefore contain a non-error
`ProcessOutcome`. Consumers should inspect `AgentProcess.outcome.code` when
they need to distinguish exhausted, cancelled, and failed-style terminations.

`CompletionPolicy` is separate from `EarlyTerminationPolicy`, because early
termination is an existing hard stop path rather than evolving agenda
completion.

### ProcessCancellationToken

`ProcessContext.cancellationToken` exposes a pollable token for blocking action
code. During action execution, the action receives a `ProcessContext` whose
token is scoped to that action and can be passed to delegated worker threads.
The current primitive is `AgentProcess.terminateAction`, backed by
`TerminationSignal(TerminationScope.ACTION)`.

If an action returns normally after observing an action-scope termination signal,
the process records the action as terminated rather than successful progress and
then re-arbitrates.

## Runtime Behavior

Normal ingress drains at OODA tick seams. It does not mutate the blackboard from
the async publish path.

At drain time, the process:

1. expires active ingress whose TTL has elapsed
2. drains pending ingress
3. hides prior visible ingress with the same key for `LATEST` mode
4. adds the new fact to the blackboard
5. activates matching catalog entries through the approver when `activationKey`
   is present and not already active
6. records the drained fact as active ingress for TTL tracking
7. applies `EvolutionPolicy` rules over visible facts to propose runtime goals

TTL expiry hides facts and emits a hidden-ingress event. It does not delete or
mutate facts.

Wake ingress moves a blocked process from `WAITING`, `STUCK`, or `PAUSED` back
to `RUNNING`. Terminal statuses remain terminal.

Catalog entries without an `activationKey` activate at a planning tick once per
entry id. Keyed catalog entries activate when matching ingress is drained and
the process-private activation key transitions from inactive to active.
Duplicate matching ingress while the key remains active does not re-propose the
entry; call `BlackboardIngress.clearActivationKey` when the external trigger has
cleared and should be allowed to fire again. An already active entry id is
rejected.

Typed `ActivationTrigger`s layer over this latch for low-level POC coverage:

- level triggers own the false-to-true edge and false rearm through
  `BlackboardIngress.update`, with optional visible fact hiding via
  `hideOnInactive()`
- occurrence triggers dedupe by occurrence id and reactivate for new occurrence
  ids
- raw `activationKey` remains available for low-level/manual use, but examples
  should prefer policy-driven runtime goals unless they are explicitly testing
  ingress latch behavior

Runtime code can call `AgentProcess.addAgendaEntry` to propose entries directly.
Direct runtime additions are not remembered as one-shot catalog activations, so a
consumer application or action can propose a fresh entry again after the
previous entry is no longer active. This is a low-level escape hatch for tests,
explicit control, and advanced integrations; the normal public path should
compile typed `Objective`s through `EvolvingInvocation`.

## Planning Behavior

Active agenda entries are projected as `AgendaPlanningGoal` values. The wrapper
keeps the underlying goal's semantic name and carries agenda identity on
`AgendaPlanningGoal.entry`.

Active agenda entries are projected for the planning cycle. Runtime goals
compose with standing agenda-wrapped `NIRVANA` through normal planner
arbitration, preserving Hybrid's existing "real goal plus useful next action"
semantics. If any non-`NIRVANA` runtime goal is already satisfied, the process
withholds agenda-wrapped `NIRVANA` for that tick so completion behavior can
consume outputs, hide source facts, and remove resumable entries before utility
work resumes.

Hard interruption is an availability problem, not a framework priority problem.
Consumers can use existing `@Condition` methods and `@Action(pre = ...)`
preconditions to make ordinary work unavailable while a domain condition holds,
then let hazard or recovery actions become the achievable path. Goal and action
values remain the ordinary soft arbitration mechanism when more than one path is
available.

When the active agenda is empty, planning falls back to the agent's base planning
system. Completed resumable agenda goals are suppressed from that base planning
system so a just-satisfied reusable goal is not immediately selected again from
the agent's declared goals.

## Completion Behavior

Plain agent goals still complete the process normally.

Agenda goals use their entry's `AgendaCompletionMode`:

- `TERMINAL` sets a completed outcome and completes the process.
- `RESUMABLE` removes the selected agenda entry, records the underlying goal as
  completed for base-goal suppression, consumes visible blackboard outputs that
  satisfy the entry goal, consumes policy-created rule activation records, sets
  a continue outcome, and re-runs arbitration. This prevents a reactivated entry
  from being immediately satisfied by stale output from its previous activation
  and prevents the same fact observation from repeatedly firing the same rule.
- `COMPOSITE_TERMINAL` completes only when `completionPredicate` returns true.
  If the wrapped child goal is achieved before the composite predicate is true,
  the process moves to `WAITING`.

If a resumable agenda drains and no remaining plan can be found, the process
becomes `TERMINATED` with an `EXHAUSTED` outcome rather than entering the normal
recoverable stuck path.

## Invariants

- Preserve blackboard append/hide semantics: ingress adds and hides objects,
  never mutates or removes them.
- Preserve the OODA loop: normal evolution happens at planning ticks.
- Preserve planner independence: Evolving is not a new `PlannerType`.
- Preserve deterministic activation: typed facts and direct runtime proposals go
  through the same approval seam.
- Preserve event integration: ingress publish, drain, and hide use existing
  process events.
- Preserve Java usability: public contracts use constructors and
  `@JvmOverloads` where needed.

## Test Coverage

The POC has focused tests for:

- agenda projection without mutating `Agent.goals`
- `EvolvingInvocation.createProcess(...)` creating an unstarted process with
  objective-authored evolution, initial facts, canonicalized goals, and manual
  ingress/tick control
- action-produced runtime facts activating process-local runtime goals through
  `EvolutionPolicy`
- `ObjectiveAuthor` returning an `ObjectivePlan` with an `EvolutionPolicy`
- activation keys and one-shot unkeyed catalog activation
- activation-key idempotence for completed keyed `RESUMABLE` entries, with
  explicit false-then-true rearm behavior
- typed level triggers for false-to-true activation, duplicate suppression, and
  false-then-true rearm, including configured hide-on-inactive behavior
- typed occurrence triggers for duplicate occurrence-id suppression and new-id
  reactivation
- direct runtime agenda addition and approval rejection
- duplicate goal names distinguished by agenda entry identity and bindings
- high-value runtime goals winning through normal arbitration
- condition-gated ordinary work becoming unavailable during normal arbitration
- runtime goals composing with agenda-wrapped `NIRVANA` for partial progress
  without starving satisfied runtime-goal completion
- agenda-wrapped `NIRVANA` preserving Hybrid utility behavior
- resumable completion, base-goal suppression, and exhausted agenda handling
- composite terminal completion and waiting behavior
- `CompletionPolicy` outcomes for completed, exhausted, and cancelled
- ingress wake from `WAITING`, `STUCK`, and `PAUSED`
- latest/coalesced ingress hide behavior and TTL hide behavior
- action-scope cancellation through the existing `terminateAction` primitive
- Java construction of `EvolutionOptions`, `AgendaEntry`, and
  `AgendaEntryApprover`
- Java use of `ObjectiveAuthorRequest.objectiveAs(Class<T>)`,
  `AgendaEntry.of(...).with...`, and `Nirvana.NIRVANA`
- Java use of `ObjectiveAuthor` with `EvolutionPolicy`
- Java use of typed `ActivationTrigger` with local POC fact ingress

## POC Boundary and Cleanup Direction

Current conclusion: the branch is directionally right as a throwaway
implementation POC. The upstream proposal should not PR these names and classes
as-is. `EvolvingInvocation` plus `ObjectiveAuthor` are useful target concepts,
while lower-level POC pieces should either become internal implementation
details, be redesigned, or disappear after the desired contracts are expressed
with upstream-friendly APIs.

Keep as conceptual runtime substrate:

- sanctioned process-local fact ingress: the right seam for selected external
  facts entering a running process. The local POC name is `BlackboardIngress`, but
  upstream should treat naming and shape as open. Likely public spellings are
  `process.ingress().publish(...)` as the conservative seam-oriented option or
  `process.facts().publish(...)` as a friendlier facade if Embabel wants to
  introduce first-class `Facts` vocabulary. Do not bless direct
  `blackboard.add(...)` as the happy path for external ingress. Ingress should
  not replace application-owned state modules for high-frequency or reversible
  current state.
- `ProcessCancellationToken`: needed for cooperative blocking actions
- `GoalAgenda` and `AgendaEntry`: the runtime mechanism underneath
  `EvolvingInvocation`
- `CompletionPolicy` and `ProcessOutcome`: needed to distinguish completed,
  exhausted, cancelled, and continue outcomes
- existing tests around ingress, agenda projection, Java construction, and
  completion behavior

Keep, but demote from the normal user-facing path:

- `AgentProcess.addAgendaEntry(...)`
- `AgentProcess.goalAgenda`
- `EvolutionOptions.agendaCatalog`

These should remain available as low-level escape hatches and test hooks, but
normal users should enter through `EvolvingInvocation`, not manual agenda
mutation.

Revisit after more consumer dogfood and framework-level acceptance coverage:

- `ActivationTrigger` and `activationKey`: these are low-level POC primitives.
  Objective handlers, `ObjectiveAuthor`, and `EvolutionPolicy` should own
  higher-level fact-to-runtime-goal mapping. A future upstream API should hide
  trigger/latch taxonomy from normal consumers.
- `AgendaEntryApprover`: duplicates `GoalChoiceApprover`'s approval protocol
  shape, although its request payload is agenda-specific. Prefer a shared
  approval response/protocol abstraction over directly reusing
  `GoalChoiceApprover`.
- `COMPOSITE_TERMINAL` and `AgendaCompletionPredicate`: may belong at the
  future `ObjectivePlan` level instead of a single `AgendaEntry`, especially for
  multi-entry objectives such as "collect all required kit items." Prefer
  existing `Condition` composition or an adapter if it can express the
  completion rule without agenda-specific context.
- completed agenda goal suppression in `AbstractAgentProcess`: may be a
  workaround for reusable goals leaking back into base planning on the low-level
  `AgentProcess` path. `EvolvingInvocation` creates a synthetic agent whose
  planner-visible goals come from the agenda, which should reduce this
  suppression's role for normal evolving usage.
- agenda-wrapped `NIRVANA`: consumer dogfood has validated this path for
  continuous utility work, but keep watching whether it remains natural enough
  for framework-level examples. If it becomes awkward, replace it with
  first-class recurring or standing activity semantics.

Guiding principle:

- `EvolvingInvocation` = public interface
- `ObjectiveAuthor` = optional objective-authoring seam
- `GoalAgenda` = runtime mechanism
- process-local fact ingress = external-event ingress
- `AgendaEntryApprover` = authorization seam
- Planner = deterministic execution

## Remaining POC Gap

- Add batch `ObjectivePlan` approval, including objective identity for
  multi-entry plans such as "collect all required kit items." The current POC
  validates authored agenda goals against the active `AgentScope`, but approval
  remains entry-level through `AgendaEntryApprover`.
- Define richer objective-to-entry compilation for typed `Objective`s. The
  current `ObjectiveAuthor` returns an `ObjectivePlan` that already carries
  agenda entries, which avoids manual runtime `GoalAgenda` mutation but leaves
  reusable compilation helpers as future work.
- Add a framework-level dogfood acceptance test mirroring the external consumer
  scenario: repeatedly collect, store, resume, handle hazards through normal
  arbitration, and complete without stale one-shot goal satisfaction.
- Broaden user-facing examples once the API has more consumer mileage beyond the
  current deterministic `ObjectiveAuthor` and low-level `EvolutionOptions`
  examples.
- Decide whether richer standing activity semantics are needed after the
  agenda-wrapped `NIRVANA` path has more consumer mileage.
- Revisit whether any API should be marked internal or moved before an upstream
  PR.

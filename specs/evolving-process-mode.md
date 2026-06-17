# Evolving Process Mode

## Overview

Embabel already supports invocation-time agent and goal selection through Autonomy
and scoped invocation through `UtilityInvocation` and `SupervisorInvocation`.
Event-driven domains need the same kind of public invocation shape for
long-lived processes whose objectives can change while running.

Evolving Process Mode fills that runtime seam without introducing a new
`PlannerType` or repeatedly invoking Open/Supervisor mode. It lets a running
agent process add, remove/expire, and prioritize active goals while preserving
Embabel's existing planner model.

Evolving mode composes with GOAP, Utility, and Hybrid planners by changing the
effective planning system available to a process at OODA seams. Agenda
projection does not mutate `Agent.goals`.

The current implementation is a POC for the roadmap shape described in the
README: a process can work with multiple goals and modify the running process as
new facts make additional agenda goals relevant.

V1 evolves agenda entries over the `AgentScope` created for the process. The
current POC runs `ObjectiveAuthor` after that scope has been created. Choosing
or assembling scope based on the objective before launch is future work, and
adding new agents, actions, or capability modules to an already-running process
is out of scope for this POC.

## Problem Statement

Embabel can compose a plan at process start, but event-driven domains need a
running process to adapt its goals as external events and facts change without
turning the runtime loop over to an LLM or forcing each agent to manually
orchestrate other agents.

For example, a consumer objective like "Collect samples in Zone A until 500
samples are stored" must react while running:

- workspace becomes full -> store samples
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
event/fact -> approved runtime objective -> planner-visible goal -> deterministic action
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
- AgendaEntry: runtime wrapper that projects a `Goal` into the active process
  with bindings, lane, completion mode, activation key, and source context.

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
- `AgendaEntryApprover`: authorization and safety seam
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
domain capability modules packaged with those annotations.

Manual-drive shape, matching the POC test fixture:

```kotlin
val process = EvolvingInvocation.on(agentPlatform)
    .withScope(CollectionCapabilitiesAgent)
    .withObjectiveAuthor(objectiveAuthor)
    .createProcess(CollectSamplesUntil(zone = "zone-a", target = 1))

process.ingress.publish(
    CollectionActivated("zone-a"),
    IngressOptions(activationKey = "collect-sample", wake = IngressWake.WAKE),
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
- `ObjectivePlan`s are compiled into agenda entries. The POC `ObjectivePlan`
  carries agenda entries directly; richer objective-to-entry compilation remains
  a Remaining POC Gap.
- `AgendaEntryApprover` authorizes entry activation.
- The evolving process projects approved entries into planner-visible agenda
  goals.
- The planner executes declared actions only.
- LLMs do not run the hot loop.
- LLMs do not directly mutate the agenda.
- Runtime facts enter through ingress.
- Safety preemption remains deterministic and approver-gated.

This contract is the core boundary between flexible objective authoring and
deterministic execution.

## Crew/CrewAI Mental Model Mapping

Event-driven applications often want a CrewAI-like mental model without
importing CrewAI's runtime semantics into Embabel.

The mapping is:

- Crew/capability set -> `AgentScopeBuilder`
- Purpose-built agents -> reusable Embabel agents/capability modules
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
collection, storage, and hazard-response capabilities. Runtime facts about
workspace state, location, hazards, nearby items, and progress enter through
ingress; approved runtime objectives become planner-visible agenda goals.

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
expensive LLM orchestration the response to every external state change.

## Implemented POC Surface

Status: Current low-level implementation. This is the runtime mechanism under
the proposed public interface above.

### EvolutionOptions

`ProcessOptions.evolution` carries an `EvolutionOptions` value with:

- `agendaCatalog`: an activatable catalog of agenda entries
- `agendaEntryApprover`: an approval seam for catalog activation and runtime
  entry proposals
- `completionPolicy`: a process outcome policy

`ProcessOptions.withEvolution` installs an `EvolutionOptions` value.
`EvolutionOptions.initialAgenda` remains as a deprecated alias for
`agendaCatalog`; entries here are a catalog, not active initial state.
`agendaCatalog` is part of the low-level POC surface and should become an
escape hatch beneath `EvolvingInvocation`, not the normal user entry point.

### BlackboardIngress

`BlackboardIngress` is exposed from both `AgentProcess` and `ProcessContext`.
`publish` queues a typed fact for the next process seam and returns an
`IngressReceipt`.
`IngressReceipt` acknowledges publication and provides a correlation id for
events, logging, and tests. It does not mean the fact has already been drained
into the blackboard.

`IngressOptions` describes one publish operation:

- `mode`: `APPEND` or `LATEST`
- `wake`: `NONE`, `WAKE`, or `SAFETY_PREEMPT`
- `coalesceKey`: replaces pending ingress with the same key before drain
- `activationKey`: activates matching catalog entries at drain time
- `ttl`: hides the drained fact after the duration expires

`activationKey` bridges ingress to agenda activation: when a fact drains with
activation key `K`, catalog entries with `activationKey == K` are proposed
through the approver if key `K` was not already true on the blackboard. This
makes activation key ingress edge-triggered: duplicate publishes while `K` is
already true are idempotent, and a later occurrence can rearm by setting `K` to
false before the next matching publish.

`BlackboardIngress.publish` is safe to call from non-process threads. It queues
pending ingress under lock and does not mutate the blackboard directly. Normal
blackboard writes happen at process seams. `SAFETY_PREEMPT` is the narrow
exception: it may signal currently active actions immediately so cooperative
actions can exit at their next checkpoint. If no action is active, it does not
create cancellation for a future action.

`BlackboardIngress` is distinct from `ReplanRequestedException`.
`ReplanRequestedException` is initiated by an action or tool loop that is already
running inside the process. Ingress is external async fact publication into a
running process. It is not reinventing replanning; it covers the opposite
direction of state change.

Consumer dogfooding found that append-only blackboard facts are a poor fit for
mutable world-state booleans unless hiding, coalescing, or TTL semantics are
explicit. Prefer one of these patterns:

- use `IngressMode.LATEST`, `coalesceKey`, and `ttl` for external facts whose
  visible state should replace or expire older facts
- use `activationKey` when an external event should activate a runtime agenda
  entry
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
- `lane`: `ECONOMIC` or `SAFETY`
- `completionMode`
- `activationKey`
- `ttl`
- `createdAt`
- `completionPredicate`

Completion modes are intentionally small in the first POC:

- `TERMINAL`: satisfying the entry completes the process
- `RESUMABLE`: satisfying the entry removes it and re-arbitrates
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
includes the proposed entry, source fact, source type, lane, bindings, current
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
`IngressWake.SAFETY_PREEMPT` trips action-scope termination signals for actions
active at publish time, so cooperative blocking actions can exit at bounded
checkpoints. The current primitive is `AgentProcess.terminateAction`, backed by
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
5. sets the activation condition for `activationKey`, when present
6. activates matching catalog entries through the approver
7. records the drained fact as active ingress for TTL tracking

TTL expiry hides facts and emits a hidden-ingress event. It does not delete or
mutate facts.

Wake ingress moves a blocked process from `WAITING`, `STUCK`, or `PAUSED` back
to `RUNNING`. Terminal statuses remain terminal.

Safety preempt is narrow:

1. a fact is published with `IngressWake.SAFETY_PREEMPT`
2. action-scope termination is signalled for actions active at publish time
3. a cooperative in-flight action exits at a checkpoint
4. ingress drains at the process seam
5. any matching safety agenda entry activates
6. safety-lane planning preempts economic agenda entries

Catalog entries without an `activationKey` activate at a process seam once per
entry id. Keyed catalog entries activate when matching ingress is drained and
the activation key transitions from not-true to true. Duplicate matching ingress
while the key remains true does not re-propose the entry; reset the key to false
when the external trigger has cleared and should be allowed to fire again. An
already active entry id is rejected.

Runtime code can call `AgentProcess.addAgendaEntry` to propose entries directly.
Direct runtime additions are not remembered as one-shot catalog activations, so a
host or action can propose a fresh entry again after the previous entry is no
longer active. This is a low-level escape hatch for tests, explicit control, and
advanced integrations; the normal public path should compile typed `Objective`s
through `EvolvingInvocation`.

## Planning Behavior

Active agenda entries are projected as `AgendaPlanningGoal` values. The wrapper
keeps the underlying goal's semantic name and carries agenda identity on
`AgendaPlanningGoal.entry`.

If any active agenda entry is in `AgendaLane.SAFETY`, only safety entries are
projected for that planning cycle. Otherwise all active agenda entries are
projected.

Safety uses a hard lane rather than high utility because value or net-value
ranking is still soft preference; it cannot guarantee preemption.

When the active agenda is empty, planning falls back to the agent's base planning
system. Completed resumable agenda goals are suppressed from that base planning
system so a just-satisfied reusable goal is not immediately selected again from
the agent's declared goals.

## Completion Behavior

Plain agent goals still complete the process normally.

Agenda goals use their entry's `AgendaCompletionMode`:

- `TERMINAL` sets a completed outcome and completes the process.
- `RESUMABLE` removes the selected agenda entry, records the underlying goal as
  completed for base-goal suppression, sets a continue outcome, and re-runs
  arbitration.
- `COMPOSITE_TERMINAL` completes only when `completionPredicate` returns true.
  If the wrapped child goal is achieved before the composite predicate is true,
  the process moves to `WAITING`.

If a resumable agenda drains and no remaining plan can be found, the process
becomes `TERMINATED` with an `EXHAUSTED` outcome rather than entering the normal
recoverable stuck path.

## Invariants

- Preserve blackboard append/hide semantics: ingress adds and hides objects,
  never mutates or removes them.
- Preserve the OODA loop: normal evolution happens at process seams.
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
- activation keys and one-shot unkeyed catalog activation
- activation-key idempotence for completed keyed `RESUMABLE` entries, with
  explicit false-then-true rearm behavior
- direct runtime agenda addition and approval rejection
- duplicate goal names distinguished by agenda entry identity and bindings
- safety-lane hard priority over economic entries
- agenda-wrapped `NIRVANA` preserving Hybrid utility behavior
- resumable completion, base-goal suppression, and exhausted agenda handling
- composite terminal completion and waiting behavior
- `CompletionPolicy` outcomes for completed, exhausted, and cancelled
- ingress wake from `WAITING`, `STUCK`, and `PAUSED`
- latest/coalesced ingress hide behavior and TTL hide behavior
- safety preempt of a cooperative blocking action
- Java construction of `EvolutionOptions`, `AgendaEntry`, and
  `AgendaEntryApprover`
- Java use of `ObjectiveAuthorRequest.objectiveAs(Class<T>)`,
  `AgendaEntry.of(...).with...`, and `Nirvana.NIRVANA`

## POC Boundary and Cleanup Direction

Current conclusion: the branch is directionally right, and
`EvolvingInvocation` plus `ObjectiveAuthor` are now the POC public interface.
Some lower-level POC pieces should therefore become internal, experimental, or
low-level escape hatches after the public interface is dogfooded. Do not delete
the runtime mechanism yet.

Keep as core runtime substrate:

- `BlackboardIngress` and ingress events: the right seam for external facts
  entering a running process
- `ProcessCancellationToken`: needed for safety preemption and cooperative
  blocking actions
- `GoalAgenda`, `AgendaEntry`, and `AgendaLane.SAFETY`: the runtime mechanism
  underneath `EvolvingInvocation`
- `CompletionPolicy` and `ProcessOutcome`: needed to distinguish completed,
  exhausted, cancelled, and continue outcomes
- existing tests around ingress, safety preempt, agenda projection, Java
  construction, and completion behavior

Keep, but demote from the normal user-facing path:

- `AgentProcess.addAgendaEntry(...)`
- `AgentProcess.goalAgenda`
- `EvolutionOptions.agendaCatalog`

These should remain available as low-level escape hatches and test hooks, but
normal users should enter through `EvolvingInvocation`, not manual agenda
mutation.

Revisit after more consumer dogfood and framework-level acceptance coverage:

- `activationKey`: currently a stringly ingress-to-agenda bridge. It may stay
  low-level, but objective handlers or `ObjectiveAuthor` should own higher
  level fact-to-objective mapping.
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
- `BlackboardIngress` = external-event ingress
- `AgendaEntryApprover` = authorization and safety seam
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
  scenario: repeatedly collect, store, resume, safety-preempt, and complete
  without stale one-shot goal satisfaction.
- Broaden user-facing examples once the API has more consumer mileage beyond the
  current deterministic `ObjectiveAuthor` and low-level `EvolutionOptions`
  examples.
- Decide whether richer standing activity semantics are needed after the
  agenda-wrapped `NIRVANA` path has more consumer mileage.
- Revisit whether any API should be marked internal or moved before an upstream
  PR.

## Branch Scope

Uncommitted Log4j2/logging changes appear unrelated to evolving mode and should
not ride along with evolving-mode unless a real dependency appears. They may be
good work, but they likely belong in a separate branch or PR:

- `AgentLoggingEnvironmentPostProcessor.java`
- `agent-platform.properties`
- `log4j2-embabel.xml`
- logging docs, tests, and test resources

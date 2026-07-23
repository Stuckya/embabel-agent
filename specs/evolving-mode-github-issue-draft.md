# GitHub Issue Draft: Evolving Mode

> **Status: architectural rewrite, v6.**
>
> This version supersedes the graph-derived episode design captured at
> `f87a365166cd55ecd203848d1bbde685f8cb3383` and the persistent-episode
> model in v5.
>
> The planner may change. No new `PlannerType` is introduced.

Draft issue text for the post-1.0.0 Evolving Mode discussion.

Discussion context: https://github.com/embabel/embabel-agent/discussions/1725

## Title

Evolving Mode

## Summary

Embabel Agent already replans as actions add typed objects to working
memory. Evolving Mode adds a first-class lifecycle for work submitted
during or after an agent execution:

- `ctx.evolve(value)` submits work from an action;
- `process.evolve(value)` submits work from outside action execution;
- each accepted submission is a persistent occurrence;
- each planner-selected attempt is a new episode in a child process;
- every episode ends as `COMPLETED`, `STUCK`, `FAILED`, or `CANCELLED`;
- a terminal episode never mutates or resumes;
- a retained occurrence may be selected again in a later, fresh episode;
  and
- only the selected planner decides what work to run and what a planning
  outcome means.

Evolving Mode is an execution and lifecycle model. It is not a planning
strategy and therefore is not a `PlannerType`.

## Non-Negotiable Principles

This proposal is governed by seven principles:

1. Episodes are repeatable.
2. Episodes are consumable, enabling repeatable cycles.
3. A `STUCK` episode is a natural trigger for Open Evolving.
4. Internal and external evolution use the same contract:
   `ctx.evolve()` and `process.evolve()`.
5. Calling `evolve()` never changes the running episode. It creates a new
   occurrence, and any attempt to handle that occurrence is a new episode.
6. Episodes run in child processes, providing an isolation boundary.
7. Episodes never infer action-to-goal chains or otherwise recreate
   planner logic.

The seventh principle is an architectural constraint. The evolving
runtime must not inspect actions, goals, preconditions, effects, costs, or
types to imitate a planner decision.

## Domain Model

### Root process

The long-lived agent process that owns the planner session, accepted
occurrences, world revision, and active child processes.

The root process is not an episode. This keeps the statement “episodes run
in child processes” literal.

### Occurrence

One accepted call to `ctx.evolve(value)` or `process.evolve(value)`.

An occurrence:

- has a stable `OccurrenceId`;
- carries the submitted value;
- records causal lineage;
- persists independently of any execution attempt;
- is either available, awaiting a relevant world change, running,
  consumed, or cancelled; and
- may produce zero, one, or many episodes before it is consumed.

Every accepted call creates a distinct occurrence, even if an equal value
was submitted earlier. The submitted domain object does not need to carry
an occurrence identifier.

### Episode

One execution attempt selected by the planner for one occurrence.

An episode:

- has a fresh `EpisodeId`;
- belongs to exactly one occurrence;
- runs in exactly one child process;
- has one immutable terminal outcome;
- is never resumed; and
- may have an optional observed trace.

The action path is not the episode’s identity. It is evidence of what
happened during that attempt. Different episodes for the same occurrence
may legitimately follow different paths because the planner, state, or
available capabilities changed.

### Episode execution record

The immutable result of a terminal attempt:

```kotlin
data class EpisodeExecution(
    val id: EpisodeId,
    val occurrenceId: OccurrenceId,
    val childProcessId: String,
    val outcome: EpisodeOutcome,
    val trace: EpisodeTrace,
)

enum class EpisodeOutcome {
    COMPLETED,
    STUCK,
    FAILED,
    CANCELLED,
}
```

`EpisodeTrace` contains observed execution facts such as action
invocations. It does not contain a reconstructed plan and is not required
to define episode identity.

### Planner session

The process-lifetime, planner-owned interface through which the selected
planner receives current occurrences and terminal execution results, then
returns the next directive.

The planner session owns deliberation, routing, plan construction,
obstruction reporting, retry policy, and completion decisions.

### Child mission

An opaque planner-produced description of one attempt.

The runtime may invoke the mission’s child-materialization operation and
pass the resulting agent to child-process creation. It must not inspect
the mission to recover goals, actions, costs, or dependencies.

### Standing state

State that intentionally survives an episode. Application-owned domain
state is standing state by nature. Embabel-managed state created inside a
child is child-local unless an action explicitly shares it with the root.

## Lifecycle

The lifecycle separates durable work identity from execution:

```text
ctx.evolve(value) or process.evolve(value)
                 |
                 v
       occurrence accepted
                 |
                 v
        planner deliberates
                 |
        RunEpisode(occurrenceId, mission)
                 |
                 v
       fresh EpisodeId + child process
                 |
                 v
       terminal EpisodeExecution
                 |
                 v
        planner deliberates again
          /          |           \
         /           |            \
consume occurrence  await       run a fresh episode
                   occurrence    for the occurrence
```

The terminal episode does not become pending or running again. Repeatable
work means the occurrence can cause another episode, not that an episode
is reopened.

### `COMPLETED`

`COMPLETED` means the child reached its terminal condition. The planner
receives that execution result and decides whether the occurrence is now
consumed.

Completion of an episode does not automatically complete the root
process.

### `STUCK`

`STUCK` is a terminal episode outcome. The child process is finished and
the episode record is immutable.

The planner may return `AwaitOccurrence` with a planner-owned obstruction
and world interest. The occurrence remains unconsumed. On a relevant
world revision, the planner may select it again, producing a new episode
with a new child process and `EpisodeId`.

This is also the Open Evolving seam: the framework can offer the
planner-reported obstruction to an objective author without reverse
engineering the planner’s graph.

### `FAILED` and `CANCELLED`

These are also terminal episode outcomes. The planner decides whether a
failure warrants a fresh attempt, waiting, occurrence cancellation, or a
process-level response. Cancellation is delivered cooperatively to active
children.

## Evolution API

Internal and external evolution have the same submission semantics:

```kotlin
interface ActionContext {
    fun evolve(value: Any): OccurrenceId
}

interface AgentProcess {
    fun evolve(value: Any): OccurrenceId
}
```

Both entry points:

1. atomically accept a new occurrence;
2. assign a fresh `OccurrenceId`;
3. record causal metadata available at the call site;
4. publish `OccurrenceAcceptedEvent`; and
5. notify the planner session that the world revision changed.

`ctx.evolve()` additionally records the exact parent occurrence, episode,
child process, and action invocation when available.

`evolve()` never splices work into the currently executing plan. If it is
called from a child episode, that child continues unchanged and the new
occurrence is considered by a later planning turn.

Open Evolving eventually submits its validated result through the same
contract.

## Planner Interface

The existing planner implementation gains a process-lifetime session. No
parallel planning engine and no new `PlannerType` are introduced.

The required conceptual shape is:

```kotlin
interface PlanningSession {
    fun next(turn: PlanningTurn): PlanningDirective
}

data class PlanningTurn(
    val revision: Long,
    val occurrences: List<PlanningOccurrenceView>,
    val outcomes: List<EpisodeExecution>,
    val excludedActionNames: Set<String>,
    val availableChildCapacity: Int,
)

data class PlanningOccurrenceView(
    val id: OccurrenceId,
    val occurrence: Any,
    val state: PlanningOccurrenceState,
    val attemptCount: Int,
    val waitingSinceRevision: Long?,
    val evaluate: ((() -> Any?) -> Any?),
)
```

The concrete typing of occurrence evaluation may evolve. Its architectural
purpose is fixed: the selected planner can deliberate against the
occurrence-specific world without the runtime interpreting planner
internals.

The planner returns one explicit directive:

```kotlin
sealed interface PlanningDirective {

    data class RunRoot(
        val execution: PlannerExecution,
    ) : PlanningDirective

    data class RunEpisode(
        val occurrenceId: OccurrenceId,
        val mission: ChildMission,
    ) : PlanningDirective

    data class AwaitOccurrence(
        val occurrenceId: OccurrenceId,
        val interest: WorldInterest,
        val obstruction: PlanningObstruction?,
    ) : PlanningDirective

    data class CompleteOccurrence(
        val occurrenceId: OccurrenceId,
    ) : PlanningDirective

    data class CancelOccurrence(
        val occurrenceId: OccurrenceId,
        val reason: String,
    ) : PlanningDirective

    data object AwaitProcess : PlanningDirective

    data class CompleteProcess(
        val goal: Any?,
    ) : PlanningDirective
}
```

`PlanningSession.next()` is the only route by which Evolving Mode obtains
a decision about:

- whether an occurrence is relevant;
- which occurrence should run;
- what child mission should run;
- whether an occurrence should wait, retry, complete, or cancel;
- whether root work should run; or
- whether the root process should complete.

Planner implementations may inspect their own goals, actions, plans,
preconditions, effects, costs, heuristics, and obstruction models. Those
details do not escape as a second planning language in Evolving Mode.

## Responsibilities

### Evolving runtime owns

- occurrence identity, persistence, and lineage;
- atomic acceptance of `evolve()` calls;
- world revision notification;
- assigning a fresh episode identity per attempt;
- child-process creation and isolation;
- hard resource and action-budget enforcement;
- explicit state sharing;
- cooperative cancellation delivery;
- retaining terminal execution records for the next planning turn; and
- lifecycle events.

### Selected planner owns

- binding occurrences to declared objectives;
- choosing between root and occurrence work;
- choosing among competing occurrences;
- plan construction and refinement;
- action and goal value semantics;
- costs, heuristics, preconditions, and effects;
- constructing the opaque child mission;
- declaring that an attempt should be run;
- interpreting terminal episode outcomes;
- deciding whether an occurrence should wait, retry, complete, or cancel;
- reporting an obstruction when it can do so;
- repairing or replacing work after an outcome; and
- deciding whether the root process is complete.

### Generic child execution owns

- invoking actions selected through the normal planner/executor path;
- collecting observed action and process outcomes;
- maintaining observed action history;
- reporting `COMPLETED`, `STUCK`, `FAILED`, or `CANCELLED`; and
- never turning an observed result into a planning decision.

## Prohibited Planner Recreation

Evolving runtime and child execution code must not:

- scan action input types to decide whether an occurrence is accepted;
- derive an action-to-goal path;
- inspect goal graphs or planner plans;
- inspect action preconditions or effects;
- duplicate cost, value, or heuristic rules;
- infer which goal a terminal action completed;
- decide that a `STUCK` result implies a particular retry; or
- unpack an opaque child mission to recover planner objects.

Deleting these paths is preferred to preserving them as fallback
behavior. A planner implementation that lacks enough information must be
extended at the planner seam.

## Events

The observable lifecycle is:

```kotlin
OccurrenceAcceptedEvent(
    occurrenceId,
    occurrence,
    causedByOccurrenceId,
    causedByEpisodeId,
    causedByChildProcessId,
    publishedBy,
)

EpisodeStartedEvent(
    occurrenceId,
    episodeId,
    childProcessId,
)

EpisodeFinishedEvent(
    execution: EpisodeExecution,
)

OccurrenceConsumedEvent(
    occurrenceId,
    episodeId,
)
```

Required ordering for one successful attempt:

```text
OccurrenceAccepted
EpisodeStarted
EpisodeFinished(COMPLETED)
OccurrenceConsumed
```

For `STUCK`, no consumed event is emitted. A later attempt for the same
occurrence emits a new started/finished pair with a different episode and
child-process identity.

Legacy episode-completion events may be translated at the
`AgentProcess` compatibility boundary. The generic episode runtime must
not inspect planner goals to synthesize them.

## Concurrency and Isolation

- Occurrence acceptance is atomic and safe from action and external
  callers.
- The planner receives immutable turn snapshots.
- Each `RunEpisode` creates a fresh child process.
- Child-local blackboard mutations do not silently leak into the root.
- Explicit sharing is application behavior, not automatic episode merge.
- Planner concurrency is bounded by `availableChildCapacity`.
- Duplicate submissions are distinct occurrences.
- At most one attempt for a given occurrence runs at a time unless a
  future planner contract explicitly permits otherwise.

## Open Evolving

Open Evolving is a later capability built on the same lifecycle:

1. an episode finishes `STUCK`;
2. the selected planner returns `AwaitOccurrence` and an obstruction;
3. an `ObjectiveAuthor` receives that planner-owned obstruction;
4. it proposes a constrained, validated scope or objective revision;
5. the accepted revision changes the world; and
6. the planner may issue `RunEpisode` for the retained occurrence.

Step 6 creates a fresh episode. Open Evolving never mutates the `STUCK`
episode and never asks the runtime to infer what plan was missing.

The obstruction model is intentionally planner-owned. A generic initial
model may include stable categories such as no plan, missing capability,
policy rejection, or exhausted alternatives, but the runtime treats the
payload as opaque diagnostic input.

## Relation to Classical Planning

The architecture follows the separation used in classical and online
planning:

- the problem solver owns the model and chooses actions;
- execution observes what happened;
- monitoring reports divergence or inability to continue; and
- replanning is a new planner decision from the resulting state.

An execution trace is therefore not a plan, and a runtime cannot safely
recover planner intent from the path it observed. This directly avoids
the “action A began the episode, action Z ended it, therefore A-to-Z is
the episode” assumption. A planner is never guaranteed to choose that
path.

## Compatibility and Migration

- Existing non-evolving agent execution remains unchanged.
- Existing planner types remain the public selection mechanism.
- The current condition planner implements the planning-session seam;
  other planners can implement the same contract incrementally.
- Existing `EpisodeCompletedEvent` consumers may be supported by a
  process-level compatibility adapter.
- Internal types that previously called persistent occurrences
  “episodes” should migrate to occurrence terminology. Public semantics
  follow this specification even during that transition.

## Delivery Slices

### Slice 1: terminal episode execution

- introduce `EpisodeId`, `EpisodeOutcome`, and `EpisodeExecution`;
- assign a fresh episode and child identity for every attempt;
- publish started and finished events; and
- retain a `STUCK` occurrence without reopening its episode.

### Slice 2: repeatable occurrence handling

- feed terminal executions back to the planner session;
- add explicit complete, await, cancel, and rerun directives;
- consume only on the planner’s explicit directive; and
- prove a retained occurrence can run in a fresh episode after a world
  change.

### Slice 3: zero planner recreation

- remove runtime action-input, goal, precondition, and plan inspection;
- make child missions opaque;
- keep legacy translation outside the generic episode runtime; and
- add architecture tests that forbid dependencies from runtime execution
  code to planner graph types.

### Slice 4: unified evolution and lineage

- make `ctx.evolve()` and `process.evolve()` share acceptance behavior;
- record exact child lineage for internal evolution;
- prove that evolving during an episode leaves that episode unchanged;
  and
- emit occurrence consumption separately from episode completion.

### Slice 5: Open Evolving

- standardize the minimum obstruction envelope;
- define objective-author policy and validation;
- apply accepted revisions as world changes;
- submit authored work through the existing `evolve()` contract; and
- prove recovery always starts a new child episode.

## Acceptance Criteria

The feature is acceptable when tests at the public event/API boundary
prove:

1. `ctx.evolve()` and `process.evolve()` produce equivalent occurrence,
   episode, and consumption lifecycles.
2. Two equal submissions create two occurrences and two consumable
   cycles.
3. Every attempt has a new `EpisodeId` and child-process identity.
4. A `STUCK` episode is terminal and its occurrence is not consumed.
5. A relevant world change can cause that occurrence to run in a fresh
   episode.
6. Calling `evolve()` from a child preserves exact causal lineage and
   does not mutate the child’s episode.
7. A planner can select an occurrence without any action input-type
   relationship for runtime code to infer.
8. Runtime execution succeeds with a child mission whose internal
   planner representation is inaccessible.
9. Architecture checks prevent the episode runtime and executor from
   depending on planner graph types.
10. Existing non-evolving behavior and compatibility events remain
    green.

## Decision

Adopt occurrence as the persistent work identity and episode as one
terminal child execution attempt.

Extend the existing planner behind a planner-session seam. Do not add a
new planner type. Keep every routing, goal, plan, obstruction, retry, and
completion decision inside the selected planner.

This gives Evolving Mode repeatability, consumability, isolation, and a
clean Open Evolving trigger without ever reconstructing planner logic
from an observed execution.

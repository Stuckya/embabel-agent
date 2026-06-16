# Evolving Process Mode

## Overview

Evolving Process Mode is a process/runtime overlay that lets a running agent
process add, expire, and arbitrate agenda goals while preserving Embabel's
existing planner model.

Evolving mode is not a `PlannerType`. It composes with GOAP, Utility, and
Hybrid planners by changing the effective planning system available to a process
at OODA seams. Agenda projection does not mutate `Agent.goals`.

The current implementation is a POC for the roadmap shape described in the
README: a process can work with multiple goals and modify the running process as
new facts make additional goals or agents relevant.

## Implemented POC Surface

### EvolutionOptions

`ProcessOptions.evolution` carries an `EvolutionOptions` value with:

- `agendaCatalog`: an activatable catalog of agenda entries
- `agendaEntryApprover`: an approval seam for catalog activation and runtime
  entry proposals
- `completionPolicy`: a process outcome policy

`ProcessOptions.withEvolution` installs an `EvolutionOptions` value.
`EvolutionOptions.initialAgenda` remains as a deprecated alias for
`agendaCatalog`; entries here are a catalog, not active initial state.

### BlackboardIngress

`BlackboardIngress` is exposed from both `AgentProcess` and `ProcessContext`.
`publish` queues a typed fact for the next process seam and returns an
`IngressReceipt`.

`IngressOptions` describes one publish operation:

- `mode`: `APPEND` or `LATEST`
- `wake`: `NONE`, `WAKE`, or `SAFETY_PREEMPT`
- `coalesceKey`: replaces pending ingress with the same key before drain
- `activationKey`: activates matching catalog entries at drain time
- `ttl`: hides the drained fact after the duration expires

Ingress publish is safe for external callers. It records pending ingress under
lock and may wake a blocked process. Blackboard writes still happen at process
seams.

### GoalAgenda

`GoalAgenda` is an immutable overlay of agenda entries that can be projected
into planning. It exposes `withEntry`, `withoutEntry`, and `expire`, all of
which return a new agenda instance.

The active agenda is visible as `AgentProcess.goalAgenda`.

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

### CompletionPolicy

`CompletionPolicy` evaluates the process and current agenda and returns a
`ProcessOutcome`.

Outcome codes are:

- `CONTINUE`: keep running normal process logic
- `COMPLETED`: mark the process completed
- `EXHAUSTED`: terminate the process as a non-success exhausted outcome
- `CANCELLED`: terminate the process as a cancellation outcome

`CompletionPolicy` is separate from `EarlyTerminationPolicy`, because early
termination is an existing hard stop path rather than evolving agenda
completion.

### ProcessCancellationToken

`ProcessContext.cancellationToken` exposes a pollable token for blocking action
code. `IngressWake.SAFETY_PREEMPT` trips an action-scope termination signal
immediately, so cooperative blocking actions can exit at bounded checkpoints.

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
2. the action-scope termination signal is tripped immediately
3. a cooperative in-flight action exits at a checkpoint
4. ingress drains at the process seam
5. any matching safety agenda entry activates
6. safety-lane planning preempts economic agenda entries

Catalog entries without an `activationKey` activate at a process seam once per
entry id. Keyed catalog entries activate when matching ingress is drained. An
already active entry id is rejected.

Runtime code can call `AgentProcess.addAgendaEntry` to propose entries directly.
Direct runtime additions are not remembered as one-shot catalog activations, so a
host or action can propose a fresh entry again after the previous entry is no
longer active.

## Planning Behavior

Active agenda entries are projected as `AgendaPlanningGoal` values. The wrapper
keeps the underlying goal's semantic name and carries agenda identity on
`AgendaPlanningGoal.entry`.

If any active agenda entry is in `AgendaLane.SAFETY`, only safety entries are
projected for that planning cycle. Otherwise all active agenda entries are
projected.

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
- activation keys and one-shot unkeyed catalog activation
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

## Remaining Follow-Up

- Broaden user-facing examples once the API stabilizes beyond POC status.
- Decide whether richer standing activity semantics are needed after the
  agenda-wrapped `NIRVANA` path has more consumer mileage.
- Revisit whether any API should be marked internal or moved before an upstream
  PR.

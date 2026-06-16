# Evolving Process Mode

## Overview

Introduce Evolving Process Mode as a process/runtime module that lets a running
agent process add, expire, and arbitrate goals while preserving Embabel's
existing planner model.

Evolving mode should not introduce a new `PlannerType`. It should compose with
GOAP, Utility, and Hybrid planners by changing the effective planning system
available to the process at each OODA seam.

The intent is to implement the roadmap shape described in the README: a process
can work with multiple goals and modify the running process as new facts make
additional goals or agents relevant.

## Current State

Embabel already has most of the primitives needed:

- `Blackboard` provides append-only process context with `hide` for excluding
  visible objects without deleting them.
- `AgentProcess` and `ProcessContext` provide the runtime seam for actions,
  planning, status, history, and process options.
- `ProcessOptions` and `ProcessControl` configure process behavior through
  immutable data classes and withers.
- `AgenticEventListener` is the existing event seam for platform and process
  events.
- `GoalChoiceApprover` and `Ranker` are the existing autonomy seams for initial
  goal selection.
- `ReplanRequestedException` lets an action update the blackboard and request a
  replan without treating the request as an error.
- GOAP, Utility, and Hybrid are existing planner adapters with different
  arbitration behavior.

The missing depth is runtime evolution. Today a process plans from a fixed agent
scope, external events have no first-class typed ingress path into the process,
and process completion is still centered on the currently selected goal.

## Proposed Contracts

### EvolutionOptions

Add an `EvolutionOptions` data class and a `withEvolution` wither on
`ProcessOptions`.

`EvolutionOptions` should be a configuration bag, not a builder. It should use
`@JvmOverloads` for Java callers and expose Java-friendly defaults from a
companion object where appropriate.

### BlackboardIngress

Add a process-level ingress interface for typed facts:

```kotlin
interface BlackboardIngress {
    fun publish(fact: Any, options: IngressOptions = IngressOptions()): IngressReceipt
}
```

Ingress should be available from both `AgentProcess` and `ProcessContext`.

### IngressOptions

`IngressOptions` describes one publish operation:

- mode: append or latest
- wake: no wake, wake, or safety preempt
- coalesce key
- activation key
- TTL

Latest semantics should be implemented by adding the new fact and hiding older
visible facts with the same key. TTL expiry should be a logged hide operation,
not deletion or mutation.

### GoalAgenda

`GoalAgenda` is an immutable runtime overlay over the agent's known goals,
actions, and conditions.

It should not mutate `Agent.goals`. It should expose withers such as
`withEntry`, `withoutEntry`, and `expire`, returning a new agenda instance.

The process uses the agenda to build the effective planning system for a tick.

### AgendaEntry

An agenda entry references a known goal and carries runtime context:

- id
- goal
- bindings
- source
- lane: economic or safety
- completion mode
- activation key
- optional TTL

Completion modes:

- terminal: satisfying the entry completes the process
- resumable: satisfying the entry removes or marks the entry complete, then
  re-arbitrates
- keep-alive: keeps the process alive until host stop or cancellation
- composite terminal: completes when a deterministic predicate over child
  entries or facts is satisfied

### AgendaEntryApprover

Add an approver seam mirroring the shape of `GoalChoiceApprover`, but scoped to
runtime agenda entries.

The request should include the proposed entry, source fact, source type, lane,
bindings, current agenda, and process state. This is a sibling pattern to
`GoalChoiceApprover`, not a replacement.

### CompletionPolicy

Add a completion or outcome policy for Evolving processes.

This should not be modeled as `EarlyTerminationPolicy`, because early
termination currently means a `TERMINATED` process rather than successful
completion.

The outcome policy should distinguish:

- continue
- completed
- exhausted
- cancelled

### ProcessCancellationToken

Expose a pollable cancellation token from `ProcessContext`.

Blocking actions should be able to check bounded cancellation points and exit
cleanly. Safety preemption should trip the token immediately, then let the next
process seam drain ingress and re-arbitrate.

## Runtime Behavior

Normal ingress drains at OODA tick seams. It should not mutate the blackboard
from an arbitrary async path.

Safety ingress is special but narrow. An approved safety fact may trip the
cancellation token immediately so an in-flight action can exit at its next
checkpoint. The blackboard and agenda still activate through the normal process
seam.

The safety path is:

1. safety fact is published
2. approver accepts the safety lane
3. cancellation token is tripped
4. current action exits at a checkpoint
5. ingress drains at the process seam
6. safety agenda entry activates
7. planner re-arbitrates

Runtime typed-fact activation must be deterministic and must not require LLM
ranking. LLM-assisted deliberation may publish facts or propose agenda entries,
but those entries pass through the same approval seam as code- or event-produced
entries.

## Invariant Preservation

- Preserve blackboard immutability: ingress adds and hides objects, never
  mutates or removes them.
- Preserve the OODA loop: normal evolution happens at process seams.
- Preserve planner independence: Evolving is not a new `PlannerType`.
- Preserve deterministic runtime activation: typed facts can activate agenda
  entries without LLM involvement.
- Preserve existing event integration: use `AgenticEventListener` rather than a
  parallel event seam.
- Preserve Java usability: public contracts need idiomatic Java construction and
  call sites.

## Implementation Phases

### Phase 1: Iterative STUCK handling

Replace recursive stuck-handler re-entry with an iterative loop that consults
termination and outcome policies between attempts.

This is a correctness improvement independent of the rest of Evolving mode.

### Phase 2: Cancellation token

Add a pollable cancellation token to `ProcessContext` and action execution.

Include Java fixtures showing token polling from Java action code.

### Phase 3: Blackboard ingress

Add typed ingress with append, latest, coalescing, activation keys, TTL hide,
and process events.

Ingress should preserve the blackboard's append/hide model and drain at process
seams.

### Phase 4: Goal agenda and completion

Add immutable agenda entries, agenda approval, effective planning-system
projection, and process outcome policy.

Do not add a new planner type.

### Phase 5: Documentation and examples

Move stable user-facing material to `embabel-agent-docs` only after the runtime
contracts have been validated.

Use Asciidoctor syntax and examples from Embabel repositories, not application
specific code.

## Considerations

- Keep public surface minimal. Make implementation classes internal where
  possible and use `@ApiStatus.Internal` when public visibility is required for
  technical reasons.
- Use `*Options` for configuration bags and `*Policy` for behavioral strategy.
- Keep `GoalAgenda` immutable and copy-on-write.
- Do not rely on Kotlin extension functions for public API.
- Avoid broad PRs. The implementation should be staged so maintainers can
  review each seam independently.

## Testing Strategy

- Work test first.
- Use MockK for Kotlin tests.
- Use Mockito only for Java fixtures.
- Add Java fixture tests for:
  - `ProcessOptions.withEvolution`
  - publishing a fact
  - polling cancellation
  - implementing `AgendaEntryApprover` as a Java lambda
- Verify append/hide semantics for latest facts.
- Verify activation keys prevent repeated activation unless explicitly rearmed.
- Verify TTL expiry emits a replayable event and hides rather than deletes.
- Verify safety preemption exits a blocking action at a checkpoint and
  re-arbitrates to the safety entry.
- Verify agenda completion distinguishes completed, exhausted, cancelled, and
  keep-alive host stop.
- Build the closest Maven module after each phase.

## Progress Log

### 2026-06-15

- Created initial draft proposal for local POC and later upstream discussion.

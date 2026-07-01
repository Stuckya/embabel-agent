# Evolving Mode Discussion Reply Draft

Discussion context: https://github.com/embabel/embabel-agent/discussions/1725

This is a short reply to the latest discussion question before opening the issue.

> @simeshev, happy to.
>
> The practical shape is a long-lived process with one stable objective, current-state conditions supplied by the consumer application, and selected runtime goals added at planning boundaries.
>
> My real domain is a long-lived agent driving a live external system. I am abstracting it here as sample collection so the discussion stays generic.
>
> Example: start a process with the objective "collect samples in Zone A until 500 samples are stored." The process has scoped capabilities for collection, navigation, storage, hazard response, and buffer/workspace state.
>
> While it is running:
>
> - when the local workspace becomes full, storage actions become achievable or valuable through conditions and normal planner action selection
> - when storage completes, collection resumes
> - when a hazard is currently visible, conditions and normal planner action selection can make hazard response the available path
> - when an occurrence fact needs handling, evolution policy can install a runtime goal that finishes once and marks its rule-local activation consumed
> - when 500 samples are stored, the process completes
>
> I do not want the collection capability to call the storage or navigation capability directly. I want capabilities to publish or consume typed facts and let Embabel compose the scoped capabilities through GOAP/Utility/Hybrid.
>
> So the loop I am looking for is:
>
> ```text
> action output or selected external fact ingress
>   -> runtime fact
>   -> evolution policy
>   -> process-local runtime goal
>   -> existing planner
>   -> declared action
> ```
>
> I see the first increments as:
>
> 1. internally produced facts drive runtime goals
> 2. selected externally published facts drive the same runtime-goal engine
> 3. observability keeps each wake-up attached to the existing process/session
>
> Cooperative interruption of a running action, open re-authoring, and process-local scope expansion follow once the core engine is proven; the issue will carry a sub-issue per stream.
>
> Pub/sub fan-out is not required for my current use case. I only need to publish selected facts to one known running process.

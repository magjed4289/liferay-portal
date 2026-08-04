# Fetch and Diff Testray Analysis Tasks

Testray's "Analysis Task" (surfaced in the UI at `https://testray.liferay.com/#/testflow/<taskId>`) clusters a routine's recent failures into **subtasks** (`ST-1`, `ST-2`, …), each a group of case results that share an error signature. This reference covers how to pull a task's subtasks, resolve their member tests, and diff two tasks against each other.

## Preconditions

`${TESTRAY_CLIENT_ID}` and `${TESTRAY_CLIENT_SECRET}` must be set in the environment. Without them, abort and surface the reason.

## Authentication

Fetch a bearer token once per run and reuse it for every call:

```bash
export ACCESS_TOKEN=$(curl \
	--data "grant_type=client_credentials" \
	--header "Authorization: Basic $(printf '%s:%s' "${TESTRAY_CLIENT_ID}" "${TESTRAY_CLIENT_SECRET}" | base64 --wrap 0)" \
	--header "Content-Type: application/x-www-form-urlencoded" \
	--request POST \
	--silent \
	--url "https://testray.liferay.com/o/oauth2/token" \
	| grep -o '"access_token":"[^"]*"' | sed 's/.*:"//;s/"//')
```

## Resolve the Team Routine

Use the canonical master project ID `35392` as `<masterProjectId>` (browsable at `https://testray.liferay.com/#/project/35392/routines`). Derive the team routine from `.github/CODEOWNERS` exactly as in [`../../test-fix/references/testray.md`](../../test-fix/references/testray.md#resolve-a-test-name-to-a-case-result-id) — a routine is named `[master] ci:test:<team>`. For the Headless team this resolves to `[master] ci:test:headless`, routine ID `994140`. Resolve it fresh each run rather than hardcoding, in case Testray re-creates the routine:

```bash
curl \
	--data-urlencode "filter=name eq '[master] ci:test:<team>' and r_routineToProjects_c_projectId eq '35392'" \
	--data-urlencode "pageSize=1" \
	--get \
	--header "Accept: application/json" \
	--header "Authorization: Bearer ${ACCESS_TOKEN}" \
	--silent \
	--url "https://testray.liferay.com/o/c/routines"
```

## Find or Create the Freshest Analysis Task

When the caller doesn't name a Task, resolve `<newTaskId>` from the routine itself instead of asking: find the freshest build, reuse its Task if one exists, or create one.

### Find the Freshest `DONE` Build

```bash
curl \
	--data-urlencode "filter=r_routineToBuilds_c_routineId eq '<routineId>'" \
	--data-urlencode "sort=dateCreated:desc" \
	--data-urlencode "pageSize=5" \
	--get \
	--header "Accept: application/json" \
	--header "Authorization: Bearer ${ACCESS_TOKEN}" \
	--url "https://testray.liferay.com/o/c/builds"
```

Walk the page newest-first and take the first build whose `importStatus.key` is `DONE` — the newest build is sometimes still importing. Unlike `TestrayAutomatedTasks`'s `get_latest_done_build` (`utils/liferay_utils/testray_utils/testray_helpers.py`), which only ever looks at `builds[0]` and gives up with no result when that one isn't `DONE` yet, walk back through the small page fetched above rather than bailing on the very first build.

### Reuse an Existing Task for That Build

```bash
curl \
	--data-urlencode "filter=r_buildToTasks_c_buildId eq '<buildId>'" \
	--get \
	--header "Accept: application/json" \
	--header "Authorization: Bearer ${ACCESS_TOKEN}" \
	--url "https://testray.liferay.com/o/c/tasks"
```

- **Exactly one task exists, not `ABANDONED`** → that is `<newTaskId>`. Reuse it regardless of its own `dueStatus` (`OPEN`/`INANALYSIS`/`PROCESSING`/`COMPLETE` are all fine to diff against) — this skill only reads a task, it never needs it in any particular state, unlike `TestrayAutomatedTasks`'s `prepare_task` (`testray_helpers.py`), which skips reuse on `COMPLETE` because *its* job is to actively triage, not just report.
- **Exactly one task exists, `ABANDONED`** → stop and surface this to the user rather than creating a second task for the same build; that's an unusual state worth a human look, not a silent workaround.
- **No task exists** → create one, below.
- **More than one task exists** → stop immediately and surface every one of them (with its link and status) to the user. Do not guess which to keep, abandon one automatically, or create a third. This exact situation has already taken the Headless routine down in production: two tasks existed for one build, the build's own results API couldn't resolve which to use, and the routine was stuck until a human manually abandoned the extra one. Treat it the same way here — a case for a person to resolve, not code.

### Create the Task and Its Subtasks

```bash
curl --request POST \
	--header "Authorization: Bearer ${ACCESS_TOKEN}" \
	--header "Content-Type: application/json" \
	--data '{"name": "<build name>", "r_buildToTasks_c_buildId": <buildId>, "dueStatus": {"key": "INANALYSIS", "name": "In Analysis"}}' \
	--url "https://testray.liferay.com/o/c/tasks/"
```

Before triggering clustering, re-check for the same build one more time (identical query to **Reuse an Existing Task for That Build**, above). This closes the race where something else — most notably `TestrayAutomatedTasks`'s own cron (`.github/workflows/main.yml`, Mon/Wed 6 AM, plus `workflow_dispatch`, with no locking of its own) — creates a task for the same build in the moment between the first check and this one:

- **Still just the one we made** → proceed.
- **A second task now exists** → this is the exact duplicate-task incident described above, caught before it can do any damage. Abandon the one just created — same `PATCH .../o/c/tasks/{id}` with `{"dueStatus": {"key": "ABANDONED", "name": "Abandoned"}}` documented under **Carry Forward Subtask Claims Across Analysis Tasks** below — rather than the other one, since ours has no subtasks yet and nothing depends on it. Reuse the pre-existing task as `<newTaskId>` instead (going through the same reuse check above), and mention to the user that a race was caught and resolved this way.

Then trigger clustering **exactly once** — read the regeneration quirk above first; a second call against the same task multiplies its subtasks, not refreshes them:

```bash
curl --request POST \
	--header "Authorization: Bearer ${ACCESS_TOKEN}" \
	--url "https://testray.liferay.com/o/testray-rest/v1.0/testray-testflow/<newTaskId>"
```

This call is synchronous and returns `{"subtaskAmount": N}` directly (confirmed live — no job ID, no polling needed). Treat a missing or zero `subtaskAmount` as a real failure worth surfacing, not a silent proceed.

## Fetch an Analysis Task's Subtasks

```bash
curl \
	--data-urlencode "testrayTaskId=<taskId>" \
	--data-urlencode "pageSize=200" \
	--get \
	--header "Accept: application/json" \
	--header "Authorization: Bearer ${ACCESS_TOKEN}" \
	--url "https://testray.liferay.com/o/testray-rest/v1.0/testray-testflow/testray-subtask"
```

Page through with `page=2`, `page=3`, … while `items.length * page < totalCount`.

Each item has `id`, `name` (`ST-<n>`), `score`, `status`, `error`, `issues` (linked Jira/GitHub issue keys, usually empty), `userName` (assignee, usually empty).

**Regeneration quirk:** repeatedly calling `POST /o/testray-rest/v1.0/testray-testflow/<taskId>` (the single-task detail endpoint) appears to trigger Testray to re-run its clustering, which creates a **fresh row per subtask name** without deleting the old one — `totalCount` for the same task has been observed to triple across a session of polling. Do not call that POST endpoint speculatively. When reading subtasks, always **dedupe by `name`, keeping the row with the highest `id`** — that is the latest generation and the only one with live case-result linkage (older generations end up with zero linked case results, confirmed via the filter below).

## Regeneration Can Be Triggered By Anyone, At Any Time

Confirmed live: task `501637926` went from 70 to 140 subtasks (one stale duplicate per name, e.g. two rows both named `ST-1`) after this skill had called the clustering-trigger endpoint exactly once, per the rule above. The user confirmed they opened that task's analysis view in the Testray UI while the run was still in progress — the Testray frontend re-invoking the same `POST /o/testray-rest/v1.0/testray-testflow/<taskId>` endpoint on load is the direct cause, independent of anything this skill does. Confirmed on the same incident: the older generation's rows dropped to zero linked case results exactly as the existing rule predicts, so the dedupe-by-highest-ID rule above still resolves the live row correctly — the risk is not in reading subtasks, it is in **holding onto an ID across time**.

This does not mean the skill should stop triggering clustering itself when creating a new task — a freshly created task has zero subtasks until that call is made, and the response is a synchronous, on-demand cluster rather than a queued background job, so there is no substitute for calling it once to get data for the run in progress. What this incident changes is how long to trust a subtask ID once resolved: treat the regeneration quirk as a risk for the entire remaining lifetime of the task, not just a hazard to avoid within the create-and-cluster step. A subtask ID resolved early in a run (e.g. during **Diff**) can go stale before that same run finishes, silently orphaning any write or link made against it later.

Resolve a subtask ID as close as possible to the moment it is actually used for a write (a claim-migration `PUT`) or a durable link (a report, a Confluence page) — never reuse an ID cached from an earlier step in the same run. Immediately before either kind of use, re-fetch the subtask (dedupe by `name`, keep the highest `id`) and confirm it still has linked case results; if the ID cached earlier has gone stale, redo the write or link against the live one instead.

Stale duplicate rows are safe to delete (`DELETE /o/c/subtasks/{id}`, the same generic object endpoint used for the `PUT` above) once confirmed — but confirm every single one first: for each candidate, verify `r_subtaskToCaseResults_c_subtaskId eq '<id>'` against `/o/c/caseresults` returns zero, and only delete rows that pass. Never delete the highest-`id` row per name — that is the live one. This is not automatic cleanup this skill should run unprompted on every pass; do it only when a human notices the duplication and asks for it removed.

## Resolve a Subtask's Member Tests

Each case result carries `r_subtaskToCaseResults_c_subtaskId` (plural — `r_subtaskToCaseResult…` singular does not exist and returns `BAD_REQUEST`). Filter on it to get the subtask's current members:

```bash
curl \
	--data-urlencode "filter=r_subtaskToCaseResults_c_subtaskId eq '<subtaskId>'" \
	--data-urlencode "pageSize=300" \
	--get \
	--header "Accept: application/json" \
	--header "Authorization: Bearer ${ACCESS_TOKEN}" \
	--url "https://testray.liferay.com/o/c/caseresults"
```

Read `id` (case result ID), `r_caseToCaseResult_c_caseId` (case ID — the stable test identity), `r_buildToCaseResult_c_buildId`, and `dueStatus.key` (`FAILED` / `BLOCKED` / `PASSED`) from each item.

Across every subtask of one Analysis Task, `r_buildToCaseResult_c_buildId` is uniform — that single build is the one the task actually analyzed. There is no direct field linking a task to a build; derive it this way instead of guessing from timestamps.

`score` is a rolling weight accumulated across a window of recent builds, not the current build's failure count — expect `sum(score)` across all subtasks to exceed the resolved build's own failed-case count. Use the **linked case result count**, not `score`, whenever a per-build number is needed.

## Report the Team-Scoped Total, Not the Build's Raw Total

A build is shared Jenkins infrastructure — multiple teams' routines point at the same build, and Testray tags each case result with an owning team (`r_teamToCaseResult_c_teamId`) after the fact. The build object's own `caseResultFailed` / `caseResultPassed` fields sum **every** team sharing that build, not just the one this skill cares about. Using that raw field as "current failures" overstates the number, sometimes substantially (confirmed: a build reporting `caseResultFailed: 114` was actually `99` Headless-owned plus `15` from other teams).

Get the team-scoped breakdown directly instead of re-deriving it:

```bash
curl \
	--get \
	--header "Accept: application/json" \
	--header "Authorization: Bearer ${ACCESS_TOKEN}" \
	--url "https://testray.liferay.com/o/testray-rest/v1.0/testray-status-metrics/by-testray-buildId/<buildId>/testray-teams-metrics"
```

Each item is `{testrayTeamId, testrayTeamName, testrayStatusMetric: {failed, passed, blocked, untested, testfix, total}}`. Match `testrayTeamName` to the team this run cares about (`Headless`) and use *that* entry's `failed`/`passed` for every headline number — never the build's own top-level fields.

This does not, by itself, mean the subtask/case-result data pulled earlier is contaminated — in practice, every case result belonging to another team turned out to be an infra placeholder (`PortalLogAssertorTest-*`, per-executor slots) that the placeholder filter already excludes, so the individual test-level diff was unaffected. But verify this rather than assume it: cross-check that `<team's failed count> + <team's blocked count>` equals the real (placeholder-filtered) `FAILED`/`BLOCKED` count computed from the subtask data. If they don't match, some other team's *named* test has leaked into the pool, and case results should be filtered by `r_teamToCaseResult_c_teamId eq '<teamId>'` directly rather than trusted from subtask linkage alone.

## Resolve Case Names

Batch-resolve `r_caseToCaseResult_c_caseId` values to human-readable test names, 25 at a time (URL length limit):

```bash
curl \
	--data-urlencode "filter=id eq '<id1>' or id eq '<id2>' or …" \
	--data-urlencode "pageSize=50" \
	--get \
	--header "Accept: application/json" \
	--header "Authorization: Bearer ${ACCESS_TOKEN}" \
	--url "https://testray.liferay.com/o/c/cases"
```

Some resolved names are not real tests — filter them out before reporting:

- Per-executor placeholder slots, matching `/\d+/\d+$` (e.g. `functional-tomcat101-postgresql163/7/5`).
- `Top Level Build`.
- `PortalLogAssertorTest-<slot>` (log assertors, not test cases).

## Diff Two Analysis Tasks

1. Fetch and dedupe both tasks' subtasks (see regeneration quirk above).

1. For each, resolve every subtask's linked case results, filter out placeholder names, and confirm they resolve to a single build ID.

1. **Diff by case ID directly — never by subtask presence.** Build the complete set of real (non-placeholder) `FAILED`/`BLOCKED` case IDs for each task by pooling the members of *every* subtask, not just ones that look distinctive. Then:

	- `new = newerCaseIds - olderCaseIds` — real regressions.
	- `fixed = olderCaseIds - newerCaseIds` — real fixes.
	- `still = newerCaseIds & olderCaseIds` — unchanged, not reported individually.

	Do not use "subtask signature present only on one side" as the new/fixed signal. A subtask is Testray's cluster for *one build*, not a tracked entity across builds — when a cluster has several member tests and only some of them stop failing, the cluster's signature persists on both sides (kept alive by the remaining members) and the diff looks unchanged even though real fixes happened inside it. Matching at the subtask level instead of the case-ID level silently drops every one of those partial fixes — in practice this has undercounted "fixed" by an order of magnitude. Subtask signatures are still useful, but only for *labeling* a regression's cluster and error text in the report, and for the environment-incident check below — never for detecting whether something changed.

1. **Cross-check every case in `new` and `fixed` individually** against the *other* build's own result for that case ID (`filter=r_buildToCaseResult_c_buildId eq '<otherBuildId>' and r_caseToCaseResult_c_caseId eq '<caseId>'`) before reporting it. This catches a second, independent failure mode: a case whose error text changed enough that it would normalize to a different signature on each side, while never actually leaving the failing set — confirm the older side was truly not failing (for `new`) or the newer side is truly `PASSED`/intentionally `BLOCKED` (for `fixed`) before trusting the set difference.

1. Treat any case whose error text is a generic environment/boot failure (`The build failed prior to running the test.`, an `org.apache.tools.ant.TaskAdapter` stack frame, `stop-docker-containers:`, `Failed for unknown reason`, a bare `[echo]`) as part of one infrastructure incident, not an independent code regression — there is no commit to blame for a build that never ran the test. Apply this check per case, not per subtask: a subtask can carry a generic-sounding signature while still grouping real, individually-meaningful failures.

## Check Case History for a Linked Jira Issue Before Attributing by Commit

Before falling back to `git log` archaeology for any **new-regression** case, check whether Testray already has a Jira ticket linked to that case — this is a direct answer, not an inference. Pull the case's full history (not filtered to FAILED/BLOCKED, and not routine-filtered, since the interesting entry is usually the request itself) and read the `issues` field:

```bash
curl \
	--data-urlencode "pageSize=300" \
	--data-urlencode "sort=executionDate:desc" \
	--get \
	--header "Accept: application/json" \
	--header "Authorization: Bearer ${ACCESS_TOKEN}" \
	--silent \
	--url "https://testray.liferay.com/o/testray-rest/v1.0/testray-case-result-history/<caseId>"
```

`issues` is populated only on entries a tester manually marked `BLOCKED` while citing a known ticket — it is empty on ordinary `FAILED`/`PASSED` entries, including the two builds actually being diffed almost always. Two different readings follow from what turns up:

- An `issues` entry that falls **inside or adjacent to the date range between the two compared builds** is a direct, high-confidence answer — use that ticket instead of searching commits.
- Entries that only exist **well before** the range (weeks or months earlier) are not the fix for the current occurrence — they are prior triage. But a long tail of many *different* ticket numbers across many months for the same case is itself a signal: it says the case has been chronically flaky and repeatedly re-triaged under a rotating cast of tickets, which is good evidence for reporting it as flaky rather than a fresh regression, even though it does not name what fixed *this* occurrence.

Either way, check this before spending a git-log budget on a case — it is one HTTP call and sometimes obviates the search entirely.

## Resolve a Fixed Cluster's Ticket Lifecycle

A **fixed** cluster gets a different, cheaper treatment than a new regression — it never falls back to `git log` widening at all. Group by the *older* task's subtask (Testray's own error-signature cluster), not by individual case, and follow this instead of the git-log archaeology above.

### No Ticket, No Search

Read the older subtask's `issues` field (already fetched alongside every other subtask field). Empty → stop immediately and report the cluster as **Unattributed (no ticket)** — do not widen into product code for it. Confirmed live: 0 of 6 real-fixed clusters in one full run had any ticket attached (all sat at `issues: ''`, `status: OPEN`, never triaged) — this is the common case, and skipping it outright is the whole point of this section.

### A Ticket ID Rarely Matches the Commit Directly — Expand to Parent/Subtasks First

Liferay's Jira splits a tracking ticket from the "Technical Task" that actually implements it, and commit messages are conventionally prefixed with whichever ticket the engineer was looking at — usually the Technical Task, not its parent. Confirmed live: `LPD-98246` (a Closed tracking ticket) has **zero** commits anywhere in history matching `git log --grep="LPD-98246"`; its child Technical Task `LPD-98247` has two — `d62de9c`/`9e36ea7 LPD-98247 Fix Widget Page card missing from Add Page wizard`. Searching only the ticket ID Testray recorded would have missed the real fix entirely. Resolve the related set before searching:

```bash
curl \
	--url "https://liferay.atlassian.net/rest/api/3/issue/<ticketKey>?fields=parent,subtasks" \
	--user "${JIRA_API_USER}:${JIRA_API_TOKEN}" \
	--silent
```

Related IDs = `{ticketKey}` ∪ `{fields.parent.key, if present}` ∪ `{fields.subtasks[].key}`. One level up, one level down is enough — this is the actual Task/Technical-Task shape seen live, not a deeper hierarchy.

### Search the Compared Range for a Substantive Commit

```bash
git log <olderBuildSha>..<newerBuildSha> --grep="^(<ID1>|<ID2>|...) " -E -i
```

Discard any match whose subject, after stripping the ticket prefix, is pure housekeeping noise — `SF`, `SF Auto`, `SF forced this`, `Regen`, `Wordsmith`, `prep next`, `buildLang`, `buildService`/`buildServices`, `buildRest`, or an `Autogenerated(...)` variant. A real, behavior-changing commit has to remain among the matches for the ticket to count as confirmed.

### Classify the Cluster

Using each member case's status in the newer build (already resolved during **Diff**) plus whether a substantive commit turned up:

| Member cases in newer build | Substantive commit found? | Outcome |
| --- | --- | --- |
| All confirmed `PASSED` | Yes | Propose **close**, comment "Fixed as of `<newerBuildSha>`" |
| All confirmed `PASSED` | No | Propose **neither** — flag "needs manual verification" (ticket exists, tests pass, nothing in range confirms why) |
| Some still `FAILED`/`BLOCKED`, all showing the *same* error as the subtask's original `error` | — | Propose **comment only**, note the partial fix and which cases remain, do not close |
| Some still `FAILED`/`BLOCKED`, at least one showing a *different* error | — | Propose **close anyway** — the original problem is resolved; a new/different failure will get its own ticket when someone picks it up next cycle |
| Any member `NOT_FOUND_IN_BUILD`, rest `PASSED` | — | Treat as "same error persists" — `NOT_FOUND_IN_BUILD` is not `PASSED` |

### Resolving "Close" Is Not Always One Call

A direct-to-Closed transition is not guaranteed to exist from wherever the ticket currently sits. Confirmed live: `GET .../issue/LPD-98577/transitions` (status `In Progress`) offers only "Back to Selected for Development" / "Back to Open" — no path to Closed at all — while the same call against `LPD-98426` (status `Open`) offers a direct `71 -> Closed`. Always resolve the real transition first:

```bash
curl \
	--url "https://liferay.atlassian.net/rest/api/3/issue/<ticketKey>/transitions" \
	--user "${JIRA_API_USER}:${JIRA_API_TOKEN}" \
	--silent
```

Look for one whose `to.statusCategory.key` is `"done"`. None available from the current status → before falling back to "needs a manual transition," check whether the ticket has a `Technical Task` among the `subtasks` already resolved under **A Ticket ID Rarely Matches the Commit Directly — Expand to Parent/Subtasks First** above. Liferay's convention splits a tracking `Task` from the `Technical Task` that actually carries the implementing commit — the tracking `Task` itself often has no direct path to `Closed` at all, while its `Technical Task` does. Confirmed live: `LPD-98577` (issuetype `Task`, status `In Progress`) had no `Closed` transition, but its subtask `LPD-98578` (issuetype `Technical Task`, the one the commit `cb4a41d` was actually filed under) did — closing `LPD-98578` first is what let `LPD-98577` close afterward.

When a `Technical Task` subtask exists, check `GET .../issue/<technicalTaskKey>/transitions` the same way:

- **A `done`-category transition exists on the Technical Task** → propose closing the Technical Task first (the "Fixed as of `<newerBuildSha>`" comment belongs there, since that is where the implementing commit is referenced), then re-check the parent Task's own transitions — closing every subtask commonly unlocks or auto-fires the parent's own close, so report the plan as "close `<technicalTaskKey>`, then `<ticketKey>` should follow" rather than treating the parent's missing transition as a dead end.
- **No `Technical Task` subtask, or it also has no `done`-category transition** → this is the actual dead end; report "cannot auto-close from status `<current>`, needs a manual transition" as before.

### Not Yet Wired to Write

The skill computes and reports every classification above, but does not yet call the write endpoints — shown here for when that changes, not for use today:

```bash
# Close (once a done-category transition ID has been resolved above)
curl --request POST \
	--header "Content-Type: application/json" \
	--user "${JIRA_API_USER}:${JIRA_API_TOKEN}" \
	--data '{"transition": {"id": "<transitionId>"}}' \
	--url "https://liferay.atlassian.net/rest/api/3/issue/<ticketKey>/transitions"

# Comment (with or without a close, per the classification table above)
curl --request POST \
	--header "Content-Type: application/json" \
	--user "${JIRA_API_USER}:${JIRA_API_TOKEN}" \
	--data '{"body": {"type": "doc", "version": 1, "content": [{"type": "paragraph", "content": [{"type": "text", "text": "<comment text>"}]}]}}' \
	--url "https://liferay.atlassian.net/rest/api/3/issue/<ticketKey>/comment"
```

## Match Adjacent-Task Subtasks First

Before the broader historical sweep below, compare `<previousTaskId>`'s subtasks against `<newTaskId>`'s subtasks directly, at the subtask level — this catches the common case (a recurring failure triaged on the immediately-prior task) more cheaply and more completely than any status-filtered search:

1. Fetch and dedupe both tasks' subtasks (same dedupe-by-name-keep-highest-id rule as everywhere else).

1. For every `<previousTaskId>` subtask with a non-empty `issues` field — regardless of its `dueStatus` — resolve its member case IDs.

1. For each, find the `<newTaskId>` subtask sharing the same (or materially overlapping) case-ID membership. Case ID is the reliable signal here; matching by error text alone can miss a match once the error message has drifted slightly between builds.

1. When a match is found and the new subtask doesn't already carry its own fresh `issues`/`userId` this cycle, sync forward exactly as described in **Carry Forward Subtask Claims Across Analysis Tasks** below.

Why this step exists: a subtask's manual triage very often ends at `dueStatus: COMPLETE` (the analyst filed a ticket and considers their part done), not `INANALYSIS`. Confirmed live: three separate `COMPLETE`-status subtasks on one previous task (one tagged LPD-100532, one tagged both LPD-100552 and LPD-100572, one tagged LPD-100596) were invisible to a `status=INANALYSIS`-only search and were found only by this direct comparison — one of them alone covered 15 member tests, all silently un-ticketed on the new task despite the underlying bug already being reported and still open. Checking one adjacent task's full subtask list regardless of status is cheap (a few dozen to ~100 subtasks, one page of calls); doing that for every task in Testray's history is not — that cost/coverage tradeoff is exactly why this step is scoped to `<previousTaskId>` alone, with the broader multi-task sweep below handling anything older.

## Carry Forward Subtask Claims Across Analysis Tasks

Testray generates a fresh set of subtasks every time a new Analysis Task is created for the same routine, but a subtask's triage state — `dueStatus`, `issues` (Jira keys), assignee — belongs to that one task and does not carry over. Manual work already done on a past task (someone set `INANALYSIS`, attached an LPD ticket, assigned themselves) looks lost on the next task's corresponding subtask unless it is deliberately copied forward.

### Writable Fields, Confirmed Against the REST Module Source

**Subtask** (`o/c/subtasks/{id}`, generic object endpoint, `PUT`) — `dueStatus` (`OPEN` / `INANALYSIS` / `COMPLETE` / `MERGED`, as `{"key": ..., "name": ...}`), `issues` (plain string, comma-separated Jira keys, no format validation), `r_userToSubtasks_userId` (numeric assignee — resolve it from a subtask the person already touched, e.g. `r_userToSubtasks_userId` on `GET /o/c/subtasks/{id}`, never guess from a display name):

```bash
curl --request PUT \
	--header "Authorization: Bearer ${ACCESS_TOKEN}" \
	--header "Content-Type: application/json" \
	--data '{"dueStatus": {"key": "INANALYSIS", "name": "In Analysis"}, "issues": "LPD-XXXXX", "r_userToSubtasks_userId": <userId>}' \
	--url "https://testray.liferay.com/o/c/subtasks/<subtaskId>"
```

**Task** (`o/c/tasks/{id}`, generic object endpoint, `PATCH`) — a *separate* picklist from the subtask's own: `OPEN`, `INANALYSIS`, `PROCESSING`, `COMPLETE`, `ABANDONED`. Nothing server-side gates a task's status on its subtasks' states — that's purely a convention other tooling follows, not an actual constraint, so a direct abandon is safe once the completeness check below passes:

```bash
curl --request PATCH \
	--header "Authorization: Bearer ${ACCESS_TOKEN}" \
	--header "Content-Type: application/json" \
	--data '{"dueStatus": {"key": "ABANDONED", "name": "Abandoned"}}' \
	--url "https://testray.liferay.com/o/c/tasks/<taskId>"
```

### Finding Historical Claims Cheaply

The subtask list endpoint is not limited to one task — omit `testrayTaskId` and filter by `testrayTeamIds`/`status`/`issues` instead to search every historical task in a single call. The endpoint takes one `status` value at a time, so query once for `INANALYSIS` and once for `COMPLETE` — the two states a human actually leaves a triaged subtask at — and merge the results; a search scoped to `INANALYSIS` alone silently misses every claim someone marked `COMPLETE` after filing its ticket:

```bash
curl \
	--data-urlencode "testrayTeamIds=<teamId>" \
	--data-urlencode "status=INANALYSIS" \
	--data-urlencode "pageSize=200" \
	--get \
	--header "Accept: application/json" \
	--header "Authorization: Bearer ${ACCESS_TOKEN}" \
	--url "https://testray.liferay.com/o/testray-rest/v1.0/testray-testflow/testray-subtask"
```

Repeat with `status=COMPLETE` and merge. This is far cheaper than walking every past build/task to find its subtasks one at a time — in practice it returned every `INANALYSIS`/`COMPLETE` Headless subtask in Testray's entire history (dozens, not hundreds) in one page each.

### Matching a Claim to the Current Task

Subtask IDs never carry across tasks and error-signature text can drift, so match by **case ID** (the stable test identity), not by subtask ID or name: resolve a candidate subtask's member case IDs the same way as anywhere else in this doc (`r_subtaskToCaseResults_c_subtaskId` filter), and check whether any of them are also a member of a subtask in the task being updated.

### Before Abandoning a Source Task, Verify — Don't Infer — Every Case Is Accounted For

A case that didn't get migrated because it no longer shows up in the new task's subtasks is not automatically safe to ignore. Query that case's result directly in the newer build and require an explicit `PASSED` (or a status the run intentionally accepts) before counting it as resolved:

```bash
curl \
	--data-urlencode "filter=r_buildToCaseResult_c_buildId eq '<newerBuildId>' and r_caseToCaseResult_c_caseId eq '<caseId>'" \
	--get \
	--header "Accept: application/json" \
	--header "Authorization: Bearer ${ACCESS_TOKEN}" \
	--url "https://testray.liferay.com/o/c/caseresults"
```

`NOT_FOUND_IN_BUILD` is not `PASSED` — it means the case simply wasn't tracked in that build (an environment/shard rotation, most likely), and its status is genuinely unknown. Confirmed live: of one task's ten claimed cases, five were `PASSED` in the newer build and five came back `NOT_FOUND_IN_BUILD` — the task was correctly left un-abandoned rather than guessed at.

### A Task with No Claims at All Is a Separate, Simpler Case

The claim search above only ever surfaces tasks that have at least one `INANALYSIS` subtask somewhere — a task where every subtask is still sitting at the auto-generated `OPEN`, untouched by anyone, never appears as a candidate and so never gets checked for abandonment by the logic above. That's a real gap, not a non-issue: confirmed live on task `500153871` (98 subtasks, all `OPEN`, zero with any assignee or `issues`) — a fully-untouched task that the claim-matching path would have ignored forever. There is nothing on a task like this to lose, so it doesn't need the case-by-case completeness check at all: fetch its subtasks, and if none carry `status: INANALYSIS`, abandon it directly. `<previousTaskId>` is the one task worth checking this way on every run, since it's the one this run's diff just used as the baseline and is now superseded — do not extend the same sweep to older tasks beyond it.

## Check Whether a Carried-Forward Fix Already Landed

A carried-forward cluster (from **Match Adjacent-Task Subtasks First** or **Carry Forward Subtask Claims Across Analysis Tasks**) already has a ticket attached, but the ticket alone doesn't say whether its fix is actually in the build the report is looking at. Distinguishing "still waiting on the fix" from "the fix should be in this build already and the test still fails" needs two checks per carried-forward cluster: find the real PR, then find its real merge status — neither of which any single state field (Jira's ticket status, Jira's dev-status, or GitHub's own PR state) answers reliably for this project on its own. Run this for **every** carried-forward cluster, including one whose ticket's own top-level status looks untouched (`In Progress`/`Open`) — confirmed live: a ticket sitting at `In Progress` was nearly reported as "no PR yet" when its Technical Task subtask was actually `Closed` with an already-merged fix. The parent ticket's own status is never a valid reason to skip this check.

### Find the Real PR by Searching the Fork Directly — Jira's `dev-status` Count Can Be a False Negative

Resolve the ticket's Technical Task first (parent/subtask expansion, same as **Resolve a Fixed Cluster's Ticket Lifecycle**) — commits are filed under the Technical Task's ID, not the tracking Task's. Then search `brianchandotcom/liferay-portal` directly, since this project titles every PR `<ticketId> <description>`:

```bash
gh api "search/issues?q=repo:brianchandotcom/liferay-portal+<ticketId>+in:title+type:pr" --jq '.items[] | {number, title, state}'
```

This is the primary, reliable lookup — not a fallback. Jira's `dev-status` API can be checked too for corroborating detail (`GET /rest/dev-status/1.0/issue/summary?issueId=<numericId>`, numeric ID from `GET /rest/api/3/issue/<ticketKey>?fields=id`), but **do not treat `summary.pullrequest.overall.count: 0` as proof no PR exists.** Confirmed live: a Technical Task with a real, already-merged PR still showed `count: 0` in `dev-status` — the count itself is an unreliable negative, not only the `state` field (below). Always search GitHub directly regardless of what `dev-status` reports.

No PR found on GitHub either → genuinely **still waiting**, stop here.

### `DECLINED`, GitHub's `merged: false`, and "This Pull Request Is Closed, but the Branch Has Unmerged Commits" Do Not Mean Rejected

This project's actual contribution workflow re-applies a PR's diff as a **brand-new commit** directly on `brianchandotcom/master` (a cherry-pick, not a GitHub-native merge), then closes the original PR. Because the new commit has a different SHA and different parent than the original branch's own commit, every state field git/GitHub can offer reports it as unmerged, even though the change genuinely landed:

- GitHub PR: `merged: false`, state `closed`.
- Jira dev-status: `state: "DECLINED"`.
- GitHub's own PR page banner: "This pull request is closed, but the `<branch>` branch has unmerged commits" — this is checking literal git ancestry (is the branch's own commit SHA reachable from master?) and is technically correct, but does not mean the change was rejected. It means the change was re-committed rather than merged as-is.

Confirmed live on 4 separate PRs across two investigations, all actually landed despite every one of these signals saying otherwise. The reliable tell is an issue-level comment from the merge bot:

```bash
gh api repos/<owner>/<repo>/issues/<prNumber>/comments --jq '.[] | {user: .user.login, body, created_at}'
```

A comment reading `Merged. Thank you.` followed by a `https://github.com/<owner>/<repo>/compare/<beforeSha>...<afterSha>` link is the real, authoritative signal. The commit(s) in that compare range are what actually landed — not the PR branch's own head commit, and not comparable by tree SHA either (the tree differs because the parent commit differs; comparing full trees will look like a mismatch even for an identical change). To confirm the applied commit really is the same change as the original PR, compare their **patches**, not their trees:

```bash
gh api repos/<headOwner>/<headRepo>/commits/<prHeadSha> --jq '.files[] | {filename, patch}'
gh api repos/<owner>/<repo>/commits/<appliedSha> --jq '.files[] | {filename, patch}'
```

Identical `filename`/`patch` pairs across both confirms it's the same change, just re-committed. No "Merged. Thank you." comment on the PR at all → it really was declined/closed without merging → **still waiting**, stop here.

### Confirm Ancestry Against the Analyzed Build, Not Just "Currently in Some Branch"

Once the real applied commit is in hand (from the compare link above, not the PR's own head commit), check whether it landed before the specific build this run analyzed — not merely whether it exists somewhere in history today. Fetch the fork remote first if the commit isn't already present locally (`git fetch <forkRemote> master`):

```bash
git merge-base --is-ancestor <appliedCommitSha> <newerBuildSha> && echo "ancestor" || echo "not an ancestor"
```

- **Ancestor** → the fix was already present when this build ran, and the test still failed anyway. Flag the cluster **needs re-investigation**, distinct from every other still-waiting cluster — this is the one case the team should look at again, not just wait on.
- **Not an ancestor** → confirm separately whether the commit is at least an ancestor of the fork's own current tip (`git merge-base --is-ancestor <appliedCommitSha> <forkRemote>/master`) to state the finding precisely: merged into the fork but not yet synced to the `liferay/liferay-portal` upstream this build ran against → **still waiting on the sync**, not a failure of the fix, and explicitly not the "declined" the raw state fields implied.

## URL Patterns

Deep-linkable Testray UI routes, useful for citing evidence in a report:

| What | Pattern |
| --- | --- |
| Analysis Task | `https://testray.liferay.com/#/testflow/<taskId>` |
| Subtask | `https://testray.liferay.com/#/testflow/<taskId>/subtasks/<subtaskId>` |
| Build | `https://testray.liferay.com/#/project/<projectId>/routines/<routineId>/build/<buildId>` |
| Case result | `https://testray.liferay.com/#/project/<projectId>/routines/<routineId>/build/<buildId>/case-result/<caseResultId>` |
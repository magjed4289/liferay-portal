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

Before falling back to `git log` archaeology for any case in `new` or `fixed`, check whether Testray already has a Jira ticket linked to that case — this is a direct answer, not an inference. Pull the case's full history (not filtered to FAILED/BLOCKED, and not routine-filtered, since the interesting entry is usually the request itself) and read the `issues` field:

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

## URL Patterns

Deep-linkable Testray UI routes, useful for citing evidence in a report:

| What | Pattern |
| --- | --- |
| Analysis Task | `https://testray.liferay.com/#/testflow/<taskId>` |
| Build | `https://testray.liferay.com/#/project/<projectId>/routines/<routineId>/build/<buildId>` |
| Case result | `https://testray.liferay.com/#/project/<projectId>/routines/<routineId>/build/<buildId>/case-result/<caseResultId>` |

There is no dedicated URL for a single subtask — link to the parent Analysis Task and cite the `ST-<n>` badge for the reader to find it.

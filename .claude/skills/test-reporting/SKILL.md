---

argument-hint: '[newAnalysisTaskIdOrUrl]'
description: Diff two Testray Analysis Tasks for ci:test:headless, attribute new regressions to commits, and check whether the team caused them.
name: test-reporting

---

# Test Reporting

Diff the headless team's `ci:test:headless` Testray Analysis Task against the last one this skill recorded, report what got fixed and what regressed, and — for every regression — name the responsible commit and say whether it's the team's own doing or an outside change to a module the team owns.

## References

Before running, read both — they are the source of truth this skill builds on:

- [`references/testray-testflow.md`](references/testray-testflow.md) — how to fetch an Analysis Task's subtasks, resolve their member tests, and diff two tasks safely.
- `../../rules/jira.md` — how to authenticate `curl` against Jira. The same token authenticates Confluence Cloud's REST API too. All Jira and Confluence calls go through `curl`, never the Atlassian MCP.

The team roster is not carried in this repo — it names colleagues' personal emails, Jira account IDs, and Slack handles, and has no business sitting in a `liferay-portal` clone's history. Fetch it fresh each run instead, straight from its source of truth in the `liferay-headless` plugin repo:

```bash
gh api repos/liferay-headless/liferay-headless/contents/rules/team.md --jq '.content' | base64 --decode
```

A commit's author is a team member only if their email appears in that table; never guess from a display name.

## Preconditions

Some steps need a `liferay-portal` clone to run `git log` against (to find the commit behind a regression) — any clone that tracks `liferay/liferay-portal` works, since the commit SHAs Testray reports are upstream SHAs. If the current working directory is not one, ask the user for the path to one rather than guessing.

`${TESTRAY_CLIENT_ID}` and `${TESTRAY_CLIENT_SECRET}` must be set for Testray access; `${JIRA_API_USER}` and `${JIRA_API_TOKEN}` for Confluence access. Without them, abort and surface which is missing.

## Input

### New Analysis Task

`${ARGUMENTS}` is the Testray Analysis Task to compare against — either a bare task ID or a full `https://testray.liferay.com/#/testflow/<taskId>` URL. When omitted, resolve it instead of asking: see **Resolve `<newTaskId>`** in the workflow below.

### Baseline (Previous Analysis Task)

Read from the tracking page (see **State**) rather than asked for — that is the entire point of recording it. When the tracking page does not exist yet or has no recorded task, this is the first-ever run: skip straight to **First Run** in the workflow below.

## State

State lives in a single Confluence page, not a local file — the page's own revision history doubles as an audit trail, and any team member can read it without a repo checkout.

**Tracking page**: titled exactly `Headless Testray Regression Tracking`, in the `ENGHEADLESS` space. Find it with:

```bash
curl \
	--get \
	--data-urlencode 'cql=space = "ENGHEADLESS" AND title = "Headless Testray Regression Tracking"' \
	--header "Accept: application/json" \
	--user "${JIRA_API_USER}:${JIRA_API_TOKEN}" \
	--url "https://liferay.atlassian.net/wiki/rest/api/content/search"
```

The page's most recent version **is** the previous run's report. Read the number after "Analysis Task" in its first paragraph — that is `<previousTaskId>`. Every run **replaces** this page's body wholesale (via `PUT /wiki/api/v2/pages/<id>`, incrementing `version.number`) with the new report, opening with the same marker line so the next run can parse it:

```
Analysis Task <newTaskId> compared against <previousTaskId> — generated <date>.
```

On the very first run (no page exists), create it with that marker line naming only `<newTaskId>` (no `previousTaskId` yet, since there is nothing to compare against), plus one sentence explaining that the next run will be the first real diff.

## Workflow

### Resolve `<newTaskId>`

When `${ARGUMENTS}` names a task, that is `<newTaskId>` — move on to **First Run** or **Resolve Both Tasks**. Otherwise, follow [`references/testray-testflow.md`](references/testray-testflow.md#find-or-create-the-freshest-analysis-task): find the freshest `DONE` build for the team routine, reuse its Task if one already exists, or create the Task and generate its subtasks if none does. Never let more than one task exist for the same build — that has already taken the routine down in production once; if the build already has more than one task, or a race creates one during this run, follow the reference doc rather than guessing which to keep. Tell the user which path was taken — reused an existing task, or created a new one.

### First Run

Create the tracking page (see **State**) recording only the new task ID. Tell the user there is no prior baseline, so nothing was compared — the next invocation will be the first real diff. Stop here.

### Resolve Both Tasks

For `<newTaskId>` and `<previousTaskId>`, follow [`references/testray-testflow.md`](references/testray-testflow.md) to fetch and dedupe each task's subtasks, resolve every subtask's linked case results (filtering out placeholder/log-assertor names), and confirm the single build ID each task's case results resolve to. Note each build's `gitHash` from `/o/c/builds/<buildId>`, but get `failed`/`passed` from the **Headless** entry of that build's `testray-teams-metrics` (per the reference doc's **Report the Team-Scoped Total** section) — never the build's own top-level `caseResultFailed`/`caseResultPassed`, which sum every team sharing that build. These back the summary table.

### Diff

Follow the **Diff Two Analysis Tasks** procedure in the reference doc: diff by real case ID directly (pooling every subtask's members first — never by subtask signature presence, which hides any fix or regression that happens inside a cluster still kept alive by other members), then cross-check every case in the resulting new/fixed sets individually against the *other* build's own result for that case ID. Separate the generic environment/boot-failure cases from genuine code regressions — they get reported, but never attributed to a commit.

### Resolve Fixed-Cluster Ticket Lifecycle

For every older-task subtask behind a fixed or partially-fixed case this run — grouped by that subtask, not by individual case — follow [`references/testray-testflow.md`](references/testray-testflow.md#resolve-a-fixed-clusters-ticket-lifecycle):

1. No ticket in that subtask's `issues` → report it as **Unattributed (no ticket)** immediately; do not search git log for it.

1. Ticket found → resolve its Jira parent/subtasks, search the compared commit range for any related ID, discard housekeeping-only matches, and classify the cluster: fully fixed with a substantive commit found → propose close; fully fixed with none found → needs manual verification; partially fixed with the same error persisting → propose comment only; partially fixed with a different error → propose close anyway.

1. **This is report-only for now** — compute and report the proposed action (close, comment, or neither), but do not call the Jira transition or comment endpoints yet.

This is the only attribution a fixed cluster gets — it either resolves here (ticket found) or is reported unattributed directly (no ticket), with no git-log fallback either way. **Attribute Changes to Commits** below is scoped to new regressions only.

### Sync Claims from Past Analyses

The user (or a teammate) manually triages subtasks in Testray between runs — assigning one, attaching an LPD ticket, moving it to `INANALYSIS`. That work lives on the task it was done on; the next Analysis Task Testray generates for the same recurring failure starts its corresponding subtask back at `OPEN`/unassigned. Carry it forward instead of letting it look unclaimed again, per [`references/testray-testflow.md`](references/testray-testflow.md#carry-forward-subtask-claims-across-analysis-tasks):

1. Query `testray-testflow/testray-subtask` **without** a `testrayTaskId` — scoped to the team and `status=INANALYSIS` — to find every historical claim across all past tasks in one call. Drop any result belonging to `<newTaskId>` itself, and any with an empty `issues` field.

1. For each surviving candidate, check every Jira key in its `issues` field (comma-split) — drop the candidate if every key is Closed.

1. Resolve each surviving candidate's member case IDs (same `r_subtaskToCaseResults_c_subtaskId` filter used throughout). Build a `caseId → {issues, userId, userName, sourceTaskId}` map; when two candidates claim the same case, keep the one from the higher `testrayTaskId`.

1. For every subtask already resolved for `<newTaskId>` in **Diff**: skip it if it already carries its own `userId`/`issues` this cycle — never clobber fresh manual triage. Otherwise, if any member case ID is in the map, `PUT /o/c/subtasks/{newSubtaskId}` with that claim's `issues`, `r_userToSubtasks_userId`, and `dueStatus: INANALYSIS`. Never modify the *source* subtask's own fields — the claim is copied forward, not detached from where it came from.

1. For each distinct `sourceTaskId` touched above, check whether *every* one of its surviving candidate cases is now accounted for — either migrated in the previous step, or independently confirmed `PASSED` in `<newTaskId>`'s build (query the case result directly; do not infer this from absence, since `NOT_FOUND_IN_BUILD` is not `PASSED`). When every case clears one of those two bars, `PATCH /o/c/tasks/{sourceTaskId}` with `{"dueStatus": {"key": "ABANDONED", "name": "Abandoned"}}` right away. A task with even one case that's merely missing or still failing unmatched is left untouched — abandoning it would lose the only remaining record of that claim.

1. Separately, check `<previousTaskId>` specifically (the task this run's diff used as the baseline, regardless of whether it produced any migration candidates above): fetch its subtasks and check whether *any* carry `status: INANALYSIS`. If none do — every subtask is still at the auto-generated `OPEN`, nobody ever triaged it — there is nothing on it to lose, so abandon it directly, the same `PATCH` as above, with no matching step required. This is what catches a fully-untouched previous task that the claim search above would otherwise never consider, since it never had a claim to find in the first place. Do not widen this check to older tasks beyond `<previousTaskId>` — that would be a much larger sweep than one run should take on by itself.

### Attribute Changes to Commits

Run this for every **new regression** only — a fixed cluster is handled entirely by **Resolve Fixed-Cluster Ticket Lifecycle** above (resolved there via its ticket, or reported unattributed directly when it has none); this section is not a fallback for those.

For each new-regression case (or group of cases sharing one error signature — a single regression commonly breaks several tests in the same feature area at once, so group before attributing rather than repeating the search per test):

1. **Check Testray's own case history first**, per [`references/testray-testflow.md`](references/testray-testflow.md#check-case-history-for-a-linked-jira-issue-before-attributing-by-commit) — an `issues` entry landing inside the compared date range is a direct answer and skips the rest of this section entirely. A long tail of older, unrelated tickets is still worth noting in the report as evidence of chronic flakiness, even when it doesn't name the fix.

1. Otherwise, locate the owning module or test file from the case name (e.g. a Poshi `.testcase`, a `*ResourceTest.java`, a Playwright `.spec.ts`).

1. In the `liferay-portal` clone, run `git log <olderBuildSha>..<newerBuildSha> -- <path>` scoped to that module, then widen the search (feature area, DTO class name, error keyword via `git log -S"<term>"`) until a commit or tight commit cluster explains the change. Read each candidate's full message and diff before deciding. Label the attribution **plausible** rather than confirmed when the link is topical/file-level rather than a direct match to the assertion that changed.

1. Resolve the commit's author email and check it against the roster fetched in **References**:
	- **On the roster** → this is the team's own work. The regression is squarely the team's to fix.
	- **Not on the roster** → search the `liferay-headless/liferay-portal` GitHub repository for an existing "Intruders 🦹‍♂️" issue mentioning the commit's short SHA:

		```bash
		gh issue list --repo liferay-headless/liferay-portal --search "<shortSha>" --state all --json number,title,state,url
		```

		When found, read the issue (and, if closed, its triage comment) to report whether it was already escalated or dismissed, and link it. When no issue mentions it yet, say so plainly — it means the automated Intruders triage has not caught this one, and the team may want to flag it.

When no commit or linked issue is found after widening the search, report the case as unattributed rather than guessing.

### Build the Report

Both the Confluence page body and the chat reply share this structure:

1. **Top summary table**, exactly these four rows, computed from the resolved builds and the diff — this is the part the team actually reads:

	| Metric | Value |
	| --- | --- |
	| Current failures (total) | `<newer build's Headless-team failed count, from testray-teams-metrics>` |
	| Current failures, excluding the environment-incident cluster(s) | `<total minus every FAILED case result inside a generic boot-failure subtask>` |
	| Tests fixed since the last analysis | `<count of individually-confirmed fixes>` |
	| Issues to be claimed by the team | `<count of unattributed regressions, plus any team-member-authored ones>` |

1. **Accountability callout**, directly under the table: if every attributed regression traces to a non-team commit, state plainly that the team's own work produced zero of them, and link the Intruders issue(s) covering each cause (or note that none exists yet). If any attributed regression traces to a team member's commit, name them and the commit instead — do not soften it into the same reassuring framing. When no commit was found for any regression at all, say so plainly instead of forcing this framing — flag the recurrence pattern if the same test was unattributed in the previous report too.

1. **New regressions**, one subsection per root cause, each with: the Testray subtask badge(s), the error signature, every affected test as a link to its case result (`.../build/<newerBuildId>/case-result/<caseResultId>`), and — when attributed — the commit table (SHA, author, date, message) plus the roster/Intruders verdict from the previous step.

1. **Environment incident**, listing the boot-failure subtask(s) and their test counts, explicitly excluded from commit attribution. State plainly when there is none this round.

1. **Issues to be claimed**, the unattributed or team-caused regressions, so the team has a concrete pickup list.

1. **Fixed**, foldable given the count is often large, one row per confirmed-fixed test: a link to its case result on the *older* build (where it last failed), that failure's error text, and an **LPD Ticket** column from **Resolve Fixed-Cluster Ticket Lifecycle** — the specific ticket when a substantive commit confirmed it, "needs manual verification" when a ticket exists but nothing in range confirms it, or "Unattributed (no ticket)" when the cluster never carried one. Group the summary above the table by ticket (or "unattributed") so one fix that resolved many tests reads as one entry, not a wall of identical rows.

1. **Fixed Tickets — Proposed Jira Actions**, one row per ticketed cluster **Resolve Fixed-Cluster Ticket Lifecycle** touched this run: ticket, cluster, classification, and the exact action that would be taken (close + comment, comment only, or needs manual verification). Report-only — nothing here has actually been written to Jira yet. Omit the section entirely when no fixed cluster carried a ticket this run.

1. **Still failing, error changed** — tests that matched a "new" signature on one side and a "fixed" signature on the other purely because the error text shifted, but the individual cross-check in **Diff** showed they were failing on both builds all along. Report these separately with both errors shown side by side; never let one leak into the New Regressions or Fixed sections.

1. **Carried Forward From Past Analyses** — one row per claim migrated in **Sync Claims from Past Analyses**: test, ticket, assignee, link to the source task. Omit the section when nothing migrated this run.

1. **Past Tasks Abandoned** — one row per source task the sync step just marked `ABANDONED`, with a link and which of the two reasons applied (every live claim migrated/resolved, or the task had zero claims to begin with), so the action is visible and auditable rather than silent. Omit when none qualified.

1. **Methodology** footer: the two build SHAs compared, the routine, and a one-line note that infra/log-assertor placeholder cases were excluded.

Publish the Confluence page per **State**, then post the same content to the chat as the final reply. When the Artifact tool is available in this session, also render it as a styled HTML page for the calling user — but the Confluence page is the durable record; never skip it in favor of the Artifact.
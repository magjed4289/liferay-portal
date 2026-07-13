---

argument-hint: '<newAnalysisTaskIdOrUrl>'
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

`${ARGUMENTS}` is the Testray Analysis Task to compare against — either a bare task ID or a full `https://testray.liferay.com/#/testflow/<taskId>` URL. When absent, ask the user for it; do not guess the latest task automatically, since triggering a fresh analysis run is a manual step on their end.

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

### First Run

Create the tracking page (see **State**) recording only the new task ID. Tell the user there is no prior baseline, so nothing was compared — the next invocation will be the first real diff. Stop here.

### Resolve Both Tasks

For `<newTaskId>` and `<previousTaskId>`, follow [`references/testray-testflow.md`](references/testray-testflow.md) to fetch and dedupe each task's subtasks, resolve every subtask's linked case results (filtering out placeholder/log-assertor names), and confirm the single build ID each task's case results resolve to. Note each build's own `gitHash`, `caseResultFailed`, and `caseResultPassed` from `/o/c/builds/<buildId>` — these back the summary table.

### Diff

Follow the **Diff Two Analysis Tasks** procedure in the reference doc: match subtasks by normalized error signature, then cross-check every candidate new-failure and candidate-fixed test individually against the *other* build's own result for that case ID. Separate the generic environment/boot-failure cluster(s) from genuine code regressions — they get reported, but never attributed to a commit.

### Attribute Regressions to Commits

For each genuine new regression (or group of regressions sharing one error signature):

1. Locate the owning module or test file from the case name (e.g. a Poshi `.testcase`, a `*ResourceTest.java`, a Playwright `.spec.ts`).

1. In the `liferay-portal` clone, run `git log <olderBuildSha>..<newerBuildSha> -- <path>` scoped to that module, then widen the search (feature area, DTO class name, error keyword via `git log -S"<term>"`) until a commit or tight commit cluster explains the change. Read each candidate's full message and diff before deciding.

1. Resolve every candidate commit's author email and check it against the roster fetched in **References**:
	- **On the roster** → this is the team's own work. The regression is squarely the team's to fix.
	- **Not on the roster** → search the `liferay-headless/liferay-portal` GitHub repository for an existing "Intruders 🦹‍♂️" issue mentioning the commit's short SHA:

		```bash
		gh issue list --repo liferay-headless/liferay-portal --search "<shortSha>" --state all --json number,title,state,url
		```

		When found, read the issue (and, if closed, its triage comment) to report whether it was already escalated or dismissed, and link it. When no issue mentions it yet, say so plainly — it means the automated Intruders triage has not caught this one, and the team may want to flag it.

When no commit is found after widening the search, report the regression as unattributed rather than guessing.

### Build the Report

Both the Confluence page body and the chat reply share this structure:

1. **Top summary table**, exactly these four rows, computed from the resolved builds and the diff — this is the part the team actually reads:

	| Metric | Value |
	| --- | --- |
	| Current failures (total) | `<newer build's caseResultFailed>` |
	| Current failures, excluding the environment-incident cluster(s) | `<total minus every FAILED case result inside a generic boot-failure subtask>` |
	| Tests fixed since the last analysis | `<count of individually-confirmed fixes>` |
	| Issues to be claimed by the team | `<count of unattributed regressions, plus any team-member-authored ones>` |

1. **Accountability callout**, directly under the table: if every attributed regression traces to a non-team commit, state plainly that the team's own work produced zero of them, and link the Intruders issue(s) covering each cause (or note that none exists yet). If any attributed regression traces to a team member's commit, name them and the commit instead — do not soften it into the same reassuring framing. When no commit was found for any regression at all, say so plainly instead of forcing this framing — flag the recurrence pattern if the same test was unattributed in the previous report too.

1. **New regressions**, one subsection per root cause, each with: the Testray subtask badge(s), the error signature, every affected test as a link to its case result (`.../build/<newerBuildId>/case-result/<caseResultId>`), and — when attributed — the commit table (SHA, author, date, message) plus the roster/Intruders verdict from the previous step.

1. **Environment incident**, listing the boot-failure subtask(s) and their test counts, explicitly excluded from commit attribution. State plainly when there is none this round.

1. **Issues to be claimed**, the unattributed or team-caused regressions, so the team has a concrete pickup list.

1. **Fixed**, one row per confirmed-fixed test with a link to its case result on the *older* build (where it last failed) and that failure's error text.

1. **Still failing, error changed** — tests that matched a "new" signature on one side and a "fixed" signature on the other purely because the error text shifted, but the individual cross-check in **Diff** showed they were failing on both builds all along. Report these separately with both errors shown side by side; never let one leak into the New Regressions or Fixed sections.

1. **Methodology** footer: the two build SHAs compared, the routine, and a one-line note that infra/log-assertor placeholder cases were excluded.

Publish the Confluence page per **State**, then post the same content to the chat as the final reply. When the Artifact tool is available in this session, also render it as a styled HTML page for the calling user — but the Confluence page is the durable record; never skip it in favor of the Artifact.

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

When `${ARGUMENTS}` names a task, that is `<newTaskId>` — move on to **First Run** or **Resolve Both Tasks**. Otherwise, follow [`references/testray-testflow.md`](references/testray-testflow.md#find-or-create-the-freshest-analysis-task): find the freshest `DONE` build for the team routine, reuse its Task if one already exists, or create the Task and generate its subtasks if none does. Never let more than one task exist for the same build — that has already taken the routine down in production once; if the build already has more than one task, or a race creates one during this run, follow the reference doc rather than guessing which to keep. Tell the user which path was taken — reused an existing task, or created a new one. When a task gets freshly created and clustered this run, also warn the user not to open its analysis view in the Testray UI until the run finishes — confirmed live, doing so re-triggers clustering and duplicates every subtask (see [`references/testray-testflow.md`](references/testray-testflow.md#regeneration-can-be-triggered-by-anyone-at-any-time)).

### First Run

Create the tracking page (see **State**) recording only the new task ID. Tell the user there is no prior baseline, so nothing was compared — the next invocation will be the first real diff. Stop here.

### Resolve Both Tasks

For `<newTaskId>` and `<previousTaskId>`, follow [`references/testray-testflow.md`](references/testray-testflow.md) to fetch and dedupe each task's subtasks, resolve every subtask's linked case results (filtering out placeholder/log-assertor names), and confirm the single build ID each task's case results resolve to. Note each build's `gitHash` from `/o/c/builds/<buildId>`, but get `failed`/`passed` from the **Headless** entry of that build's `testray-teams-metrics` (per the reference doc's **Report the Team-Scoped Total** section) — never the build's own top-level `caseResultFailed`/`caseResultPassed`, which sum every team sharing that build. These back the summary table.

### Match Adjacent-Task Subtasks

Before anything else — before **Diff**, before any case-ID-level comparison — compare `<previousTaskId>`'s subtasks against `<newTaskId>`'s subtasks directly, at the subtask level, per [`references/testray-testflow.md`](references/testray-testflow.md#match-adjacent-task-subtasks-first). For every `<previousTaskId>` subtask, regardless of its `dueStatus` (`OPEN`, `INANALYSIS`, `COMPLETE`, whatever it sits at), find its counterpart on `<newTaskId>` by shared case-ID membership. When a match carries a non-empty `issues` field on the old side, sync it onto the new side per [`references/testray-testflow.md`](references/testray-testflow.md#writable-fields-confirmed-against-the-rest-module-source) (never clobbering fresh triage already present on the new subtask) — **but do not write anything for it until Check Whether a Carried-Forward Fix Already Landed has run for that claim.** A subtask-level write is safe here specifically because the match is 1:1 at the subtask level, but the *status value* still depends on the verdict: mirror the source's `dueStatus` only when the verdict is **Still waiting**; write nothing at all when it is **Requires attention** (confirmed live: a claim mirrored forward as `COMPLETE` onto four still-actively-failing subtasks had to be reverted once the merge-status check came back **Requires attention** instead).

This step exists because real triage in Testray very often ends at `COMPLETE` (ticket filed, work considered done), not `INANALYSIS` — a search scoped to `status=INANALYSIS` alone misses every one of those. Checking all of `<previousTaskId>`'s subtasks is cheap (one task, a few dozen to ~100 subtasks) and catches every carry-forward claim sitting on the immediately-prior task. This is the skill's only carry-forward mechanism — a claim sitting on a task *older* than `<previousTaskId>` (a recurring failure that skipped a generation or more) is out of scope; a prior, more thorough sweep of the routine's full history was tried and dropped for being too heavy for the value it added (thousands of historical subtasks to page through and cross-check against Jira for a handful of extra matches).

Once every match is resolved, check whether `<previousTaskId>` itself can be retired: per [`references/testray-testflow.md`](references/testray-testflow.md#before-abandoning-a-source-task-verify--dont-infer--every-case-is-accounted-for), for every `<previousTaskId>` subtask that carried a ticket, confirm each of its member cases is now accounted for — either migrated above, or independently confirmed `PASSED` in `<newTaskId>`'s build (query the case result directly; `NOT_FOUND_IN_BUILD` is not `PASSED`). When every ticketed subtask clears that bar (including the trivial case of zero tickets on the task to begin with), `PATCH /o/c/tasks/{previousTaskId}` with `{"dueStatus": {"key": "ABANDONED", "name": "Abandoned"}}` right away. Leave it untouched if even one case is merely missing or still failing unmatched — abandoning it would lose the only remaining record of that claim. Do not widen this check to any task older than `<previousTaskId>`.

### Diff

Follow the **Diff Two Analysis Tasks** procedure in the reference doc: diff by real case ID directly (pooling every subtask's members first — never by subtask signature presence, which hides any fix or regression that happens inside a cluster still kept alive by other members), then cross-check every case in the resulting new/fixed sets individually against the *other* build's own result for that case ID. Separate the generic environment/boot-failure cases from genuine code regressions — they get reported, but never attributed to a commit.

### Resolve Fixed-Cluster Ticket Lifecycle

For every older-task subtask behind a fixed or partially-fixed case this run — grouped by that subtask, not by individual case — follow [`references/testray-testflow.md`](references/testray-testflow.md#resolve-a-fixed-clusters-ticket-lifecycle):

1. No ticket in that subtask's `issues` → report it as **Unattributed (no ticket)** immediately; do not search git log for it.

1. Ticket found → resolve its Jira parent/subtasks, search the compared commit range for any related ID, discard housekeeping-only matches, and classify the cluster: fully fixed with a substantive commit found → propose close; fully fixed with none found → needs manual verification; partially fixed with the same error persisting → propose comment only; partially fixed with a different error → propose close anyway.

1. **This is report-only for now** — compute and report the proposed action (close, comment, or neither), but do not call the Jira transition or comment endpoints yet.

This is the only attribution a fixed cluster gets — it either resolves here (ticket found) or is reported unattributed directly (no ticket), with no git-log fallback either way. **Attribute Changes to Commits** below is scoped to new regressions only.

### Check Whether a Carried-Forward Fix Already Landed

For **every** cluster carried forward by **Match Adjacent-Task Subtasks** — no exceptions, and do not skip this for a ticket whose own top-level status looks untouched (`In Progress`/`Open`) — determine whether its fix has actually merged, and if so, whether that merge predates `<newTaskId>`'s build. This is what tells the team "still waiting" apart from "the fix should already be in this build, and it isn't working." Confirmed live: a ticket sitting at `In Progress` was skipped on the assumption nothing had happened yet, when its Technical Task subtask was already `Closed` with a merged fix — the parent ticket's own status is not a valid signal to skip the check. Follow [`references/testray-testflow.md`](references/testray-testflow.md#check-whether-a-carried-forward-fix-already-landed):

1. Resolve the ticket's Technical Task (parent/subtask expansion, same as **Resolve a Fixed Cluster's Ticket Lifecycle**) — always, even if the parent ticket itself looks untouched.

1. Search `brianchandotcom/liferay-portal` directly for a PR titled with the ticket ID (the Technical Task's ID, since that's the one commits are filed under) — this project titles every PR `<ticketId> <description>`, so this is a reliable primary lookup, not a fallback. **Do not rely on Jira's `dev-status` API as the gate for "no PR exists."** Confirmed live: `dev-status`'s own `summary.pullrequest.overall.count` returned `0` for a Technical Task that had a real, merged PR — the count itself is an unreliable negative, not just the `state` field (see next step). Query `dev-status` for corroborating detail if it has any, but always search GitHub directly regardless of what `count` says.

1. No PR found on GitHub either → genuinely **still waiting**, stop here.

1. A PR found, in any state, including `DECLINED`/closed-unmerged, or GitHub's own PR-page banner reading "closed, but the branch has unmerged commits" → **none of these mean rejected, and none is a final answer.** This project's actual contribution workflow re-applies a PR's diff as a brand-new commit directly on `brianchandotcom/master` (a cherry-pick, not a GitHub-native merge) and closes the original PR — every state field git/GitHub can offer reports that as unmerged (correctly, by literal git ancestry) even though the change genuinely landed. Confirmed live on 4 separate PRs, all actually merged despite every one of these signals saying otherwise. Check the PR's own issue-level comments (`GET /repos/<owner>/<repo>/issues/<number>/comments`) for a bot comment reading "Merged. Thank you." with a `compare/<beforeSha>...<afterSha>` link — that link's commit(s) are the real applied commit. To confirm it's really the same change (not just a similarly-titled one), compare its patch against the original PR head commit's patch, not their tree SHAs, which will differ even for an identical change since the parent commit differs.

1. No such comment found → genuinely not merged → **still waiting**, stop here.

1. Comment found → resolve the real commit from the compare link and check whether it is an ancestor of `<newTaskId>`'s build SHA in the `liferay-portal` clone (fetch the fork remote if the commit isn't already present locally). Ancestor → the fix was already in the build the team is looking at, yet the test still failed: verdict **Requires attention**. Not an ancestor → merged into the fork, not yet synced to the `liferay/liferay-portal` upstream this build ran against: verdict **Still waiting**.

All of the investigation above (PR numbers, commit SHAs, bot comments, ancestry checks) is working detail for reaching a verdict — it stays out of the report itself. The report gets only the two-value verdict and a one-line merge status; see **Build the Report**.

### Attribute Changes to Commits

Run this for every **new regression** only — a fixed cluster is handled entirely by **Resolve Fixed-Cluster Ticket Lifecycle** above; this section is not a fallback for those.

This step has one narrow job: **rule in or out a Headless team member's own commit as the cause.** It is not root-cause analysis — finding the actual mechanism of a failure is what `/test-fix` is for. The moment a check below comes back negative for the team, stop; do not widen the search, read full diffs, or chase a specific external author.

For each new-regression error signature (group first, per **Diff**):

1. **Pick one representative case from the group** — prefer whichever member's case result has the lowest recorded `duration`, falling back to any member if `duration` isn't populated. Run every remaining step against that one case only.

1. **Check Testray's own case history for the representative**, per [`references/testray-testflow.md`](references/testray-testflow.md#check-case-history-for-a-linked-jira-issue-before-attributing-by-commit) — an `issues` entry landing inside the compared date range is a direct answer and skips the rest of this section entirely. A long tail of older, unrelated tickets is still worth noting in the report as evidence of chronic flakiness, even when it doesn't name the fix.

1. Otherwise, locate the owning test file from the representative's case name (e.g. a Poshi `.testcase`, a `*ResourceTest.java`, a Playwright `.spec.ts`) — **prefer the specific file over its containing module directory.** A directory-level path can pull in an unrelated sibling test's commit from a different team member, which then has to be judged away by reading its subject; scoping to the exact file avoids that dilution entirely. Fall back to the narrowest containing directory only when the case name doesn't cleanly map to one file (e.g. a shared macro or a Java class split across files).

1. In the `liferay-portal` clone, run `git log <olderBuildSha>..<newerBuildSha> -- <path>` scoped to that file (or, on the fallback above, its narrowest containing directory) — **one call, no `-S` keyword widening, no broadening beyond that path.**

1. **Early exit:** resolve every returned commit's author email against the roster fetched in **References**, discarding pure housekeeping commits (`SF`, `SF Auto`, `Regen`, `buildLang`/`buildService`/`buildRest`, `Autogenerated(...)`, etc. — the same noise list as **Resolve Fixed-Cluster Ticket Lifecycle**) before checking:
	- **No commit in that log has a roster email as author** (including an empty log) → **stop here.** Report the case as **unattributed to the team** — do not identify a specific external author or commit, and do not search for an Intruders issue. That level of detail is out of scope for this report.
	- **A roster-authored commit is found and its subject is plausibly connected to the failure** → cite it (SHA, author, date, subject) and mark the cluster **team-caused**. The regression is squarely the team's to fix.
	- **A roster-authored commit is found but it's unrelated/housekeeping only** → treat as no team-relevant commit found; note its existence in one line for transparency, still report as unattributed to the team.

Roster matching is by commit author **email** against the roster's `Email(s)` column — a commit carries no GitHub handle, so that roster column isn't used here.

### Cross-Check Against the Acceptance Routine

A test failing in both `ci:test:headless` and the shared, release-gating `EE Development Acceptance (master)` routine (ID `590307` — confirmed live, a second routine shares that exact name; `590307` is the correct one, see the reference doc) is higher priority than one failing only in the team's own daily routine. Run this once, against the full set of case IDs already destined for the report — every **New Regression** and every **Carried Forward** cluster's member cases — per [`references/testray-testflow.md`](references/testray-testflow.md#cross-check-against-the-acceptance-routine):

1. Resolve the freshest `DONE` build of routine `590307` once.

1. For each case ID, query `/o/c/caseresults` filtered to that case ID and that one build ID — a single direct call per case, not a paged case-history walk.

1. `FAILED`/`BLOCKED` there → tag the row **Acceptance** (the priority signal) and sort it to the top of its table. `PASSED` there on a strictly newer commit than the build being analyzed → tag **may already be fixed** instead (a different, softer note — do not conflate the two). No result, or `UNTESTED` → no tag; both mean "no signal," not "not failing there."

This is a priority signal only — it never changes a case's verdict (`Unattributed`, `Team-caused`, `Still waiting`, etc.) or which section it belongs in, only how it's flagged and ordered within its own table.

### Build the Report

Both the Confluence page body and the chat reply share this structure:

1. **Top summary table**, exactly these four rows, computed from the resolved builds and the diff — this is the part the team actually reads:

	| Metric | Value |
	| --- | --- |
	| Current failures (total) | `<newer build's Headless-team failed count, from testray-teams-metrics>` |
	| Current failures, excluding the environment-incident cluster(s) | `<the total above minus every current failure whose error is a generic boot/environment signature>` |

	Compute the second row by scanning the **entire current-failures pool** — every `FAILED`/`BLOCKED` case in the newer build, not only the new regressions — and subtracting each case whose error matches a generic boot/environment signature (`The build failed prior to running the test.`, an `org.apache.tools.ant.TaskAdapter` stack frame, `stop-docker-containers:`, `Failed for unknown reason`, a bare `[echo]` — the same list the **Diff** step in [`references/testray-testflow.md`](references/testray-testflow.md#diff-two-analysis-tasks) uses). Match **per case, not per subtask** — a subtask can carry a generic-sounding signature while grouping real failures, and a real test can boot-fail inside an otherwise-meaningful cluster. When the two rows are equal, that is a claim that zero current failures are boot/environment failures; verify it against the pool before publishing rather than defaulting to it.
	| Tests fixed since the last analysis | `<count of individually-confirmed fixes>` |
	| Issues to be claimed by the team | `<count of unattributed regressions, plus any team-member-authored ones>` |

1. **Accountability callout**, directly under the table: if no regression this run traces to a roster-authored commit, state plainly that the team's own work produced zero of them. If any regression traces to a team member's commit, name them and the commit instead — do not soften it into the same reassuring framing. Everything else is simply **unattributed to the team** — this report does not identify who else it might be; flag the recurrence pattern if the same test was unattributed in the previous report too.

1. **New Regressions — Issues to Be Claimed**, one combined table, **one row per subtask** (not per member test — the team picks up and files a ticket against the cluster as a whole, not test-by-test): subtask badge (linked — this is the *only* link in the row; do not also link individual case results, since the subtask view already surfaces every member), a bare test count (`1`, `2`, … — not the test names; names and case-result detail live in Testray itself, one click away via the badge), a **Component(s)** column, a short history note (`None — brand new`, or `<N> tickets since <date>` when chronic — one clause, not a paragraph), a verdict column (`Unattributed`, or `Team-caused: <SHA> <author>` when a roster-authored commit was found), and an **Acceptance** column from **Cross-Check Against the Acceptance Routine** (`Acceptance` when also failing there, `May already be fixed` when passing on a newer commit there, blank otherwise). Sort rows tagged `Acceptance` to the top of the table — that's the whole point of the check.

	**Component(s)** is deliberately in place of showing the raw error text — the error is one click away via the subtask badge, while which product area is affected is what actually helps someone decide whether to pick the work up. See [`references/testray-testflow.md`](references/testray-testflow.md#resolve-a-subtasks-components) for how to resolve it.

	This is both the technical detail *and* the team's pickup list — there is no separate "Issues to be claimed" section; keeping the two apart duplicated every fact with the second copy missing the links, which is strictly worse, not a shorter summary. Subtask-badge links are not stable — re-resolve every subtask ID linked anywhere in the report (here, and in **Carried Forward From Past Analyses** and **Past Tasks Abandoned**) right before publishing, immediately before this step, rather than reusing IDs resolved earlier in the run. See [`references/testray-testflow.md`](references/testray-testflow.md#regeneration-can-be-triggered-by-anyone-at-any-time).

1. **Environment incident**, listing the boot-failure subtask(s) and their test counts, explicitly excluded from commit attribution. Identify these by scanning the whole current-failures pool for the generic boot/environment signatures listed under the summary table's second row (per case, not per subtask) — the same set that row subtracts, so the two always agree. State plainly when there is none this round, but only after that scan comes back empty — never assume none without checking.

1. **Fixed**, foldable given the count is often large, grouped by ticket (or "Unattributed (no ticket)") per **Resolve Fixed-Cluster Ticket Lifecycle** — the group heading itself names the ticket and test count once, so each group's table drops straight to a link to its case result on the *older* build (where it last failed) and that failure's error text. Do not add a per-row ticket column repeating what the group heading already says — that's the same fact stated once per row for no reason.

1. **Fixed Tickets — Proposed Jira Actions**, one row per ticketed cluster **Resolve Fixed-Cluster Ticket Lifecycle** touched this run: ticket, classification, and the exact action that would be taken (close + comment, comment only, or needs manual verification) — state the action tersely (e.g. `Close + comment "Fixed as of <SHA>", citing <SHA>`), not a restated cluster description or the commit's full subject line; that description already lives in **Fixed**'s group heading just above. Report-only — nothing here has actually been written to Jira yet. Omit the section entirely when no fixed cluster carried a ticket this run.

1. **Still failing, error changed** — tests that matched a "new" signature on one side and a "fixed" signature on the other purely because the error text shifted, but the individual cross-check in **Diff** showed they were failing on both builds all along. Report these separately with both errors shown side by side; never let one leak into the New Regressions or Fixed sections. When none are found, don't just assert "none this round" — show the check that ruled it out as a tiny table: how many new regressions were queried directly against the older build and how many came back confirmed `PASSED` (not merely absent, and not `NOT_FOUND_IN_BUILD`, which is not the same thing), and the same tally for fixed cases against the newer build. A bare conclusion reads as a hand-wave; the numerator/denominator is what makes it checkable.

1. **Carried Forward From Past Analyses** — one row per subtask/cluster migrated in **Match Adjacent-Task Subtasks**, not per individual test: subtask badge, test count, a **Component(s)** column (same resolution as **New Regressions** — [`references/testray-testflow.md`](references/testray-testflow.md#resolve-a-subtasks-components), across every member case of every subtask badge in the row, since one ticket can span several subtasks), ticket(s), assignee, merge status, verdict (from **Check Whether a Carried-Forward Fix Already Landed**), and an **Acceptance** column (from **Cross-Check Against the Acceptance Routine**, same values as the New Regressions table). Keep every cell terse — a short label, not a paragraph:

	- **Merge status**: `No PR yet`, or `Merged into brianchandotcom/master`. Never mention specific commit SHAs, PR numbers, bot-comment quotes, or GitHub/Jira field names in the report itself — that's investigation detail, not something the team needs read back to them. Say only what actually happened (merged or not), never *how* it was confirmed.
	- **Fix landed in build `#<newerBuildNumber>`?**: `Yes` or `No`, nothing else.
	- **Verdict**: `Still waiting`, or `Requires attention` when the fix landed in the analyzed build and the test still failed. Two values only — do not add parenthetical caveats or explain the reasoning inline.

	Sort rows tagged `Acceptance` to the top, same as the New Regressions table. Keep all of this in a single table — splitting the merge-status detail into a second table below the main one reads as two disconnected reports, not one. Do not add prose paragraphs above or below the table explaining the investigation, corrections from a prior run, or process gaps found this run — the table's cells are the report. Omit the section when nothing migrated this run.

1. **Past Tasks Abandoned** — one row for `<previousTaskId>` if **Match Adjacent-Task Subtasks** just marked it `ABANDONED`, with a link and which of the two reasons applied (every live claim migrated/resolved, or the task had zero claims to begin with), so the action is visible and auditable rather than silent. Omit when it wasn't abandoned this run.

1. **Methodology** footer: the two build SHAs compared, the routine, and a one-line note that infra/log-assertor placeholder cases were excluded.

Publish the Confluence page per **State**, then post the same content to the chat as the final reply. Do not also render an Artifact — the Confluence page is the only durable output the team actually uses.
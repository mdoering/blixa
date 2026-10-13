# Work together

A Blixa project can have many curators. This page covers who can do what, how to bring people in,
and the tools that keep a team in sync: work objectives, edit locks, the change history,
discussions and your personal dashboard.

## Members & roles

Every project member has one of three roles:

| Role | Can do |
|---|---|
| **owner** | Everything an editor can, plus manage members, invitations and join requests, make the project public, publish and delete releases, and delete the project. |
| **editor** | Edit names, references and the tree; import, merge and match; review validation issues; triage and moderate discussions. |
| **viewer** | Read everything in the project, export it, and take part in discussions. |

A project always keeps at least one owner. You can't demote the last one.

### Add or change a member

Use this when the person already has a Blixa account, i.e. has signed in at least once.

1. Open **Members** in the sidebar. Only owners see the controls.
2. Type their **username** in the first field and pick a role (**owner**, **editor** or **viewer**).
3. Click **Add / update**. If they are already a member, this changes their role.

To change a role later, pick a new one in the **Role** column. To take someone off the project,
click **Remove** and confirm.

![The Members page](/help/img/members.png)

### Invite by email

Use this when the person doesn't have an account yet, or you don't know their username.

1. On **Members**, click **Invite by email**.
2. Enter their **Email**, choose a **Role**, and optionally add a **Message** for the email.
3. Click **Send invitation**.

The invitee gets a link valid for 30 days. Opening it shows **Join "…"**. After signing in (**Sign
in with ORCID to accept**), they click **Accept invitation** and join with the role you chose.

Open invitations are listed under **Pending invitations**, each with:

- **Copy link**: copy the invitation link to send it another way.
- **Resend**: email it again with a fresh 30-day link.
- **Revoke**: cancel it. The link stops working.

Expired invitations are marked **expired**. Resend them, or invite again.

### Join requests

Use this when people find your public project and ask to take part.

Anyone viewing a [public project page](/help/publish#the-public-project-page) can click **Request
to join**, enter their **ORCID** (plus an optional name and message) and click **Send request**.

As an owner, you see the number of open requests as a badge on **Members** in the sidebar. The
requests are listed under **Join requests** on that page.

1. Check the request's ORCID link to see who it is.
2. Once they have signed in to Blixa with that ORCID, add them with **Add / update** (see above).
3. Click **Dismiss** to remove the request from the list.

## Work objectives

Use this when you want to group your edits under a named piece of work, e.g. "Revise *Leopardus*"
or "Fix playground issues", so they can be found together later.

![The work objective selector in the header](/help/img/objective-selector.png)

An objective is an open [discussion](#discussions). While it is active, every change you save and
every name you lock is tagged with it. Objectives are optional. With **No objective**, editing works
exactly the same.

1. Click the objective selector in the top bar (it shows **No objective** by default).
2. Under **Working on**, pick an open discussion. Or click **New objective…**, enter a **Title** and
   click **Create & activate**. This creates an internal discussion and makes it active.
3. Edit as usual. Your changes now carry the objective's name in [History](#history) and
   [Activity](#soft-locks).
4. When you're done, pick **No objective**, and resolve the discussion if the work is finished.

Your active objective is remembered per project in your browser. It is personal and doesn't
affect other users. Only discussions with status **Open** can be objectives. If the active one is
resolved or rejected, the selector returns to **No objective**.

## Soft locks

Use this to see who is working on what, so you don't edit the same name at the same time.

Blixa takes a **soft lock** on a name as soon as you start editing it: when you change a field, or
copy a value from [Compare with CLB](/help/checklistbank#copy-values-into-your-taxon). The lock
is renewed while the name stays open. It is released when you close the name or move on to another
one. If you just walk away, it expires after about five minutes. Viewers never lock anything.

Locks are a warning, not a barrier. Others can still edit the name, and Blixa's save check stops
anyone from silently overwriting a newer version.

- **In the Tree and Names**, a lock icon next to a name means someone is editing it. Hover for "…
  is editing". The icon is orange for others and grey for your own locks.
- **On the taxon**, an orange banner warns "… is editing this name — your changes may conflict".
  Consider checking with them first, for example in a discussion.

### The Activity page

1. Open **Activity** in the sidebar. The page is titled **Current work**.
2. Each active lock is listed with the **Name/entity** (click to open it), the **Objective**, the
   **Holder**, **Since** and **Expires**.
3. To give up one of your own locks straight away, click **Release**.

![The Activity page listing current soft locks](/help/img/activity.png)

If nobody is editing, the page says "No one is editing anything right now." When a lock is
released or expires, Blixa revalidates that name and everything below it (see
[Validation issues](/help/validation#when-validation-runs)).

## History

Use this to see what changed in the project, who changed it and when.

1. Open **History** in the sidebar.
2. Each entry shows the operation (e.g. create, update, delete), the name or reference (click to open
   it), the objective it was made under (if any), the user and how long ago.
3. Click **Show diff** to see exactly which fields changed. **Hide diff** collapses it again.
4. To see only the changes made under one objective, pick it in **All objectives** (top right).
5. Use **Previous** and **Next** to page back in time.

Every project member can see the history.

![The History page](/help/img/history.png)

## Discussions

Use discussions to talk about the data: a question about a name, a planned revision, feedback
from outside the team. They are separate from the automatic [validation issues](/help/validation).

![A discussion with a comment that mentions a name and a person](/help/img/discussion-detail.png)

![The Discussions page](/help/img/discussions.png)

### Start a discussion

1. Open **Discussions** in the sidebar and click **New discussion**.
2. Enter a **Title** and, optionally, a **Body**. Markdown works.
3. Click **Create**. You automatically follow your own discussion.

Any project member can start a discussion. On the **Discussions** page you can search them
(**Search discussions…**), filter by **Status** and **Author**, and sort by **Created** or
**Modified**, **Newest** or **Oldest** first. Click a row to open it.

### Mention names and people

In the body or a comment:

- Type **#** followed by the start of a name with a capital letter (e.g. `#Lynx`) and pick the
  name from the list. Blixa stores a stable link to the name, so it keeps working if the name is
  later corrected. The discussion then shows up on that taxon's **Discussions** tab.
- Type **@** followed by a username and pick a member to mention them. A comment that mentions
  someone appears in their [Dashboard](#your-dashboard) inbox.

For example, start a discussion "Two *Lynx rufus* records" that mentions `#Lynx rufus`, so whoever
opens the bobcat sees it.

### Comment and follow

1. Open a discussion.
2. Type in **Add a comment…** and click **Comment**. Commenting also makes you a follower.
3. Click **Follow** (the heart) to follow a discussion without commenting, or **Following** to stop.
   The button shows how many people follow it.

Followers get an email when someone else comments, provided they have an email address on their
account and email is set up on the server.

### Statuses

| Status | Meaning |
|---|---|
| **Review** | Submitted from outside (see [External submissions](#external-submissions)) and waiting for an editor. |
| **Open** | Active. Only open discussions can be [work objectives](#work-objectives). |
| **Resolved** | Settled. |
| **Rejected** | Dismissed. |

To change the status, click **Edit** on the discussion and pick a new **Status**, then **Save**. The
author, owners and editors can do this. For a discussion in **Review**, editors see **Accept** (it
becomes Open) and **Reject** buttons right below the title.

### Internal or public

Discussions are **Internal (members only)** by default. An owner or editor can make one **Public**
via **Edit** → **Visibility**. Anyone with the link can then read it, without signing in. Public
discussions carry a **Public** badge and a **View public page ↗** link.

### Linked changes

Use this to record which edits a discussion led to, as evidence for a decision.

At the bottom of a discussion, **Linked changes** lists the edits attached to it. Owners and editors
can pick one in **Link a recent change…** and click **Link**, or remove one with its **×** button.

### External submissions

Use this to let an outside system (for example the COL feedback bot) file discussions in your
project.

1. On **Discussions**, click the gear icon (**Discussion settings**). Only owners and editors see
   it.
2. In **External submissions**, click **Generate token**, then **Copy** the **API token**.
3. Give the token and the **Endpoint** shown in the dialog to the external system.

Submissions arrive with status **Review** for an editor to accept or reject. **Regenerate** replaces
the token (the old one stops working). **Revoke** disables submissions.

## Your dashboard

Use this as your starting point. It collects what needs your attention across all your projects.

Click **Dashboard** in the sidebar.

![The personal dashboard](/help/img/dashboard.png)

**Needs attention** lists, when they apply:

- **New pings in discussions since your last visit**: new comments that mention you or are on
  discussions you follow or started, with links to each one.
- **Review submissions to triage**: externally submitted discussions waiting in your projects.
- **Missing metadata**: projects you own that lack a license, description, or geographic or
  taxonomic scope. See [Publish, release & export](/help/publish#project-metadata).
- **Open errors**: validation errors per project.
- **Taxa you still have locked**: click one to go back to it.
- **People awaiting approval**: shown only to administrators.

When there is nothing, it says "You're all caught up."

Below that, **Recently edited by you** links to the names you last worked on. **My projects** shows
each project with your role, its counts of accepted names, synonyms and open issues, and quick
links to **tree**, **names** and **issues**.

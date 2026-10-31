// A ticket: create form, Documents tab (versions + diff), right column with status and PMO decisions.
import { t } from "../i18n.js";
import { api, diffView, el, fill, markdown, showError, workState } from "../lib.js";
import { askAgent, renderChat, renderProgress } from "./chat.js";
import { renderConflicts, syncStatus } from "../sync.js";

const EDITABLE = ["analysis.md", "plan.md", "summary.md"];
const selectedDoc = new Map();
const selectedTab = new Map();

const base = (project, id) => `/api/projects/${encodeURIComponent(project)}/tasks/${encodeURIComponent(id)}`;

/** Re-runs the page router; SSE also refreshes, this covers a dropped event stream. */
const rerender = () => window.dispatchEvent(new HashChangeEvent("hashchange"));

function actionLabel(from, to) {
  const specific = `act.${to}.${from}`;
  return t(specific) === specific ? t(`act.${to}`) : t(specific);
}

export function renderNewTask(mid, project, onCreated) {
  const auto = project.config.ticketCode === "auto";
  const status = el("div");
  const form = el("form", { class: "form" },
    el("label", {}, el("span", {}, t("type")),
      el("select", { name: "type" }, ["FEAT", "MAINT", "BUG"].map((v) => el("option", { value: v }, v)))),
    el("label", {}, el("span", {}, t("code")),
      el("input", { name: "code", disabled: auto, placeholder: auto ? t("codeAuto") : t("codeHint") })),
    el("label", {}, el("span", {}, t("title")), el("input", { name: "title" })),
    el("label", {}, el("span", {}, t("request")), el("textarea", { name: "request", rows: 8, required: true })),
    el("label", { class: "check small" }, el("input", { type: "checkbox", name: "analyze", checked: true }), " ", t("autoAnalyze")),
    status,
    el("button", { type: "submit", class: "primary" }, t("createTicket")));
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const data = new FormData(form);
    try {
      const created = await api(`/api/projects/${encodeURIComponent(project.name)}/tasks`, {
        method: "POST",
        body: { type: data.get("type"), code: data.get("code") ?? "", title: data.get("title"), request: data.get("request"),
          analyze: data.get("analyze") === "on" }
      });
      onCreated(created.task.id, created.maskedCount);
    } catch (err) {
      showError(status, err);
    }
  });
  mid.replaceChildren(el("h2", {}, t("createTicket")), form);
}

/** Documents tab: chips per document, Markdown view, editing with versions and diff (FR-09). */
async function renderDocs(box, project, detail, selected, notice) {
  const id = detail.task.id;
  const names = [...detail.docs, ...EDITABLE.filter((n) => !detail.docs.includes(n))];
  const current = selected ?? selectedDoc.get(id) ?? (detail.docs.includes("analysis.md") ? "analysis.md" : "task.md");
  selectedDoc.set(id, current);
  const chips = el("div", { class: "chips" }, names.map((n) => el("button", {
    type: "button",
    class: `chip${n === current ? " active" : ""}${detail.docs.includes(n) ? "" : " missing"}`,
    onclick: () => renderDocs(box, project, detail, n)
  }, n)));
  const body = el("div", { class: "doc" });
  box.replaceChildren(chips, notice ?? "", body);

  let doc = null;
  try {
    doc = await api(`${base(project, id)}/doc?name=${encodeURIComponent(current)}`);
  } catch {
    // not written yet
  }
  const editable = EDITABLE.includes(current);
  const show = (content) => current.endsWith(".md") && current !== "task.md" ? markdown(content) : el("pre", {}, content);
  const tools = el("div", { class: "row" });
  body.replaceChildren(tools, doc ? show(doc.content) : el("p", { class: "muted" }, t("noDoc")));

  if (editable) {
    tools.append(el("button", { type: "button", class: "link", onclick: () => edit() }, t("edit")));
  }
  if (doc && doc.versions > 0) {
    const options = () => Array.from({ length: doc.versions }, (_, i) => el("option", { value: i + 1 }, `v${i + 1}`));
    const from = el("select", {}, options());
    const to = el("select", {}, options());
    to.value = String(doc.versions);
    const out = el("div");
    tools.append(el("span", { class: "muted" }, ` ${t("versions")}:`), from,
      el("button", { type: "button", class: "link", onclick: async () => {
        const v = await api(`${base(project, id)}/doc/version?name=${current}&n=${from.value}`);
        out.replaceChildren(show(v.content));
      } }, t("view")),
      el("span", { class: "muted" }, " ↔ "), to,
      el("button", { type: "button", class: "link", onclick: async () => {
        const d = await api(`${base(project, id)}/doc/diff?name=${current}&from=${from.value}&to=${to.value}`);
        out.replaceChildren(diffView(d.diff || "(=)"));
      } }, t("compare")));
    body.append(out);
  }

  function edit() {
    const area = el("textarea", { class: "editor", rows: 20, value: doc?.content ?? "" });
    const status = el("div");
    body.replaceChildren(area, status, el("div", { class: "row" },
      el("button", { type: "button", class: "primary", onclick: async () => {
        try {
          const saved = await api(`${base(project, id)}/doc?name=${current}`, { method: "PUT", body: { content: area.value } });
          const fresh = await api(base(project, id));
          renderDocs(box, project, fresh, current,
            saved.maskedCount > 0 ? el("p", { class: "warn" }, t("masked", { n: saved.maskedCount })) : null);
        } catch (err) {
          showError(status, err);
        }
      } }, t("save")),
      el("button", { type: "button", class: "link", onclick: () => renderDocs(box, project, detail, current) }, t("cancel"))));
  }
}

const short = (sha) => (sha ? sha.slice(0, 8) : "–");

/** Commit one repo: secret files refused, forbidden/schema/dependency files need the PMO's explicit approval first (FR-32). */
async function commitRepo(project, id, repo, message, status) {
  const scope = await api(`${base(project, id)}/dev/scope?repo=${encodeURIComponent(repo)}`);
  if (scope.secrets.length) {
    throw new Error(t("secretFiles", { files: scope.secrets.join(", ") }));
  }
  const doCommit = async () => {
    await api(`${base(project, id)}/dev/commit`, { method: "POST", body: { repo, message } });
    rerender();
  };
  if (!scope.needApproval.length) {
    await doCommit();
    return;
  }
  const kind = (f) => scope.scope.forbidden.includes(f) ? t("forbidden") : scope.scope.schema.includes(f) ? t("dbSchema") : t("dependency");
  status.replaceChildren(el("div", { class: "warn confirm-card" },
    el("strong", {}, t("needApproval")),
    el("ul", {}, scope.needApproval.map((f) => el("li", {}, el("code", {}, f), ` – ${kind(f)}`))),
    el("button", { type: "button", class: "primary", onclick: async () => {
      try {
        await api(`${base(project, id)}/dev/approve`, { method: "POST", body: { repo, files: scope.needApproval } });
        await doCommit();
      } catch (err) {
        showError(status, err);
      }
    } }, t("approveAndCommit")),
    el("button", { type: "button", class: "link", onclick: () => status.replaceChildren() }, t("cancel"))));
}

/** "Code" section: per repo branch, commits, files, gate result, diff and commit form (FR-26, FR-27). */
function renderCode(box, project, detail, dev) {
  const id = detail.task.id;
  const canCommit = detail.task.status === "in_dev";
  fill(box, dev.repos.length ? el("h3", {}, t("codeSection")) : null, dev.repos.map((r) => {
    const out = el("div");
    const status = el("div");
    const message = el("input", { placeholder: t("commitMessage", { code: id }) });
    return el("section", { class: "card" },
      el("div", { class: "row" },
        el("strong", { class: "grow" }, r.repo),
        el("span", { class: `badge ${r.gatePassed ? "done" : "awaiting_approval"}` }, r.gatePassed ? t("gatePassed") : t("gateNotPassed"))),
      el("small", { class: "muted path" }, `${r.branch} ← ${r.baseBranch} @${short(r.baseCommit)} · ${r.worktree}`),
      el("p", { class: "small" }, r.pushedHead ? t("pushedAt", { sha: short(r.pushedHead) }) : t("notPushed")),
      r.commits.length ? el("ul", { class: "small" }, r.commits.map((c) => el("li", {}, c))) : el("p", { class: "muted small" }, t("noCommits")),
      r.files.length ? el("details", {}, el("summary", {}, t("changedFiles", { n: r.files.length })),
        el("ul", { class: "small path" }, r.files.map((f) => el("li", {}, f)))) : null,
      r.uncommitted.length ? el("p", { class: "warn small" }, t("uncommitted", { files: r.uncommitted.join(", ") })) : null,
      el("div", { class: "row" },
        el("button", { type: "button", class: "link", onclick: async () => {
          if (out.childElementCount) {
            out.replaceChildren();
            return;
          }
          const d = await api(`${base(project, id)}/dev/diff?repo=${encodeURIComponent(r.repo)}`);
          out.replaceChildren(diffView(d.diff.trim() || "(=)"));
        } }, t("diff"))),
      canCommit && r.uncommitted.length ? el("div", { class: "row" }, message,
        el("button", { type: "button", class: "secondary", onclick: async () => {
          try {
            await commitRepo(project, id, r.repo, message.value, status);
          } catch (err) {
            showError(status, err);
          }
        } }, t("commit"))) : null,
      status, out);
  }));
}

/** Before the push: what goes where, the quality gate report and the diffs; the push itself is the PMO's click (FR-27). */
async function pushReview(box, project, detail, dev, error) {
  const id = detail.task.id;
  let tests = null;
  try {
    tests = (await api(`${base(project, id)}/doc?name=tests.md`)).content;
  } catch {
    // no gate report: the server refuses the push anyway
  }
  const diffs = await Promise.all(dev.repos.filter((r) => r.commits.length).map(async (r) =>
    [r, (await api(`${base(project, id)}/dev/diff?repo=${encodeURIComponent(r.repo)}`)).diff]));
  fill(box, el("section", { class: "card confirm-card" },
    el("h3", {}, t("pushReview")),
    el("ul", {}, diffs.map(([r]) => el("li", {}, el("strong", {}, r.repo), ` → origin/${r.branch} · ${r.commits.length} commit · `,
      r.gatePassed ? t("gatePassed") : t("gateNotPassed")))),
    diffs.length ? null : el("p", { class: "muted" }, t("nothingToPush")),
    tests ? markdown(tests) : el("p", { class: "warn" }, t("noTests")),
    diffs.map(([r, d]) => el("details", {}, el("summary", {}, `diff ${r.repo}`), diffView(d))),
    el("div", { class: "row" },
      el("button", { type: "button", class: "primary", onclick: async () => {
        try {
          await api(`${base(project, id)}/dev/push`, { method: "POST" });
          rerender();
        } catch (err) {
          showError(error, err);
        }
      } }, t("pushNow")),
      el("button", { type: "button", class: "link", onclick: () => box.replaceChildren() }, t("cancel")))));
  box.scrollIntoView({ behavior: "smooth" });
}

/** FR-55: what moves to the other machine; confirming is the PMO's push confirmation. */
async function handoffReview(box, project, task, error) {
  let plan;
  try {
    plan = await api(`${base(project, task.id)}/dev/handoff`);
  } catch (err) {
    showError(error, err);
    return;
  }
  fill(box, el("section", { class: "card confirm-card" },
    el("h3", {}, t("handoffTitle")),
    el("p", { class: "muted small" }, t("handoffHint")),
    el("ul", {}, plan.map((r) => el("li", {}, el("strong", {}, r.repo), ` → origin/${r.branch}`,
      r.uncommitted.length ? [" · ", t("handoffWip", { n: r.uncommitted.length }), el("div", { class: "muted small path" }, r.uncommitted.join(", "))]
        : ` · ${r.head.slice(0, 8)}`))),
    el("div", { class: "row" },
      el("button", { type: "button", class: "primary", onclick: async () => {
        try {
          await api(`${base(project, task.id)}/dev/handoff`, { method: "POST" });
          rerender();
        } catch (err) {
          showError(error, err);
        }
      } }, t("handoffConfirm")),
      el("button", { type: "button", class: "link", onclick: () => box.replaceChildren() }, t("cancel")))));
  box.scrollIntoView({ behavior: "smooth" });
}

/** FR-55: the ticket was handed over; on the other machine offer to continue on the pushed commits. */
function renderHandoff(project, task) {
  const h = task.handoff;
  if (!h) {
    return null;
  }
  const here = syncStatus()?.machine;
  const repos = h.repos.map((r) => `${r.repo}: ${r.branch} @${r.commit.slice(0, 8)}`).join(" · ");
  if (h.machine === here) {
    return el("p", { class: "warn" }, t("handoffSent", { at: h.at }), " ", el("span", { class: "path" }, repos));
  }
  const error = el("div");
  return el("section", { class: "card confirm-card" },
    el("p", {}, t("handoffFrom", { machine: h.machine, at: h.at })), el("p", { class: "muted small path" }, repos),
    el("button", { type: "button", class: "primary", onclick: async () => {
      try {
        await api(`${base(project, task.id)}/dev/resume`, { method: "POST" });
        rerender();
      } catch (err) {
        showError(error, err);
      }
    } }, t("handoffResume")),
    error);
}

/** Numbered or bulleted items under the "Câu hỏi chờ PMO" heading of summary.md (sa.md asks SA to keep that section). */
export function pendingQuestions(md) {
  const lines = (md ?? "").split("\n");
  const start = lines.findIndex((l) => /^#{1,6}\s*(câu hỏi chờ pmo|questions for (the )?pmo)/i.test(l));
  if (start < 0) {
    return [];
  }
  const out = [];
  for (const line of lines.slice(start + 1)) {
    if (/^#{1,6}\s/.test(line)) {
      break;
    }
    const m = /^\s*(?:\d+[.)]|[-*])\s+(.+)/.exec(line);
    if (m && !/^\(?(không có|không còn|none|chưa có)/i.test(m[1])) {
      out.push(m[1].trim());
    } else if (!m && line.trim() && out.length) {
      out[out.length - 1] += ` ${line.trim()}`;
    }
  }
  return out;
}

/** Questions tab: each open question of the agent with its own answer box; one send starts one new session with all answers. */
function renderQuestions(box, project, detail, questions, onSent) {
  const status = el("div");
  const answers = questions.map(() => el("textarea", { rows: 2, placeholder: t("yourAnswer") }));
  fill(box, el("p", { class: "muted small" }, t("questionsHint")),
    el("ol", { class: "questions" }, questions.map((q, i) => el("li", { class: "card question" }, markdown(q), answers[i]))),
    el("div", { class: "row" }, el("button", { type: "button", class: "primary", onclick: async () => {
      const filled = questions.map((q, i) => [q, answers[i].value.trim()]).filter(([, a]) => a);
      if (!filled.length) {
        showError(status, new Error(t("noAnswers")));
        return;
      }
      try {
        const text = filled.map(([q, a], i) => `${i + 1}. ${q}\n   → ${a}`).join("\n");
        await askAgent(project, detail.task.id, `${t("answersIntro")}\n\n${text}`);
        onSent();
      } catch (err) {
        showError(status, err);
      }
    } }, t("sendAnswers"))),
    status);
}

/** Ticket branch, or the PMO option to code and push straight on the base branch; fixed once a worktree exists. */
function renderBranch(project, task, dev, error) {
  const name = el("div", { class: "path" }, dev.direct ? t("directBranch") : task.branch);
  if (dev.repos.length || task.status === "done") {
    return name;
  }
  const box = el("input", { type: "checkbox", checked: dev.direct });
  box.addEventListener("change", async () => {
    if (box.checked && !confirm(t("directConfirm"))) {
      box.checked = false;
      return;
    }
    try {
      await api(`${base(project, task.id)}/dev/direct`, { method: "PUT", body: { direct: box.checked } });
      rerender();
    } catch (err) {
      showError(error, err);
    }
  });
  return [name, el("label", { class: "check small" }, box, " ", t("directOption"))];
}

function renderRepoPicker(project, detail, repoNames, error) {
  const task = detail.task;
  if (task.status === "done") {
    return null;
  }
  const box = (n) => el("label", { class: "check" },
    el("input", { type: "checkbox", value: n, checked: task.repos.includes(n) }), " ", n);
  // SA picks the impacted services (set_repos); the rest of the project stays one click away for the PMO
  const impacted = repoNames.filter((n) => task.repos.includes(n)).map(box);
  const others = repoNames.filter((n) => !task.repos.includes(n)).map(box);
  const boxes = [...impacted, ...others];
  return el("div", { class: "repo-picker" },
    impacted.length ? impacted : el("p", { class: "muted small" }, t("reposNone")),
    others.length ? el("details", {}, el("summary", { class: "small muted" }, t("reposOther", { n: others.length })), others) : null,
    el("button", { type: "button", class: "link", onclick: async () => {
      try {
        const repos = boxes.map((b) => b.querySelector("input")).filter((i) => i.checked).map((i) => i.value);
        await api(`${base(project, task.id)}/repos`, { method: "PUT", body: repos });
        rerender();
      } catch (err) {
        showError(error, err);
      }
    } }, t("saveRepos")));
}

function renderRight(right, project, detail, dev, repoNames, reviewBox, totals, activity) {
  const task = detail.task;
  const work = workState(task, activity);
  const running = Boolean(activity.working?.[task.id]);
  const from = task.status;
  const error = el("div");
  const call = async (path) => {
    try {
      await api(`${base(project, task.id)}${path}`, { method: "POST" });
      rerender();
    } catch (err) {
      showError(error, err);
    }
  };
  const move = async (to, gate) => {
    if (from === "awaiting_push" && to === "done") {
      await pushReview(reviewBox, project, detail, dev, error);
      return;
    }
    if (from === "ready" && to === "in_dev") {
      await call("/implement"); // Dev pulls the base branch and implements plan.md on its own; SA verifies after
      return;
    }
    if (gate && !confirm(t("confirmGate", { action: actionLabel(from, to), id: task.id }))) {
      return;
    }
    try {
      await api(`${base(project, task.id)}/transition`, { method: "POST", body: { to } });
      rerender();
    } catch (err) {
      showError(error, err);
    }
  };
  const implementNow = (a) => from === "ready" && a.to === "in_dev";
  const gates = detail.actions.filter((a) => a.pmoGate || implementNow(a) || (from === "awaiting_approval" && a.to === "sa_analysis"));
  const manual = detail.actions.filter((a) => !gates.includes(a));

  const reopenBox = el("div", { class: "reopen" });
  const reopen = (kind) => {
    const area = el("textarea", { rows: 4, placeholder: t("describe") });
    reopenBox.replaceChildren(area, el("button", { type: "button", class: "primary", onclick: async () => {
      try {
        await api(`${base(project, task.id)}/reopen`, { method: "POST", body: { kind, description: area.value } });
        reopenBox.replaceChildren();
        rerender();
      } catch (err) {
        showError(error, err);
      }
    } }, t("send")));
  };

  fill(right,
    el("h3", {}, task.id),
    el("dl", {},
      el("dt", {}, t("status")), el("dd", {}, el("span", { class: `badge ${task.status}` }, t(`status.${task.status}`)),
        work ? [" ", el("span", { class: `work ${work.cls}` }, t(work.key, { n: work.n }))] : null),
      el("dt", {}, t("type")), el("dd", {}, task.type),
      el("dt", {}, t("branch")), el("dd", {}, renderBranch(project, task, dev, error)),
      el("dt", {}, t("repos")), el("dd", {}, renderRepoPicker(project, detail, repoNames, error)
        ?? (task.repos.length ? task.repos.join(", ") : "–")),
      el("dt", {}, t("version")), el("dd", {}, String(task.version)),
      el("dt", {}, t("reopened")), el("dd", {}, String(task.reopened)),
      detail.queuePosition ? [el("dt", {}, t("queuePos")), el("dd", {}, String(detail.queuePosition))] : null,
      el("dt", {}, t("tokens")), el("dd", {}, totals)),
    gates.length || from === "done" ? el("h3", { class: "section" }, t("pmoDecisions")) : null,
    el("div", { class: "actions" }, gates.map((a) => el("button", {
      type: "button", class: a.pmoGate || implementNow(a) ? "primary" : "secondary", onclick: () => move(a.to, a.pmoGate)
    }, actionLabel(from, a.to)))),
    from === "done" ? el("div", { class: "actions" },
      el("button", { type: "button", class: "secondary", onclick: () => reopen("bug") }, t("reportBug")),
      el("button", { type: "button", class: "secondary", onclick: () => reopen("logic") }, t("addLogic"))) : null,
    reopenBox,
    from === "in_dev" ? [el("h3", { class: "section" }, t("devSteps")), el("div", { class: "actions" },
      running ? null : el("button", { type: "button", class: "primary", onclick: () => call("/implement") }, t("implement")),
      el("button", { type: "button", class: "secondary", onclick: () => call("/dev/start") }, t("pullBase")),
      dev.repos.length ? el("button", { type: "button", class: "secondary", onclick: () => call("/dev/gate") }, t("runGate")) : null,
      dev.repos.length && !dev.direct && !running ? el("button", { type: "button", class: "secondary",
        onclick: () => handoffReview(reviewBox, project, task, error) }, t("handoff")) : null),
    el("p", { class: "muted small" }, t("gateHint"))] : null,
    manual.length ? [el("h3", { class: "section" }, t("manualMoves")), el("p", { class: "muted small" }, t("manualHint")),
      el("div", { class: "actions" }, manual.map((a) => el("button", {
        type: "button", class: "secondary", onclick: () => move(a.to, false)
      }, actionLabel(from, a.to))))] : null,
    error);
}

/** Loads the ticket and fills the middle and right columns. state.masked: warning to show once after creation. */
export async function renderTask(mid, right, project, id, state) {
  let detail;
  try {
    detail = await api(base(project, id));
  } catch (err) {
    showError(mid, err);
    return;
  }
  const [dev, info, activity] = await Promise.all([
    api(`${base(project, id)}/dev`).catch(() => ({ repos: [] })),
    api(`/api/projects/${encodeURIComponent(project)}`),
    api(`/api/projects/${encodeURIComponent(project)}/agents`).catch(() => ({ working: {}, questions: {}, failed: [] }))]);
  const notice = state.masked ? el("p", { class: "warn" }, t("masked", { n: state.masked })) : null;
  state.masked = 0;
  const body = el("div");
  const review = el("div");
  const code = el("div");
  const totals = el("span", { class: "small" }, "–");
  const summary = detail.docs.includes("summary.md")
    ? await api(`${base(project, id)}/doc?name=summary.md`).then((d) => d.content).catch(() => "") : "";
  const questions = pendingQuestions(summary);
  const names = [questions.length ? "questions" : null, "chat", "docs"].filter(Boolean);
  const saved = selectedTab.get(id);
  const tab = names.includes(saved) ? saved : notice ? "docs" : questions.length ? "questions" : "chat";
  const show = (name) => {
    selectedTab.set(id, name);
    tabs.querySelectorAll(".tab").forEach((b) => b.classList.toggle("active", b.dataset.tab === name));
    if (name === "questions") {
      renderQuestions(body, project, detail, questions, () => show("chat"));
    } else if (name === "chat") {
      renderChat(body, project, detail, totals);
    } else {
      renderDocs(body, project, detail, null, notice);
    }
  };
  const tabs = el("div", { class: "tabs" }, names.map((name) => el("button", {
    type: "button", class: `tab${name === "questions" ? " tab-questions" : ""}`, "data-tab": name, onclick: () => show(name)
  }, name === "questions" ? [t("questions"), " ", el("span", { class: "count" }, String(questions.length))] : t(name))));
  const progress = el("div");
  const conflicts = el("div");
  fill(mid, el("h2", {}, `${detail.task.id} · ${detail.task.title}`), renderHandoff(project, detail.task), conflicts, progress,
    review, code, tabs, body);
  renderConflicts(conflicts, project, id, rerender);
  renderProgress(progress, project, id).catch(() => { /* the chat tab still shows the steps */ });
  renderCode(code, project, detail, dev);
  renderRight(right, project, detail, dev, info.config.repos.map((r) => r.name), review, totals, activity);
  show(tab);
}

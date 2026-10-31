// Project workspace (localhost:7070/<project>): task list | task content | details and PMO decisions.
// Routes: #  overview · #config · #rules · #graph · #log · #new · #task=<id>
import { applyI18n, mountLangSwitch, t } from "./i18n.js";
import { WAITS_FOR_PMO, api, el, fill, notify, setFavicon, showError, workState } from "./lib.js";
import { renderConfig } from "./views/config.js";
import { onAgentEvent, tokens } from "./views/chat.js";
import { renderGraph } from "./views/graph.js";
import { mountSync, onSync, onSyncEvent, syncStatus } from "./sync.js";
import { renderLog } from "./views/log.js";
import { renderRules } from "./views/rules.js";
import { renderNewTask, renderTask } from "./views/task.js";

applyI18n();
mountLangSwitch(document.getElementById("lang"));

const STATUSES = ["new", "sa_analysis", "awaiting_approval", "ready", "in_dev", "sa_verify", "awaiting_push", "done"];
const name = decodeURIComponent(location.pathname.split("/")[1]);
const api$ = `/api/projects/${encodeURIComponent(name)}`;
const mid = document.getElementById("mid");
const right = document.getElementById("right");
const list = document.getElementById("task-list");
const queueBox = document.getElementById("queue");
const filterStatus = document.getElementById("filter-status");
const filterType = document.getElementById("filter-type");
const state = { masked: 0 };
let project;
let tasks = [];
let refreshGraph = null;
let activity = { running: [], working: {}, failed: [], questions: {}, queueRunning: false };

fill(filterStatus, el("option", { value: "" }, t("allStatus")),
  STATUSES.map((s) => el("option", { value: s }, t(`status.${s}`))));
fill(filterType, el("option", { value: "" }, t("allTypes")),
  ["FEAT", "MAINT", "BUG"].map((v) => el("option", { value: v }, v)));
filterStatus.addEventListener("change", renderList);
filterType.addEventListener("change", renderList);

function route() {
  const hash = location.hash.slice(1);
  if (hash.startsWith("task=")) {
    return { view: "task", id: decodeURIComponent(hash.slice(5)) };
  }
  return { view: ["config", "new", "graph", "rules", "log"].includes(hash) ? hash : "overview" };
}

function setHeader() {
  document.getElementById("project-name").textContent = project.name;
  document.getElementById("project-dot").style.background = project.config.color;
  setTabState();
}

const WORK_ORDER = ["dev", "verify", "sa", "pmo", "error"];

/**
 * Tab title and icon always say what is going on (AC-08): "(2) shop · Dev đang triển khai (FEAT-001) +1", with a dot colored
 * like the work pills: SA blue, Dev purple, SA verifying teal, PMO needed yellow, error red. (n) = tickets waiting for the PMO.
 */
function setTabState() {
  const states = tasks.map((x) => [x, workState(x, activity)]).filter(([, w]) => w)
    .sort((a, b) => WORK_ORDER.indexOf(a[1].cls) - WORK_ORDER.indexOf(b[1].cls));
  const pending = states.filter(([, w]) => w.cls === "pmo").length;
  const [top, w] = states[0] ?? [];
  const label = top ? `${t(w.key, { n: w.n })} (${top.id})${states.length > 1 ? ` +${states.length - 1}` : ""}` : "DevMap";
  document.title = `${pending ? `(${pending}) ` : ""}${project.name} · ${label}`;
  setFavicon(project.config.color, w?.cls);
}

const openTask = (id) => () => {
  location.hash = `task=${encodeURIComponent(id)}`;
};

/** Notify when the agent needs the PMO, finished, or failed; only while this tab is in the background. */
function notifyChanges(before, after) {
  for (const x of tasks) {
    const old = before.get(x.id);
    if (old && old !== x.status && WAITS_FOR_PMO.includes(x.status)) {
      notify(`${project.name} · ${x.id}`, t(`notify.${x.status}`, { title: x.title }), `${name}/${x.id}`, openTask(x.id), true);
    }
  }
  for (const id of after.failed.filter((x) => !activity.failed.includes(x))) {
    notify(`${project.name} · ${id}`, t("notify.error"), `${name}/${id}`, openTask(id));
  }
  for (const id of activity.running.filter((x) => !after.running.includes(x) && !after.failed.includes(x))) {
    const x = tasks.find((y) => y.id === id);
    if (x && !WAITS_FOR_PMO.includes(x.status) && !after.questions[id]) {
      notify(`${project.name} · ${id}`, t("notify.done"), `${name}/${id}`, openTask(id));
    }
  }
  // SA/Dev asked the PMO something: stays on screen until the PMO clicks through to the Questions tab
  for (const [id, n] of Object.entries(after.questions)) {
    if (n > (activity.questions[id] ?? 0)) {
      const x = tasks.find((y) => y.id === id);
      notify(`${project.name} · ${id}`, t("notify.questions", { n, title: x?.title ?? "" }), `${name}/${id}`, openTask(id), true);
    }
  }
}

async function refreshActivity(before) {
  const after = await api(`${api$}/agents`);
  if (before) {
    notifyChanges(before, after);
  }
  activity = after;
  setTabState();
  renderList();
  renderUsage().catch(() => { /* the box just keeps its last numbers */ });
  renderQueueRun();
}

const usageBox = document.getElementById("usage");

const clock = (epochSeconds) => new Date(epochSeconds * 1000).toLocaleString();
/** Translated name of a quota value, or the raw value for kinds this build does not know yet. */
const quotaLabel = (group, value) => (t(`${group}.${value}`) === `${group}.${value}` ? value : t(`${group}.${value}`));

/** FR-40 header box: project tokens and the account quota windows Claude Code reported in the latest session. */
async function renderUsage() {
  const u = await api(`${api$}/usage`);
  fill(usageBox, el("summary", {}, `${t("tokenUsage")}: ${tokens(u.today).toLocaleString()}`),
    el("div", {},
      el("strong", {}, t("tokens")),
      el("p", {}, t("tokensToday", { n: tokens(u.today).toLocaleString() }), el("br"),
        t("tokensProject", { n: tokens(u.project).toLocaleString() }), el("br"),
        t("tokensCost", { cost: u.project.costUsd.toFixed(4) })),
      u.quota.length === 0 ? el("p", { class: "muted" }, t("quotaNone")) : u.quota.map((q) => {
        // utilization is a fraction of the window; older Claude Code builds leave it out
        const pct = q.utilization === null || q.utilization === undefined ? null : Math.round(q.utilization <= 1 ? q.utilization * 100 : q.utilization);
        return [el("div", {}, el("strong", {}, quotaLabel("quota", q.type)), " · ", quotaLabel("quotaStatus", q.status),
          pct === null ? "" : ` · ${t("quotaUsed", { pct })}`),
        pct === null ? null : el("div", { class: "meter" },
          el("span", { class: pct >= 90 ? "full" : pct >= 70 ? "warn" : "", style: `width:${Math.min(pct, 100)}%` })),
        el("div", { class: "muted" }, [q.resetsAt ? t("quotaReset", { at: clock(q.resetsAt) }) : null,
          t("quotaSeen", { at: q.seenAt })].filter(Boolean).join(" · "))];
      })));
}

function mountNotifyButton() {
  const b = document.getElementById("notify-on");
  b.hidden = !("Notification" in window) || Notification.permission !== "default";
  b.addEventListener("click", async () => {
    await Notification.requestPermission();
    b.hidden = true;
  });
}

/** FR-54: the ticket has a file kept twice after a sync conflict. */
function conflictPill(x) {
  return syncStatus()?.conflicts.some((c) => c.project === name && c.ticket === x.id)
    ? el("small", { class: "work error" }, t("sync.conflictPill")) : null;
}

function workPill(x) {
  const w = workState(x, activity);
  return w ? el("small", { class: `work ${w.cls}` }, t(w.key, { n: w.n })) : null;
}

/** PMO approved and queued: Dev implements from the documents; SA verifies by itself after Dev hands over. */
async function implement(id) {
  try {
    await api(`${api$}/tasks/${encodeURIComponent(id)}/implement`, { method: "POST" });
    await refreshActivity(null);
  } catch (err) {
    showError(queueBox, err);
  }
}

function renderList() {
  const r = route();
  const shown = tasks.filter((x) => (!filterStatus.value || x.status === filterStatus.value)
    && (!filterType.value || x.type === filterType.value));
  list.replaceChildren(...shown.map((x) => el("li", { class: r.id === x.id ? "active" : "" },
    el("a", { href: `#task=${encodeURIComponent(x.id)}` },
      el("div", { class: "row" }, el("strong", { class: "grow" }, x.id), conflictPill(x), workPill(x)),
      el("div", {}, x.title),
      el("small", { class: `badge ${x.status}` }, `${x.type} · ${t(`status.${x.status}`)}`)))));
  if (shown.length === 0) {
    list.replaceChildren(el("li", { class: "muted" }, t("noTasks")));
  }
}

const queueRun = el("div", { class: "row small" });

function renderQueueRun() {
  const on = activity.queueRunning;
  fill(queueRun,
    el("button", { type: "button", class: on ? "small" : "primary small", onclick: async () => {
      try {
        activity = await api(`${api$}/queue/run`, { method: "POST", body: { on: !on } });
        renderQueueRun();
      } catch (err) {
        showError(queueRun, err);
      }
    } }, t(on ? "queueStop" : "queueRun")),
    on ? el("span", { class: "muted" }, activity.queueTicket ? t("queueAt", { id: activity.queueTicket }) : "") : null);
}

async function renderQueue() {
  const queue = await api(`${api$}/queue`);
  if (queue.length === 0) {
    queueBox.replaceChildren(activity.queueRunning ? queueRun : "");
    return;
  }
  const ids = queue.map((x) => x.id);
  const move = async (i, delta) => {
    const order = [...ids];
    [order[i], order[i + delta]] = [order[i + delta], order[i]];
    try {
      await api(`${api$}/queue`, { method: "PUT", body: order });
      await renderQueue();
    } catch (err) {
      showError(queueBox, err);
    }
  };
  queueBox.replaceChildren(el("h3", { class: "section" }, t("queue")), queueRun, el("ol", { class: "queue" }, queue.map((x, i) => el("li", {},
    el("a", { href: `#task=${encodeURIComponent(x.id)}` }, x.id), " ",
    activity.working[x.id] ? workPill(x) : el("button", { type: "button", class: "link", title: t("implement"), onclick: () => implement(x.id) }, "▶"),
    i > 0 ? el("button", { type: "button", class: "link", title: t("up"), onclick: () => move(i, -1) }, "↑") : null,
    i < queue.length - 1 ? el("button", { type: "button", class: "link", title: t("down"), onclick: () => move(i, 1) }, "↓") : null))));
}

function renderProjectInfo() {
  const c = project.config;
  fill(right,
    el("h3", {}, t("status")),
    el("dl", {},
      el("dt", {}, t("dataDir")), el("dd", { class: "path" }, project.dataDir),
      el("dt", {}, t("ticketCode")), el("dd", {}, c.ticketCode === "auto" ? t("ticketAuto") : t("ticketManual")),
      el("dt", {}, t("repos")),
      el("dd", {}, el("ul", {}, c.repos.map((r) => el("li", {}, r.name, el("span", { class: "muted" }, ` @${r.baseBranch}`)))))));
}

/** FR-51: repos of the project with no folder on this machine yet: pick an existing folder or clone into one. */
function missingRepos() {
  const missing = project.config.repos.filter((r) => !r.path);
  if (missing.length === 0) {
    return null;
  }
  const error = el("div");
  const act = (r, kind) => async () => {
    try {
      const path = (await api("/api/folders/pick", { method: "POST" })).path;
      if (!path) {
        return;
      }
      project = await api(`${api$}/repos/${encodeURIComponent(r.name)}/${kind}`, { method: "POST", body: { path } });
      renderOverview();
      renderProjectInfo();
    } catch (err) {
      showError(error, err);
    }
  };
  return el("section", { class: "card missing-repos" },
    el("h3", {}, t("repoMissingTitle", { n: missing.length })),
    el("p", { class: "muted small" }, t("repoMissingHint")),
    el("ul", {}, missing.map((r) => el("li", {}, el("strong", {}, r.name), " ", el("span", { class: "muted path" }, r.remote ?? t("noRemote")), " ",
      el("button", { type: "button", class: "secondary small", onclick: act(r, "locate") }, t("repoPick")), " ",
      r.remote ? el("button", { type: "button", class: "secondary small", onclick: act(r, "clone") }, t("repoClone")) : null))),
    error);
}

function renderOverview() {
  const repos = project.config.repos;
  fill(mid, missingRepos(), el("h2", {}, t("services")),
    repos.length === 0 ? el("p", { class: "muted" }, t("noServices")) : repos.map((r) => el("section", { class: "card" },
      el("h3", {}, r.name, el("span", { class: "muted" }, ` @${r.baseBranch}`)),
      el("small", { class: "muted path" }, r.path ?? t("repoNoPath")),
      r.services.length === 0
        ? el("p", { class: "muted" }, t("noServices"))
        : el("table", {},
          el("thead", {}, el("tr", {}, el("th", {}, t("services")), el("th", {}, t("servicePath")), el("th", {}, t("kind")))),
          el("tbody", {}, r.services.map((s) => el("tr", {},
            el("td", {}, s.name), el("td", { class: "path" }, s.path || "."), el("td", {}, s.kind))))))));
}

async function render() {
  await refreshTasks();
  const r = route();
  document.querySelectorAll("nav .tab").forEach((b) => b.classList.toggle("active", b.dataset.view === r.view));
  renderList();
  refreshGraph = null;
  if (r.view === "task") {
    await renderTask(mid, right, name, r.id, state);
    return;
  }
  if (r.view === "graph") {
    refreshGraph = renderGraph(mid, right, name, tasks);
    return;
  }
  if (r.view === "rules") {
    await renderRules(mid, right, name);
    return;
  }
  if (r.view === "log") {
    await renderLog(mid, right, name, tasks);
    return;
  }
  renderProjectInfo();
  if (r.view === "config") {
    renderConfig(mid, project, (saved) => {
      project = saved;
      setHeader();
      renderProjectInfo();
    });
  } else if (r.view === "new") {
    renderNewTask(mid, project, (id, masked) => {
      state.masked = masked;
      location.hash = `task=${encodeURIComponent(id)}`;
    });
  } else {
    renderOverview();
  }
}

async function refreshTasks() {
  tasks = await api(`${api$}/tasks`);
  await renderQueue();
}

const EDITING = "textarea.editor, .reopen textarea, .confirm-card, .card input:not(:placeholder-shown), .chat-input:not(:placeholder-shown)";

/** Another tab, the CLI or (later) an agent changed a ticket: refresh, but never under an open editor. */
function listen() {
  const events = new EventSource(`/api/events?project=${encodeURIComponent(name)}`);
  events.addEventListener("sync", (e) => onSyncEvent(JSON.parse(e.data)));
  onSync(async (s, pulled) => {
    renderList();
    if (pulled && !document.querySelector(EDITING)) {
      project = await api(api$);
      await render(); // another machine pushed: show its tickets and documents
    }
  });
  events.addEventListener("task", async (e) => {
    const before = new Map(tasks.map((x) => [x.id, x.status]));
    await refreshTasks();
    await refreshActivity(before);
    const r = route();
    const change = JSON.parse(e.data);
    if (r.view === "task" && r.id === change.taskId && !document.querySelector(EDITING)) {
      await renderTask(mid, right, name, r.id, state);
    }
    renderList();
  });
  events.addEventListener("graph", () => refreshGraph?.());
  events.addEventListener("agent", async (e) => {
    const change = JSON.parse(e.data);
    onAgentEvent(change);
    if (change.state !== "running" || !activity.running.includes(change.taskId)) {
      await refreshActivity(new Map(tasks.map((x) => [x.id, x.status])));
    }
  });
}

window.addEventListener("hashchange", render);

try {
  await mountSync(name);
  project = await api(api$);
  if (project.error) {
    throw new Error(project.error);
  }
  await refreshTasks();
  await refreshActivity(null);
  setHeader();
  mountNotifyButton();
  await render();
  listen();
} catch (err) {
  document.title = `${name} · DevMap`;
  showError(mid, err.message.startsWith("Không có dự án") ? new Error(t("notFound")) : err);
}

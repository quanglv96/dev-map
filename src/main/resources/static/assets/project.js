// Project workspace (localhost:7070/<project>): task list | task content | details and PMO decisions.
// Routes: #  overview · #config · #new · #task=<id>
import { applyI18n, mountLangSwitch, t } from "./i18n.js";
import { WAITS_FOR_PMO, api, el, fill, setFavicon, showError } from "./lib.js";
import { renderConfig } from "./views/config.js";
import { renderGraph } from "./views/graph.js";
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
  return { view: ["config", "new", "graph"].includes(hash) ? hash : "overview" };
}

function setHeader() {
  document.title = `${project.name} · DevMap`;
  document.getElementById("project-name").textContent = project.name;
  document.getElementById("project-dot").style.background = project.config.color;
  setFavicon(project.config.color);
}

function renderList() {
  const r = route();
  const shown = tasks.filter((x) => (!filterStatus.value || x.status === filterStatus.value)
    && (!filterType.value || x.type === filterType.value));
  list.replaceChildren(...shown.map((x) => el("li", { class: r.id === x.id ? "active" : "" },
    el("a", { href: `#task=${encodeURIComponent(x.id)}` },
      el("div", { class: "row" },
        el("strong", { class: "grow" }, x.id),
        WAITS_FOR_PMO.includes(x.status) ? el("span", { class: "dot pending", title: t("pending") }) : null),
      el("div", {}, x.title),
      el("small", { class: `badge ${x.status}` }, `${x.type} · ${t(`status.${x.status}`)}`)))));
  if (shown.length === 0) {
    list.replaceChildren(el("li", { class: "muted" }, t("noTasks")));
  }
}

async function renderQueue() {
  const queue = await api(`${api$}/queue`);
  if (queue.length === 0) {
    queueBox.replaceChildren();
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
  queueBox.replaceChildren(el("h3", { class: "section" }, t("queue")), el("ol", { class: "queue" }, queue.map((x, i) => el("li", {},
    el("a", { href: `#task=${encodeURIComponent(x.id)}` }, x.id), " ",
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

function renderOverview() {
  const repos = project.config.repos;
  fill(mid, el("h2", {}, t("services")),
    repos.length === 0 ? el("p", { class: "muted" }, t("noServices")) : repos.map((r) => el("section", { class: "card" },
      el("h3", {}, r.name, el("span", { class: "muted" }, ` @${r.baseBranch}`)),
      el("small", { class: "muted path" }, r.path),
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

/** Another tab, the CLI or (later) an agent changed a ticket: refresh, but never under an open editor. */
function listen() {
  const events = new EventSource(`/api/events?project=${encodeURIComponent(name)}`);
  events.addEventListener("task", async (e) => {
    await refreshTasks();
    const r = route();
    const change = JSON.parse(e.data);
    if (r.view === "task" && r.id === change.taskId && !document.querySelector("textarea.editor, .reopen textarea")) {
      await renderTask(mid, right, name, r.id, state);
    }
    renderList();
  });
  events.addEventListener("graph", () => refreshGraph?.());
}

window.addEventListener("hashchange", render);

try {
  project = await api(api$);
  if (project.error) {
    throw new Error(project.error);
  }
  setHeader();
  await render();
  listen();
} catch (err) {
  document.title = `${name} · DevMap`;
  showError(mid, err.message.startsWith("Không có dự án") ? new Error(t("notFound")) : err);
}

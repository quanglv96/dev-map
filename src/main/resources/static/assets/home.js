import { applyI18n, mountLangSwitch, t } from "./i18n.js";
import { api, el, fill, setFavicon, showError } from "./lib.js";

applyI18n();
mountLangSwitch(document.getElementById("lang"));
setFavicon("#2563eb");

const list = document.getElementById("projects");
const form = document.getElementById("add-form");
const repoRows = document.getElementById("repo-rows");
const addError = document.getElementById("add-error");

function serviceCount(project) {
  return project.config ? project.config.repos.reduce((n, r) => n + r.services.length, 0) : 0;
}

async function loadProjects() {
  try {
    const projects = await api("/api/projects");
    if (projects.length === 0) {
      list.replaceChildren(el("p", { class: "muted" }, t("noProjects")));
      return;
    }
    list.replaceChildren(el("ul", { class: "project-list" }, projects.map((p) => el("li", {},
      el("span", { class: "dot", style: `background:${p.config?.color ?? "#9ca3af"}` }),
      el("a", { href: `/${p.name}` }, el("strong", {}, p.name)),
      el("span", { class: "muted" }, p.error
        ? ""
        : ` · ${p.config.repos.length} ${t("repos").toLowerCase()} · ${serviceCount(p)} ${t("services").toLowerCase()}`),
      p.error ? el("span", { class: "error" }, " " + p.error) : null,
      el("small", { class: "muted path" }, p.dataDir),
      el("button", { type: "button", class: "link danger", onclick: () => unregister(p.name) }, t("unregister"))
    ))));
  } catch (err) {
    showError(list, err);
  }
}

async function unregister(name) {
  if (!confirm(t("confirmUnregister", { name }))) {
    return;
  }
  try {
    await api(`/api/projects/${encodeURIComponent(name)}`, { method: "DELETE" });
    loadProjects();
  } catch (err) {
    showError(list, err);
  }
}

/** One repo of the new project; removing it only drops it from the list, nothing on disk is touched. */
function addRepoRow(repo = { path: "", baseBranch: "main", services: [] }) {
  const row = el("div", {},
    el("div", { class: "row" },
      el("input", { name: "repoPath", placeholder: t("repoPath"), value: repo.path, required: true }),
      el("input", { name: "baseBranch", placeholder: t("baseBranch"), value: repo.baseBranch, required: true, class: "short" }),
      el("button", { type: "button", class: "link danger", title: t("removeFromDevmapHint"), onclick: () => row.remove() },
        t("removeFromDevmap"))),
    repo.services.length
      ? el("small", { class: "muted" }, `${t("services")}: ${repo.services.map((s) => s.name).join(", ")}`)
      : null);
  repoRows.append(row);
}

/** OS folder dialog opened by the local server: the browser cannot hand out absolute paths. Null on cancel. */
async function pickFolder(start, button) {
  button.disabled = true;
  try {
    return (await api(`/api/folders/pick?start=${encodeURIComponent(start)}`, { method: "POST" })).path;
  } finally {
    button.disabled = false;
  }
}

/** Folders already under the data root; free ones (no registered project uses them) can be deleted. */
const dataInput = form.elements.dataRoot;
const dataFolders = document.getElementById("data-folders");

async function scanDataRoot() {
  const root = dataInput.value.trim();
  if (!root) {
    fill(dataFolders);
    return;
  }
  try {
    const f = await api(`/api/folders?path=${encodeURIComponent(root)}`);
    fill(dataFolders, f.dirs.length === 0 ? null : [el("small", { class: "muted" }, t("dataFolders")),
      el("ul", { class: "plain" }, f.dirs.map((d) => el("li", {},
        el("span", { class: "path" }, d.name), " ",
        d.usedBy
          ? el("small", { class: "muted" }, t("usedBy", { name: d.usedBy }))
          : el("button", { type: "button", class: "link danger", onclick: () => deleteFolder(d.path) }, t("remove")))))]);
  } catch (err) {
    showError(dataFolders, err);
  }
}

async function deleteFolder(path) {
  if (!confirm(t("confirmDeleteFolder", { path }))) {
    return;
  }
  try {
    await api(`/api/folders?path=${encodeURIComponent(path)}`, { method: "DELETE" });
    scanDataRoot();
  } catch (err) {
    showError(dataFolders, err);
  }
}

/** Scan only once a folder was actually chosen in the dialog, not while the PMO types. */
document.getElementById("pick-data").addEventListener("click", async (event) => {
  try {
    const path = await pickFolder(dataInput.value.trim(), event.currentTarget);
    if (path) {
      dataInput.value = path;
      scanDataRoot();
    }
  } catch (err) {
    showError(dataFolders, err);
  }
});

/** Project folder = parent of the service repos; scanned only once a folder was chosen. */
const projectRoot = document.getElementById("project-root");
document.getElementById("pick-project").addEventListener("click", async (event) => {
  try {
    const path = await pickFolder(projectRoot.value, event.currentTarget);
    if (!path) {
      return;
    }
    projectRoot.value = path;
    const found = await api(`/api/repos/scan?path=${encodeURIComponent(path)}`);
    repoRows.replaceChildren();
    found.forEach((r) => addRepoRow(r));
    showError(addError, found.length ? null : new Error(t("noReposFound")));
  } catch (err) {
    showError(addError, err);
  }
});

/** FR-41: decisions waiting for the PMO and running tickets across all projects. */
async function loadOverview() {
  const pending = document.getElementById("pending");
  const running = document.getElementById("running");
  try {
    const overview = await api("/api/overview");
    const block = (key) => {
      const rows = overview.flatMap((p) => p[key].map((x) => el("li", {},
        el("span", { class: "dot", style: `background:${p.color}` }), " ",
        el("a", { href: `/${p.project}#task=${encodeURIComponent(x.id)}` }, `${p.project} · ${x.id}`),
        ` ${x.title} `, el("small", { class: `badge ${x.status}` }, t(`status.${x.status}`)))));
      return rows.length ? el("ul", { class: "plain" }, rows) : el("p", { class: "muted" }, t("none"));
    };
    const waiting = overview.reduce((n, p) => n + p.pending.length, 0);
    document.title = `${waiting ? `(${waiting}) ` : ""}DevMap`;
    setFavicon("#2563eb", waiting ? "pending" : null);
    pending.replaceChildren(block("pending"));
    running.replaceChildren(block("running"));
  } catch (err) {
    showError(pending, err);
  }
}

new EventSource("/api/events").addEventListener("task", loadOverview);
loadOverview();

document.getElementById("add-repo").addEventListener("click", () => addRepoRow());
addRepoRow();

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const data = new FormData(form);
  const branches = data.getAll("baseBranch");
  const body = {
    name: data.get("name").trim(),
    color: data.get("color"),
    dataRoot: data.get("dataRoot").trim(),
    repos: data.getAll("repoPath").map((path, i) => ({ path: path.trim(), baseBranch: branches[i].trim() }))
  };
  try {
    const project = await api("/api/projects", { method: "POST", body });
    location.href = `/${project.name}`;
  } catch (err) {
    showError(addError, err);
  }
});

loadProjects();

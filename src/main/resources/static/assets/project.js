import { applyI18n, mountLangSwitch, t } from "./i18n.js";
import { api, el, setFavicon, showError } from "./lib.js";

applyI18n();
mountLangSwitch(document.getElementById("lang"));

const name = decodeURIComponent(location.pathname.split("/")[1]);
const mid = document.getElementById("mid");
const right = document.getElementById("right");
let project;
let view = "overview";

function setHeader() {
  const color = project.config.color;
  document.title = `${project.name} · DevMap`;
  document.getElementById("project-name").textContent = project.name;
  document.getElementById("project-dot").style.background = color;
  setFavicon(color);
}

function renderRight() {
  const c = project.config;
  right.replaceChildren(
    el("h3", {}, t("status")),
    el("dl", {},
      el("dt", {}, t("dataDir")), el("dd", { class: "path" }, project.dataDir),
      el("dt", {}, t("ticketCode")), el("dd", {}, c.ticketCode === "auto" ? t("ticketAuto") : t("ticketManual")),
      el("dt", {}, t("repos")),
      el("dd", {}, el("ul", {}, c.repos.map((r) => el("li", {}, r.name, el("span", { class: "muted" }, ` @${r.baseBranch}`)))))));
}

function renderOverview() {
  const repos = project.config.repos;
  mid.replaceChildren(el("h2", {}, t("services")),
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

function serviceRow(s = { name: "", path: "", kind: "" }) {
  const row = el("tr", { class: "service" },
    el("td", {}, el("input", { name: "sName", value: s.name, required: true })),
    el("td", {}, el("input", { name: "sPath", value: s.path })),
    el("td", {}, el("input", { name: "sKind", value: s.kind, class: "short" })),
    el("td", {}, el("button", { type: "button", class: "link danger", onclick: () => row.remove() }, t("remove"))));
  return row;
}

function repoCard(r = { path: "", baseBranch: "main", services: [] }) {
  const body = el("tbody", {}, r.services.map((s) => serviceRow(s)));
  const error = el("div");
  const pathInput = el("input", { name: "rPath", value: r.path, placeholder: t("repoPath"), required: true });
  const detect = async () => {
    try {
      const services = await api(`/api/services/detect?path=${encodeURIComponent(pathInput.value.trim())}`);
      body.replaceChildren(...services.map((s) => serviceRow(s)));
      showError(error, null);
    } catch (err) {
      showError(error, err);
    }
  };
  const card = el("fieldset", { class: "repo" },
    el("div", { class: "row" }, pathInput,
      el("input", { name: "rBranch", value: r.baseBranch, placeholder: t("baseBranch"), required: true, class: "short" }),
      el("button", { type: "button", class: "link danger", onclick: () => card.remove() }, t("remove"))),
    el("table", {},
      el("thead", {}, el("tr", {}, el("th", {}, t("services")), el("th", {}, t("servicePath")), el("th", {}, t("kind")), el("th"))),
      body),
    el("div", { class: "row" },
      el("button", { type: "button", class: "link", onclick: detect }, t("detectServices")),
      el("button", { type: "button", class: "link", onclick: () => body.append(serviceRow()) }, t("addService"))),
    error);
  return card;
}

function readRepo(card) {
  const path = card.querySelector("[name=rPath]").value.trim();
  return {
    name: path.replace(/[\\/]+$/, "").split(/[\\/]/).pop(),
    path,
    baseBranch: card.querySelector("[name=rBranch]").value.trim(),
    services: [...card.querySelectorAll("tr.service")].map((tr) => ({
      name: tr.querySelector("[name=sName]").value.trim(),
      path: tr.querySelector("[name=sPath]").value.trim(),
      kind: tr.querySelector("[name=sKind]").value.trim()
    }))
  };
}

function renderConfig() {
  const c = project.config;
  const repos = el("div", {}, c.repos.map((r) => repoCard(r)));
  const status = el("div");
  const branchInputs = Object.entries(c.branchPatterns).map(([type, pattern]) =>
    el("label", {}, el("span", {}, type), el("input", { name: `branch-${type}`, value: pattern, required: true })));
  const form = el("form", { class: "form" },
    el("label", {}, el("span", {}, t("color")), el("input", { name: "color", type: "color", value: c.color })),
    el("label", {}, el("span", {}, t("ticketCode")),
      el("select", { name: "ticketCode" },
        el("option", { value: "manual", selected: c.ticketCode === "manual" }, t("ticketManual")),
        el("option", { value: "auto", selected: c.ticketCode === "auto" }, t("ticketAuto")))),
    el("label", {}, el("span", {}, t("ticketPattern")),
      el("input", { name: "ticketPattern", value: c.ticketPattern ?? "", placeholder: "^NYX-\\d+$" })),
    el("fieldset", {}, el("legend", {}, t("branchPatterns")), branchInputs),
    el("label", {}, el("span", {}, t("environments")), el("input", { name: "environments", value: c.environments.join(", ") })),
    el("fieldset", {}, el("legend", {}, t("repos")), repos,
      el("button", { type: "button", class: "link", onclick: () => repos.append(repoCard()) }, t("addRepo"))),
    status,
    el("button", { type: "submit", class: "primary" }, t("save")));

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const data = new FormData(form);
    const config = {
      ...c,
      color: data.get("color"),
      ticketCode: data.get("ticketCode"),
      ticketPattern: data.get("ticketPattern").trim() || null,
      branchPatterns: Object.fromEntries(Object.keys(c.branchPatterns).map((type) => [type, data.get(`branch-${type}`).trim()])),
      environments: data.get("environments").split(",").map((s) => s.trim()).filter(Boolean),
      repos: [...repos.querySelectorAll("fieldset.repo")].map(readRepo)
    };
    try {
      project = await api(`/api/projects/${encodeURIComponent(name)}/config`, { method: "PUT", body: config });
      setHeader();
      renderRight();
      status.replaceChildren(el("p", { class: "ok" }, t("saved")));
    } catch (err) {
      showError(status, err);
    }
  });
  mid.replaceChildren(el("h2", {}, t("projectConfig")), form);
}

function render() {
  document.querySelectorAll("nav .tab").forEach((b) => b.classList.toggle("active", b.dataset.view === view));
  if (view === "config") {
    renderConfig();
  } else {
    renderOverview();
  }
}

document.querySelectorAll("nav .tab").forEach((b) => b.addEventListener("click", () => {
  view = b.dataset.view;
  render();
}));

try {
  project = await api(`/api/projects/${encodeURIComponent(name)}`);
  if (project.error) {
    throw new Error(project.error);
  }
  setHeader();
  renderRight();
  render();
} catch (err) {
  document.title = `${name} · DevMap`;
  showError(mid, err.message.startsWith("Không có dự án") ? new Error(t("notFound")) : err);
}

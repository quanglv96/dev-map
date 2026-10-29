// "Cấu hình dự án": repos, base branches, services, ticket code rules, branch patterns, secret masking (FR-02 → FR-04).
import { t } from "../i18n.js";
import { api, el, showError } from "../lib.js";

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

const lines = (text) => text.split("\n").map((s) => s.trim()).filter(Boolean);

/** Renders the form into target; onSaved(project) is called with the saved project. */
export function renderConfig(target, project, onSaved) {
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
    el("fieldset", {}, el("legend", {}, t("secrets")),
      el("label", {}, el("span", {}, t("secretPatterns")),
        el("textarea", { name: "secretPatterns", rows: 3, value: c.secrets.patterns.join("\n") })),
      el("label", {}, el("span", {}, t("secretAllow")),
        el("textarea", { name: "secretAllow", rows: 3, value: c.secrets.allow.join("\n") }))),
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
      repos: [...repos.querySelectorAll("fieldset.repo")].map(readRepo),
      secrets: { patterns: lines(data.get("secretPatterns")), allow: lines(data.get("secretAllow")) }
    };
    try {
      const saved = await api(`/api/projects/${encodeURIComponent(project.name)}/config`, { method: "PUT", body: config });
      onSaved(saved);
      status.replaceChildren(el("p", { class: "ok" }, t("saved")));
    } catch (err) {
      showError(status, err);
    }
  });
  target.replaceChildren(el("h2", {}, t("projectConfig")), form);
}

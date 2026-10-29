// "Nhật ký" (NFR-10): every action of the PMO, the agents and DevMap, newest first; filter by ticket.
import { t } from "../i18n.js";
import { api, el, fill, showError } from "../lib.js";

export async function renderLog(mid, right, project, tasks) {
  const ticket = el("select", {}, el("option", { value: "" }, t("allTickets")), tasks.map((x) => el("option", { value: x.id }, x.id)));
  const body = el("tbody");
  const error = el("div");
  const load = async () => {
    try {
      const rows = await api(`/api/projects/${encodeURIComponent(project)}/audit?ticket=${encodeURIComponent(ticket.value)}`);
      fill(body, rows.length ? rows.map((r) => el("tr", {},
        el("td", { class: "nowrap" }, r.at), el("td", {}, r.actor), el("td", {}, r.ticket ?? ""),
        el("td", {}, r.repo ?? ""), el("td", {}, r.service ?? ""), el("td", { class: "small" }, r.rule ?? ""),
        el("td", { class: "small" }, r.action))) : el("tr", {}, el("td", { colspan: 7, class: "muted" }, t("noLog"))));
      showError(error, null);
    } catch (err) {
      showError(error, err);
    }
  };
  ticket.addEventListener("change", load);
  fill(mid, el("h2", {}, t("auditLog")), el("div", { class: "row" }, ticket), error,
    el("table", {},
      el("thead", {}, el("tr", {}, ["time", "actor", "ticket", "repo", "service", "rule", "action"].map((k) => el("th", {}, t(`log.${k}`))))),
      body));
  fill(right, el("p", { class: "muted small" }, t("auditHint")));
  await load();
}

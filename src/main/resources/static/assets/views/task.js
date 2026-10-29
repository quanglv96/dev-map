// A ticket: create form, Documents tab (versions + diff), right column with status and PMO decisions.
import { t } from "../i18n.js";
import { api, diffView, el, fill, markdown, showError } from "../lib.js";

const EDITABLE = ["analysis.md", "plan.md", "summary.md"];
const selectedDoc = new Map();

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
    status,
    el("button", { type: "submit", class: "primary" }, t("createTicket")));
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const data = new FormData(form);
    try {
      const created = await api(`/api/projects/${encodeURIComponent(project.name)}/tasks`, {
        method: "POST",
        body: { type: data.get("type"), code: data.get("code") ?? "", title: data.get("title"), request: data.get("request") }
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

function renderRight(right, project, detail) {
  const task = detail.task;
  const from = task.status;
  const error = el("div");
  const move = async (to, gate) => {
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
  const gates = detail.actions.filter((a) => a.pmoGate || (from === "awaiting_approval" && a.to === "sa_analysis"));
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
      el("dt", {}, t("status")), el("dd", {}, el("span", { class: `badge ${task.status}` }, t(`status.${task.status}`))),
      el("dt", {}, t("type")), el("dd", {}, task.type),
      el("dt", {}, t("branch")), el("dd", { class: "path" }, task.branch),
      el("dt", {}, t("repos")), el("dd", {}, task.repos.length ? task.repos.join(", ") : "–"),
      el("dt", {}, t("version")), el("dd", {}, String(task.version)),
      el("dt", {}, t("reopened")), el("dd", {}, String(task.reopened)),
      detail.queuePosition ? [el("dt", {}, t("queuePos")), el("dd", {}, String(detail.queuePosition))] : null),
    gates.length || from === "done" ? el("h3", { class: "section" }, t("pmoDecisions")) : null,
    el("div", { class: "actions" }, gates.map((a) => el("button", {
      type: "button", class: a.pmoGate ? "primary" : "secondary", onclick: () => move(a.to, a.pmoGate)
    }, actionLabel(from, a.to)))),
    from === "done" ? el("div", { class: "actions" },
      el("button", { type: "button", class: "secondary", onclick: () => reopen("bug") }, t("reportBug")),
      el("button", { type: "button", class: "secondary", onclick: () => reopen("logic") }, t("addLogic"))) : null,
    reopenBox,
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
  const notice = state.masked ? el("p", { class: "warn" }, t("masked", { n: state.masked })) : null;
  state.masked = 0;
  const docs = el("div");
  mid.replaceChildren(el("h2", {}, `${detail.task.id} · ${detail.task.title}`), el("h3", {}, t("docs")), docs);
  renderDocs(docs, project, detail, null, notice);
  renderRight(right, project, detail);
}

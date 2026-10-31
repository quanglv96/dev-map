// Chat tab of a ticket (FR-37 → FR-40): every question starts a new agent session; steps stream in over SSE ("agent" events).
import { t } from "../i18n.js";
import { api, el, fill, markdown, showError } from "../lib.js";

let current = null;

const base = (project, id) => `/api/projects/${encodeURIComponent(project)}/tasks/${encodeURIComponent(id)}`;

export const tokens = (u) => (u ? u.input + u.cacheRead + u.cacheWrite + u.output : 0);

function usageLine(u) {
  if (!u) {
    return null;
  }
  return el("small", { class: "muted" }, t("usage", {
    input: u.input + u.cacheRead + u.cacheWrite, cache: u.cacheRead, output: u.output, cost: u.costUsd.toFixed(4)
  }));
}

function stepView(s) {
  const first = s.text.split("\n")[0];
  const label = s.kind === "tool" ? `🔧 ${first}` : s.kind === "note" ? `· ${first}` : s.kind === "error" ? `⚠ ${first}` : first;
  return s.text.includes("\n") || s.text.length > 140
    ? el("details", { class: "step" }, el("summary", {}, label.slice(0, 140)), el("pre", {}, s.text))
    : el("div", { class: "step" }, label);
}

function messageView(project, id, m) {
  if (m.from === "PMO") {
    return el("div", { class: "msg pmo" }, el("div", { class: "bubble" }, m.text), el("small", { class: "muted" }, m.at));
  }
  const input = el("div");
  return el("div", { class: `msg agent ${m.state}` },
    el("div", { class: "bubble" },
      el("div", { class: "row small" },
        el("strong", {}, m.from), el("span", { class: "muted" }, ` · ${t(m.mode === "verify" ? "verifySession" : "newSession")} · ${m.at}`)),
      m.context.length ? el("small", { class: "muted" }, t("contextFiles", { files: m.context.join(", ") })) : null,
      m.steps.length ? el("details", { class: "steps", open: m.state === "running" },
        el("summary", {}, t("steps", { n: m.steps.length })), m.steps.map(stepView)) : null,
      m.state === "running" ? el("p", { class: "muted" }, t("agentRunning")) : null,
      m.text ? markdown(m.text) : null,
      el("div", { class: "row" }, usageLine(m.usage),
        el("button", { type: "button", class: "link small", onclick: async () => {
          if (input.childElementCount) {
            input.replaceChildren();
            return;
          }
          try {
            const log = await api(`${base(project, id)}/chat/input?no=${m.id}`);
            input.replaceChildren(el("pre", { class: "input-log" }, log.content));
          } catch (err) {
            showError(input, err);
          }
        } }, t("inputLog"))),
      input));
}

async function draw() {
  const { project, id, list, totals } = current;
  const view = await api(`${base(project, id)}/chat`);
  fill(list, view.messages.length ? view.messages.map((m) => messageView(project, id, m)) : el("p", { class: "muted" }, t("noChat")));
  list.scrollTop = list.scrollHeight;
  current.form.querySelector("button[type=submit]").disabled = view.running;
  if (totals) {
    fill(totals, t("tokensTicket", { n: tokens(view.ticket).toLocaleString() }), el("br"),
      t("tokensProject", { n: tokens(view.project).toLocaleString() }));
  }
}

/** SSE "agent" event: redraw only the open chat of that ticket. */
export function onAgentEvent(change) {
  if (current && current.project === change.project && current.id === change.taskId && document.body.contains(current.list)) {
    draw();
  }
}

/** One PMO question = one new agent session (FR-37); role "" picks SA or Dev by status. */
export function askAgent(project, id, question, role = "", fullScan = false) {
  return api(`${base(project, id)}/ask`, { method: "POST", body: { role, question, fullScan } });
}

/** totals: element in the right column for the token sums (FR-40). */
export async function renderChat(box, project, detail, totals) {
  const id = detail.task.id;
  const devTime = ["ready", "in_dev"].includes(detail.task.status);
  const list = el("div", { class: "chat" });
  const status = el("div");
  const question = el("textarea", { class: "chat-input", rows: 3, placeholder: t("askPlaceholder") });
  const role = el("select", {},
    el("option", { value: "" }, t("roleAuto", { role: devTime ? "Dev" : "SA" })),
    el("option", { value: "sa" }, "SA"),
    devTime ? el("option", { value: "dev" }, "Dev") : null);
  const scan = el("input", { type: "checkbox" });
  const form = el("form", { class: "chat-form" }, question,
    el("div", { class: "row" }, role,
      el("label", { class: "check small grow" }, scan, " ", t("fullScanOnce")),
      el("button", { type: "submit", class: "primary" }, t("send"))),
    status);
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    try {
      if (scan.checked) {
        const est = await api(`/api/projects/${encodeURIComponent(project)}/graph/estimate`);
        if (!confirm(t("fullScanAskConfirm", { files: est.files }))) {
          return;
        }
      }
      await askAgent(project, id, question.value, role.value, scan.checked);
      question.value = "";
      scan.checked = false;
      status.replaceChildren();
      await draw();
    } catch (err) {
      showError(status, err);
    }
  });
  current = { project, id, list, form, totals };
  box.replaceChildren(list, form);
  await draw();
}

// Room of a ticket (PROMPT_ROOM FR-71 → FR-81): PMO on the right, SA and Dev on the left, DevMap cards (proposals, automatic
// document updates with diff and undo, notices) in between. The PMO writes once; the server's local router picks who answers.
// Agents never read this room: they exchange through documents and events. Steps stream in over SSE ("agent" / "task" events).
import { t } from "../i18n.js";
import { api, diffView, el, fill, markdown, showError } from "../lib.js";

let current = null;
let progress = null;

const base = (project, id) => `/api/projects/${encodeURIComponent(project)}/tasks/${encodeURIComponent(id)}`;

export const tokens = (u) => (u ? u.input + u.cacheRead + u.cacheWrite + u.output : 0);

const AVATAR = { PMO: "PMO", SA: "SA", DEV: "Dev", DEVMAP: "DM" };

function avatar(who, busy = false) {
  return el("span", { class: `avatar ${who.toLowerCase()}${busy ? " busy" : ""}`, title: who }, AVATAR[who] ?? who);
}

function usageLine(u) {
  if (!u) {
    return null;
  }
  return el("small", { class: "muted" }, t("usage", {
    input: u.input + u.cacheRead + u.cacheWrite, cache: u.cacheRead, output: u.output, cost: u.costUsd.toFixed(4)
  }));
}

function stepLabel(s) {
  const first = s.text.split("\n")[0];
  return s.kind === "tool" ? `🔧 ${first}` : s.kind === "note" ? `· ${first}` : s.kind === "error" ? `⚠ ${first}` : first;
}

function stepView(s) {
  const label = stepLabel(s);
  return s.text.includes("\n") || s.text.length > 140
    ? el("details", { class: "step" }, el("summary", {}, label.slice(0, 140)), el("pre", {}, s.text))
    : el("div", { class: "step" }, label);
}

const sessionLabel = (m) => t(m.mode === "verify" ? "verifySession" : m.mode === "sync" ? "room.sync" : "newSession");

function agentView(project, id, m) {
  const input = el("div");
  return el("div", { class: `msg agent ${m.from.toLowerCase()} ${m.state}` }, avatar(m.from),
    el("div", { class: "bubble" },
      el("div", { class: "row small" },
        el("strong", {}, AVATAR[m.from] ?? m.from), el("span", { class: "muted" }, ` · ${sessionLabel(m)} · ${m.at}`)),
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

/** FR-80: "SA đã cập nhật plan.md (v4) theo đề xuất của Dev" with the diff one click away and an undo button. */
function docCard(project, id, c, out, error) {
  return [
    el("button", { type: "button", class: "link small", onclick: async () => {
      if (out.childElementCount) {
        out.replaceChildren();
        return;
      }
      try {
        const d = c.from > 0
          ? (await api(`${base(project, id)}/doc/diff?name=${encodeURIComponent(c.doc)}&from=${c.from}&to=${c.to}`)).diff
          : (await api(`${base(project, id)}/doc/version?name=${encodeURIComponent(c.doc)}&n=${c.to}`)).content;
        out.replaceChildren(diffView(d || "(=)"));
      } catch (err) {
        showError(out, err);
      }
    } }, t("room.showDiff")),
    c.from > 0 && !c.undone ? el("button", { type: "button", class: "link small", onclick: async () => {
      try {
        await api(`${base(project, id)}/room/undo`, { method: "POST", body: { doc: c.doc, from: c.from, to: c.to } });
        await draw();
      } catch (err) {
        showError(error, err);
      }
    } }, t("room.undo")) : null,
    c.undone ? el("small", { class: "muted" }, ` ${t("room.undone")}`) : null];
}

/** FR-76: author, summary, impact, status; details and the PMO's decision when the proposal waits for it. */
function proposalCard(project, id, c, proposals, error) {
  const p = proposals.get(c.proposal);
  if (!p) {
    return null;
  }
  const decide = (status) => async () => {
    try {
      await api(`${base(project, id)}/room/proposals/${encodeURIComponent(p.id)}`, { method: "POST", body: { status, note: "" } });
      await draw();
    } catch (err) {
      showError(error, err);
    }
  };
  return el("details", { class: "proposal-detail" },
    el("summary", {}, `${t("room.proposal")} · ${p.type} · ${p.affects} · ${p.status}`),
    el("div", {}, el("strong", {}, p.summary)),
    p.location ? el("div", { class: "path" }, [p.location.repo, p.location.file, p.location.signature].filter(Boolean).join(" · ")) : null,
    p.proposal ? markdown(p.proposal) : null,
    p.note ? el("div", { class: "muted" }, p.note) : null,
    p.views.length ? [el("strong", {}, t("room.views")), el("ul", {}, p.views.map((v) => el("li", {}, v)))] : null,
    p.refs.length ? el("div", { class: "muted small" }, p.refs.join(", ")) : null,
    p.status === "NEEDS_PMO" ? el("div", { class: "row" },
      el("button", { type: "button", class: "primary", onclick: decide("APPLIED") }, t("room.apply")),
      el("button", { type: "button", class: "secondary", onclick: decide("REJECTED") }, t("room.reject"))) : null);
}

function choiceCard(project, id, c, error) {
  return el("div", { class: "row" }, c.options.map((o) => {
    const [kind, value] = o.split(":");
    return kind === "priority" ? el("button", { type: "button", class: "secondary", onclick: async () => {
      try {
        await api(`${base(project, id)}/room/priority`, { method: "POST", body: { first: value } });
        await draw();
      } catch (err) {
        showError(error, err);
      }
    } }, t("room.priority", { id: value })) : null;
  }));
}

function cardView(project, id, m, proposals) {
  const c = m.card;
  const out = el("div");
  const error = el("div");
  const body = c.type === "doc" ? docCard(project, id, c, out, error)
    : c.type === "proposal" ? proposalCard(project, id, c, proposals, error)
      : c.type === "choice" ? choiceCard(project, id, c, error) : null;
  return el("div", { class: `msg card ${c.level ?? ""}${c.undone ? " undone" : ""}` }, avatar("DEVMAP"),
    el("div", { class: "bubble" }, el("div", {}, c.text), el("small", { class: "muted" }, m.at), body, out, error));
}

function messageView(project, id, m, proposals) {
  if (m.from === "PMO") {
    return el("div", { class: "msg pmo" }, el("div", { class: "row small" }, el("span", { class: "muted" }, m.at), avatar("PMO")),
      el("div", { class: "bubble" }, m.text));
  }
  return m.card ? cardView(project, id, m, proposals) : agentView(project, id, m);
}

async function draw() {
  const [view, list] = await Promise.all([api(`${base(current.project, current.id)}/chat`),
    api(`${base(current.project, current.id)}/room/proposals`).catch(() => [])]);
  drawView(view, new Map(list.map((p) => [p.id, p])));
}

const atBottom = (list) => list.scrollHeight - list.scrollTop - list.clientHeight < 40;

/** A finished agent message never changes: its node stays, so an opened step or input log and the reading position survive. */
function messageNode(m, proposals) {
  if (m.card) {
    return messageView(current.project, current.id, m, proposals); // cards change (undone, proposal status): always fresh
  }
  const key = `${m.from}/${m.id}/${m.at}`;
  const kept = current.nodes.get(key);
  if (kept && kept.state === m.state && m.state !== "running") {
    return kept.node;
  }
  const node = messageView(current.project, current.id, m, proposals);
  current.nodes.set(key, { state: m.state, node });
  return node;
}

function drawView(view, proposals = new Map()) {
  const { list, totals } = current;
  const follow = atBottom(list);
  const top = list.scrollTop;
  fill(list, view.messages.length ? view.messages.map((m) => messageNode(m, proposals)) : el("p", { class: "muted" }, t("noChat")));
  list.scrollTop = follow ? list.scrollHeight : top; // follows new steps unless the PMO scrolled up to read
  drawBar(view.runningRoles ?? []);
  if (totals) {
    fill(totals, t("tokensTicket", { n: tokens(view.ticket).toLocaleString() }), el("br"),
      t("tokensProject", { n: tokens(view.project).toLocaleString() }));
  }
}

/** FR-71: members, ticket status and who is running; FR-83: pause / resume the automatic sync. */
function drawBar(running) {
  const task = current.detail.task;
  const paused = Boolean(task.flags?.syncPaused);
  fill(current.bar,
    el("span", { class: "members", title: t("room.members") }, avatar("PMO"), avatar("SA", running.includes("SA")),
      avatar("DEV", running.includes("DEV"))),
    el("span", { class: `badge ${task.status}` }, t(`status.${task.status}`)),
    el("small", { class: "muted grow" }, running.length ? t("room.running", { roles: running.map((r) => AVATAR[r]).join(", ") })
      : t("room.idle")),
    el("button", { type: "button", class: "link small", onclick: async () => {
      try {
        const updated = await api(`${base(current.project, current.id)}/room/pause`, { method: "POST", body: { paused: !paused } });
        current.detail = { ...current.detail, task: updated };
        drawBar(running);
      } catch (err) {
        showError(current.status, err);
      }
    } }, paused ? t("room.resume") : t("room.pause")),
    paused ? el("div", { class: "small warn" }, t("room.pausedNote")) : null);
}

/** Banner above the ticket tabs while an agent works on it: who, how many steps, the latest step, a moving bar. */
function drawProgress(view) {
  const running = view.messages.filter((x) => x.from !== "PMO" && !x.card && x.state === "running");
  if (!running.length) {
    progress.box.replaceChildren();
    return;
  }
  fill(progress.box, running.map((m) => {
    const role = m.mode === "verify" ? "VERIFY" : m.from;
    const last = m.steps.at(-1);
    return el("div", { class: `progress ${role.toLowerCase()}` },
      el("div", { class: "row small" }, el("strong", {}, t(`work.${role}`)),
        el("span", { class: "muted grow" }, ` · ${sessionLabel(m)} · ${t("steps", { n: m.steps.length })} · ${m.at}`)),
      el("div", { class: "bar" }),
      last ? el("div", { class: "small muted now" }, stepLabel(last).slice(0, 200)) : null);
  }));
}

export async function renderProgress(box, project, id) {
  progress = { box, project, id };
  drawProgress(await api(`${base(project, id)}/chat`));
}

const showing = (x, change) => x && x.project === change.project && x.id === change.taskId;

/** SSE "agent" event: one fetch redraws the open room and the progress banner of that ticket. */
export async function onAgentEvent(change) {
  const room = showing(current, change) && document.body.contains(current.list);
  const bar = showing(progress, change) && document.body.contains(progress.box);
  if (!room && !bar) {
    return;
  }
  if (room) {
    await draw();
  }
  if (bar) {
    drawProgress(await api(`${base(change.project, change.taskId)}/chat`));
  }
}

/** One PMO question = one new agent session (FR-37); role "" picks SA or Dev by status. Used by the Questions tab. */
export function askAgent(project, id, question, role = "", fullScan = false) {
  return api(`${base(project, id)}/ask`, { method: "POST", body: { role, question, fullScan } });
}

/** Once the list is on the page: back to where the PMO was reading on this ticket, else the newest message. */
export function placeChat() {
  if (current?.list.isConnected) {
    current.list.scrollTop = current.restore ?? current.list.scrollHeight;
  }
}

/** The router was not sure (FR-72) or the bug case is unclear (FR-93): one line with buttons, the text is sent again with the choice. */
function drawAsk(text, res) {
  const send = (body) => async () => {
    try {
      await sendRoom(body);
    } catch (err) {
      showError(current.status, err);
    }
  };
  if (res.ask === "role") {
    fill(current.ask, el("div", { class: "room-ask" }, el("span", {}, t("room.askRole")),
      el("button", { type: "button", class: "secondary", onclick: send({ text, to: "sa" }) }, t("room.toSa")),
      el("button", { type: "button", class: "secondary", onclick: send({ text, to: "dev" }) }, t("room.toDev"))));
  } else if (res.ask === "case") {
    fill(current.ask, el("div", { class: "room-ask" }, el("span", {}, t("room.askCase")),
      res.guess ? el("small", { class: "muted", title: (res.evidence ?? []).join("\n") }, t("room.guess", { kase: res.guess })) : null,
      res.options.map((k) => el("button", { type: "button", class: k === res.guess ? "primary" : "secondary",
        onclick: send({ text, bugCase: k }) }, t(`room.case.${k}`)))));
  } else {
    current.ask.replaceChildren();
  }
}

async function sendRoom(body) {
  const res = await api(`${base(current.project, current.id)}/room/send`, { method: "POST", body });
  drawAsk(body.text, res);
  if (!res.ask) {
    current.input.value = "";
    current.status.replaceChildren(res.guess ? el("small", { class: "muted" }, t("room.caseSent", { kase: res.guess })) : "");
  }
  await draw();
}

/** totals: element in the right column for the token sums (FR-40). */
export async function renderChat(box, project, detail, totals) {
  const id = detail.task.id;
  const list = el("div", { class: "chat" });
  const bar = el("div", { class: "room-bar" });
  const status = el("div");
  const ask = el("div");
  const input = el("textarea", { class: "chat-input", rows: 3, placeholder: t("room.placeholder") });
  const role = el("select", {},
    el("option", { value: "" }, t("roleAuto", { role: "SA / Dev" })),
    el("option", { value: "sa" }, "@SA"),
    el("option", { value: "dev" }, "@Dev"));
  const scan = el("input", { type: "checkbox" });
  const form = el("form", { class: "chat-form" }, ask, input,
    el("div", { class: "row" }, role,
      el("label", { class: "check small grow" }, scan, " ", t("fullScanOnce")),
      el("button", { type: "submit", class: "primary" }, t("send"))),
    status);
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    try {
      if (scan.checked) {
        // AC-09: a full scan belongs to one question of one role, outside the router
        const est = await api(`/api/projects/${encodeURIComponent(project)}/graph/estimate`);
        if (!confirm(t("fullScanAskConfirm", { files: est.files }))) {
          return;
        }
        await askAgent(project, id, input.value, role.value, true);
        input.value = "";
        scan.checked = false;
        await draw();
        return;
      }
      await sendRoom({ text: input.value, to: role.value });
    } catch (err) {
      showError(status, err);
    }
  });
  const same = current?.project === project && current.id === id && current.list.isConnected;
  const restore = same && !atBottom(current.list) ? current.list.scrollTop : null;
  current = { project, id, detail, list, bar, form, input, ask, status, totals, restore, nodes: new Map() };
  box.replaceChildren(bar, list, form);
  await draw();
  placeChat();
}

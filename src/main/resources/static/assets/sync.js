// Data sync between the PMO's machines (FR-46 → FR-57): status in the top bar, lock choice banner, settings, conflicts.
import { t } from "./i18n.js";
import { api, el, fill, showError } from "./lib.js";

let status = null;
let box = null;
let banner = null;
const listeners = [];

export const syncStatus = () => status;

/** fn(status, pulled) after every change; pulled = new data arrived from another machine. */
export function onSync(fn) {
  listeners.push(fn);
}

const STATE_CLASS = { OFF: "off", SYNCED: "ok", SYNCING: "busy", PENDING: "warn", ERROR: "bad", CONFLICT: "bad", LOCKED: "warn" };

/** Mounts the top-bar box (#sync) and the lock banner (#sync-banner); returns once the first status is shown. */
export async function mountSync() {
  box = document.getElementById("sync");
  banner = document.getElementById("sync-banner");
  try {
    update(await api("/api/sync"), false);
  } catch {
    // older server or no sync: the box stays empty
  }
}

/** SSE "sync" event payload: { status, pulled }. */
export function onSyncEvent(data) {
  update(data.status, data.pulled);
}

function update(next, pulled) {
  status = next;
  render();
  listeners.forEach((fn) => fn(status, pulled));
}

const call = (path, body, method = "POST") => api(path, { method, body }).then((s) => update(s.status ?? s, false));

function render() {
  if (!box || !status) {
    return;
  }
  const s = status;
  const error = el("div");
  const act = (path) => async () => {
    try {
      await call(path);
    } catch (err) {
      showError(error, err);
    }
  };
  fill(box,
    el("summary", { class: `sync-state ${STATE_CLASS[s.state]}` }, t(`sync.${s.state}`)),
    el("div", {},
      s.state === "OFF" ? el("p", { class: "muted" }, t("sync.offHint")) : [
        el("dl", {},
          el("dt", {}, t("sync.machine")), el("dd", {}, s.machine),
          el("dt", {}, t("dataDir")), el("dd", { class: "path" }, s.dataDir),
          el("dt", {}, t("sync.remote")), el("dd", { class: "path" }, s.remote ?? "–"),
          el("dt", {}, t("sync.last")), el("dd", {}, s.lastSync ? new Date(s.lastSync).toLocaleString() : "–"),
          el("dt", {}, t("sync.pending")), el("dd", {}, t("sync.pendingN", { ahead: s.ahead, dirty: s.dirty })),
          s.lock ? [el("dt", {}, t("sync.lock")), el("dd", {}, `${s.lock.machine} · ${new Date(s.lock.heartbeat).toLocaleTimeString()}`)] : null),
        s.message ? el("p", { class: s.state === "ERROR" ? "error" : "muted" }, s.message) : null,
        s.warning ? el("p", { class: "warn" }, s.warning) : null,
        s.blocked.length ? [el("p", { class: "error" }, t("sync.blocked")), el("ul", {}, s.blocked.map((b) => el("li", { class: "path" }, b)))] : null,
        s.conflicts.length ? [el("p", { class: "warn" }, t("sync.conflicts")), el("ul", {}, s.conflicts.map((c) => el("li", {},
          c.ticket ? el("a", { href: `/${encodeURIComponent(c.project)}#task=${encodeURIComponent(c.ticket)}` }, `${c.project}/${c.ticket}`) : c.project,
          " · ", el("span", { class: "path" }, c.file))))] : null,
        s.confirmPush ? el("div", { class: "confirm-card card" }, el("p", {}, t("sync.confirmPush", { n: s.ahead })),
          el("button", { type: "button", class: "primary", onclick: act("/api/sync/push") }, t("sync.push"))) : null,
        el("div", { class: "actions" },
          el("button", { type: "button", class: "secondary", disabled: s.state === "SYNCING", onclick: act("/api/sync/now") }, t("sync.now")),
          s.readOnly && !s.lockChoice ? el("button", { type: "button", class: "secondary", onclick: act("/api/sync/lock/take") }, t("sync.take")) : null)],
      error));
  renderBanner();
}

/** FR-53: another machine has DevMap open; the PMO decides once: view only, or take over. */
function renderBanner() {
  if (!banner) {
    return;
  }
  const s = status;
  if (!s.lockChoice && !s.readOnly) {
    banner.replaceChildren();
    return;
  }
  const error = el("span");
  const act = (path) => async () => {
    try {
      await call(path);
    } catch (err) {
      showError(error, err);
    }
  };
  fill(banner, el("div", { class: "sync-banner" },
    el("strong", {}, s.message || t("sync.LOCKED")), " ",
    s.lockChoice ? [
      el("button", { type: "button", class: "secondary small", onclick: act("/api/sync/lock/view") }, t("sync.viewOnly")),
      el("button", { type: "button", class: "primary small", onclick: act("/api/sync/lock/take") }, t("sync.take"))]
      : [el("span", { class: "muted" }, t("sync.readOnlyHint")), " ",
        el("button", { type: "button", class: "link small", onclick: act("/api/sync/lock/take") }, t("sync.take"))],
    error));
}

/** Settings screen (Cấu hình, trang chủ): turn sync on here, or clone the data repo on a new machine; auto push. */
export async function renderSyncSettings(target) {
  const s = status ?? await api("/api/sync");
  const error = el("div");
  const done = el("div");
  const remote = el("input", { name: "remote", placeholder: "git@github.com:acme/devmap-data.git", required: true });
  const path = el("input", { name: "path", placeholder: s.dataDir ?? "D:/devmap-data" });
  const pick = el("button", { type: "button", onclick: async () => {
    try {
      const picked = (await api(`/api/folders/pick?start=${encodeURIComponent(path.value)}`, { method: "POST" })).path;
      if (picked) {
        path.value = picked;
      }
    } catch (err) {
      showError(error, err);
    }
  } }, t("browse"));
  const run = (kind) => async () => {
    try {
      const r = await api(`/api/sync/${kind}`, { method: "POST", body: { remote: remote.value.trim(), path: path.value.trim() } });
      update(r.status, kind === "clone");
      fill(done, el("p", { class: "ok" }, t(`sync.${kind}Done`, { projects: r.projects.join(", ") || "–" })),
        r.warning ? el("p", { class: "warn" }, r.warning) : null);
      showError(error, null);
    } catch (err) {
      showError(error, err);
    }
  };
  const autoPush = el("input", { type: "checkbox", checked: s.autoPush, onchange: async () => {
    try {
      await call("/api/sync/auto-push", { on: autoPush.checked }, "PUT");
    } catch (err) {
      showError(error, err);
    }
  } });
  fill(target, el("fieldset", { class: "sync-settings" }, el("legend", {}, t("sync.title")),
    s.state === "OFF" ? [
      el("p", { class: "muted small" }, t("sync.setupHint")),
      el("label", {}, el("span", {}, t("sync.remote")), remote),
      el("label", {}, el("span", {}, t("sync.folder")), el("span", { class: "row" }, path, pick)),
      el("div", { class: "actions" },
        el("button", { type: "button", class: "primary", onclick: run("init") }, t("sync.init")),
        el("button", { type: "button", class: "secondary", onclick: run("clone") }, t("sync.clone")))]
      : el("p", {}, t("sync.onAt", { dir: s.dataDir, remote: s.remote ?? "–", machine: s.machine })),
    el("label", { class: "check" }, autoPush, " ", t("sync.autoPush")),
    el("small", { class: "muted" }, t("sync.autoPushHint")),
    done, error));
}

/** FR-54: both versions of each conflicting file of a ticket; the PMO keeps one or merges, then the copy is removed. */
export function renderConflicts(target, project, ticketId, onResolved) {
  const list = (status?.conflicts ?? []).filter((c) => c.project === project && c.ticket === ticketId);
  if (list.length === 0) {
    target.replaceChildren();
    return;
  }
  fill(target, el("section", { class: "card confirm-card" },
    el("h3", {}, t("sync.conflictTitle", { n: list.length })),
    el("p", { class: "muted small" }, t("sync.conflictHint")),
    list.map((c) => {
      const error = el("div");
      const merged = el("textarea", { rows: 8, class: "editor", value: `${c.current}\n${c.other}` });
      const save = (content) => async () => {
        try {
          await call("/api/sync/conflicts/resolve", { conflictFile: c.conflictFile, content });
          onResolved?.();
        } catch (err) {
          showError(error, err);
        }
      };
      return el("details", { open: true }, el("summary", { class: "path" }, c.file),
        el("div", { class: "grid2" },
          el("div", {}, el("strong", {}, t("sync.current")), el("pre", { class: "conflict" }, c.current)),
          el("div", {}, el("strong", {}, t("sync.other", { machine: c.machine })), el("pre", { class: "conflict" }, c.other))),
        el("div", { class: "actions" },
          el("button", { type: "button", class: "secondary", onclick: save(c.current) }, t("sync.keepCurrent")),
          el("button", { type: "button", class: "secondary", onclick: save(c.other) }, t("sync.keepOther", { machine: c.machine }))),
        el("label", {}, el("span", {}, t("sync.merge")), merged),
        el("button", { type: "button", class: "primary", onclick: () => save(merged.value)() }, t("sync.saveMerged")),
        error);
    })));
}

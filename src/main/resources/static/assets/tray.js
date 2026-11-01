// Windows tray notifications: settings card on the home page (FR-66), and telling the server which ticket this tab shows (FR-65).
import { t } from "./i18n.js";
import { api, el, fill, showError } from "./lib.js";

const KINDS = ["DECISION", "QUESTION", "PUSH", "FAILURE", "SYNC"];
const FOCUS_SECONDS = 30;

/** The tab reports while visible; the server forgets a tab that stopped reporting, in case the browser crashed. */
export function reportFocus(project, currentTicket) {
  const tab = crypto.randomUUID();
  const send = () => {
    const ticket = document.visibilityState === "visible" ? currentTicket() : null;
    fetch("/api/tray/focus", {
      method: "POST",
      keepalive: true,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ tab, project, ticket })
    }).catch(() => {
      // only decides whether a pop-up shows; the next report tries again
    });
  };
  send();
  document.addEventListener("visibilitychange", send);
  window.addEventListener("hashchange", send);
  window.addEventListener("pagehide", () => {
    const body = new Blob([JSON.stringify({ tab, project, ticket: null })], { type: "application/json" });
    navigator.sendBeacon("/api/tray/focus", body);
  });
  setInterval(() => {
    if (document.visibilityState === "visible") {
      send();
    }
  }, FOCUS_SECONDS * 1000);
}

/** True when the tray icon already notifies: the page then stays quiet, so the PMO does not get every event twice. */
export async function trayActive() {
  try {
    return (await api("/api/tray/settings")).active;
  } catch {
    return false;
  }
}

/** A checkbox for each thing: checked = notify, stored as "muted" so whatever is added later starts on. */
function muteList(values, muted, labelOf, onChange) {
  return values.map((value) => {
    const box = el("input", { type: "checkbox" });
    box.checked = !muted.includes(value);
    box.addEventListener("change", () => onChange(value, !box.checked));
    return el("label", { class: "inline" }, box, " ", labelOf(value));
  });
}

export async function renderTraySettings(box) {
  try {
    const [view, projects] = await Promise.all([api("/api/tray/settings"), api("/api/projects")]);
    let settings = view.settings;
    const out = el("div", {});
    const save = async (change) => {
      try {
        settings = (await api("/api/tray/settings", { method: "PUT", body: { ...settings, ...change } })).settings;
        fill(out);
      } catch (err) {
        showError(out, err);
      }
    };
    const toggle = (key) => (value, mute) => {
      const rest = settings[key].filter((x) => x !== value);
      save({ [key]: mute ? [...rest, value] : rest });
    };
    const quiet = el("input", { type: "checkbox" });
    quiet.checked = settings.quietEnabled;
    quiet.addEventListener("change", () => save({ quietEnabled: quiet.checked }));
    const from = el("input", { type: "time", value: settings.quietFrom, class: "short" });
    const to = el("input", { type: "time", value: settings.quietTo, class: "short" });
    from.addEventListener("change", () => save({ quietFrom: from.value }));
    to.addEventListener("change", () => save({ quietTo: to.value }));
    fill(box,
      view.active ? null : el("p", { class: "muted" }, t("tray.inactive")),
      el("small", { class: "muted" }, t("tray.kinds")),
      el("div", {}, muteList(KINDS, settings.mutedKinds, (k) => t(`tray.kind.${k}`), toggle("mutedKinds"))),
      projects.length ? el("small", { class: "muted" }, t("tray.projects")) : null,
      el("div", {}, muteList(projects.map((p) => p.name), settings.mutedProjects, (p) => p, toggle("mutedProjects"))),
      el("div", { class: "row" }, el("label", { class: "inline" }, quiet, " ", t("tray.quiet")), from, to),
      el("small", { class: "muted" }, t("tray.quietHint")),
      view.snoozed
        ? el("p", {}, t("tray.snoozed", { until: settings.snoozeUntil.replace("T", " ").slice(0, 16) }), " ",
          el("button", { type: "button", class: "link", onclick: async () => {
            await save({ snoozeUntil: null });
            renderTraySettings(box);
          } }, t("tray.resume")))
        : null,
      out);
  } catch (err) {
    showError(box, err);
  }
}

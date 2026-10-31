// Small helpers shared by every page: JSON API calls, DOM building, Markdown, project-colored favicon, browser notifications.
import { marked } from "../vendor/marked.esm.js";
import DOMPurify from "../vendor/purify.es.js";

/** Markdown → sanitized DOM; documents are written by agents, so never trust their HTML. */
export function markdown(text) {
  const box = el("div", { class: "md" });
  box.innerHTML = DOMPurify.sanitize(marked.parse(text ?? ""));
  return box;
}

/** Unified diff with +/- lines colored. */
export function diffView(text) {
  return el("pre", { class: "diff" }, text.split("\n").map((line) => el("span", {
    class: line.startsWith("+") && !line.startsWith("+++") ? "add" : line.startsWith("-") && !line.startsWith("---") ? "del" : ""
  }, line + "\n")));
}

export const WAITS_FOR_PMO = ["awaiting_approval", "awaiting_push"];

export async function api(path, { method = "GET", body } = {}) {
  const res = await fetch(path, {
    method,
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  if (res.status === 204) {
    return null;
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(data.error || `${res.status} ${res.statusText}`);
  }
  return data;
}

/** el("div", {class: "x", onclick: fn}, "text", child) – text is always set as text, never HTML. */
export function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === undefined || v === null || v === false) {
      continue;
    }
    if (k.startsWith("on")) {
      node.addEventListener(k.slice(2), v);
    } else if (k in node && k !== "list") {
      node[k] = v;
    } else {
      node.setAttribute(k, v === true ? "" : v);
    }
  }
  node.append(...children.flat(Infinity).filter((c) => c !== null && c !== undefined && c !== false));
  return node;
}

/** replaceChildren with el()'s rules: arrays are flattened, null/false are skipped (native would print "null"). */
export function fill(target, ...children) {
  target.replaceChildren(...children.flat(Infinity).filter((c) => c !== null && c !== undefined && c !== false));
}

/** Project-colored icon; dot by work state (same colors as the .work pills) or none. */
const DOT = { sa: "#2563eb", dev: "#7c3aed", verify: "#0891b2", pmo: "#eab308", error: "#dc2626" };

/**
 * What a ticket is waiting on, for the tab title, the task list and the ticket page: { key, cls } or null.
 * activity: GET /agents (working: id → SA | DEV | VERIFY, questions: id → open questions to the PMO).
 */
export function workState(task, activity) {
  const role = activity.working?.[task.id];
  if (role) {
    return { key: `work.${role}`, cls: role.toLowerCase() };
  }
  if (activity.questions?.[task.id]) {
    return { key: "work.questions", cls: "pmo", n: activity.questions[task.id] };
  }
  if (WAITS_FOR_PMO.includes(task.status)) {
    return { key: `work.${task.status}`, cls: "pmo" };
  }
  return activity.failed?.includes(task.id) ? { key: "work.error", cls: "error" } : null;
}

export function setFavicon(color, dot) {
  const mark = DOT[dot] ? `<circle cx="12" cy="12" r="4" fill="${DOT[dot]}" stroke="#fff" stroke-width="1.5"/>` : "";
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16"><circle cx="7" cy="7" r="6.5" fill="${color}"/>${mark}</svg>`;
  let link = document.querySelector("link[rel=icon]");
  if (!link) {
    link = el("link", { rel: "icon" });
    document.head.append(link);
  }
  link.href = "data:image/svg+xml," + encodeURIComponent(svg);
}

export function showError(target, err) {
  target.replaceChildren(err ? el("p", { class: "error" }, err.message || String(err)) : "");
}

/**
 * OS notification (SRS "Hiển thị tiến trình") whenever the PMO is not looking at this page: another tab, another app, or the
 * browser behind other windows. The same tag replaces, so several tabs of one project do not stack.
 * sticky: stays on screen until clicked (the PMO has to decide or answer).
 */
export function notify(title, body, tag, onclick, sticky = false) {
  if (!("Notification" in window) || Notification.permission !== "granted" || document.hasFocus()) {
    return;
  }
  const n = new Notification(title, { body, tag, requireInteraction: sticky, icon: document.querySelector("link[rel=icon]")?.href });
  n.onclick = () => {
    window.focus();
    onclick?.();
    n.close();
  };
}

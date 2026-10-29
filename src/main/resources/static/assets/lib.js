// Small helpers shared by every page: JSON API calls, DOM building, Markdown, project-colored favicon.
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

export function setFavicon(color) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16"><circle cx="8" cy="8" r="7" fill="${color}"/></svg>`;
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

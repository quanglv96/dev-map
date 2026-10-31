// Graph screen (FR-17, FR-22, FR-23): Service → API → Function, ticket highlight, blast radius, manual edges, index jobs.
// Drawn locally from the JSON index with Cytoscape; nothing here involves an agent or tokens (AIR-08).
import cytoscape from "../../vendor/cytoscape.esm.min.js";
import { t } from "../i18n.js";
import { api, el, fill, showError } from "../lib.js";

const COLORS = { service: "#2563eb", api: "#0891b2", class: "#7c3aed", function: "#64748b", table: "#b45309", topic: "#15803d" };
const SHAPES = { service: "round-rectangle", api: "tag", class: "rectangle", function: "ellipse", table: "barrel", topic: "diamond" };
const STYLE = [
  { selector: "node", style: {
    label: "data(short)", "font-size": 10, "text-valign": "bottom", "text-margin-y": 4, color: "#1f2937",
    "background-color": (n) => COLORS[n.data("kind")] ?? "#999", shape: (n) => SHAPES[n.data("kind")] ?? "ellipse",
    width: (n) => n.data("kind") === "service" ? 46 : 22, height: (n) => n.data("kind") === "service" ? 30 : 22,
    "text-wrap": "ellipsis", "text-max-width": 140, "min-zoomed-font-size": 7 } },
  { selector: "node:selected", style: { "border-width": 3, "border-color": "#111827" } },
  { selector: "node.ticket", style: { "border-width": 4, "border-color": "#f59e0b" } },
  { selector: "node.radius", style: { "background-color": "#dc2626" } },
  { selector: "node.seed", style: { "border-width": 4, "border-color": "#dc2626", "background-color": "#7f1d1d" } },
  { selector: "edge", style: {
    width: 1.5, "curve-style": "bezier", "target-arrow-shape": "triangle", "arrow-scale": 0.8,
    "line-color": "#94a3b8", "target-arrow-color": "#94a3b8", label: "data(type)", "font-size": 8, color: "#64748b",
    "text-rotation": "autorotate", "min-zoomed-font-size": 9 } },
  { selector: "edge.medium", style: { "line-style": "dashed" } },
  { selector: "edge.low", style: { "line-style": "dotted" } },
  { selector: "edge.manual", style: { "line-color": "#f59e0b", "target-arrow-color": "#f59e0b" } },
  { selector: "edge.contains", style: { "line-color": "#e5e7eb", "target-arrow-color": "#e5e7eb", label: "" } },
  { selector: ".faded", style: { opacity: 0.12 } }
];

/** Per view: how the nodes are placed when no saved positions exist. */
const LAYOUTS = ["auto", "cose", "breadthfirst", "concentric", "circle", "grid"];

function layoutOptions(name, level, focus) {
  const tree = level === "node" || level === "radius" || level === "service";
  const pick = name === "auto" ? (tree ? "breadthfirst" : "cose") : name;
  return { name: pick, animate: false, padding: 30, nodeDimensionsIncludeLabels: true, directed: true, spacingFactor: 1.25,
    roots: pick === "breadthfirst" && focus ? [focus] : undefined, nodeRepulsion: 20000, idealEdgeLength: 140, avoidOverlap: true,
    concentric: (n) => (n.id() === focus ? 10 : n.degree()), levelWidth: () => 2 };
}

// dragged node positions per view survive re-renders and reloads; only a viewer convenience, so storage may fail
const POS_KEY = "devmap.graph.pos";

function loadPositions() {
  try {
    return JSON.parse(localStorage.getItem(POS_KEY)) ?? {};
  } catch {
    return {};
  }
}

function savePositions(all) {
  try {
    localStorage.setItem(POS_KEY, JSON.stringify(all));
  } catch {
    // private window or full storage: positions just are not remembered
  }
}

/** Mounts the screen; returns a refresh(eventState) function for SSE "graph" events. */
export function renderGraph(mid, right, project, tasks) {
  const base = `/api/projects/${encodeURIComponent(project)}/graph`;
  const state = { level: "services", focus: null, trail: [], depth: 2, selected: null, edgeFrom: null, ticket: "", radius: null,
    layout: "auto" };
  const positions = loadPositions();
  const viewKey = () => `${project}|${state.level}|${state.focus}|${state.depth}|${state.layout}|${state.radius ? "r" : ""}`;
  const crumbs = el("div", { class: "crumbs" });
  const error = el("div");
  const box = el("div", { id: "cy", class: "cy" });
  const results = el("div", { class: "search-results" });
  const search = el("input", { type: "search", placeholder: t("graphSearch"), oninput: () => doSearch(search.value) });
  const depth = el("select", { onchange: () => { state.depth = Number(depth.value); draw(); } },
    [1, 2, 3].map((d) => el("option", { value: d, selected: d === 2 }, `${t("depth")} ${d}`)));
  const ticket = el("select", { onchange: () => { state.ticket = ticket.value; draw(); } },
    el("option", { value: "" }, t("highlightTicket")), tasks.map((x) => el("option", { value: x.id }, `${x.id} · ${x.title}`)));
  const layout = el("select", { title: t("graphLayout"), onchange: () => { state.layout = layout.value; draw(); } },
    LAYOUTS.map((v) => el("option", { value: v }, t(`layout.${v}`))));
  const zoomBy = (f) => cy.zoom({ level: cy.zoom() * f, renderedPosition: { x: box.clientWidth / 2, y: box.clientHeight / 2 } });
  const canvasTools = el("div", { class: "row graph-canvas-tools small" },
    el("button", { type: "button", class: "secondary", title: t("zoomIn"), onclick: () => zoomBy(1.25) }, "+"),
    el("button", { type: "button", class: "secondary", title: t("zoomOut"), onclick: () => zoomBy(0.8) }, "−"),
    el("button", { type: "button", class: "secondary", onclick: () => cy.fit(undefined, 30) }, t("fitView")),
    layout,
    el("button", { type: "button", class: "secondary", title: t("relayoutHint"), onclick: () => {
      delete positions[viewKey()];
      savePositions(positions);
      draw();
    } }, t("relayout")),
    el("span", { class: "muted" }, t("canvasHint")));
  fill(mid, el("div", { class: "row graph-tools" }, crumbs, el("span", { class: "grow" }), depth, ticket,
    el("div", { class: "search" }, search, results)),
  el("details", { class: "graph-help card" }, el("summary", {}, t("graphHelpTitle")),
    t("graphHelp").split("\n").map((line) => el("p", { class: "small" }, line))),
  canvasTools, error, box,
  el("p", { class: "muted small" }, t("graphLegend")));
  const cy = cytoscape({ container: box, style: STYLE, wheelSensitivity: 0.25, maxZoom: 4, minZoom: 0.05, boxSelectionEnabled: true });
  cy.on("tap", "node", (e) => {
    focusOn(e.target);
    select(e.target.data());
  });
  cy.on("tap", (e) => {
    if (e.target === cy) {
      cy.elements().removeClass("faded");
    }
  });
  cy.on("dbltap", "node", (e) => drill(e.target.data()));
  cy.on("dragfree", "node", () => {
    positions[viewKey()] = Object.fromEntries(cy.nodes().map((n) => [n.id(), n.position()]));
    savePositions(positions);
  });

  /** Click a node: keep it and its direct neighbours in front, fade the rest. */
  function focusOn(node) {
    cy.elements().addClass("faded");
    node.closedNeighborhood().removeClass("faded");
  }

  async function doSearch(q) {
    if (q.trim().length < 2) {
      results.replaceChildren();
      return;
    }
    const nodes = await api(`${base}/search?q=${encodeURIComponent(q.trim())}`);
    fill(results, nodes.map((n) => el("button", { type: "button", class: "link", onclick: () => {
      results.replaceChildren();
      drill(n);
    } }, `${n.kind}: ${n.label}`)));
  }

  /** Double-click / search: service → its APIs; anything else → its dependency neighborhood. */
  function drill(node) {
    state.trail.push({ level: state.level, focus: state.focus });
    if (node.kind === "service" && state.level === "services") {
      Object.assign(state, { level: "service", focus: node.id });
    } else {
      Object.assign(state, { level: "node", focus: node.id });
    }
    state.radius = null;
    state.selected = node;
    draw();
    renderSide();
  }

  function renderCrumbs() {
    const parts = [el("button", { type: "button", class: "link", onclick: () => {
      Object.assign(state, { level: "services", focus: null, trail: [], radius: null });
      draw();
    } }, t("services"))];
    if (state.focus) {
      parts.push(" › ", el("strong", {}, (state.level === "radius" ? `${t("blastRadius")}: ` : "") + state.focus.replace(/^\w+:/, "")));
    }
    if (state.trail.length) {
      parts.push(" ", el("button", { type: "button", class: "link", onclick: () => {
        Object.assign(state, state.trail.pop(), { radius: null });
        draw();
      } }, `← ${t("back")}`));
    }
    fill(crumbs, parts);
  }

  async function draw() {
    renderCrumbs();
    try {
      const url = state.level === "services" ? `${base}/services`
        : state.level === "service" ? `${base}/service?id=${encodeURIComponent(state.focus)}`
          : `${base}/neighborhood?node=${encodeURIComponent(state.focus)}&depth=${state.depth}&dir=${state.level === "radius" ? "in" : "both"}`;
      const g = await api(url);
      const ticketServices = state.ticket ? (await api(`${base}/ticket/${encodeURIComponent(state.ticket)}`)).services : [];
      const ticketNames = ticketServices.map((id) => id.slice(4));
      cy.elements().remove();
      cy.add(g.nodes.map((n) => ({
        data: { ...n, short: n.kind === "function" || n.kind === "class" ? n.label.split(".").slice(-2).join(".") : n.label },
        classes: [ticketServices.includes(n.id) || ticketNames.includes(n.service) ? "ticket" : "",
          state.radius?.ids.has(n.id) || (n.kind === "service" && state.radius?.services.has(n.label)) ? "radius" : "",
          state.radius?.seed === n.id ? "seed" : ""].join(" ")
      })));
      cy.add(g.edges.map((e) => ({
        data: { id: `${e.from}|${e.to}|${e.type}`, source: e.from, target: e.to, type: e.type, confidence: e.confidence },
        classes: `${e.confidence} ${e.type} ${e.source === "manual" ? "manual" : ""}`
      })));
      const saved = positions[viewKey()];
      cy.layout(saved && cy.nodes().every((n) => saved[n.id()])
        ? { name: "preset", positions: (n) => saved[n.id()], padding: 30 }
        : layoutOptions(state.layout, state.level, state.focus)).run();
      showError(error, g.truncated ? new Error(t("truncated")) : null);
    } catch (err) {
      showError(error, err);
    }
  }

  /** FR-23 blast radius: draws everything that depends on the node within the chosen depth (max 3), across repos. */
  async function blastRadius(node) {
    const g = await api(`${base}/neighborhood?node=${encodeURIComponent(node.id)}&depth=${state.depth}&dir=in`);
    state.trail.push({ level: state.level, focus: state.focus });
    Object.assign(state, { level: "radius", focus: node.id,
      radius: { seed: node.id, ids: new Set(g.nodes.map((n) => n.id)), services: new Set(g.nodes.map((n) => n.service)) } });
    draw();
  }

  function select(node) {
    state.selected = node;
    renderSide();
  }

  async function startJob(full) {
    try {
      if (full) {
        const est = await api(`${base}/estimate`);
        if (!confirm(t("fullScanConfirm", { files: est.files, repos: Object.keys(est.filesByRepo).length }))) {
          return;
        }
      }
      await api(`${base}/update`, { method: "POST", body: { full } });
      renderSide();
    } catch (err) {
      showError(error, err);
    }
  }

  async function renderSide() {
    const [status, edges] = await Promise.all([api(`${base}/status`), api(`${base}/edges`)]);
    const n = state.selected;
    const empty = status.repos.every((r) => !r.indexedCommit);
    const edgeForm = el("div");
    if (state.edgeFrom && n && n.id !== state.edgeFrom) {
      const type = el("select", {}, ["manual", "http", "calls", "depends", "publishes", "consumes", "uses_table", "config"]
        .map((v) => el("option", { value: v }, v)));
      const conf = el("select", {}, ["high", "medium", "low"].map((v) => el("option", { value: v }, v)));
      const note = el("input", { placeholder: t("note") });
      fill(edgeForm, el("p", {}, `${state.edgeFrom} → ${n.id}`), el("div", { class: "row" }, type, conf), note,
        el("button", { type: "button", class: "primary", onclick: async () => {
          try {
            await api(`${base}/edges`, { method: "POST", body: { from: state.edgeFrom, to: n.id, type: type.value,
              confidence: conf.value, note: note.value } });
            state.edgeFrom = null;
            refresh();
          } catch (err) {
            showError(error, err);
          }
        } }, t("saveEdge")));
    }
    fill(right,
      el("h3", {}, t("index")),
      empty ? el("p", { class: "warn" }, t("noIndex")) : null,
      el("ul", { class: "plain small" }, status.repos.map((r) => el("li", {}, el("strong", {}, r.repo), ` @${r.baseBranch}: `,
        r.indexedCommit ? `${r.indexedCommit.slice(0, 8)} · ${r.files} file · ${r.indexedAt}` : t("notIndexed")))),
      el("p", { class: "muted small" }, `${status.nodes} node · ${status.edges} edge`),
      status.running ? el("p", { class: "warn" }, t("indexRunning")) : el("div", { class: "actions" },
        el("button", { type: "button", class: "secondary", disabled: empty, onclick: () => startJob(false) }, t("updateByDiff")),
        el("button", { type: "button", class: empty ? "primary" : "secondary", onclick: () => startJob(true) }, t("fullScan"))),
      status.error ? el("p", { class: "error" }, status.error) : null,
      status.last ? el("ul", { class: "plain small muted" }, status.last.repos.map((r) =>
        el("li", {}, `${r.repo}: ${t("parsed")} ${r.parsed}, ${t("removed")} ${r.removed} – ${r.note}`))) : null,
      n ? [el("h3", { class: "section" }, n.label),
        el("p", { class: "muted small path" }, [n.kind, n.service, n.file ? `${n.repo}/${n.file}${n.line ? ":" + n.line : ""}` : null]
          .filter(Boolean).join(" · ")),
        n.signature ? el("pre", { class: "sig" }, n.signature) : null,
        el("div", { class: "actions" },
          el("button", { type: "button", class: "secondary", onclick: () => drill(n) }, t("expand")),
          el("button", { type: "button", class: "secondary", onclick: () => blastRadius(n) }, t("blastRadius")),
          el("button", { type: "button", class: "secondary", onclick: () => { state.edgeFrom = n.id; renderSide(); } },
            t("edgeFromHere"))),
        edgeForm] : el("p", { class: "muted small" }, t("graphHint")),
      el("h3", { class: "section" }, t("manualEdges")),
      edges.length === 0 ? el("p", { class: "muted small" }, t("none")) : el("ul", { class: "plain small" }, edges.map((e) =>
        el("li", {}, `${e.from} → ${e.to} (${e.type}, ${e.confidence}) ${e.note} `,
          el("button", { type: "button", class: "link danger", onclick: () => api(`${base}/edges/${e.id}`, { method: "DELETE" })
            .then(refresh).catch((err) => showError(error, err)) }, t("remove"))))));
  }

  function refresh() {
    draw();
    renderSide();
  }

  refresh();
  return refresh;
}

// "Rule và coding convention": project default and per-service .devrules.yml (FR-29 → FR-32), conventions.md (FR-33).
import { t } from "../i18n.js";
import { api, el, fill, markdown, showError } from "../lib.js";

const lines = (text) => text.split("\n").map((s) => s.trim()).filter(Boolean);
const area = (name, list, rows = 3) => el("textarea", { name, rows, value: (list ?? []).join("\n") });
const check = (name, on, label, disabled = false) =>
  el("label", { class: "check" }, el("input", { type: "checkbox", name, checked: on, disabled }), " ", label);

/** One level (project default or a service) as a form over a full Rules object; the server stores only differences. */
function rulesForm(base, view, level, rules, onSaved) {
  const isDefault = level === "_default";
  const q = rules.quality;
  const status = el("div");
  const preset = el("select", { name: "preset" },
    el("option", { value: "" }, isDefault ? t("presetNone") : t("presetByKind")),
    Object.keys(view.presets).map((p) => el("option", { value: p, selected: rules.preset === p && (!isDefault || !!rules.preset) }, p)));
  const form = el("form", { class: "form" },
    el("label", {}, el("span", {}, t("preset")), preset),
    el("fieldset", {}, el("legend", {}, "Git"),
      isDefault ? [
        el("label", {}, el("span", {}, t("commitTemplate")), el("input", { name: "commitTemplate", value: rules.git.commitTemplate })),
        el("label", {}, el("span", {}, t("protectedBranches")), area("protectedBranches", rules.git.protectedBranches))
      ] : el("p", { class: "muted small" }, t("gitProjectWide")),
      el("p", { class: "muted small" }, t("baseProtected", { list: view.lockedBaseBranches.join(", ") })),
      check("locked1", true, t("lockPush"), true), check("locked2", true, t("lockForce"), true),
      check("locked3", true, t("lockProtected"), true)),
    el("fieldset", {}, el("legend", {}, t("quality")),
      check("layering", q.layering, t("layering")),
      check("staticAnalysis", q.staticAnalysis, t("staticAnalysis")),
      el("label", {}, el("span", {}, t("staticCommands")), area("staticCommands", q.staticCommands, 2)),
      check("unitTest", q.unitTest, t("unitTest")),
      el("label", {}, el("span", {}, t("command")), el("input", { name: "unitTestCommand", value: q.unitTestCommand })),
      check("integrationTest", q.integrationTest, t("integrationTest")),
      el("label", {}, el("span", {}, t("command")), el("input", { name: "integrationTestCommand", value: q.integrationTestCommand })),
      el("label", {}, el("span", {}, t("timeoutMinutes")),
        el("input", { name: "timeoutMinutes", type: "number", min: 1, max: 240, value: q.timeoutMinutes, class: "short" }))),
    el("fieldset", {}, el("legend", {}, t("scope")),
      el("label", {}, el("span", {}, t("forbiddenPaths")), area("forbiddenPaths", rules.scope.forbiddenPaths)),
      el("label", {}, el("span", {}, t("schemaPaths")), area("schemaPaths", rules.scope.schemaPaths)),
      el("label", {}, el("span", {}, t("dependencyFiles")), area("dependencyFiles", rules.scope.dependencyFiles)),
      el("p", { class: "muted small" }, t("scopeHint"))),
    status,
    el("div", { class: "row" },
      el("button", { type: "submit", class: "primary" }, t("save")),
      !isDefault && view.overridden[level] ? el("button", { type: "button", class: "link danger", onclick: async () => {
        await api(`${base}/services/${encodeURIComponent(level)}`, { method: "DELETE" });
        onSaved(level);
      } }, t("resetInherit")) : null));

  // Picking a preset fills its commands so the stored override is the preset, not the old commands.
  preset.addEventListener("change", () => {
    const p = view.presets[preset.value];
    if (!p) {
      return;
    }
    form.elements.staticCommands.value = (p.static_commands ?? []).join("\n");
    form.elements.unitTestCommand.value = p.unit_test_command ?? "";
    form.elements.integrationTestCommand.value = p.integration_test_command ?? "";
    form.elements.staticAnalysis.checked = p.static_analysis ?? form.elements.staticAnalysis.checked;
    form.elements.unitTest.checked = p.unit_test ?? form.elements.unitTest.checked;
  });

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const f = form.elements;
    const body = {
      preset: f.preset.value || null,
      git: isDefault ? { commitTemplate: f.commitTemplate.value.trim(), protectedBranches: lines(f.protectedBranches.value) } : rules.git,
      quality: {
        layering: f.layering.checked,
        staticAnalysis: f.staticAnalysis.checked,
        staticCommands: lines(f.staticCommands.value),
        unitTest: f.unitTest.checked,
        unitTestCommand: f.unitTestCommand.value.trim(),
        integrationTest: f.integrationTest.checked,
        integrationTestCommand: f.integrationTestCommand.value.trim(),
        timeoutMinutes: Number(f.timeoutMinutes.value)
      },
      scope: {
        forbiddenPaths: lines(f.forbiddenPaths.value),
        schemaPaths: lines(f.schemaPaths.value),
        dependencyFiles: lines(f.dependencyFiles.value)
      }
    };
    try {
      await api(isDefault ? `${base}/default` : `${base}/services/${encodeURIComponent(level)}`, { method: "PUT", body });
      onSaved(level, true);
    } catch (err) {
      showError(status, err);
    }
  });
  return form;
}

async function renderConventions(box, base) {
  const status = el("div");
  let view;
  try {
    view = await api(`${base}/conventions`);
  } catch (err) {
    showError(box, err);
    return;
  }
  const run = (fn) => async () => {
    try {
      await fn();
      await renderConventions(box, base);
    } catch (err) {
      showError(status, err);
    }
  };
  const edit = () => {
    const editor = el("textarea", { class: "editor", rows: 24, value: view.content });
    fill(box, el("h2", {}, t("conventions")), el("p", { class: "muted small" }, t("conventionsPaste")), editor, status,
      el("div", { class: "row" },
        el("button", { type: "button", class: "primary", onclick: run(() =>
          api(`${base}/conventions`, { method: "PUT", body: { content: editor.value } })) }, t("save")),
        el("button", { type: "button", class: "link", onclick: () => renderConventions(box, base) }, t("cancel"))));
  };
  fill(box,
    el("h2", {}, t("conventions"), " ", el("span", { class: `badge ${view.approved ? "done" : "awaiting_approval"}` },
      view.approved ? t("approved") : t("draft"))),
    el("p", { class: "muted small" }, t("conventionsHint")),
    el("div", { class: "row" },
      el("button", { type: "button", class: "secondary", onclick: run(() => api(`${base}/conventions/collect`, { method: "POST" })) },
        t("collect")),
      el("button", { type: "button", class: "link", onclick: edit }, t("edit")),
      view.approved ? null : el("button", { type: "button", class: "primary", onclick: run(() =>
        api(`${base}/conventions/approve`, { method: "POST" })) }, t("approve"))),
    status,
    markdown(view.content));
}

export async function renderRules(mid, right, project) {
  const base = `/api/projects/${encodeURIComponent(project)}/rules`;
  const formBox = el("div");
  const convBox = el("div", { class: "card" });
  let view;
  const draw = async (level, saved = false) => {
    try {
      view = await api(base);
    } catch (err) {
      showError(mid, err);
      return;
    }
    const levels = ["_default", ...Object.keys(view.services)];
    const rules = level === "_default" ? view.projectDefault : view.services[level];
    fill(right, el("h3", {}, t("rulesLevels")), el("div", { class: "actions" }, levels.map((l) => el("button", {
      type: "button",
      class: l === level ? "primary" : "secondary",
      onclick: () => draw(l)
    }, l === "_default" ? t("projectDefault") : l, view.overridden[l] ? ` · ${t("overridden")}` : ""))),
    el("p", { class: "muted small" }, t("rulesInherit")));
    fill(formBox, el("h2", {}, t("rules"), " · ", level === "_default" ? t("projectDefault") : level),
      saved ? el("p", { class: "ok" }, t("saved")) : null,
      rulesForm(base, view, level, rules, draw));
  };
  mid.replaceChildren(formBox, convBox);
  await draw("_default");
  await renderConventions(convBox, base);
}

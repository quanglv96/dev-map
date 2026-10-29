import js from "@eslint/js";
import globals from "globals";

export default [
  { ignores: ["src/main/resources/static/vendor/**"] },
  js.configs.recommended,
  {
    files: ["src/main/resources/static/**/*.js"],
    languageOptions: { ecmaVersion: 2022, sourceType: "module", globals: globals.browser },
    rules: { eqeqeq: "error", "no-var": "error", "prefer-const": "error" }
  }
];

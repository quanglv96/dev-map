[SYSTEM REQUIREMENT & AI EXECUTION INSTRUCTIONS]

1. CORE EXECUTION WORKFLOW
- Step 1 (PMO): Receive requirements in natural language / business voice.
- Step 2 (SA/BA & AI Agent): Translate requirement into technical spec by querying the local Codebase Index & Dependency Tree-Map. Confirm business logic with PMO.
- Step 3 (Dev & AI Code Assistant): Implement code, execute local static code analysis (Linter/CI), and confirm completed task status back to PMO.

2. REPOSITORY & SYNC RULES
- Always execute `git pull` on the target service repository before processing any task or analysis.
- Do NOT perform a full initial scan or full codebase re-indexing on startup.
- Track updates incrementally using `git diff`: Only scan and analyze modified or newly added files/modules to update the system topology.

3. DEPENDENCY TREE-MAP & TOKEN OPTIMIZATION RULES
- Build and maintain a lightweight text/JSON-based Dependency Graph (AST / Class / Method Signatures) from the incremental scan data.
- Do NOT generate Mermaid charts or visual diagrams to avoid unnecessary overhead and token bloat.
- Limit context retrieval to a maximum depth of 2-3 dependency layers around the target files.
- Inject only Class/Method Signatures and targeted code snippets (RAG) into the LLM context window. Never send full source files or entire directories.
- Strictly adhere to Prompt Caching for static system architecture rules, coding conventions, and global schemas.

4. QUALITY GATES & CONVENTIONS
- Enforce strict layer separation (Controller -> Service -> Repository).
- Every code modification must pass static analysis (ESLint, SonarQube, Checkstyle) and unit tests before confirming task closure to PMO.

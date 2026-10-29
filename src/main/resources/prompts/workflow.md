[SYSTEM ARCHITECTURE & BUSINESS WORKFLOW SPECIFICATION]

1. END-TO-END BUSINESS WORKFLOW
- Step 1 (PMO Request): PMO inputs feature requests or bug issues using plain natural language.
- Step 2 (AI/Local Sanitization): The system automatically parses raw input into a structured JSON Spec using local rules/lightweight NLP (Extracting: Issue_ID, Target_Module, Action_Type).
- Step 3 (SA/BA Analysis & Mapping):
  * SA/BA queries the local Dependency Tree-Map (Max 2-3 depth levels) to pinpoint affected files.
  * SA/BA generates a concise Technical Solution Spec and confirms business rules with PMO.
- Step 4 (Dev Implementation): Dev/AI Assistant writes code based strictly on the Technical Spec and runs local quality gates (Linter, Unit Tests).
- Step 5 (SA/BA Code Verification): SA/BA verifies the generated code against the original PMO spec via `git diff` to ensure zero regression, architectural adherence, and convention compliance.
- Step 6 (Closure & Confirmation): SA/BA reports verification proof to PMO for final confirmation and task sign-off.

2. REPOSITORY & INCREMENTAL SYNC RULES
- Mandatory Synchronization: Always execute `git pull` on the target service repository before processing any new task or analysis.
- No Full Initialization: NEVER perform a full repository scan or full re-indexing on tool startup.
- Incremental Tracking: Use `git diff` to detect modified/added files post-pull and incrementally update only the affected nodes in the local Dependency Tree-Map.

3. TOKEN OPTIMIZATION ENGINE (CORE STRATEGY)
- Layer 1 (Input Compression): Strip all conversational filler from PMO inputs. Convert raw data into compact JSON schemas before feeding to LLMs.
- Layer 2 (Tree-Map Routing): Inject only localized module dependencies into LLM context. Limit graph traversal to a maximum depth of 2-3 layers around the target feature.
- Layer 3 (Code Abstraction via AST):
  * Never transmit full `.java`, `.ts`, or source files.
  * Extract and inject ONLY Class/Method Signatures (AST) and targeted method snippets (RAG) relevant to the task.
- Layer 4 (No Visual Diagrams): Strictly prohibit generating Mermaid charts or visual topology diagrams to prevent token bloat. Maintain graph data purely in lightweight JSON/Text formats.
- Layer 5 (Prompt Caching & Session Isolation):
  * Apply System Prompt Caching for global coding conventions, layered architecture rules (Controller -> Service -> Repository), and DB schemas.
  * Enforce strict session isolation: Purge/flush context memory immediately after a task is closed to prevent context window bloat across different issues.

4. QUALITY GATES & CONVENTIONS ENFORCEMENT
- Architectural Compliance: Enforce strict separation of layers (Controller -> Service -> Repository).
- Automated Validation: Every code change must pass static analysis tools (ESLint, SonarQube, Checkstyle) and unit test suites prior to SA verification.
- Zero Scope Creep: SA verification must explicitly diff commit outputs against PMO requirements before triggering final PMO notification.

# Ruflo — Claude Code Configuration

## Rules

- Do what has been asked; nothing more, nothing less
- NEVER create files unless absolutely necessary — prefer editing existing files
- NEVER create documentation files unless explicitly requested
- NEVER save working files or tests to root — use `/src`, `/tests`, `/docs`, `/config`, `/scripts`
- ALWAYS read a file before editing it
- NEVER commit secrets, credentials, or .env files
- NEVER add a `Co-Authored-By` trailer to user commits unless this project's `.claude/settings.json` has `attribution.commit` set (#2078). The Claude Code Bash tool may suggest one in its default commit-message template — ignore it. `Co-Authored-By` is semantic authorship attribution under git/GitHub convention; the tool is the facilitator, not a co-author.
- Keep files under 500 lines
- Validate input at system boundaries

## Agent Comms (SendMessage-First Coordination)

Named agents coordinate via `SendMessage`, not polling or shared state.

```
Lead (you) ←→ architect ←→ developer ←→ tester ←→ reviewer
              (named agents message each other directly)
```

### Spawning a Coordinated Team

```javascript
// ALL agents in ONE message, each knows WHO to message next
Agent({ prompt: "Research the codebase. SendMessage findings to 'architect'.",
  subagent_type: "researcher", name: "researcher", run_in_background: true })
Agent({ prompt: "Wait for 'researcher'. Design solution. SendMessage to 'coder'.",
  subagent_type: "system-architect", name: "architect", run_in_background: true })
Agent({ prompt: "Wait for 'architect'. Implement it. SendMessage to 'tester'.",
  subagent_type: "coder", name: "coder", run_in_background: true })
Agent({ prompt: "Wait for 'coder'. Write tests. SendMessage results to 'reviewer'.",
  subagent_type: "tester", name: "tester", run_in_background: true })
Agent({ prompt: "Wait for 'tester'. Review code quality and security.",
  subagent_type: "reviewer", name: "reviewer", run_in_background: true })

// Kick off the pipeline
SendMessage({ to: "researcher", summary: "Start", message: "[task context]" })
```

### Patterns

| Pattern | Flow | Use When |
|---------|------|----------|
| **Pipeline** | A → B → C → D | Sequential dependencies (feature dev) |
| **Fan-out** | Lead → A, B, C → Lead | Independent parallel work (research) |
| **Supervisor** | Lead ↔ workers | Ongoing coordination (complex refactor) |

### Rules

- ALWAYS name agents — `name: "role"` makes them addressable
- ALWAYS include comms instructions in prompts — who to message, what to send
- Spawn ALL agents in ONE message with `run_in_background: true`
- After spawning: STOP, tell user what's running, wait for results
- NEVER poll status — agents message back or complete automatically

## Swarm & Routing

### Config
- **Topology**: hierarchical-mesh (anti-drift)
- **Max Agents**: 15
- **Memory**: hybrid
- **HNSW**: Enabled
- **Neural**: Enabled

```bash
npx @claude-flow/cli@latest swarm init --topology hierarchical --max-agents 8 --strategy specialized
```

### Agent Routing

| Task | Agents | Topology |
|------|--------|----------|
| Bug Fix | researcher, coder, tester | hierarchical |
| Feature | architect, coder, tester, reviewer | hierarchical |
| Refactor | architect, coder, reviewer | hierarchical |
| Performance | perf-engineer, coder | hierarchical |
| Security | security-architect, auditor | hierarchical |

### When to Swarm
- **YES**: 3+ files, new features, cross-module refactoring, API changes, security, performance
- **NO**: single file edits, 1-2 line fixes, docs updates, config changes, questions

### 3-Tier Model Routing

| Tier | Handler | Use Cases |
|------|---------|-----------|
| 1 | Agent Booster (WASM) | Simple transforms — skip LLM, use Edit directly |
| 2 | Haiku | Simple tasks, low complexity |
| 3 | Sonnet/Opus | Architecture, security, complex reasoning |

## Memory & Learning

### Before Any Task
```bash
npx @claude-flow/cli@latest memory search --query "[task keywords]" --namespace patterns
npx @claude-flow/cli@latest hooks route --task "[task description]"
```

### After Success
```bash
npx @claude-flow/cli@latest memory store --namespace patterns --key "[name]" --value "[what worked]"
npx @claude-flow/cli@latest hooks post-task --task-id "[id]" --success true --store-results true
```

### MCP Tools (use `ToolSearch("keyword")` to discover)

| Category | Key Tools |
|----------|-----------|
| **Memory** | `memory_store`, `memory_search`, `memory_search_unified` |
| **Bridge** | `memory_import_claude`, `memory_bridge_status` |
| **Swarm** | `swarm_init`, `swarm_status`, `swarm_health` |
| **Agents** | `agent_spawn`, `agent_list`, `agent_status` |
| **Hooks** | `hooks_route`, `hooks_post-task`, `hooks_worker-dispatch` |
| **Security** | `aidefence_scan`, `aidefence_is_safe`, `aidefence_has_pii` |
| **Hive-Mind** | `hive-mind_init`, `hive-mind_consensus`, `hive-mind_spawn` |

### Background Workers

| Worker | When |
|--------|------|
| `audit` | After security changes |
| `optimize` | After performance work |
| `testgaps` | After adding features |
| `map` | Every 5+ file changes |
| `document` | After API changes |

```bash
npx @claude-flow/cli@latest hooks worker dispatch --trigger audit
```

## Agents

**Core**: `coder`, `reviewer`, `tester`, `planner`, `researcher`
**Architecture**: `system-architect`, `backend-dev`, `mobile-dev`
**Security**: `security-architect`, `security-auditor`
**Performance**: `performance-engineer`, `perf-analyzer`
**Coordination**: `hierarchical-coordinator`, `mesh-coordinator`, `adaptive-coordinator`
**GitHub**: `pr-manager`, `code-review-swarm`, `issue-tracker`, `release-manager`

Any string works as a custom agent type.

## Build & Test

- ALWAYS run tests after code changes
- ALWAYS verify build succeeds before committing

```bash
npm run build && npm test
```

## CLI Quick Reference

```bash
npx @claude-flow/cli@latest init --wizard           # Setup
npx @claude-flow/cli@latest swarm init --v3-mode     # Start swarm
npx @claude-flow/cli@latest memory search --query "" # Vector search
npx @claude-flow/cli@latest hooks route --task ""    # Route to agent
npx @claude-flow/cli@latest doctor --fix             # Diagnostics
npx @claude-flow/cli@latest security scan            # Security scan
npx @claude-flow/cli@latest performance benchmark    # Benchmarks
```

26 commands, 140+ subcommands. Use `--help` on any command for details.

## Setup

```bash
claude mcp add claude-flow -- npx -y @claude-flow/cli@latest
npx @claude-flow/cli@latest daemon start
npx @claude-flow/cli@latest doctor --fix
```

**Agent tool** handles execution (agents, files, code, git). **MCP tools** handle coordination (swarm, memory, hooks). **CLI** is the same via Bash.

## Git Commit Guidelines

Git commit messages must strictly adhere to structured, informative conventions. Vague one-liners, uninformative summaries, or rough commits without structured bodies are strictly prohibited.

### Format & Length Specifications

1. **Subject Line**: `<type>(<scope>): <concise Chinese summary>`
   - **Length**: **≤ 50 Chinese characters** (approx. 72 characters). One sentence summarizing the core objective. Never end with a period (`.`).
   - **Punctuation**: Follow `<type>(<scope>)` with a half-width colon and a space (`": "`).
2. **Blank Line**: **Exactly one empty line** must separate the subject line and the body.
3. **Body**:
   - **Two-Tier Hierarchical Structure (Pattern A / B)**: Use a **numbered list** with 2–4 main items. **All items must have a newline followed by a 3-space indented `- ` sub-bullet** (even single points must be indented on a new line to ensure strict visual uniformity):
     ```text
     1. <Topic / Module Name>:
        - <Specific technical changes and core explanation A>
        - <Specific technical changes and core explanation B (if applicable)>
     ```
     - Keep main topic descriptions within 40–100 characters; limit nested sub-bullets to 1–3 per item.
   - **Single-Tier Flat Structure (Pattern C)**: Use a **flat bullet list** (3–5 `- ` points) without nested tiers, concisely stating technical capabilities or changes:
     ```text
     - <Technical point / change explanation A>
     - <Technical point / change explanation B>
     ```
     - Keep each bullet point within 30–80 characters, clearly detailing concrete implementations.
   - **Line Wrapping & Visual Cleanliness**: Control bullet sentence length carefully. Strictly avoid awkward line wrapping where only 2–3 trailing characters spill over into an orphaned next line; ensure lines are either comfortably single-line or evenly balanced.
4. **Structural Pattern Selection (Flexible adaptation by nature of commit)**:
   - **Pattern A: Multi-Feature / Comprehensive Module (for `feat` / large-scale `refactor`)**: Divide main items by functional modules (e.g., 1. Core Feature, 2. UI/Interaction Refinement, 3. Architecture Refactoring).
   - **Pattern B: Single Defect / Cause-and-Effect Fix (for `fix` / `perf`)**: Structure items by the **investigation and resolution chain** (e.g., 1. Root Cause Analysis, 2. Core Mechanism Fix, 3. Business Scope / Coverage), avoiding forced artificial modularization.
   - **Pattern C: Self-Contained Feature / Flat Checklist (for standalone sub-systems, targeted `perf`, or `build` maintenance)**: When changes are highly cohesive under a single topic (e.g., introducing a logging system, targeted performance overhaul, dependency upgrade), use 3–5 flat bullet points to list system capabilities or optimizations cleanly without over-engineering layers.

### Common Types and Scopes

- **Type**: `feat` (new feature) | `fix` (bug fix) | `perf` (performance) | `refactor` (refactoring) | `build` (build/deps) | `test` (tests)
- **Scope**: `download` (download engine/labels) | `gallery` (detail/reader) | `gallerylog` (operation logs) | `smb` (SMB storage) | `settings` (preferences)

### Language & Writing Requirements

- **Output Language**: **Standard Chinese** for descriptive text; technical names, protocol abbreviations, and class names must preserve their original English casing (e.g., `GID`, `SQLite`, `SMB`, `CBZ`, `DownloadManager`).
- **Perspective**: Emphasize technical substance, root causes, and business impact. Avoid superficial narrations like "changed line X".
- **Signature Trailers**: Trailers such as `Co-authored-by` or `Signed-off-by` are allowed but strictly optional. Prioritize clarity and technical completeness.


---
description: The main session analyzes and plans; local Ollama models implement or review by role (coder/reviewer).
allowed-tools: ["Bash", "Read", "Grep", "Write", "Edit"]
---

Handle the user's request ($ARGUMENTS) by following the steps below strictly in order.

### Step 1: Analyze and plan (Claude main session)
- Analyze the request ($ARGUMENTS) and work out which files must be modified or created.
- Use `Read` or `Grep` to identify **only the parts of existing code that the change needs**. Local models get confused by large files, so pick just the key files.
- Split the work into small units and decide which role below handles each unit. Parts that need many convention judgments (e.g. service logic) may be written by Claude directly.

### Step 2: Delegate by role (`scripts/ollama_implements.py`)

The role -> model mapping is fixed in the script's `ROLES`. To change a model, edit only the script.

| Role (`--role`) | Model | Use for |
|---|---|---|
| `coder` | `qwen2.5-coder:7b` | Boilerplate: model classes, simple mappers |
| `reviewer` | `qwen3.8:latest` | Reviewing generated or existing code (bugs, project-rule violations) |

```bash
python scripts/ollama_implements.py --role coder "<requirements for one work unit>" -c <reference file 1> -c <reference file 2>
python scripts/ollama_implements.py --role reviewer "<review request>" -c <file to review>
```

- Pass reference code with `-c <file path>` (repeatable). Do not paste code snippets into the arguments; if needed, save them to a file in the scratchpad and pass that.
- The script injects the project rules (no records, extend `BaseModel`, package split, etc.) as the system prompt, so do not repeat them in the request.
- `reviewer` (27B) is slow on first load and per reply (a single small file took 4-6 minutes). Use it only for reviews that matter.
- **Run sequentially, never in parallel.** The machine has 32GB RAM and only 8GB VRAM, so the 27B model runs mostly from system RAM.
  - Run one call at a time and wait for it to finish before starting the next. Do not launch `coder` and `reviewer` together or fan calls out to parallel subagents.
  - The script enforces this with a machine-wide lock file (`kkdugi-ollama.lock` in the OS temp dir). If another call is running, it exits immediately with code 3 and names the holder; wait for that call to finish and run yours again. Never delete the lock file (the OS releases it automatically if the holder dies).
  - The script's timeout is 30 minutes and it keeps the model loaded for 30 minutes (`TIMEOUT_SEC`, `KEEP_ALIVE`). Do not retry a slow call: a retry queues a second request behind the first.
  - The Bash tool caps a foreground command at 10 minutes, so run `reviewer` with `run_in_background: true` and wait for its completion notification.

### Step 3: Apply and verify (Claude main session)
- Do not trust local model output as-is: review it, then apply it with `Edit`/`Write`.
- Fix any project-rule violations in the generated code yourself.
- Finish with `./mvnw.cmd -B -ntp test`, and report failures as they are.

"""Delegate work to local Ollama models by role (--role).

Examples:
    python scripts/ollama_implements.py --role coder "Write the AdminFoo model class" -c Base.java
    python scripts/ollama_implements.py --role reviewer "Review this code" -c Foo.java -c Bar.java
    cat Foo.java | python scripts/ollama_implements.py --role reviewer "Review" -c -

Role -> model and system prompt mappings live in ROLES below. To change a model, edit only that.
"""
import argparse
import contextlib
import json
import os
import re
import sys
import tempfile
import time
import urllib.error
import urllib.request

OLLAMA_URL = "http://localhost:11434/api/generate"
# Local hardware is 32GB RAM + 8GB VRAM, so the 27B reviewer runs mostly from system RAM and is slow.
# Keep the timeout generous and keep the model loaded between calls. Run calls one at a time.
TIMEOUT_SEC = 1800
KEEP_ALIVE = "30m"
# Only one call may run at a time, across every agent/process on this machine (see ollama_lock).
LOCK_PATH = os.path.join(tempfile.gettempdir(), "kkdugi-ollama.lock")
EXIT_BUSY = 3

PROJECT_RULES = """\
Project rules (must follow):
- Java 17, Spring Boot 4, MyBatis. Import Jackson 3: tools.jackson.databind.ObjectMapper.
- No records. DB-row models extend kkdugi.core.models.BaseModel; list search params extend BaseParams.
  Everything else (commands/results) is a plain class with final fields and an all-args constructor.
- Split each feature into {package}.models / mapper / service / exceptions.
- Server-generated IDs: implement SerialConfig and call SerialUtils.next(config).
- Fixed value sets implement kkdugi.core.enums.CodeEnums (never add a per-column MyBatis typeHandler).
"""

# role -> model / system prompt / strip the code fence when the reply is a single fenced block
ROLES = {
    "coder": {
        "model": "qwen2.5-coder:7b",
        "system": (
            "You are a senior Java developer. Given the request and reference code, output only "
            "the code that is modified or newly written. Keep explanations minimal. "
            "For multiple files, put the file path on its own line before each code block.\n\n"
            + PROJECT_RULES
        ),
        "strip_fences": True,
    },
    "reviewer": {
        "model": "qwen3.8:latest",
        "system": (
            "You are a strict senior code reviewer. Find bugs, project-rule violations, and missing "
            "exception/edge cases in the reference code, and report them briefly, most severe first. "
            "If there are no problems, say so and nothing else. Do not speculate without evidence.\n\n"
            + PROJECT_RULES
        ),
        "strip_fences": False,
    },
}

FENCE_ONLY = re.compile(r"^\s*```[\w+-]*\n(.*?)\n```\s*$", re.DOTALL)


def _lock_byte(f, lock):
    """Lock (or unlock) byte 0 of the lock file without blocking. Raises OSError if it is held."""
    f.seek(0)
    if os.name == "nt":
        import msvcrt
        msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK if lock else msvcrt.LK_UNLCK, 1)
    else:
        import fcntl
        fcntl.flock(f, (fcntl.LOCK_EX | fcntl.LOCK_NB) if lock else fcntl.LOCK_UN)


@contextlib.contextmanager
def ollama_lock(role_name):
    """Allow one Ollama call at a time. The OS drops the lock if this process dies, so it never goes stale.

    Byte 0 is the lock; holder info is written after it so a blocked caller can read it.
    """
    f = os.fdopen(os.open(LOCK_PATH, os.O_RDWR | os.O_CREAT), "r+b")
    try:
        _lock_byte(f, True)
    except OSError:
        f.seek(1)
        holder = f.read().decode("utf-8", "replace").strip() or "unknown"
        f.close()
        sys.stderr.write(
            f"Another Ollama call is already running ({holder}). Calls must run one at a time: "
            f"wait for it to finish, then run this again. Do not delete {LOCK_PATH}.\n"
        )
        sys.exit(EXIT_BUSY)
    try:
        f.seek(1)
        f.truncate()
        f.write(f"pid={os.getpid()} role={role_name} started={time.strftime('%H:%M:%S')}".encode("utf-8"))
        f.flush()
        yield
    finally:
        _lock_byte(f, False)
        f.close()


def read_context(paths):
    chunks = []
    for path in paths:
        if path == "-":
            chunks.append(sys.stdin.read())
        else:
            with open(path, encoding="utf-8") as f:
                chunks.append(f"// {path}\n{f.read()}")
    return "\n\n".join(chunks)


def call_ollama(role_name, request, context):
    role = ROLES[role_name]
    prompt = f"Request:\n{request}\n\nReference code:\n{context or '(none)'}"
    payload = {
        "model": role["model"],
        "system": role["system"],
        "prompt": prompt,
        "stream": False,
        "keep_alive": KEEP_ALIVE,
    }
    req = urllib.request.Request(
        OLLAMA_URL,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_SEC) as response:
            result = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            sys.exit(f"Model '{role['model']}' not found. Run `ollama pull {role['model']}` and retry.")
        sys.exit(f"Ollama error {e.code}: {e.read().decode('utf-8', 'replace')}")
    except (urllib.error.URLError, TimeoutError) as e:
        sys.exit(f"Failed to call Ollama ({OLLAMA_URL}): {e}. Is the server running?")

    text = result.get("response", "")
    if role["strip_fences"]:
        m = FENCE_ONLY.match(text)
        if m:
            text = m.group(1)
    return text


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stdin.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("-r", "--role", required=True, choices=sorted(ROLES), help="role to delegate the task to")
    parser.add_argument("request", help="what to do")
    parser.add_argument("-c", "--code-file", action="append", default=[], metavar="PATH",
                        help="reference code file (repeatable; '-' reads stdin)")
    args = parser.parse_args()
    context = read_context(args.code_file)
    with ollama_lock(args.role):
        print(call_ollama(args.role, args.request, context))


if __name__ == "__main__":
    main()

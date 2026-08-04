;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.proc
  "One way to spawn a shell command that cannot block waiting to be asked
   something.

   Every LLM-supplied shell command in this component used to build its own
   `[\"/bin/sh\" \"-c\" command]` — five copies of the same three lines, in the
   `bash` tool, the `:bash` task executor, the coact shell primitive,
   `run-script-block`, and the skills runner. Each closed the child's stdin
   and each had the same hole, because the shape was copied rather than
   shared: closing stdin stops a command reading the PIPE, but not one that
   opens `/dev/tty` or spawns an askpass dialog.

   That difference is invisible until there is a terminal to block on.
   Headless — CI, a test runner, `by` under another agent — `/dev/tty` fails
   ENXIO and everything looks fine. Run the same command from `bb tui` and
   `git` asking for a password blocks until its timeout, with the prompt on
   the user's screen and `:output` empty, so the model cannot even tell why
   it stalled.

   Hence this namespace: the protection lives in ONE place, and a sixth
   spawn site is a two-line change that inherits it instead of a fourth copy
   of the bug. It deliberately depends on nothing, so any caller can use it.

   Two mechanisms, applied together:

     `sh-argv`         — run the command in its own SESSION, so it has no
                         controlling terminal and `/dev/tty` fails ENXIO.
     `harden-env!`     — make credential prompts FAIL rather than wait.")

(def ^:const new-session-script
  "Shell prologue that re-execs the command in a BRAND-NEW SESSION.

   The command arrives as `$1` — an argv element, never interpolated into
   this script — so no quoting in the user's command can break the wrapper.

   perl is tried BEFORE setsid, the reverse of the detach chain in the app's
   `spawn-detached-reduce!`, and the order is load-bearing: util-linux
   `setsid` FORKS when its caller is already a process-group leader, and a
   forked child would let the parent exit immediately — `.waitFor` would
   report success while the real work ran on, losing both the exit code and
   the output. `POSIX::setsid()` never forks, so the PID we wait on stays the
   PID that does the work.

   Unlike that detach path this one does NOT abort when setsid fails: a
   command that cannot get its own session should still run, just without the
   protection."
  (str "if command -v perl >/dev/null 2>&1; then "
       "exec perl -MPOSIX -e 'POSIX::setsid(); exec @ARGV' /bin/sh -c \"$1\"; "
       "elif command -v setsid >/dev/null 2>&1; then "
       "exec setsid /bin/sh -c \"$1\"; "
       "else exec /bin/sh -c \"$1\"; fi"))

(def non-interactive-env
  "Environment that makes credential prompts FAIL rather than wait.

   The new session removes the terminal, which stops anything reading
   /dev/tty. It does not stop the other way in: `git` and `ssh` will spawn an
   ASKPASS program, and a GUI one — the common case on a desktop — pops a
   dialog and blocks with no terminal involved at all.

   Two distinct mechanisms are at play and only one of them blocks:

     - `credential.helper` (osxkeychain, libsecret, a token script) answers
       git PROGRAMMATICALLY and never prompts. It is untouched, so silent
       keychain-backed auth keeps working exactly as before.
     - `GIT_ASKPASS` / `SSH_ASKPASS` spawn a program to ask a human. That is
       the one that hangs, so it is pointed at `false`.

   `false` rather than an absolute path because /usr/bin/false and /bin/false
   disagree across platforms; git resolves the name on PATH. It exits
   non-zero, so git reports \"unable to read askpass response\" and then, with
   the prompt disabled, \"could not read Username … terminal prompts
   disabled\" — a diagnosis instead of a hang. `SSH_ASKPASS_REQUIRE=never`
   (OpenSSH 8.4+) tells ssh not to reach for askpass at all.

   This overrides a user's own askpass, which is the point — but it is not a
   dead end. A command can set its own: `GIT_ASKPASS=/my/helper git fetch …`
   wins over what it inherits, so a genuinely non-interactive helper stays
   usable without a config knob for it."
  {"GIT_TERMINAL_PROMPT" "0"
   "GIT_ASKPASS"         "false"
   "SSH_ASKPASS"         "false"
   "SSH_ASKPASS_REQUIRE" "never"})

(defn sh-argv
  "Argv for running `command` under `/bin/sh` in its own session.

   Drop-in for `[\"/bin/sh\" \"-c\" command]`; hand it to ProcessBuilder the
   same way. `$0` is set to \"brainyard-sh\" so the wrapper is identifiable in
   a process listing."
  [command]
  ["/bin/sh" "-c" new-session-script "brainyard-sh" (str command)])

(defn harden-env!
  "Apply `non-interactive-env` to `pb`'s environment. Returns `pb`.

   Call BEFORE any caller-supplied env so an explicit override still wins —
   inherited env is a default, not a ceiling."
  ^ProcessBuilder [^ProcessBuilder pb]
  (let [env (.environment pb)]
    (doseq [[k v] non-interactive-env] (.put env ^String k ^String v)))
  pb)

(defn shell-pb
  "A ProcessBuilder for `command`, in its own session, with credential
   prompts disarmed and stdout/stderr merged.

   The one-call form for the common case. Callers needing more (a working
   directory, extra env) `doto` the result — `harden-env!` has already run,
   so their own env entries override it."
  ^ProcessBuilder [command]
  (-> (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String (sh-argv command)))
      (harden-env!)
      (doto (.redirectErrorStream true))))

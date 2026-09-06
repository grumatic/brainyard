;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.scripts
  "The script library — a tool surface that is a DIRECTORY, not a registry.

   Backs `script-agent` (docs/design/script-agent-design.md). A script is an
   ordinary executable file. The model writes one with a heredoc, `chmod +x`s
   it, and calls it by bare name from then on — this turn, later turns, later
   sessions — because every bash/python fence runs with the library's `bin/`
   dirs prepended to PATH.

   Three scopes, highest precedence first:

     project   <project>/.brainyard/scripts/{bin,lib}   the model writes here
     user      ~/.brainyard/scripts/{bin,lib}           hand-authored, cross-project
     builtin   ~/.brainyard/scripts/builtin/{bin,lib}   materialized from `builtin-scripts`

   Precedence is deliberate: copying a builtin into the project dir and editing
   it is the intended way to specialize one, so a project script SHADOWS a
   same-named user or builtin script. The index reports the shadow, because a
   silent one means the model cannot tell which of two files it just ran.

   Why builtins are materialized from strings rather than shipped as resources:
   a classpath resource needs native-image resource-config to survive into the
   binary, and a missing entry fails at runtime with an empty library and no
   error anyone would connect to the cause. A string in this namespace is
   compiled in, so it cannot go missing — and rewriting the file on a content
   change makes a binary upgrade refresh the pack for free.

   Nothing here is privileged. A library script is the same file `run-script-block`
   already writes to a temp path and runs; what the library adds is persistence
   and discoverability, not reach."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

;; ============================================================================
;; Builtin pack — kept to four on purpose
;; ============================================================================
;;
;; A large builtin pack is a tool roster wearing a different hat: prompt weight
;; for capabilities the model did not ask for and cannot easily audit. Each of
;; these earns its slot by covering something the model cannot write for itself
;; cheaply, or gets wrong by default. Everything else starts life as a project
;; script.

(def ^:private scripts-ls-body
  "#!/usr/bin/env bash
# name: scripts-ls
# desc: List every script in the library — scope, name, description, path.
# usage: scripts-ls [-l]
set -o pipefail

long=0
[ \"${1:-}\" = \"-l\" ] && long=1

seen=\" \"
IFS=':' read -r -a entries <<<\"${BY_SCRIPT_ROOTS:-}\"
for e in ${entries[@]+\"${entries[@]}\"}; do
  [ -n \"$e\" ] || continue
  scope=\"${e%%=*}\"
  root=\"${e#*=}\"
  bin=\"$root/bin\"
  [ -d \"$bin\" ] || continue
  for f in \"$bin\"/*; do
    [ -f \"$f\" ] || continue
    n=\"$(basename \"$f\")\"
    mark=\"\"
    case \"$seen\" in
      *\" $n \"*) mark=\"  (shadowed)\" ;;
      *) seen=\"$seen$n \" ;;
    esac
    d=\"$(sed -n 's/^# *desc: *//p' \"$f\" | head -1)\"
    [ -n \"$d\" ] || d=\"(undocumented)\"
    if [ \"$long\" = 1 ]; then
      printf '%-20s %-8s %s%s\\n%30s%s\\n' \"$n\" \"$scope\" \"$d\" \"$mark\" \"\" \"$f\"
    else
      printf '%-20s %-8s %s%s\\n' \"$n\" \"$scope\" \"$d\" \"$mark\"
    fi
  done
done
")

(def ^:private scripts-new-body
  "#!/usr/bin/env bash
# name: scripts-new
# desc: Create a project script with a header skeleton, already executable.
# usage: scripts-new <name> [--python] [--desc 'one line']
set -euo pipefail

name=\"\"
lang=bash
desc=\"\"
while [ $# -gt 0 ]; do
  case \"$1\" in
    --python|--py) lang=python; shift ;;
    --bash|--sh)   lang=bash;   shift ;;
    --desc)        desc=\"${2:-}\"; shift 2 ;;
    -*)            echo \"scripts-new: unknown option $1\" >&2; exit 2 ;;
    *)             name=\"$1\";  shift ;;
  esac
done

if [ -z \"$name\" ]; then
  echo \"usage: scripts-new <name> [--python] [--desc 'one line']\" >&2
  exit 2
fi

bin=\"${BY_SCRIPT_PROJECT_BIN:-}\"
if [ -z \"$bin\" ]; then
  echo \"scripts-new: BY_SCRIPT_PROJECT_BIN is unset (no project script dir)\" >&2
  exit 1
fi
mkdir -p \"$bin\"
target=\"$bin/$name\"
if [ -e \"$target\" ]; then
  echo \"scripts-new: $target already exists\" >&2
  exit 1
fi

if [ \"$lang\" = python ]; then
  cat >\"$target\" <<PYEOF
#!/usr/bin/env python3
# name: $name
# desc: ${desc:-TODO one-line description}
# usage: $name <args>
import sys


def main(argv):
    raise SystemExit(\"$name: not implemented\")


if __name__ == \"__main__\":
    main(sys.argv[1:])
PYEOF
else
  cat >\"$target\" <<SHEOF
#!/usr/bin/env bash
# name: $name
# desc: ${desc:-TODO one-line description}
# usage: $name <args>
set -euo pipefail

echo \"$name: not implemented\" >&2
exit 1
SHEOF
fi

chmod +x \"$target\"
echo \"$target\"
")

(def ^:private scripts-doctor-body
  "#!/usr/bin/env bash
# name: scripts-doctor
# desc: Syntax-check every script; report missing headers, shebangs and exec bits.
# usage: scripts-doctor
set -o pipefail

rc=0
IFS=':' read -r -a entries <<<\"${BY_SCRIPT_ROOTS:-}\"
for e in ${entries[@]+\"${entries[@]}\"}; do
  [ -n \"$e\" ] || continue
  scope=\"${e%%=*}\"
  root=\"${e#*=}\"
  bin=\"$root/bin\"
  [ -d \"$bin\" ] || continue
  for f in \"$bin\"/*; do
    [ -f \"$f\" ] || continue
    probs=\"\"
    [ -x \"$f\" ] || probs=\"$probs not-executable;\"
    head -1 \"$f\" | grep -q '^#!' || probs=\"$probs no-shebang;\"
    grep -q '^# *desc:' \"$f\" || probs=\"$probs no-desc-header;\"
    # A `# name:` that disagrees with the filename is a copy someone forgot to
    # edit. PATH resolves the FILENAME, so the header is the half that is wrong.
    declared=\"$(sed -n 's/^# *name: *//p' \"$f\" | head -1)\"
    if [ -n \"$declared\" ] && [ \"$declared\" != \"$(basename \"$f\")\" ]; then
      probs=\"$probs name-header-says-'$declared';\"
    fi
    if head -1 \"$f\" | grep -qi python; then
      python3 -c 'import ast,sys; ast.parse(open(sys.argv[1]).read())' \"$f\" 2>/dev/null \\
        || probs=\"$probs syntax-error;\"
    else
      bash -n \"$f\" 2>/dev/null || probs=\"$probs syntax-error;\"
    fi
    if [ -n \"$probs\" ]; then
      echo \"FAIL  $scope  $f  $probs\"
      rc=1
    else
      echo \"ok    $scope  $f\"
    fi
  done
done
exit $rc
")

(def ^:private fetch-body
  "#!/usr/bin/env bash
# name: fetch
# desc: HTTP GET a URL with a timeout and a size cap; body to stdout, status to stderr.
# usage: fetch <url> [--max-bytes N] [--timeout SECS] [--header 'K: V']...
set -uo pipefail

url=\"\"
max_bytes=2000000
timeout=60
headers=()
while [ $# -gt 0 ]; do
  case \"$1\" in
    --max-bytes) max_bytes=\"${2:-}\"; shift 2 ;;
    --timeout)   timeout=\"${2:-}\";   shift 2 ;;
    --header|-H) headers+=(-H \"${2:-}\"); shift 2 ;;
    -*)          echo \"fetch: unknown option $1\" >&2; exit 2 ;;
    *)           url=\"$1\"; shift ;;
  esac
done

if [ -z \"$url\" ]; then
  echo \"usage: fetch <url> [--max-bytes N] [--timeout SECS] [--header 'K: V']...\" >&2
  exit 2
fi

body=\"$(mktemp)\"
trap 'rm -f \"$body\"' EXIT
code=\"$(curl --silent --show-error --location \\
             --max-time \"$timeout\" --max-filesize \"$max_bytes\" \\
             ${headers[@]+\"${headers[@]}\"} \\
             --output \"$body\" --write-out '%{http_code}' -- \"$url\")\"
curl_rc=$?
cat \"$body\"
echo \"fetch: HTTP ${code:-?} $url\" >&2
[ \"$curl_rc\" -ne 0 ] && exit \"$curl_rc\"
case \"${code:-0}\" in
  2*) exit 0 ;;
  *)  exit 22 ;;
esac
")

(def ^:private by-tool-body
  "#!/usr/bin/env python3
# name: by-tool
# desc: Call a brainyard tool (memory recall, task inspection). QUOTE the name: by-tool 'memory$recall'
# usage: by-tool '<tool-name>' [--key value]...      QUOTE the name: $ is a
#        bash sigil, so by-tool memory$status sends \"memory\".
#
# Python rather than bash because the transport is an AF_UNIX socket: `nc -U`
# is not portable (busybox has no -U), while python3 is already required by
# this agent's own `python` fence. Composing the request here also keeps the
# quoting in one language instead of sed-escaping model-authored strings.
import os
import socket
import sys


def edn_str(s):
    return '\"' + s.replace(\"\\\\\", \"\\\\\\\\\").replace('\"', '\\\\\"') + '\"'


def main(argv):
    sock = os.environ.get(\"BY_TOOL_SOCK\")
    if not sock:
        sys.stderr.write(
            \"by-tool: the script bridge is off for this agent.\\n\"
            \"         Enable it with :enable-script-bridge \"
            \"(BY_ENABLE_SCRIPT_BRIDGE=true).\\n\")
        return 3
    if not argv or argv[0] in (\"-h\", \"--help\"):
        sys.stderr.write(
            \"usage: by-tool '<tool-name>' [--key value]...\\n\"
            \"  QUOTE the tool name — $ is a variable sigil in bash:\\n\"
            \"  by-tool 'memory$recall' --query 'prompt cache zones'\\n\"
            \"  by-tool 'task$detail' --task-id t-17 --last-n 40\\n\")
        return 2

    tool, args = argv[0], argv[1:]
    req = \"{:op :tool :tool %s :argv [%s]}\" % (
        edn_str(tool), \" \".join(edn_str(a) for a in args))

    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(float(os.environ.get(\"BY_TOOL_TIMEOUT\", \"120\")))
    try:
        s.connect(sock)
    except OSError as e:
        sys.stderr.write(\"by-tool: cannot reach the agent at %s: %s\\n\" % (sock, e))
        return 4

    try:
        s.sendall((req + \"\\n\").encode(\"utf-8\"))
        buf = b\"\"
        while b\"\\n\" not in buf:
            chunk = s.recv(65536)
            if not chunk:
                break
            buf += chunk
    except OSError as e:
        sys.stderr.write(\"by-tool: %s\\n\" % e)
        return 4
    finally:
        s.close()

    line = buf.split(b\"\\n\", 1)[0].decode(\"utf-8\", \"replace\")
    if not line:
        sys.stderr.write(\"by-tool: no response from the agent\\n\")
        return 4
    print(line)
    return 1 if line.startswith(\"{:status :error\") else 0


if __name__ == \"__main__\":
    sys.exit(main(sys.argv[1:]))
")

(def bridge-scripts
  "Materialized ONLY when `:enable-script-bridge` is on, and PRUNED from the
   builtin scope when it is off. Shipping `by-tool` unconditionally would put a
   capability in every library index that answers \"the bridge is off\" — an
   advertisement for a door that is not there — and materializing without
   pruning would leave exactly that behind the first time someone tried the
   bridge and turned it off again."
  {"by-tool" by-tool-body})

(def builtin-scripts
  "name → source. Materialized into the builtin scope's `bin/` on first use and
   rewritten whenever the content differs, so a binary upgrade refreshes them."
  {"scripts-ls"     scripts-ls-body
   "scripts-new"    scripts-new-body
   "scripts-doctor" scripts-doctor-body
   "fetch"          fetch-body})

;; ============================================================================
;; Roots
;; ============================================================================

(defn- home-scripts-root []
  (str (io/file (System/getProperty "user.home") ".brainyard" "scripts")))

(defn script-roots
  "Ordered `[{:scope :root :bin :lib}]`, HIGHEST precedence first.

   `:script-lib-dirs` (a vector of root paths) replaces the derived
   project/user pair when set; the builtin scope is always appended last, so a
   custom list can add roots but cannot delete the pack `scripts-ls` needs to
   describe itself.

   Returns roots whether or not they exist — `list-scripts` skips absent ones
   and `env-prologue` filters them, but PATH composition wants the project dir
   present from the first turn so a freshly written script resolves without a
   re-init."
  [agent]
  (let [custom  (seq (config/get-config agent :script-lib-dirs))
        derived (if custom
                  (mapv (fn [d] [:custom (str d)]) custom)
                  [[:project (str (io/file (config/project-dir) ".brainyard" "scripts"))]
                   [:user    (home-scripts-root)]])
        all     (conj (vec derived)
                      [:builtin (str (io/file (home-scripts-root) "builtin"))])]
    (into []
          (comp (map (fn [[scope root]]
                       {:scope scope
                        :root  root
                        :bin   (str (io/file root "bin"))
                        :lib   (str (io/file root "lib"))}))
                ;; A custom list that names the same root twice would put the
                ;; dir on PATH twice and double every entry in the index.
                (distinct))
          all)))

(defn project-bin
  "The bin dir new scripts are written to — the first non-builtin root. nil
   when the roots resolve to builtin only."
  [roots]
  (:bin (first (remove #(= :builtin (:scope %)) roots))))

(defn- ensure-dir! [^String path]
  (try (.mkdirs (io/file path)) (catch Exception _ nil)))

(defonce ^:private !materialized
  ;; Per-process guard: the pack is compiled in, so re-checking it every turn
  ;; buys nothing but four stat+read pairs.
  (atom #{}))

(defn materialize-builtins!
  "Write `pack` (default `builtin-scripts`) into the builtin scope's `bin/`,
   creating or rewriting only files whose content differs, and marking them
   executable. Never throws — a library that cannot be written degrades to a
   smaller library.

   The once-per-process guard keys on the dir AND the pack's names, so turning
   the script bridge on mid-process materializes `by-tool` instead of being
   skipped by a guard that only remembers the directory."
  ([bin-dir] (materialize-builtins! bin-dir builtin-scripts))
  ([bin-dir pack]
   (let [k [bin-dir (set (keys pack))]]
     (when (and bin-dir (not (contains? @!materialized k)))
       (swap! !materialized conj k)
       (try
         (ensure-dir! bin-dir)
         (doseq [[nm body] pack]
           (let [^File f (io/file bin-dir nm)
                 cur (when (.exists f) (try (slurp f) (catch Exception _ nil)))]
             (when (not= cur body)
               (spit f body))
             (when-not (.canExecute f)
               (.setExecutable f true true))))
         (catch Exception e
           (mulog/warn ::materialize-builtins-failed :dir bin-dir :error (ex-message e))))))))

(defn ensure-roots!
  "Create the project/user `bin` and `lib` dirs and materialize the builtin
   pack. Called once per turn from init; cheap and idempotent.

   `:bridge?` adds `bridge-scripts` (the `by-tool` shim) to the pack."
  ([roots] (ensure-roots! roots {}))
  ([roots {:keys [bridge?]}]
   (doseq [{:keys [scope bin lib]} roots]
     (when-not (= :builtin scope)
       (ensure-dir! bin)
       (ensure-dir! lib)))
   (when-let [bin (some (fn [r] (when (= :builtin (:scope r)) (:bin r))) roots)]
     (materialize-builtins! bin (cond-> builtin-scripts
                                  bridge? (merge bridge-scripts)))
     ;; Materializing never removes, so turning the bridge back OFF would leave
     ;; `by-tool` on PATH and in the index — an advertisement for a door that
     ;; is not there, answering "the bridge is off" to anything that tried it.
     ;; The BUILTIN scope is ours to manage (we write and rewrite it), so
     ;; pruning what we no longer ship is the same authority. A user copy in
     ;; project or user scope is untouched, and would shadow this one anyway.
     (when-not bridge?
       (doseq [nm (keys bridge-scripts)]
         (try (.delete (io/file bin nm)) (catch Exception _ nil)))))
   roots))

;; ============================================================================
;; Index
;; ============================================================================

(def ^:private header-scan-lines
  "Lines of a script read looking for its header. A header below this is a
   header nobody writes; reading the whole file for every script on every turn
   is the cost this bounds."
  40)

(defn- parse-header
  "Pull `# name:` / `# desc:` / `# usage:` off the head of `f`. Returns a map
   with whatever it found; a script with no header is not an error.

   Scoped to the CONTIGUOUS comment block after the shebang, and first-wins
   within it. Scanning the whole head and letting the last match win is the
   obvious implementation and it is wrong: `scripts-new` contains
   `# name: $name` inside the heredoc it writes, so a whole-head scan indexed
   the generator itself as a script literally named `$name`. Any script that
   emits another script has the same shape."
  [^File f]
  (try
    (with-open [r (io/reader f)]
      (let [lines (vec (take header-scan-lines (line-seq r)))
            body  (cond-> lines
                    (str/starts-with? (or (first lines) "") "#!") (subvec 1))]
        (reduce (fn [acc line]
                  (if-let [[_ k v] (re-matches #"^#\s*(name|desc|usage):\s*(.*?)\s*$" line)]
                    (let [k (keyword k)]
                      (if (contains? acc k) acc (assoc acc k v)))
                    acc))
                {}
                (take-while #(str/starts-with? (str/trim %) "#") body))))
    (catch Exception _ {})))

(defn- script-name
  "Library name for `f`: the FILENAME, extension included. The `# name:`
   header is documentation, never the name.

   PATH resolves the filename, so anything else is a promise the shell will not
   keep — in both directions. Not the stem: the file on PATH is `pdf-pages.py`,
   so listing it as `pdf-pages` advertises an invocation that fails. And not
   the header either, which is the subtler half and was wrong here first:
   copying `clj-count` to `fetch` without editing its header made the index
   show a SECOND `clj-count` (shadowing the first) while the project `fetch` —
   the file that actually shadows the builtin on PATH — vanished from the
   listing entirely. A header that disagrees with its filename is a mistake to
   report, which `scripts-doctor` now does, not a name to honour."
  [^File f _header]
  (.getName f))

(defn list-scripts
  "Every executable in the library, highest-precedence first, with
   `:shadowed? true` on an entry a higher-precedence root already claimed.

   A shadowed entry is KEPT rather than dropped: the model needs to see that
   the builtin it remembers is being overridden, and by what."
  [roots]
  (let [seen (volatile! #{})]
    (into []
          (comp
           (mapcat (fn [{:keys [scope bin]}]
                     (let [d (io/file bin)]
                       (when (.isDirectory d)
                         (->> (.listFiles d)
                              (filter #(and (.isFile ^File %) (.canExecute ^File %)))
                              (sort-by #(.getName ^File %))
                              (map (fn [^File f]
                                     (let [h (parse-header f)
                                           n (script-name f h)]
                                       {:name  n
                                        :scope scope
                                        :path  (.getPath f)
                                        :desc  (not-empty (:desc h))
                                        :usage (not-empty (:usage h))}))))))))
           (map (fn [{:keys [name] :as e}]
                  (let [dup? (contains? @seen name)]
                    (vswap! seen conj name)
                    (assoc e :shadowed? dup?)))))
          roots)))

(defn format-scripts-brief
  "The names-only rendering, for an agent that has a tool REGISTRY and could
   otherwise re-derive work the library already holds.

   Why a second rendering rather than the same one: the full section teaches
   authoring — the heredoc skeleton, the `# desc:` contract, when to save —
   and none of that is a registry agent's job. What it needs is one fact, that
   these exist, so it can run one or hand the work to script-agent instead of
   re-deriving it from scratch. That fact is a list of names.

   Builtins are deliberately EXCLUDED. They are script-agent's own furniture
   (`scripts-new`, `scripts-doctor`), so listing them here is noise a router
   will never act on — and, since the pack is materialized on first use, it
   would also make every repo look like it has a library before anyone has
   written a script. Only what a human or an agent actually saved counts.

   Returns nil when nothing qualifies, which is the common case and costs
   exactly nothing until someone saves their first script."
  [entries limit]
  (let [named (->> entries
                   (remove :shadowed?)
                   (remove #(= :builtin (:scope %))))]
    (when (seq named)
      (let [limit  (or limit 60)
            shown  (take limit named)
            hidden (max 0 (- (count named) limit))]
        (str "## Scripts (already saved, in .brainyard/scripts/bin)\n"
             (str/join " · " (map :name shown))
             (when (pos? hidden) (str " …+" hidden " more"))
             "\n"
             "Run one directly — `bash .brainyard/scripts/bin/<name>` — or `cat` it to see "
             "what it does. Before writing a shell pipeline, check whether one of these "
             "already is it. script-agent owns adding to the set.")))))

(defn format-scripts-section
  "The `## Scripts` system-prompt section: one line per script, bounded by
   `limit`.

   One line per tool is the whole budget argument for this design — the
   registry's `### Agent Tools` block spends a description and a parameter list
   each. Drill-in is `cat $(which <name>)`, which costs nothing until wanted
   and returns the complete truth rather than a rendering of it.

   Returns nil when the library is empty AND there is nowhere to write one —
   there is no point teaching a directory convention for a directory that
   cannot exist."
  [entries limit project-bin-dir]
  (when (or (seq entries) project-bin-dir)
    (let [;; Shown relative to project-dir when it lives there, because that IS
          ;; the cwd of every block — an absolute path here is twice the width
          ;; and no more usable.
          project-bin-dir (when project-bin-dir
                            (let [pd (str (config/project-dir))]
                              (if (and (seq pd) (str/starts-with? project-bin-dir (str pd "/")))
                                (subs project-bin-dir (inc (count pd)))
                                project-bin-dir)))
          limit   (or limit 60)
          shown   (take limit entries)
          hidden  (max 0 (- (count entries) limit))
          width   (->> shown (map (comp count :name)) (reduce max 0) (max 4) (min 28))
          line    (fn [{:keys [name desc scope shadowed?]}]
                    (str (format (str "%-" width "s") name)
                         "  — " (or desc "(undocumented)")
                         (when shadowed?
                           (str "  (" (clojure.core/name scope)
                                " copy — shadowed by the higher-precedence one above))"))))]
      (str "## Scripts — your reusable tools\n"
           "Every script below is on PATH for every bash/python block. Call it by "
           "bare name.\n"
           (if (seq shown)
             (str "\n" (str/join "\n" (map line shown)) "\n"
                  (when (pos? hidden)
                    (str "…and " hidden " more — run `scripts-ls` for the full list.\n")))
             "\nThe library is empty. The first script you save appears here next turn.\n")
           "\n### Reading one\n"
           "`cat $(which <name>)` — the source IS the contract. `<name> --help` "
           "when it has one.\n"
           (when project-bin-dir
             (str "\n### Writing one\n"
                  "Inline code is for one-off work. When you find yourself typing the "
                  "same pipeline a SECOND time, the third time save it:\n"
                  "```bash\n"
                  "cat > " project-bin-dir "/<name> <<'EOF'\n"
                  "#!/usr/bin/env bash\n"
                  "# name: <name>\n"
                  "# desc: <one line — this is what appears in the list above>\n"
                  "# usage: <name> <args>\n"
                  "set -euo pipefail\n"
                  "...\n"
                  "EOF\n"
                  "chmod +x " project-bin-dir "/<name>\n"
                  "```\n"
                  "`scripts-new <name> [--python]` writes that skeleton for you. "
                  "It is on PATH from the very next block — no reload.\n"
                  "Before writing anything non-trivial, check the list above: you may "
                  "already have it.\n"))))))

;; ============================================================================
;; Environment composition
;; ============================================================================

(defn- sh-dq
  "Escape `s` for inclusion inside a double-quoted shell word. `$` and backtick
   are escaped along with `\"` and `\\` — a directory path is data, and a path
   containing `$(...)` must not become a command substitution."
  [s]
  (str/replace (str s) #"([\"$`\\])" "\\\\$1"))

(defn env-prologue
  "Shell assignments prefixed onto the command that runs a fenced block:

     PATH=\"<bins>:$PATH\" PYTHONPATH=\"<libs>${PYTHONPATH:+:$PYTHONPATH}\" \\
     BY_SCRIPT_ROOTS=\"scope=root:...\" BY_SCRIPT_PROJECT_BIN=\"...\"

   Prefixing the COMMAND STRING rather than threading an env map through the
   three spawn sites (`local-exec-shell`, the fast-eval ProcessBuilder, and the
   `:bash` task executor) is deliberate: all three run the string through
   `/bin/sh -c`, so one change covers them and none of the process plumbing
   grows a new parameter to keep in sync.

   Returns \"\" when there is nothing to add, so the command is byte-identical
   to what it was before this existed.

   Note the PATH entries are PREPENDED: a library script named `find` shadows
   the system one for the block. That is the same rule as the library's own
   precedence, and it is the only way `scripts-new` can create a working
   override."
  ([roots] (env-prologue roots nil))
  ([roots tool-sock]
  (let [dir?     (fn [p] (.isDirectory ^File (io/file ^String p)))
        existing (fn [k] (into [] (comp (map k) (filter dir?)) roots))
        bins     (existing :bin)
        libs     (existing :lib)
        pairs    (->> roots
                      (filter #(dir? (:bin %)))
                      (map #(str (name (:scope %)) "=" (:root %))))
        proj-bin (project-bin roots)]
    (if (empty? bins)
      ""
      (str "PATH=\"" (str/join ":" (map sh-dq bins)) ":$PATH\" "
           (when (seq libs)
             (str "PYTHONPATH=\"" (str/join ":" (map sh-dq libs))
                  "${PYTHONPATH:+:$PYTHONPATH}\" "))
           "BY_SCRIPT_ROOTS=\"" (str/join ":" (map sh-dq pairs)) "\" "
           (when proj-bin
             (str "BY_SCRIPT_PROJECT_BIN=\"" (sh-dq proj-bin) "\" "))
           ;; The script bridge's socket, when this agent has one. Absent means
           ;; `by-tool` says the bridge is off rather than hanging on a path
           ;; nothing is listening at.
           (when (seq tool-sock)
             (str "BY_TOOL_SOCK=\"" (sh-dq tool-sock) "\" ")))))))

(def ^:private interpreters
  "Tokens that take the real command as their next argument."
  #{"bash" "sh" "zsh" "python" "python3" "env" "exec" "time" "nohup" "sudo"})

(defn- command-heads
  "The tokens in `line` that sit in COMMAND position: the first word of each
   pipeline/list segment, plus whatever follows an interpreter.

   Splitting on shell separators rather than scanning every token is what keeps
   this from counting a name that merely appears as an argument or inside a
   message — `echo clj-count` is not an invocation of `clj-count`."
  [line]
  (let [segs (str/split line #"\||;|&&|\|\||\$\(|`|\bthen\b|\bdo\b|\belse\b")]
    (mapcat (fn [seg]
              (let [ws (remove str/blank? (str/split (str/trim seg) #"\s+"))
                    ;; skip leading VAR=value assignments, then the head; and
                    ;; if the head is an interpreter, the next token too.
                    ws (drop-while #(re-matches #"[A-Za-z_][A-Za-z_0-9]*=.*" %) ws)]
                (when-let [h (first ws)]
                  (if (interpreters (last (str/split h #"/")))
                    [h (second ws)]
                    [h]))))
            segs)))

(defn resolve-invoked
  "Which library scripts (if any) `code` invokes, for the `::script-block`
   event.

   Matches tokens in COMMAND position against the index, by bare name (the
   `:full` case, where the library is on PATH) or by the basename of a path
   (the `:brief` case, where an agent runs `bash .brainyard/scripts/bin/foo`).
   Both forms have to be covered or the measurement answers only for the agent
   that needed it least.

   It is a heuristic and says so — a name built at runtime from a variable is
   invisible to it. The question it exists to answer is whether the library is
   REUSED or re-typed, and that tolerates a miss far better than it tolerates
   instrumenting the shell to find out."
  [code entries]
  (let [names (into #{} (map :name) entries)]
    (->> (str/split-lines (or code ""))
         (map str/trim)
         (remove #(or (str/blank? %) (str/starts-with? % "#")))
         (mapcat command-heads)
         (keep (fn [tok]
                 (when tok
                   ;; A command head can carry trailing shell punctuation that
                   ;; belongs to the surrounding syntax, not the name —
                   ;; `$(design-docs-over-1000)` leaves the closing paren on
                   ;; the token.
                   (let [tok  (str/replace tok #"[)\];&\"']+$" "")
                         base (last (str/split tok #"/"))]
                     (cond (contains? names tok)  tok
                           (contains? names base) base)))))
         distinct
         vec)))

;; ============================================================================
;; Reuse statistics — reading the ::script-block stream back
;; ============================================================================

(def ^:private script-block-marker
  "The substring that identifies a `::script-block` event in the raw log.

   The reader pre-filters on this rather than parsing every event: the app log
   runs to tens of megabytes per rotation, and parsing all of it to find a few
   hundred events would make `by scripts reuse` cost seconds for an answer that
   is three integers."
  "coact-agent/script-block")

(defn- read-event
  "Parse one mulog EDN block, or nil. `:default` swallows tagged literals —
   the log carries `#mulog/flake \"…\"`, which has no reader here and is not
   information this needs."
  [^String block]
  (try (edn/read-string {:default (fn [_tag v] v)} block)
       (catch Exception _ nil)))

(defn script-block-events-from-log
  "Every `::script-block` event in `paths`, oldest first.

   Streams line by line, accumulating a blank-line-delimited block and parsing
   only the ones that mention the marker. A missing or unreadable file is
   skipped rather than fatal: log rotation means some of the paths a caller
   offers routinely do not exist."
  [paths]
  (into []
        (mapcat
         (fn [path]
           (let [f (io/file path)]
             (when (and (.isFile f) (.canRead f))
               (with-open [r (io/reader f)]
                 (loop [lines (line-seq r), buf (StringBuilder.), out (transient [])]
                   (if-let [l (first lines)]
                     (if (str/blank? l)
                       (let [b (str buf)]
                         (recur (rest lines) (StringBuilder.)
                                (if (str/includes? b script-block-marker)
                                  (if-let [e (read-event b)] (conj! out e) out)
                                  out)))
                       (recur (rest lines) (.append buf (str l "\n")) out))
                     (let [b (str buf)]
                       (persistent!
                        (if (and (str/includes? b script-block-marker) (read-event b))
                          (conj! out (read-event b))
                          out)))))))))
         paths)))

(defn reuse-stats
  "Fold `::script-block` events into the one number this facility exists to
   produce, plus the two breakdowns that say what to do about it.

   `:rate` is reuses over script BLOCKS, not over invocations — a block that
   called two library scripts is one act of reuse, and counting invocations
   would let a single chatty block flatter the number.

   `:by-name` answers which scripts are worth keeping; `:by-scope` answers
   whether the shipped builtin pack earns its slots. `:failed` is separate from
   `:reused` on purpose: reaching for a script that then breaks is a different
   problem from never reaching for one, and averaging them hides both."
  [events]
  (let [blocks (count events)
        reused (count (filter :reused? events))]
    {:blocks   blocks
     :reused   reused
     :rate     (if (pos? blocks) (double (/ reused blocks)) 0.0)
     :failed   (count (filter :failed? events))
     :by-name  (->> events (mapcat :invoked) frequencies
                    (sort-by (juxt (comp - val) key)) vec)
     :by-scope (->> events (mapcat :scopes) (remove nil?) frequencies
                    (sort-by (comp - val)) vec)
     :agents   (->> events (keep :agent-id) frequencies
                    (sort-by (comp - val)) vec)}))


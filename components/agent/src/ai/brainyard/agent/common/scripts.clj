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
  "Write `builtin-scripts` into the builtin scope's `bin/`, creating or
   rewriting only files whose content differs, and marking them executable.
   Idempotent, once per process per dir. Never throws — a library that cannot
   be written degrades to a smaller library."
  [bin-dir]
  (when (and bin-dir (not (contains? @!materialized bin-dir)))
    (swap! !materialized conj bin-dir)
    (try
      (ensure-dir! bin-dir)
      (doseq [[nm body] builtin-scripts]
        (let [^File f (io/file bin-dir nm)
              cur (when (.exists f) (try (slurp f) (catch Exception _ nil)))]
          (when (not= cur body)
            (spit f body))
          (when-not (.canExecute f)
            (.setExecutable f true true))))
      (catch Exception e
        (mulog/warn ::materialize-builtins-failed :dir bin-dir :error (ex-message e))))))

(defn ensure-roots!
  "Create the project/user `bin` and `lib` dirs and materialize the builtin
   pack. Called once per turn from init; cheap and idempotent."
  [roots]
  (doseq [{:keys [scope bin lib]} roots]
    (when-not (= :builtin scope)
      (ensure-dir! bin)
      (ensure-dir! lib)))
  (some-> (some (fn [r] (when (= :builtin (:scope r)) (:bin r))) roots)
          (materialize-builtins!))
  roots)

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
  "Library name for `f`: the `# name:` header if present, else the FILENAME —
   extension included.

   Deliberately not the filename with `.py`/`.sh` stripped, tempting as that
   reads. The file on PATH is `pdf-pages.py`, so that is what the shell
   resolves; a display name of `pdf-pages` would advertise an invocation that
   fails. A script that wants the short name should be saved without the
   extension, which is what `scripts-new` does."
  [^File f header]
  (or (not-empty (:name header)) (.getName f)))

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
  [roots]
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
             (str "BY_SCRIPT_PROJECT_BIN=\"" (sh-dq proj-bin) "\" "))))))

(defn resolve-invoked
  "Best-effort: which library script (if any) a bash block invoked, for the
   `::script-invoked` event.

   Matches the first bare word of each non-comment line against the index. It
   is a heuristic and says so — the question it answers is whether the library
   is being REUSED or re-typed, and that question tolerates a miss far better
   than it tolerates the cost of instrumenting the shell."
  [code entries]
  (let [names (into #{} (map :name) entries)]
    (->> (str/split-lines (or code ""))
         (map str/trim)
         (remove #(or (str/blank? %) (str/starts-with? % "#")))
         (keep #(let [w (first (str/split % #"\s+"))]
                  (when (contains? names w) w)))
         distinct
         vec)))

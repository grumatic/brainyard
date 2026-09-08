;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.util.core.env
  "The one place `by` answers \"what is the value of environment variable K?\".

   `docs/design/environment-scoping-design.md` §3.1 (Phase 0).

   Two facts make this a function rather than a lookup, and both are load-bearing.

   **The JVM environment is immutable**, so `.env` cannot be loaded into it.
   `dotenv.clj` calls `System/setProperty` instead, and the property table is
   therefore a second, equally real source of environment values. A reader that
   consults only `System/getenv` cannot see anything a user put in `.env` on the
   path where the shell wrapper is not involved — `bb tui`, `by-bin` run
   directly, `BY_JAR=1` without the wrapper.

   **Blank counts as unset.** An exported-but-empty `ANTHROPIC_API_KEY=` is a
   common shell accident, and letting it resolve masks the credential that
   should have been used next in the chain — the reasoning
   `clj-llm`'s copy of this already carried. Every caller that wanted a value
   was already blank-checking, at the helper or at the call site; the two that
   were not (`env-truthy?`/`env-int` in the app, `valid-dir-canonical` for
   `BY_WORKING_DIR`) reject a blank downstream anyway, so making it the default
   changes no behaviour and removes a step everyone had to remember.

   The one caller that must NOT collapse blank is MCP's `${VAR}` interpolation,
   which has to tell \"unset\" (leave the literal `${VAR}` in place and warn)
   from \"set to empty\" (substitute nothing). It passes
   `{:blank-as-unset? false}`, and being the exception is why that is an option
   rather than a second function nobody would know to reach for.

   **A blank environment variable therefore FALLS THROUGH to the property
   rather than shadowing it**, and this is the one deliberate behaviour change
   in the consolidation. Every private copy read `(or (getenv k) (getProperty
   k))` and blank-checked the RESULT, so `export GH_TOKEN=` in a shell profile
   silently defeated the `GH_TOKEN` in `.env` — forever, with nothing anywhere
   saying why. That is the same accident blank-as-unset exists to forgive, one
   link earlier in the chain; forgiving it at one link and not the other was an
   artifact of where the check happened to sit, not a decision.

   **Precedence is env over property, and that is not arbitrary**: both `.env`
   loaders already refuse to override a real environment variable
   (`by-wrapper.sh` tests `${!key+x}`, `dotenv.clj` tests `System/getenv`), so
   reading the property first would invert a contract two loaders implement and
   the docs state.

   This lives in `util` because it has no dependencies of its own and every
   layer needs it — the app, the base, and four components each grew a private
   copy of these five lines before it existed."
  (:require [clojure.string :as str]
            [ai.brainyard.util.core.glob :as glob]))

(defn- not-blank [v] (when-not (str/blank? v) v))

(defn- var-name
  "`k` as a variable name string, or nil. A keyword/symbol is used by NAME, so
   `:PATH` and `\"PATH\"` name the same variable and `(str :PATH)` never becomes
   a variable literally called `\":PATH\"` — the bug `mcp/client.clj`'s
   `env-var-name` was written to prevent."
  [k]
  (not-empty (cond
               (nil? k)                       nil
               (or (keyword? k) (symbol? k))  (name k)
               :else                          (str k))))

(defn resolve-var
  "Value of environment variable `k`, or nil.

   Checks the process environment first, then the JVM system-property table
   (where `.env` values live — see the namespace docstring). `k` may be a
   string, keyword or symbol (see `var-name`).

   Options:
     `:blank-as-unset?` (default true) — a value that is empty or all
       whitespace resolves to nil. Pass false only when the caller must
       distinguish a variable set to the empty string from one that is unset."
  ([k] (resolve-var k nil))
  ([k {:keys [blank-as-unset?] :or {blank-as-unset? true}}]
   (when-some [n (var-name k)]
     (if blank-as-unset?
       (or (not-blank (System/getenv n)) (not-blank (System/getProperty n)))
       (or (System/getenv n) (System/getProperty n))))))

(defonce ^:private !dotenv-keys
  ;; Variable names a `.env` file supplied, registered by the loader.
  ;;
  ;; `child-env` needs to know WHICH properties are environment values. The JVM
  ;; property table also holds ~60 standard entries (java.version, user.dir,
  ;; os.arch, …) plus anything any library set, and shipping those into a child
  ;; process as environment variables would be wrong and noisy. Only the loader
  ;; knows which keys came from a `.env`, so it says so.
  (atom #{}))

(defn register-dotenv-keys!
  "Record the variable names `.env` supplied. Called once by the loader after
   it writes them to the property table. Idempotent; last call wins.

   Injected rather than discovered, the same shape as `persist/set-root!` and
   `set-catalog-cache-root!` — the loader lives in the app project and this
   namespace sits below every component, so the knowledge has to travel down."
  [ks]
  (reset! !dotenv-keys (into #{} (keep #(some-> % name not-empty)) ks))
  nil)

(defn dotenv-keys
  "The registered `.env` variable names. Empty before the loader runs (a
   `bb` task, a test, a JVM launched by hand), which is correct: nothing was
   loaded, so nothing is missing from a child."
  []
  @!dotenv-keys)

(defn child-env
  "The `{name value}` map a spawned child needs in order to see what this
   process sees.

   A `ProcessBuilder` child inherits the real ENVIRONMENT and nothing else, so
   the one layer it is missing is exactly the one `.env` supplied — which lives
   in the property table because the JVM environment cannot be written to. That
   is why a `.env` `GH_TOKEN` reached `clj-llm` but never reached `gh`.

   Deliberately NOT the whole property table (see `!dotenv-keys`), and
   deliberately skipping any key the real environment already carries a
   NON-BLANK value for: the child inherits that value already, and `.env` never
   overrides a real variable in this process either — writing one here would
   invert, for children only, a precedence rule both loaders implement.

   The word non-blank is load-bearing, and was measured rather than reasoned:
   a `(some? (System/getenv k))` guard treats an exported-but-empty variable as
   `the child has it`, so the child receives the blank while THIS process, whose
   `resolve-var` falls through to the property, uses the real value. Parent and
   child then disagree about the same variable — the one thing this function
   exists to prevent. It is the identical shadowing bug `resolve-var` fixes one
   layer up, and it is not hypothetical: agent and CI shells commonly export
   `GIT_ASKPASS=` and `SSH_ASKPASS=` empty, which is how the proc suite caught it.

   Blank counts as unset on the property side too, so a `FOO=` line does not
   export an empty `FOO` into every subprocess."
  []
  (persistent!
   (reduce (fn [m k]
             (if (not-blank (System/getenv k))
               m
               (if-let [v (not-blank (System/getProperty k))]
                 (assoc! m k v)
                 m)))
           (transient {})
           @!dotenv-keys)))

(defn resolve-first
  "The first of `ks` that resolves, as `[k value]`, or nil.

   Returns the PAIR rather than the value because every caller needs to say
   WHICH variable supplied the credential: the provider tables carry
   alternates (`ANTHROPIC_API_KEY` then `ANTHROPIC_AUTH_TOKEN`), and status
   output that names the wrong one sends the user to edit a variable that is
   not the one in play."
  [ks]
  (some (fn [k] (when-let [v (resolve-var k)] [k v])) ks))

(defn resolve-any?
  "True when any of `ks` resolves to a non-blank value."
  [ks]
  (some? (resolve-first ks)))


;; ============================================================================
;; Scoping policy — docs/design/environment-scoping-design.md §3.3
;; ============================================================================

(def infrastructure-vars
  "Names an `:env-allow` never has to list.

   An allowlist is a statement about SECRETS AND CONFIGURATION, not about
   whether a process can find `/bin/sh`. Without this floor the first person to
   write `:env-allow [\"GITHUB_TOKEN\"]` gets a child with no `PATH`, every
   shell command failing, and no clue why — a trap, not a policy. ACP's
   `:forward-env` lists `PATH` and `HOME` by hand for exactly this reason; a
   key that applies to EVERY child cannot ask that of everyone.

   `:env-deny` still removes them, because a deny is unconditional. That is the
   escape hatch for anyone who really means it."
  #{"PATH" "HOME" "TMPDIR" "TMP" "TEMP" "SHELL" "USER" "LOGNAME"
    "LANG" "LC_ALL" "LC_CTYPE" "TZ" "TERM"})

(defn- denied? [deny k]
  (boolean (and (seq deny) (glob/first-match deny k))))

(defn- allowed? [allow explicit k]
  (or (nil? allow)                    ; no allowlist ⇒ no filtering
      (empty? allow)
      (contains? explicit k)          ; :env-vars named it; see `apply-policy!`
      (contains? infrastructure-vars k)
      (boolean (glob/first-match allow k))))

(defn apply-policy!
  "Shape `env-map` — a `ProcessBuilder`'s environment — into what a child in
   this scope should see. Mutates and returns it.

   `policy` is `{:allow [globs] :deny [globs] :vars {name value}}`; all three
   optional, and `nil`/empty means `no opinion`, so the default policy leaves
   the map exactly as `.env` alone would have. That inertness is the same
   discipline the tool permission gate ships with: a key that arrives switched
   on is a key that arrives breaking something.

   Order, and every step of it is a decision:

     1. the `.env` layer, for names the inherited environment has no non-blank
        value for — what `child-env` alone did before scoping existed
     2. `:env-vars`, the explicit values this scope declares
     3. `:env-deny` REMOVES, unconditionally — after 1 and 2, so a deny cannot
        be undone by declaring the same name somewhere else. A deny another key
        can talk round is not a deny; the same rule `:tool-deny-tools` settled.
     4. `:env-allow`, when non-nil, removes everything it does not admit —
        except the names `:env-vars` declared (the operator just named them
        explicitly; making them list each one twice is bureaucracy) and
        `infrastructure-vars`.

   Deny before allow matters in the other direction too: allow runs last, so a
   name surviving both is one the operator admitted and did not deny."
  [^java.util.Map env-map {:keys [allow deny vars]}]
  (let [explicit (into #{} (map #(some-> % name)) (keys vars))]
    ;; 1. the .env layer
    (doseq [[k v] (child-env)]
      (.put env-map ^String k ^String v))
    ;; 2. explicit values
    (doseq [[k v] vars]
      (when-let [n (some-> k name not-empty)]
        (.put env-map ^String n ^String (str v))))
    ;; 3 + 4. removals, computed over a snapshot so we never mutate while
    ;; iterating the map's own key set.
    (doseq [k (vec (.keySet env-map))]
      (when (or (denied? deny k)
                (not (allowed? allow explicit k)))
        (.remove env-map k)))
    env-map))

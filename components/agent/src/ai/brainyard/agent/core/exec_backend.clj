;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.exec-backend
  "Execution backend seam (R4 — docs/design/hermes-comparison.md).

   Abstracts WHERE/HOW the agent runs code: a backend implements `exec-shell`
   (a shell command) and `exec-clj-code` (a Clojure code block). Today's only
   backend is `LocalBackend` (this machine), which keeps behavior identical:
   `exec-shell` shells out via ProcessBuilder; `exec-clj-code` defers to the
   existing `:clj-backend` dispatch (:sandbox SCI or :nrepl). The selected
   backend is `:exec-backend` config (default :local).

   A future remote backend (e.g. `NreplBackend`) implements the same protocol
   over a remote nREPL endpoint (Clojure natively, shell via remote `(sh …)`) —
   no SCI sandbox, since SCI is in-process. The nREPL endpoint is already
   host-configurable (clj-nrepl `eval-string :host :port`).

   Dependency note: `exec-clj-code`'s Local implementation needs coact-private
   eval fns (run-clj-sandbox-block / run-clj-nrepl-block), and `core` must not
   depend on `common.coact-agent`. So coact injects that logic at load via
   `set-local-clj-eval!` — a dependency inversion that keeps the coupled code in
   coact while the protocol lives here."
  (:require [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.mulog.interface :as mulog])
  (:import [java.io File InputStreamReader StringWriter]
           [java.util.concurrent TimeUnit]))

;; ============================================================================
;; Protocol
;; ============================================================================

(defprotocol ExecutionBackend
  (exec-shell [b command opts]
    "Run shell `command` (a string, via /bin/sh -c). opts: :cwd :timeout-ms.
     Returns {:exit int :output str :error str} — combined stdout+stderr in
     :output; :error \"\" on exit 0, else a message. Times out → process killed,
     non-zero :exit, timeout :error.")
  (exec-clj-code [b code opts]
    "Evaluate Clojure `code`. opts carries the coact eval context (:agent
     :sandbox :auto-bg-ms :fast-eval-ms :from-iteration :subagent?
     :owner-agent-id). Returns a coact eval-entry map
     {:lang :code :result :output :error}."))

;; ============================================================================
;; Local shell primitive (verbatim extraction of run-script-block's spawn)
;; ============================================================================

(defn local-exec-shell
  "Synchronously run `command` via `/bin/sh -c` in `:cwd`, draining combined
   stdout+stderr, killing the process if it exceeds `:timeout-ms`. Returns
   {:exit :output :error}. Mirrors the proven ProcessBuilder logic in
   coact-agent's run-script-block (subagent / in-task branch)."
  [command {:keys [cwd timeout-ms] :or {timeout-ms 30000}}]
  (let [pb (ProcessBuilder. ^"[Ljava.lang.String;"
            (into-array String ["/bin/sh" "-c" command]))]
    (.redirectErrorStream pb true)
    (when cwd (.directory pb (File. ^String cwd)))
    (let [^Process proc (.start pb)
          _ (.close (.getOutputStream proc))
          out (StringWriter.)
          reader-future
          (future
            (let [^InputStreamReader reader (InputStreamReader. (.getInputStream proc))
                  buf (char-array 1024)]
              (try (loop []
                     (let [n (.read reader buf)]
                       (when (pos? n)
                         (.write out buf 0 ^int n)
                         (recur))))
                   (catch java.io.IOException _)
                   (catch Throwable _))))
          completed? (.waitFor proc (long timeout-ms) TimeUnit/MILLISECONDS)]
      (if completed?
        (do (deref reader-future 2000 nil)
            (let [exit (.exitValue proc)]
              {:exit exit :output (.toString out)
               :error (if (zero? exit) "" (str "Exit code: " exit))}))
        (do (.destroyForcibly proc)
            (future-cancel reader-future)
            {:exit -1 :output (.toString out)
             :error (str "Subprocess timed out after " timeout-ms "ms")})))))

;; ============================================================================
;; Clojure-eval injection seam (coact wires the real dispatch at load)
;; ============================================================================

(defonce ^:private !local-clj-eval
  (atom (fn [_code _opts]
          {:lang "clojure" :code "" :result nil :output ""
           :error "exec-clj-code not wired (coact-agent not loaded)"})))

(defn set-local-clj-eval!
  "Register LocalBackend's Clojure-eval implementation (coact's sandbox|nrepl
   dispatch). Called once from coact-agent at load."
  [f]
  (reset! !local-clj-eval f))

;; ============================================================================
;; LocalBackend
;; ============================================================================

(defrecord LocalBackend []
  ExecutionBackend
  (exec-shell [_ command opts] (local-exec-shell command opts))
  (exec-clj-code [_ code opts] (@!local-clj-eval code opts)))

(def local-backend (->LocalBackend))

(defn resolve-backend
  "Resolve the execution backend for `agent` from `:exec-backend` config.
   Only :local exists today; :nrepl/:docker/:ssh are future phases (fall back
   to local with a one-time log)."
  [agent]
  (case (config/get-config agent :exec-backend)
    :local local-backend
    (let [other (config/get-config agent :exec-backend)]
      (when-not (= :local other)
        (mulog/log ::unimplemented-backend :requested other :using :local))
      local-backend)))

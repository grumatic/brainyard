;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.acp-client.core.callbacks
  "Default handlers for agent → client reverse calls.

   When the agent invokes `fs/read_text_file`, `fs/write_text_file`, or
   `session/request_permission`, the dispatcher pump in `client.clj`
   delegates to a handler from this namespace (or a caller-supplied
   override). Handlers return either a JSON-RPC `result` value (which
   the dispatcher wraps as a success response) or throw ExceptionInfo
   with `:type :acp/method-error` to produce an error response.

   Permission-outcome mapping uses the fallback policy from
   docs/acp-design.md §9.2 decision 4:

     :allow → first option whose id starts with \"allow_\", else first
              option in the list
     :block → first option whose id starts with \"reject_\", else first
              option in the list
     :replace / :modify-args → treated as :allow with override args
     anything else → :block"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.mulog.interface :as mulog]))

;; =============================================================================
;; Filesystem callbacks
;; =============================================================================

(defn default-fs-read-text-file
  "Read a UTF-8 text file from disk. ACP requires absolute paths."
  [{:keys [path] :as params}]
  (when-not (string? path)
    (throw (ex-info "fs/read_text_file requires :path"
                    {:type :acp/method-error :params params})))
  (when-not (.isAbsolute (io/file path))
    (throw (ex-info "fs/read_text_file requires an absolute path"
                    {:type :acp/method-error :params params})))
  (try
    {:content (slurp path)}
    (catch java.io.FileNotFoundException e
      (throw (ex-info (str "file not found: " path)
                      {:type :acp/method-error :path path}
                      e)))))

(defn default-fs-write-text-file
  "Write UTF-8 text to disk. Path must be absolute. Creates parent
   directories if they don't exist."
  [{:keys [path content] :as params}]
  (when-not (string? path)
    (throw (ex-info "fs/write_text_file requires :path"
                    {:type :acp/method-error :params params})))
  (when-not (string? content)
    (throw (ex-info "fs/write_text_file requires :content"
                    {:type :acp/method-error :params params})))
  (when-not (.isAbsolute (io/file path))
    (throw (ex-info "fs/write_text_file requires an absolute path"
                    {:type :acp/method-error :params params})))
  (let [f (io/file path)]
    (when-let [parent (.getParentFile f)]
      (.mkdirs parent))
    (spit f content)
    {}))

;; =============================================================================
;; Permission outcome mapping
;; =============================================================================

(defn- find-prefixed-option
  "Return the first option whose `:optionId` starts with `prefix`, or
   nil if none found."
  [options prefix]
  (some (fn [opt]
          (when (str/starts-with? (or (:optionId opt) "") prefix)
            opt))
        options))

(defn pick-option-id
  "Map a brainyard permission decision (`:allow` | `:block` | etc.) to
   one of the agent-supplied permission option ids.

   Fallback policy per §9.2 decision 4 — agents may supply
   non-canonical option ids, so we prefer prefix matching and fall
   back to the first option."
  [decision options]
  (let [options (vec options)
        first-id (some-> options first :optionId)]
    (case decision
      (:allow :replace :modify-args)
      (or (some-> (find-prefixed-option options "allow_") :optionId)
          first-id)

      :block
      (or (some-> (find-prefixed-option options "reject_") :optionId)
          first-id)

      ;; Default: treat unknown as block.
      (or (some-> (find-prefixed-option options "reject_") :optionId)
          first-id))))

(defn default-request-permission
  "Default policy: deny everything. Real callers override this in client
   opts — the acp-agent (components/agent acp_agent.clj) routes the
   request to the agent's interactive `:user-feedback-fn` (the N-option
   picker the TUI installs), falling back to this deny posture when no
   interactive session is wired."
  [{:keys [options] :as params}]
  (mulog/info ::default-permission-deny :params params)
  {:outcome {:outcome  "selected"
             :optionId (pick-option-id :block options)}})

;; =============================================================================
;; Default callback bundle
;; =============================================================================

(def default-callbacks
  "Map of method-name → handler-fn for reverse calls. Callers may
   merge overrides into this when constructing an AcpClient."
  {"fs/read_text_file"          default-fs-read-text-file
   "fs/write_text_file"         default-fs-write-text-file
   "session/request_permission" default-request-permission})

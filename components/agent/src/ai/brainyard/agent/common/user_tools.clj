;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.user-tools
  "Runtime-defined tools — the LLM authors a tool from Clojure source and it
   becomes a first-class, persistent, discoverable tool.

   Why source (not a closure): SCI closures are not EDN-serializable, so the
   existing sandbox persistence drops them (see
   clj-sandbox extract-user-vars-with-survival `:non-edn`). We therefore persist
   the SOURCE form to `.brainyard/tools/<name>.edn` and RE-EVAL it in a sandbox
   to rehydrate the tool on call and on session start. That is the capability a
   plain `defn` in the agent's sandbox cannot provide.

   The tool body runs in a dedicated, long-lived `!tools-sandbox` (forked per
   call for isolation). The body is a `(fn [args] ...)` of one map argument and
   may compose other registered tools by their DIRECT symbol — builtins like
   `(bash {…})` / `(read-file {…})` (supplied via :extra-bindings) and other
   user tools as `(user$<name> {…})` (bound on registration). call-tool is
   intentionally hidden, so composition is by symbol, not via call-tool. User
   tools are macros over the existing tool palette, not new host primitives (no
   new privilege beyond what the sandbox already grants).

   Registration goes into the SAME `agent.core.tool/!tool-defs` registry that
   `deftool` uses, so user tools immediately show up in `list-tools` / `search`,
   flow through `call-tool`'s Malli coercion + hook/permission/depth guards, and
   get auto-bound into agent sandboxes as `user$<name>` callables."
  (:require [ai.brainyard.agent.core.tool :as tool]
            [ai.brainyard.clj-sandbox.interface :as sb]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ============================================================================
;; Tools sandbox (holds every user tool body as a real SCI var)
;; ============================================================================

(defonce ^{:doc "The long-lived sandbox holding user tool bodies as `__ut_<name>`
  vars. Forked per call so concurrent invocations don't share mutable state."}
  !tools-sandbox
  (atom nil))

(defn- tools-sandbox
  "The live tools sandbox, created on first use. `extra-bindings` (typically the
   agent's `auto-tool-bindings`) expose registered tools as DIRECT symbols a body
   can call — builtins like `(bash {…})` / `(read-file {…})` and other user
   tools as `(user$<name> {…})`. There is no generic `call-tool` helper here:
   call-tool is intentionally hidden (`:visibility :hidden`), so a tool is
   composed by its own symbol, not through call-tool."
  ([] (tools-sandbox nil))
  ([extra-bindings]
   (let [sbx (or @!tools-sandbox
                 (reset! !tools-sandbox
                         (sb/create-sandbox :bindings (or extra-bindings {}))))]
     (when (seq extra-bindings)
       (sb/update-bindings! sbx extra-bindings))
     sbx)))

(defonce ^{:doc "Set of tools-dirs already loaded this process, so `ensure-loaded!`
  is a no-op after the first session-boot for a given user/project."}
  !loaded
  (atom #{}))

(defn reset-tools-sandbox!
  "Drop the tools sandbox and the loaded-dirs set (next call rebuilds / reloads).
   For tests / reload."
  []
  (reset! !tools-sandbox nil)
  (reset! !loaded #{}))

;; ============================================================================
;; Definition / persistence / registration
;; ============================================================================

(def ^:private tool-name-re
  "User tool names: lowercase kebab, leading letter. Keeps `user$<name>` a clean
   symbol/keyword and a safe filename."
  #"^[a-z][a-z0-9-]*$")

(defn- tools-dir
  "`.brainyard/tools` under the project (fallback working dir, then cwd).
   Project-scoped, mirroring `.brainyard/skills` / `.brainyard/plans`: a tool
   authored in a checkout is a shared project asset, and project-dir is already
   the user's working directory. (A `:scope :user` variant rooted at the
   user-dir could be added later, exactly as skills do.)"
  [dirs]
  (str (or (:project-dir dirs) (:working-dir dirs) ".") "/.brainyard/tools"))

(defn- tool-id [name] (keyword (str "user$" name)))

(defn- install-body!
  "Eval `(def __ut_<name> <body>)` into the tools sandbox so it is a callable
   SCI var. Throws if the body fails to parse/eval."
  [name body-str]
  (let [r (sb/eval-code (tools-sandbox)
                        (str "(def __ut_" name " " body-str ")"))]
    (when-let [err (:error r)]
      (throw (ex-info (str "tool body failed to eval: " err)
                      {:name name :body body-str})))
    r))

(defn- register!
  "Register (or replace) the tool in the shared !tool-defs registry AND bind its
   direct `user$<name>` symbol in the tools sandbox so other user tool bodies can
   compose it by symbol after registration. The registry :fn rehydrates by
   forking the tools sandbox and calling `__ut_<name>` with the args map bound as
   `args`."
  [{:keys [name description input-schema]}]
  (let [id     (tool-id name)
        schema (or input-schema [:map])
        invoke (fn [args]
                 (let [clean (dissoc args :agent :parent-agent :agent-session
                                     :_deftool$id :_deftool$type
                                     :_deftool$description :_deftool$input-schema
                                     :_deftool$output-schema)
                       fork  (sb/fork-sandbox (tools-sandbox))]
                   (sb/set-var! fork 'args clean)
                   (let [r (sb/eval-code fork (str "(__ut_" name " args)"))]
                     (if-let [err (:error r)] {:error err} (:result r)))))]
    (swap! tool/!tool-defs assoc id
           {:id   id
            :type :tool
            :fn   invoke
            :meta {:id            id
                   :type          :tool
                   :description   description
                   :input-schema  schema
                   :output-schema [:map]
                   :category      :user
                   :user-defined  true}})
    ;; Direct symbol for body-to-body composition. Routes through the registry
    ;; (tool/call-tool) so it still gets Malli coercion + hook/permission/depth
    ;; guards — not through the hidden call-tool helper.
    (sb/set-var! (tools-sandbox)
                 (symbol (str "user$" name))
                 (fn [args]
                   (let [r (tool/call-tool id (or args {}))]
                     (if (:error-message r) {:error (:error-message r)} r))))
    id))

(defn define-tool
  "Define a reusable, persistent tool from source.

   Kwargs:
     :name          - lowercase-kebab string (matches #\"^[a-z][a-z0-9-]*$\")
     :description   - one-line description
     :input-schema  - Malli [:map ...] (default [:map]); drives coercion/validation
     :body          - a string `(fn [args] ...)` of ONE map arg
     :dirs          - {:project-dir ...} resolving where to persist

   Effects: validates + eval-smoke-tests the body, persists the source to
   `.brainyard/tools/<name>.edn`, and registers it into !tool-defs. Returns
   {:id :name :persisted}."
  [& {:keys [name description input-schema body dirs extra-bindings]}]
  (when-not (and (string? name) (re-matches tool-name-re name))
    (throw (ex-info "tool :name must match ^[a-z][a-z0-9-]*$"
                    {:name name})))
  (when-not (string? body)
    (throw (ex-info "tool :body must be a string `(fn [args] ...)`" {:body body})))
  (when (and input-schema
             (not (and (vector? input-schema) (= :map (first input-schema)))))
    (throw (ex-info "tool :input-schema must be a [:map ...] schema"
                    {:input-schema input-schema})))
  (tools-sandbox extra-bindings)                  ;; ensure + refresh tool palette
  (install-body! name body)                       ;; compile-now smoke test
  (let [dir  (tools-dir dirs)
        file (str dir "/" name ".edn")
        rec  {:name name :description description
              :input-schema (or input-schema [:map]) :body body}]
    (.mkdirs (io/file dir))
    (spit file (pr-str rec))
    (let [id (register! rec)]
      (mulog/info ::define-tool :id id :file file)
      {:id id :name name :persisted file})))

(defn load-user-tools!
  "Startup loader: re-eval every persisted body into the tools sandbox and
   re-register it. Call once when an agent session boots. Returns the names
   loaded."
  [& {:keys [dirs extra-bindings]}]
  (tools-sandbox extra-bindings)                  ;; ensure + refresh tool palette
  (let [dir (io/file (tools-dir dirs))]
    (if (.isDirectory dir)
      (let [recs (->> (.listFiles dir)
                      (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
                      (keep (fn [^java.io.File f]
                              (try (edn/read-string (slurp f))
                                   (catch Exception e
                                     (mulog/warn ::load-user-tool-read-failed
                                                 :file (.getName f) :error (ex-message e))
                                     nil))))
                      vec)]
        ;; Pass 1: register all, binding every `user$<name>` symbol up front —
        ;; bodies may reference peer tools and .edn file order is undefined.
        (doseq [rec recs] (register! rec))
        ;; Pass 2: install bodies (now all peer symbols resolve); roll a tool
        ;; back out of the registry if its body fails to eval.
        (->> recs
             (keep (fn [rec]
                     (try (install-body! (:name rec) (:body rec))
                          (:name rec)
                          (catch Exception e
                            (swap! tool/!tool-defs dissoc (tool-id (:name rec)))
                            (mulog/warn ::load-user-tool-failed
                                        :name (:name rec) :error (ex-message e))
                            nil))))
             vec))
      [])))

(defn ensure-loaded!
  "Idempotent session-boot loader: load this user/project's persisted tools the
   first time it is seen this process, and no-op thereafter. Safe to call on
   every turn. Returns the names loaded (or nil when already loaded)."
  [& {:keys [dirs extra-bindings]}]
  (let [dir (tools-dir dirs)]
    (when-not (contains? @!loaded dir)
      (swap! !loaded conj dir)
      (load-user-tools! :dirs dirs :extra-bindings extra-bindings))))

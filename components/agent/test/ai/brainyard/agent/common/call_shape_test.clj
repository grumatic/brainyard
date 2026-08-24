;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.call-shape-test
  "Guards CR-SBX-0 — kwargs is the canonical tool-call shape taught to the model.

   Three independent things are asserted here:

   1. **Equivalence.** `(tool :k v)` and `(tool {:k v})` must dispatch to the
      SAME args map through `sandbox-bindings/bind-one-tool`. The kwargs sweep
      rewrote ~121 model-facing call examples from the map form to kwargs on the
      strength of this claim; if the two conventions ever diverge, every
      rewritten example silently changes meaning.

   2. **`:code-template` validity.** `sandbox-meta`'s templates are not docs —
      `/sandbox <fn> <args>` runs them through `format` and then
      `clj-sandbox/eval-code` (see agent_tui.commands/handle-sandbox-fn). A
      template whose first key is not a declared input of its tool would fall
      out of kwargs mode and be parsed as positional args, silently passing the
      wrong shape. This asserts every template's leading key is declared.

   3. **No reintroduction.** A source scan over the agent's own instruction
      strings, so the next prompt edit cannot quietly put the map form back.

   Both are registry-driven rather than hard-coded, so a tool that renames an
   input key fails here instead of at runtime in front of a model."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [ai.brainyard.agent.common.sandbox-bindings :as sb-bind]
            [ai.brainyard.agent.common.sandbox-meta :as sm]
            [ai.brainyard.agent.core.tool :as tool]))

;; ---------------------------------------------------------------------------
;; 1. kwargs / map-form equivalence
;; ---------------------------------------------------------------------------

(defn- capture-args-tool
  "Register a throwaway tool that echoes back the args map it received, so a
   call through the sandbox binding reports exactly what dispatch built."
  [id input-schema]
  (swap! tool/!tool-defs assoc id
         {:id id :type :tool
          :fn (fn [args]
                {:seen (dissoc args :agent :parent-agent :agent-session
                               :_deftool$id :_deftool$type :_deftool$description
                               :_deftool$input-schema :_deftool$output-schema)})
          :meta {:id id :type :tool
                 :description "test capture tool"
                 :input-schema input-schema
                 :output-schema [:map]}}))

(deftest kwargs-and-map-forms-are-equivalent
  (testing "single required key"
    (let [id ::cs-one]
      (capture-args-tool id [:map [:path [:string {:desc "p"}]]])
      (try
        (let [[_ f] (#'sb-bind/bind-one-tool (tool/get-tool-defs :id id) nil)
              kw  (:seen (f :path "/tmp/x"))
              mp  (:seen (f {:path "/tmp/x"}))]
          (is (= {:path "/tmp/x"} kw) "kwargs form builds the args map")
          (is (= kw mp) "map form builds the SAME args map"))
        (finally (swap! tool/!tool-defs dissoc id)))))

  (testing "required + optional keys, order-independent"
    (let [id ::cs-many]
      (capture-args-tool id [:map
                             [:path    [:string {:desc "p"}]]
                             [:content {:optional true} [:string {:desc "c"}]]
                             [:append  {:optional true} [:boolean {:desc "a"}]]])
      (try
        (let [[_ f] (#'sb-bind/bind-one-tool (tool/get-tool-defs :id id) nil)
              kw  (:seen (f :path "/tmp/x" :content "hi" :append true))
              mp  (:seen (f {:path "/tmp/x" :content "hi" :append true}))
              rev (:seen (f :append true :path "/tmp/x" :content "hi"))]
          (is (= {:path "/tmp/x" :content "hi" :append true} kw))
          (is (= kw mp)  "map form matches kwargs")
          (is (= kw rev) "kwargs ignores declaration order"))
        (finally (swap! tool/!tool-defs dissoc id)))))

  (testing "a nested map VALUE still rides as a map under kwargs"
    ;; Kwargs removes the OUTER brace pair only — this is the documented limit
    ;; of the sweep: multi-line map args were converted, but a nested map VALUE
    ;; keeps its braces because it is data, not an argument list.
    (let [id ::cs-nested]
      (capture-args-tool id [:map [:sample [:map {:desc "s"}]]])
      (try
        (let [[_ f] (#'sb-bind/bind-one-tool (tool/get-tool-defs :id id) nil)]
          (is (= {:sample {:x 1}} (:seen (f :sample {:x 1})))))
        (finally (swap! tool/!tool-defs dissoc id)))))

  (testing "odd kwargs count is reported, not silently mis-parsed"
    (let [id ::cs-odd]
      (capture-args-tool id [:map [:path [:string {:desc "p"}]]])
      (try
        (let [[_ f] (#'sb-bind/bind-one-tool (tool/get-tool-defs :id id) nil)]
          (is (str/includes? (str (:error (f :path))) "even number")))
        (finally (swap! tool/!tool-defs dissoc id))))))

;; ---------------------------------------------------------------------------
;; 2. sandbox-meta :code-template templates are kwargs-valid
;; ---------------------------------------------------------------------------

(def ^:private template-call-re
  "Leading call in a :code-template — `(tool-id :key …)` or `(tool-id {:key …})`.
   Group 1 is the tool id, group 2 the first argument key (either shape)."
  #"^\((\S+)\s+\{?:([a-z][a-z0-9-]*)")

(deftest code-templates-lead-with-a-declared-input-key
  (let [defs (tool/get-tool-defs)
        declared (fn [id]
                   (->> (tool/malli-map-entries
                         (get-in defs [(keyword id) :meta :input-schema]))
                        (map (comp name tool/malli-map-entry-key))
                        set))
        problems
        (for [{:keys [name code-template]} sm/sandbox-functions
              :let [m (re-find template-call-re (str code-template))]
              :when m
              :let [[_ tool-id first-key] m
                    ks (declared tool-id)]
              ;; Only tools that are actually registered can be checked; a
              ;; template for a context accessor (context-get etc.) has no
              ;; registry entry and no schema to validate against.
              :when (and (contains? defs (keyword tool-id))
                         (seq ks)
                         (not (contains? ks first-key)))]
          {:fn name :template code-template :first-key first-key :declared ks})]
    (is (empty? problems)
        (str "these :code-template entries lead with an undeclared key, so they "
             "would fall out of kwargs mode when /sandbox evals them: "
             (pr-str (vec problems))))))

;; ---------------------------------------------------------------------------
;; 3. No agent instruction reintroduces the map-arg call shape
;; ---------------------------------------------------------------------------

(def ^:private live-code-exceptions
  "Real call sites (not model-facing text) that legitimately pass an args map.
   `a2a$disconnect` is invoked internally by `a2a.clj`'s :update/:delete
   branches; both shapes work on a deftool var, so these were left alone rather
   than touched for a documentation change."
  #{["a2a.clj" "a2a$disconnect"]})

(defn- src-files []
  (let [root (io/file "src/ai/brainyard/agent")]
    (when (.isDirectory root)
      (->> (file-seq root)
           (filter #(str/ends-with? (.getName ^java.io.File %) ".clj"))))))

(deftest instructions-do-not-reintroduce-map-arg-shape
  ;; The sweep rewrote ~121 call examples across 21 files; nothing but this
  ;; check stops the next instruction edit from putting the map form back.
  ;; Scoped to calls whose leading key is a DECLARED input of a REGISTERED
  ;; tool, so ordinary Clojure like `(merge {:a 1})` is never flagged.
  (if-let [files (seq (src-files))]
    (let [defs  (tool/get-tool-defs)
          keys-of (fn [t]
                    (->> (tool/malli-map-entries
                          (get-in defs [(keyword t) :meta :input-schema]))
                         (map (comp name tool/malli-map-entry-key))
                         set))
          re (re-pattern "\\((?!call-tool\\b)([a-z][a-z0-9$_-]*)\\s+\\{(:[a-z][a-z0-9-]*)")
          hits (for [^java.io.File f files
                     [i line] (map-indexed vector (str/split-lines (slurp f)))
                     [_ t k] (re-seq re line)
                     :let [k (subs k 1)]
                     :when (and (contains? defs (keyword t))
                                (contains? (keys-of t) k)
                                (not (contains? live-code-exceptions
                                                [(.getName f) t])))]
                 (str (.getName f) ":" (inc i) "  (" t " {:" k " …})"))]
      (is (empty? hits)
          (str "map-arg call shape reintroduced — prefer kwargs `(tool :k v)` "
               "per CR-SBX-0 (docs/design/sandbox-surface-and-macros-design.md):\n  "
               (str/join "\n  " hits))))
    ;; Run from a directory without the component sources (e.g. an aggregated
    ;; Polylith test run rooted elsewhere) — skip rather than fail spuriously.
    (is true "agent sources not reachable from CWD; source-scan guard skipped")))

(deftest code-templates-use-kwargs-not-map-form
  ;; The sweep's regression guard: a newly added template must not reintroduce
  ;; the `(tool {:k v})` shape the CR removed.
  (let [map-form (->> sm/sandbox-functions
                      (filter #(re-find #"^\(\S+\s+\{:" (str (:code-template %))))
                      (mapv :name))]
    (is (empty? map-form)
        (str "these :code-template entries still use the map-arg form; "
             "prefer kwargs `(tool :k v)` — see CR-SBX-0: " (pr-str map-form)))))

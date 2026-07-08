;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.acp-block-session-test
  "Unit tests for the ACP transcript live block.

   An acp-agent hands the whole turn to an external ACP loop that STREAMS an
   interleaved sequence of reasoning / assistant-message / tool calls within a
   single BT iteration. Unlike the ReAct iteration block (one discrete
   think→act→observe step), the ACP block renders the event stream
   chronologically as an ordered vector of `:segments`.

   These tests cover the pure segment transforms, the renderer (interleave
   order, header state, message tail-cap, thought toggle, quiet mode), and the
   background-session freeze routing — the same rendering-reaches-origin-session
   guarantee that `iteration-block-session-test` proves for the iteration block."
  (:require [ai.brainyard.agent-tui.session :as session]
            [ai.brainyard.agent-tui.sessions :as sessions]
            [ai.brainyard.agent-tui.layout :as layout]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:private aid :acp-agent/silver-otter-7)
(def ^:private rid "_")
(def ^:private iter 1)
(def ^:private block-id (keyword "acp-block" "acp-agent/silver-otter-7:_:1"))

(def ^:private append-text  #'session/acp-append-text)
(def ^:private upsert-tool  #'session/acp-upsert-tool)
(def ^:private resolve-tool #'session/acp-resolve-tool)
(def ^:private render-lines #'session/render-acp-block-lines)
(def ^:private update-block! #'session/update-acp-block!)

(defn- strip [s] (str/replace s #"\[[0-9;]*m" ""))
(defn- render-plain [state] (mapv strip (render-lines state \space)))

(defn- reset-state-fixture [t]
  (let [saved-sessions @sessions/!sessions
        saved-acp      @session/!acp-blocks
        saved-sb       @layout/!scrollback
        saved-blocks   @layout/!live-blocks]
    (reset! layout/!scrollback [])
    (reset! layout/!live-blocks {})
    (try (t)
         (finally
           (reset! sessions/!sessions saved-sessions)
           (reset! session/!acp-blocks saved-acp)
           (reset! layout/!scrollback saved-sb)
           (reset! layout/!live-blocks saved-blocks)))))

(use-fixtures :each reset-state-fixture)

;; ---------------------------------------------------------------------------
;; Pure segment transforms
;; ---------------------------------------------------------------------------

(deftest append-text-coalesces-consecutive-same-kind
  (testing "consecutive chunks of one kind merge into a single segment; a new kind opens a new one"
    (let [segs (-> []
                   (append-text :thought "think ")
                   (append-text :thought "more")
                   (append-text :message "hello ")
                   (append-text :message "world"))]
      (is (= [{:type :thought :text "think more"}
              {:type :message :text "hello world"}]
             segs)))))

(deftest append-text-preserves-interleave-across-a-tool
  (testing "a tool between two message runs keeps them as separate ordered segments"
    (let [segs (-> []
                   (append-text :message "part A ")
                   (upsert-tool {:call-id "c1" :tool-name "Read" :args {} :now 10})
                   (append-text :message "part B"))]
      (is (= [:message :tool :message] (mapv :type segs))
          "the tool call splits the message into two segments in arrival order"))))

(deftest upsert-tool-dedups-by-call-id
  (testing "a streaming backend's double tool_call emit (placeholder then real args) merges"
    (let [segs (-> []
                   (upsert-tool {:call-id "c1" :tool-name "Bash" :args {} :now 1})
                   (upsert-tool {:call-id "c1" :tool-name "Bash" :args {:cmd "ls"} :now 1}))]
      (is (= 1 (count segs)) "one tool segment, not two")
      (is (= {:cmd "ls"} (:args (first segs))) "the real args win"))))

(deftest resolve-tool-settles-status
  (testing "tool-use/post merges status/result into the matching :called segment"
    (let [segs (-> []
                   (upsert-tool {:call-id "c1" :tool-name "Read" :args {} :now 1})
                   (resolve-tool {:call-id "c1" :tool-name "Read" :status :done
                                  :end-ms 2 :result-chars 42}))]
      (is (= :done (:status (first segs))))
      (is (= 42 (:result-chars (first segs)))))))

;; ---------------------------------------------------------------------------
;; Renderer
;; ---------------------------------------------------------------------------

(def ^:private base-state
  {:backend :claude-code :model-label "sonnet" :stage :running :result nil
   :usage {:total 4200} :start-ms 0 :end-ms 12300
   :show-thoughts? true :message-max-lines 12
   :segments [{:type :thought :text "read the config first"}
              {:type :tool :call-id "c1" :name "Read" :args {:path "config.edn"}
               :status :done :start-ms 0 :end-ms 1200 :result-chars 1200}
              {:type :thought :text "now patch the timeout"}
              {:type :message :text "Done — timeout set to 30s."}]})

(deftest renders-header-and-chronological-interleave
  (let [lines (render-plain base-state)
        joined (str/join "\n" lines)]
    (testing "header names the backend and model, not 'Iteration N/M'"
      (is (str/includes? (first lines) "claude-code · sonnet"))
      (is (not (str/includes? joined "Iteration"))))
    (testing "segments render in arrival order: thought → tool → thought → message"
      (let [idx (fn [needle] (first (keep-indexed #(when (str/includes? %2 needle) %1) lines)))]
        (is (< (idx "read the config first")
               (idx "Read")
               (idx "now patch the timeout")
               (idx "Done — timeout set to 30s.")))))
    (testing "thought segments render as dim '● Thinking:' lines"
      (is (str/includes? joined "● Thinking: read the config first")))
    (testing "tool segments reuse the shared tool-line renderer"
      (is (str/includes? joined "Read")))))

(deftest header-marker-reflects-result
  (testing "running shows the spinner char; success ✓; failure ✗"
    (is (str/includes? (first (render-plain (assoc base-state :stage :done :result :success))) "✓"))
    (is (str/includes? (first (render-plain (assoc base-state :stage :done :result :failure))) "✗"))))

(deftest thoughts-hidden-when-disabled
  (testing ":acp-show-thoughts false suppresses thought segments but keeps tools + message"
    (let [joined (str/join "\n" (render-plain (assoc base-state :show-thoughts? false)))]
      (is (not (str/includes? joined "Thinking")))
      (is (str/includes? joined "Read"))
      (is (str/includes? joined "Done — timeout set to 30s.")))))

(deftest message-rendered-as-markdown
  (testing "assistant message markdown is rendered (markers consumed), not shown literally"
    (let [state (assoc base-state
                       :segments [{:type :message
                                   :text "See **bold** here.\n\n## Heading\n\n- item one\n- item two"}])
          joined (str/join "\n" (render-plain state))]
      (is (str/includes? joined "bold") "bold text is present")
      (is (not (str/includes? joined "**bold**")) "literal ** emphasis markers are consumed")
      (is (str/includes? joined "Heading") "heading text is present")
      (is (not (str/includes? joined "## Heading")) "literal ## header markers are consumed")
      (is (str/includes? joined "• item one") "list item rendered with a • bullet")
      (is (not (re-find #"(?m)^\s*- item one" joined)) "raw '- ' list markers are consumed"))))

(deftest message-tail-is-capped
  (testing "a long streamed message tail-caps to :message-max-lines with a [-N lines] fold"
    (let [long-msg (str/join " " (repeatedly 400 #(str "word")))
          state (assoc base-state :message-max-lines 3
                       :segments [{:type :message :text long-msg}])
          joined (str/join "\n" (render-plain state))]
      (is (str/includes? joined "lines]")
          "a [-N lines] indicator marks the elided middle"))))

;; ---------------------------------------------------------------------------
;; Background-session freeze routing (mirrors iteration-block-session-test)
;; ---------------------------------------------------------------------------

(deftest update-reaches-backgrounded-origin-session
  (testing "an ACP block update lands in its ORIGIN session even when another tab is active"
    (reset! sessions/!sessions
            {:active-idx 1
             :next-id    2
             :sessions   {0 {:id 0 :scrollback [] :live-blocks {}}
                          1 {:id 1 :scrollback [] :live-blocks {}}}})
    (reset! session/!acp-blocks
            {[aid rid iter]
             (assoc base-state
                    :agent-id aid :repeat-id rid :iteration iter
                    :session-idx 0)})
    (update-block! aid rid iter)
    (let [sb (:scrollback (sessions/get-session 0))]
      (is (some #(str/includes? (strip %) "claude-code · sonnet") sb)
          "origin session's saved scrollback shows the ACP block")
      (is (empty? (:scrollback (sessions/get-session 1)))
          "the active (foreground) session is untouched"))))

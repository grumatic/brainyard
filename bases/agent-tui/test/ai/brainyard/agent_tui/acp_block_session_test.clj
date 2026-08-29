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
            [ai.brainyard.agent-tui.persist-bridge :as persist-bridge]
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
(def ^:private freeze-block! #'session/acp-freeze-block!)

(defn- capture-tees
  "Run `f` with the disk tee stubbed, returning `[[session-id text desc] …]`.

   Stubbing at `persist-bridge/tee-scrollback!` rather than at
   `sessions/tee-to-session!` is deliberate: it is the LAST hop before bytes
   reach the file, so both routes into it — the tee-only call and the one
   `emit-to-session!` makes — are counted by the same probe. A stub one level
   up would see only one of them and could not tell a double-write from a
   single one."
  [f]
  (let [captured (atom [])]
    (with-redefs [persist-bridge/tee-scrollback!
                  (fn [sid s & [desc]] (swap! captured conj [sid s desc]) nil)]
      (f))
    @captured))

(defn- freeze-event [] {:agent {:agent-id aid} :iteration iter
                        :repeat-id rid :result :success})

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

;; ---------------------------------------------------------------------------
;; Tool result bodies
;;
;; The result reaching this block is the normalized shape built at the
;; acp-client boundary (`normalize-tool-content`), NOT the raw ACP
;; `ToolCallContent[]`. These pin that the box shows the tool's own output and
;; never the wire envelope, which is what made the block unreadable.
;; ---------------------------------------------------------------------------

(def ^:private result->body #'session/acp-tool-result->body)

(deftest tool-result-body-shows-output-not-the-envelope
  (testing "a completed result renders its :output raw — no pr-str, no `name: value` wrapper"
    (let [body (result->body {:status "completed"
                              :output "total 48\ndrwxr-xr-x  6 jake  staff"
                              :acp/content [{:type "content"
                                             :content {:type "text" :text "total 48"}}]})]
      (is (= "total 48\ndrwxr-xr-x  6 jake  staff" body))
      (testing "the raw wire vector and the redundant status never reach the box"
        (is (not (str/includes? body ":type")))
        (is (not (str/includes? body "acp/content")))
        (is (not (str/includes? body "status:")))))))

(deftest tool-result-body-is-nil-when-empty
  (testing "a completed call with no content renders no box at all"
    ;; Previously this produced a box containing only `status: completed`.
    (is (nil? (result->body {:status "completed"})))))

(deftest tool-result-body-error-is-the-message-alone
  (testing "a failed result renders just the message — the box is already red and labelled Error"
    (let [body (result->body {:status "failed" :error "permission denied"
                              :acp/content [{:type "content"
                                             :content {:type "text" :text "permission denied"}}]})]
      (is (= "permission denied" body)))))

(deftest tool-result-body-renders-a-diff
  (testing "a diff renders as +/- lines under its path, with identical context trimmed"
    (let [body (strip (result->body
                       {:status "completed"
                        :diffs [{:path "src/x.clj"
                                 :old "(ns x)\n(def a 1)\n(def b 2)"
                                 :new "(ns x)\n(def a 99)\n(def b 2)"}]}))]
      (is (str/includes? body "--- src/x.clj"))
      (is (str/includes? body "- (def a 1)"))
      (is (str/includes? body "+ (def a 99)"))
      (testing "unchanged leading/trailing lines are trimmed, not echoed"
        (is (not (str/includes? body "(ns x)")))
        (is (not (str/includes? body "(def b 2)")))))))

(deftest tool-result-body-diff-of-a-new-file
  (testing "a Write (no oldText) shows every line as an addition"
    (let [body (strip (result->body {:status "completed"
                                     :diffs [{:path "new.clj" :old nil :new "line1\nline2"}]}))]
      (is (str/includes? body "+ line1"))
      (is (str/includes? body "+ line2"))
      ;; No removal LINE (the `--- <path>` header also contains "- ").
      (is (not-any? #(str/starts-with? % "- ") (str/split-lines body))))))

(deftest tool-result-body-combines-prose-and-diff
  (testing "prose output and a diff both render, prose first"
    (let [body (strip (result->body {:status "completed"
                                     :output "Edited 1 file"
                                     :diffs [{:path "a.clj" :old "x" :new "y"}]}))]
      (is (< (.indexOf body "Edited 1 file") (.indexOf body "--- a.clj"))))))

(deftest tool-result-body-non-map-falls-back
  (testing "a non-map result still stringifies (the native tool path's behaviour)"
    (is (= "plain string" (result->body "plain string")))))

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

;; ---------------------------------------------------------------------------
;; Freeze reaches DISK
;;
;; The regression these guard: an ACP transcript renders exclusively through
;; the live-block path, which writes the terminal and `!scrollback` and nothing
;; else — the only writers of `scrollback.stream.txt` are on the `emit!` path.
;; An acp turn also has no answer emit to compensate (`:acp-show-final-answer`
;; is off by default because the block already showed the message). So before
;; the freeze-time tee, an acp session persisted its prompt echoes and usage
;; footers and NOTHING the agent said, and `--resume` restored a transcript
;; with every reply missing.
;;
;; Each of the three routes must write the bytes exactly once. Counting is the
;; whole point: a second write is not a cosmetic duplicate, it doubles the
;; transcript on every resume.
;; ---------------------------------------------------------------------------

(defn- seed-block! [session-idx]
  (reset! session/!acp-blocks
          {[aid rid iter] (assoc base-state
                                 :agent-id aid :repeat-id rid :iteration iter
                                 :session-idx session-idx)}))

(deftest freeze-tees-a-foreground-transcript-exactly-once
  (testing "origin IS the active tab — the rows are already on screen, so the freeze tees WITHOUT re-emitting"
    (reset! sessions/!sessions
            {:active-idx 0
             :next-id    1
             :sessions   {0 {:id 0 :agent-session-id "agt-fg"
                             :scrollback [] :live-blocks {}}}})
    (seed-block! 0)
    (let [teed (capture-tees #(freeze-block! (freeze-event)))]
      (is (= 1 (count teed)) "written once")
      (let [[sid text desc] (first teed)
            plain (strip text)]
        (is (= "agt-fg" sid) "to the origin tab's own session id")
        (is (str/includes? plain "claude-code · sonnet") "header persisted")
        (is (str/includes? plain "Done — timeout set to 30s.") "the assistant message persisted")
        (is (str/includes? plain "Read") "tool calls persisted")
        (is (= :acp-block (:kind desc)) "with a descriptor so resume can redraw it")))
    ;; …and did NOT also re-emit. Sending the foreground case through
    ;; `emit-to-session!` would draw the whole transcript a second time under
    ;; the widget the user is already looking at; `emit-to-session!` appends to
    ;; the session's saved scrollback, `tee-to-session!` deliberately does not
    ;; ("persist WITHOUT rendering"), so an empty vector here IS the proof that
    ;; the tee-only branch was taken.
    ;;
    ;; Deliberately NOT asserted against `layout/!scrollback`: the on-screen
    ;; copy arrives via `update-acp-block!` → `iter-sink/write-widget!`, and the
    ;; iteration sink defaults to `noop-sink` ("active when no TUI is wired —
    ;; REPL, tests that don't exercise rendering"). No test installs one, so
    ;; that vector is unconditionally empty and an assertion on it can only ever
    ;; read 0 — it would fail identically whether or not the bug were present,
    ;; which is the one thing a regression test must never do.
    (is (empty? (:scrollback (sessions/get-session 0)))
        "not redrawn under the frozen widget — the tee-only branch re-emits nothing")))

(deftest freeze-of-a-backgrounded-block-writes-once-through-the-emit
  (testing "origin is backgrounded and never rendered there — one emit, which tees on the way"
    (reset! sessions/!sessions
            {:active-idx 1
             :next-id    2
             :sessions   {0 {:id 0 :agent-session-id "agt-bg"
                             :scrollback [] :live-blocks {}}
                          1 {:id 1 :agent-session-id "agt-other"
                             :scrollback [] :live-blocks {}}}})
    (seed-block! 0)
    (let [teed (capture-tees #(freeze-block! (freeze-event)))]
      (is (= 1 (count teed)) "written once — the emit tees, the tee-only branch must not also fire")
      (is (= "agt-bg" (ffirst teed)) "to the ORIGIN tab, not the active one")
      (is (= :acp-block (:kind (nth (first teed) 2)))))
    (is (some #(str/includes? (strip %) "claude-code · sonnet")
              (:scrollback (sessions/get-session 0)))
        "and it also became visible in the backgrounded tab")))

(deftest freeze-of-an-already-buffered-block-writes-once-and-does-not-redraw
  (testing "origin backgrounded but ALREADY holding the block's rows — tee only"
    (reset! sessions/!sessions
            {:active-idx 1
             :next-id    2
             :sessions   {0 {:id 0 :agent-session-id "agt-buf"
                             :scrollback [] :live-blocks {}}
                          1 {:id 1 :agent-session-id "agt-other"
                             :scrollback [] :live-blocks {}}}})
    (seed-block! 0)
    ;; Stream one live update first, so the block is buffered into origin's
    ;; saved scrollback exactly as it would be had the user switched away
    ;; mid-turn. This is the case that reached disk NEITHER way before.
    (update-block! aid rid iter)
    (let [teed (capture-tees #(freeze-block! (freeze-event)))
          headers (filter #(str/includes? (strip %) "claude-code · sonnet")
                          (:scrollback (sessions/get-session 0)))]
      (is (= 1 (count teed)) "written once")
      (is (= "agt-buf" (ffirst teed)))
      (is (= 1 (count headers))
          "the buffered rows were not appended a second time"))))

(deftest disposed-blocks-are-not-teed
  (testing ":dispose-acp-block drops the widget — persisting what was discarded would resurrect it on resume"
    (reset! sessions/!sessions
            {:active-idx 0
             :next-id    1
             :sessions   {0 {:id 0 :agent-session-id "agt-dis"
                             :scrollback [] :live-blocks {}}}})
    (seed-block! 0)
    (with-redefs [ai.brainyard.agent.interface/get-config
                  (fn [_ k] (when (= k :dispose-acp-block) true))]
      (is (empty? (capture-tees #(freeze-block! (freeze-event))))))))

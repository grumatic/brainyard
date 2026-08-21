;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.menu-hold-test
  "Tests for the autocomplete popover's row reservation being scoped to the
   INPUT LINE rather than to the menu.

   The reservation IS the viewport geometry: `recalc-layout-rows!` takes
   `menu-height` straight out of `scroll-bottom`, and `render-viewport!`
   bottom-anchors into what is left. So each show/hide of the menu moved every
   visible row by ~30% of the screen height. Typing a sentence containing an
   `@`-token crosses zero matches and back on ordinary keystrokes, and each
   crossing flipped the text the user was reading.

   What is pinned here is the geometry, not the menu: the menu may come and go
   as often as the filter says, and only Enter (line submitted) or Esc (the user
   asking for it to be gone) gives the rows back."
  (:require [ai.brainyard.agent-tui.autocomplete :as ac]
            [ai.brainyard.agent-tui.layout :as layout]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(defn- fake-fullscreen!
  "Fullscreen layout with a writer that goes nowhere, and enough scrollback that
   the viewport is scrollable in both directions."
  []
  (reset! layout/!scrollback (mapv #(str "line " %) (range 200)))
  (reset! layout/!live-blocks {})
  (reset! layout/!scrollback-src [])
  (reset! layout/!layout
          {:mode :fullscreen :rows 40 :cols 80
           :scroll-bottom 35 :separator-row 36 :input-row 37
           :separator2-row 38 :tab-row 39 :status-row 40
           :viewport-offset 0 :input-height 1 :input-height-max 6
           :menu-height 0 :menu-height-hold 0
           :task-activity-height 0 :agent-activity-height 0
           :writer (java.io.PrintWriter. (java.io.StringWriter.))}))

(defn- reset-layout-fixture [t]
  (let [saved @layout/!layout]
    (try (t)
         (finally
           (reset! layout/!scrollback [])
           (reset! layout/!live-blocks {})
           (reset! layout/!scrollback-src [])
           (reset! layout/!layout saved)))))

(use-fixtures :each reset-layout-fixture)

(defn- scroll-bottom [] (:scroll-bottom @layout/!layout))

(defn- viewport-top
  "Index into !scrollback of the row currently on the top line of the viewport —
   the number that must not move while the user types."
  []
  (let [{:keys [viewport-offset scroll-bottom]} @layout/!layout]
    (max 0 (- (count @layout/!scrollback)
              (long (or viewport-offset 0))
              (long scroll-bottom)))))

;; ---------------------------------------------------------------------------
;; The hold
;; ---------------------------------------------------------------------------

(deftest hold-reserves-and-pins
  (testing "holding reserves rows exactly like set-menu-height!, and pins them"
    (fake-fullscreen!)
    (let [before (scroll-bottom)]
      (layout/hold-menu-height! 12)
      (is (layout/menu-height-held?))
      (is (= (- before 12) (scroll-bottom))
          "the reservation comes out of the scroll region")
      ;; What a dismiss does today.
      (layout/set-menu-height! 0)
      (is (= (- before 12) (scroll-bottom))
          "a hidden menu must NOT hand the rows back while the hold is on")
      (layout/release-menu-height!)
      (is (not (layout/menu-height-held?)))
      (is (= before (scroll-bottom))
          "releasing gives the rows back"))))

(deftest hold-survives-a-show-hide-cycle-without-moving-the-viewport
  (testing "the top visible row is identical before and after a hide/show pair"
    (fake-fullscreen!)
    (layout/hold-menu-height! 12)
    (let [top (viewport-top)]
      ;; zero matches → menu hides, reservation stays
      (ac/clear-autocomplete-menu! 12)
      (is (= 23 (scroll-bottom)) "geometry unchanged by the hide")
      (is (= top (viewport-top)) "nothing moved when the menu hid")
      ;; a matching keystroke → menu reopens into rows it already owns
      (layout/hold-menu-height! 12)
      (is (= top (viewport-top)) "nothing moved when the menu came back"))))

(deftest release-is-safe-with-no-menu-on-screen
  (testing "the reservation outlives the menu, so release alone gives it back"
    (fake-fullscreen!)
    (let [before (scroll-bottom)]
      (layout/hold-menu-height! 12)
      (ac/clear-autocomplete-menu! 12)          ;; menu hidden, rows still held
      (layout/release-menu-height!)             ;; Enter / Esc
      (is (= before (scroll-bottom)))
      (is (not (layout/menu-height-held?))))))

(deftest unheld-dismiss-still-restores-immediately
  (testing "with no hold, clearing the menu gives the rows back as before"
    (fake-fullscreen!)
    (let [before (scroll-bottom)]
      (layout/set-menu-height! 12)
      (is (= (- before 12) (scroll-bottom)))
      (ac/clear-autocomplete-menu! 12)
      (is (= before (scroll-bottom))))))

(deftest a-taller-hold-still-applies
  (testing "the hold is a floor, not a freeze — a resize re-pins at a new height"
    (fake-fullscreen!)
    (layout/hold-menu-height! 12)
    (layout/hold-menu-height! 16)
    (is (= (- 35 16) (scroll-bottom)))
    (layout/release-menu-height!)
    (is (= 35 (scroll-bottom)))))

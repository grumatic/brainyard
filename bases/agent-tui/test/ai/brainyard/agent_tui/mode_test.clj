;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.mode-test
  "Cover the §2.7 decision matrix from docs/simplified-agent-tui-arch-design.md."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.agent-tui.mode :as mode]))

(defn- with-env
  "Bind `mode/getenv` to a fixed map for the duration of `f`."
  [env f]
  (with-redefs [mode/getenv (fn [k] (get env k))]
    (f)))

(defn- with-tmux-on-path
  "Bind `mode/which` so it returns a path for `tmux` (or nil) and
   `mode/tmux-server-alive?*` to a constant. Only the *value* of `alive?` is
   asserted by tests; `tmux-bin` here is a sentinel."
  [{:keys [on-path? alive?]} f]
  (with-redefs [mode/which (fn [bin]
                             (when (and (= bin "tmux") on-path?) "/usr/bin/tmux"))
                mode/tmux-server-alive?* (fn [_env _bin] (boolean alive?))]
    (f)))

(deftest matrix-default-no-flag
  (testing "Mode A when --with-tmux not passed and not inside tmux"
    (with-env {"TMUX" nil}
      #(with-tmux-on-path {:on-path? false :alive? false}
         (fn []
           (let [r (mode/probe {})]
             (is (= :A (:mode r)))
             (is (false? (:explicit-with-tmux? r)))
             (is (false? (:inside-tmux? r)))
             (is (nil? (:guidance r))))))))

  (testing "Mode A when tmux installed but $TMUX empty"
    (with-env {"TMUX" ""}
      #(with-tmux-on-path {:on-path? true :alive? false}
         (fn []
           (let [r (mode/probe {})]
             (is (= :A (:mode r)))
             (is (true? (:tmux-on-path? r)))
             (is (false? (:inside-tmux? r))))))))

  (testing "Mode B when $TMUX is set and server alive (default no flag)"
    (with-env {"TMUX" "/tmp/tmux-501/default,12345,0"}
      #(with-tmux-on-path {:on-path? true :alive? true}
         (fn []
           (let [r (mode/probe {})]
             (is (= :B (:mode r)))
             (is (true? (:inside-tmux? r)))
             (is (true? (:tmux-server-alive? r)))
             (is (nil? (:guidance r))))))))

  (testing "Mode A when $TMUX is set but server is dead (default no flag)"
    (with-env {"TMUX" "/tmp/tmux-501/default,12345,0"}
      #(with-tmux-on-path {:on-path? true :alive? false}
         (fn []
           (let [r (mode/probe {})]
             (is (= :A (:mode r)))
             (is (true? (:inside-tmux? r)))
             (is (false? (:tmux-server-alive? r)))))))))

(deftest matrix-with-tmux-flag
  (testing "Mode B when --with-tmux + $TMUX set + alive"
    (with-env {"TMUX" "/tmp/tmux/sock,1,0"}
      #(with-tmux-on-path {:on-path? true :alive? true}
         (fn []
           (is (= :B (:mode (mode/probe {:with-tmux true}))))))))

  (testing "Mode C when --with-tmux but $TMUX empty (need session)"
    (with-env {"TMUX" nil}
      #(with-tmux-on-path {:on-path? true :alive? false}
         (fn []
           (let [r (mode/probe {:with-tmux true})]
             (is (= :C (:mode r)))
             (is (string? (:guidance r)))
             (is (re-find #"start a tmux session|tmux new" (:guidance r))))))))

  (testing "Mode C when --with-tmux but tmux not on PATH (need install)"
    (with-env {"TMUX" nil}
      #(with-tmux-on-path {:on-path? false :alive? false}
         (fn []
           (let [r (mode/probe {:with-tmux true})]
             (is (= :C (:mode r)))
             (is (re-find #"not on \$PATH|brew install tmux|apt-get install tmux"
                          (:guidance r))))))))

  (testing "Mode C when --with-tmux + $TMUX set but server dead"
    (with-env {"TMUX" "/tmp/tmux/sock,1,0"}
      #(with-tmux-on-path {:on-path? true :alive? false}
         (fn []
           (let [r (mode/probe {:with-tmux true})]
             (is (= :C (:mode r)))
             (is (re-find #"server isn't responding|tmux new" (:guidance r))))))))

  (testing "Mode C when --with-tmux + tmux not on PATH (even if $TMUX is set)"
    ;; $TMUX leaked from a parent shell where tmux *was* installed; `by` runs
    ;; in a stripped PATH. Nothing the user can do but install tmux.
    (with-env {"TMUX" "/tmp/tmux/sock,1,0"}
      #(with-tmux-on-path {:on-path? false :alive? false}
         (fn []
           (let [r (mode/probe {:with-tmux true})]
             (is (= :C (:mode r)))
             (is (re-find #"not on \$PATH" (:guidance r)))))))))

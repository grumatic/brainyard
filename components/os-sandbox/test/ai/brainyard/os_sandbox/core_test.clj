;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.os-sandbox.core-test
  "Unit tests for the pure SBPL profile builder, sandbox-exec argv builder,
   writable-roots parsing, and self-exec resolution. Process spawning (serve!)
   is host-dependent and exercised by the end-to-end smoke test, not here."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.brainyard.os-sandbox.core :as core]))

;; ---------------------------------------------------------------------------
;; build-profile-string
;; ---------------------------------------------------------------------------

(def ^:private base-opts
  {:home "/Users/jake" :cwd "/Users/jake/proj" :project-dir "/Users/jake/proj"
   :tmpdir "/var/folders/xx/T"})

(deftest profile-baseline-capabilities
  (let [p (core/build-profile-string base-opts)]
    (testing "write-containment baseline is present"
      (is (str/includes? p "(version 1)"))
      (is (str/includes? p "(deny default)"))
      (is (str/includes? p "(allow file-read*)"))
      (is (str/includes? p "(allow process-exec*)"))
      (is (str/includes? p "(allow process-fork)")))
    (testing "TTY ioctl is allowed (raw mode needs tcsetattr)"
      (is (str/includes? p "(allow file-ioctl (subpath \"/dev\"))")))
    (testing "POSIX semaphore allowed (GraalVM CSunMiscSignal startup needs sem_open)"
      (is (str/includes? p "(allow ipc-posix-sem*)")))))

(deftest profile-network-toggle
  (testing "network allowed by default"
    (is (str/includes? (core/build-profile-string base-opts) "(allow network*)")))
  (testing "network omitted when :network? false"
    (let [p (core/build-profile-string (assoc base-opts :network? false))]
      (is (not (str/includes? p "(allow network*)")))
      (is (str/includes? p "Network DENIED")))))

(deftest profile-write-allowlist
  (let [p (core/build-profile-string base-opts)]
    (testing "workspace + home brainyard + temp roots are writable"
      (is (str/includes? p "(allow file-write* (subpath \"/Users/jake/.brainyard\"))"))
      (is (str/includes? p "(allow file-write* (subpath \"/Users/jake/Library/Caches\"))"))
      (is (str/includes? p "(allow file-write* (subpath \"/Users/jake/proj\"))"))
      (is (str/includes? p "(allow file-write* (subpath \"/var/folders/xx/T\"))")))
    (testing "both symlinked and canonical temp forms are covered"
      (is (str/includes? p "(subpath \"/tmp\")"))
      (is (str/includes? p "(subpath \"/private/tmp\")"))
      (is (str/includes? p "(subpath \"/private/var/folders\")")))))

(deftest profile-extra-writes
  (let [p (core/build-profile-string (assoc base-opts :extra-writes ["/Users/jake/scratch" "/data"]))]
    (testing "each extra root becomes a write rule"
      (is (str/includes? p "(allow file-write* (subpath \"/Users/jake/scratch\"))"))
      (is (str/includes? p "(allow file-write* (subpath \"/data\"))")))))

(deftest profile-escapes-paths-with-spaces
  (let [p (core/build-profile-string (assoc base-opts :cwd "/Users/jake/My Repo"))]
    (testing "spaces survive inside the quoted SBPL literal"
      (is (str/includes? p "(subpath \"/Users/jake/My Repo\")")))))

;; ---------------------------------------------------------------------------
;; build-sandbox-argv
;; ---------------------------------------------------------------------------

(defn- pair-after [argv flag]
  (let [i (.indexOf ^java.util.List (vec argv) flag)]
    (when (>= i 0) (nth argv (inc i)))))

(deftest sandbox-argv-generated-profile
  (let [argv (core/build-sandbox-argv {:profile-string "(version 1)"
                                       :child-argv ["/usr/local/bin/by" "run" "-a" "coder"]})]
    (testing "sandbox-exec is the head, profile passed via -p"
      (is (= "/usr/bin/sandbox-exec" (first argv)))
      (is (= "(version 1)" (pair-after argv "-p"))))
    (testing "no -f and no -D for the generated (literal) profile"
      (is (not (some #{"-f"} argv)))
      (is (not (some #{"-D"} argv))))
    (testing "child argv (with its own flags) is passed through at the tail"
      (is (= ["/usr/local/bin/by" "run" "-a" "coder"]
             (subvec (vec argv) (- (count argv) 4)))))))

(deftest sandbox-argv-custom-profile
  (let [argv (core/build-sandbox-argv {:profile-path "/tmp/custom.sb"
                                       :params {:home "/Users/jake" :cwd "/Users/jake/proj"
                                                :project-dir "/Users/jake/proj" :tmpdir "/var/folders/x/T"}
                                       :child-argv ["/usr/local/bin/by" "run"]})]
    (testing "custom profile passed via -f, not -p"
      (is (= "/tmp/custom.sb" (pair-after argv "-f")))
      (is (not (some #{"-p"} argv))))
    (testing "params are forwarded as -D KEY=val for the custom profile"
      (is (some #{"HOME=/Users/jake"} argv))
      (is (some #{"CWD=/Users/jake/proj"} argv))
      (is (some #{"TMPDIR=/var/folders/x/T"} argv)))))

;; ---------------------------------------------------------------------------
;; parse-allow-writes
;; ---------------------------------------------------------------------------

(deftest parse-allow-writes-test
  (testing "nil → empty"
    (is (= [] (core/parse-allow-writes nil "/cwd" "/home"))))
  (testing "comma-separated string"
    (is (= ["/a" "/b"] (core/parse-allow-writes "/a,/b" "/cwd" "/home"))))
  (testing "vector input (cli-matic :multiple)"
    (is (= ["/a" "/b"] (core/parse-allow-writes ["/a" "/b"] "/cwd" "/home"))))
  (testing "tilde expands against home; ~ alone is home"
    (is (= ["/home/scratch"] (core/parse-allow-writes "~/scratch" "/cwd" "/home")))
    (is (= ["/home"] (core/parse-allow-writes "~" "/cwd" "/home"))))
  (testing "relative resolves against cwd"
    (is (= ["/cwd/sub"] (core/parse-allow-writes "sub" "/cwd" "/home"))))
  (testing "blanks dropped, trailing slashes trimmed, deduped"
    (is (= ["/a"] (core/parse-allow-writes "/a/,, ,/a" "/cwd" "/home")))))

;; ---------------------------------------------------------------------------
;; self-exec-argv
;; ---------------------------------------------------------------------------

(deftest self-exec-argv-override
  (testing "override var is whitespace-split"
    (is (= {:ok? true :argv ["bb" "tui"]}
           (core/self-exec-argv {"BY_SANDBOX_SELF" "bb tui"} "BY_SANDBOX_SELF")))
    (is (= {:ok? true :argv ["/path/to/by"]}
           (core/self-exec-argv {"BY_SANDBOX_SELF" "  /path/to/by  "} "BY_SANDBOX_SELF"))))
  (testing "blank override falls through to resolution (does not crash)"
    (let [r (core/self-exec-argv {"BY_SANDBOX_SELF" "   "} "BY_SANDBOX_SELF")]
      (is (contains? r :ok?))
      (is (or (true? (:ok? r)) (string? (:reason r)))))))

;; ---------------------------------------------------------------------------
;; available? — host-dependent; assert only the shape
;; ---------------------------------------------------------------------------

(deftest available-shape
  (let [r (core/available?)]
    (is (contains? r :ok?))
    (if (:ok? r)
      (is (= "/usr/bin/sandbox-exec" (:path r)))
      (is (string? (:reason r))))))

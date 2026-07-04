(ns ai.brainyard.clj-llm.parse-lm-str-test
  "string->lm-config resolution: `provider/model` preferred, legacy
   `provider:model` fallback (see llm/split-lm-str, llm/parse-lm-str). Also the
   `create-lm` :model normalization and the `format-lm-label` display helper."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.clj-llm.core.llm :as llm]
            [ai.brainyard.clj-llm.core.providers :as providers]))

(deftest split-lm-str-prefers-slash
  (testing "splits on the first `/` into [provider model]"
    (is (= ["openai" "gpt-5"] (llm/split-lm-str "openai/gpt-5")))
    (is (= ["ollama" "nomic-embed-text"] (llm/split-lm-str "ollama/nomic-embed-text"))))
  (testing "the `/` form keeps a model id that itself contains `:`"
    (is (= ["bedrock" "amazon.nova-lite-v1:0"]
           (llm/split-lm-str "bedrock/amazon.nova-lite-v1:0"))))
  (testing "only the FIRST `/` separates; further `/` stay in the model"
    (is (= ["openrouter" "anthropic/claude-3"]
           (llm/split-lm-str "openrouter/anthropic/claude-3")))))

(deftest split-lm-str-falls-back-to-colon
  (testing "with no `/`, splits on the first `:` (legacy form)"
    (is (= ["claude-code" "haiku"] (llm/split-lm-str "claude-code:haiku")))
    (is (= ["openai" "gpt-4o"] (llm/split-lm-str "openai:gpt-4o"))))
  (testing "the legacy `:` form keeps a model id with further `:`"
    (is (= ["bedrock" "amazon.nova-lite-v1:0"]
           (llm/split-lm-str "bedrock:amazon.nova-lite-v1:0")))))

(deftest split-lm-str-no-separator
  (testing "a bare token yields [token nil]"
    (is (= ["opus" nil] (llm/split-lm-str "opus")))))

(deftest parse-lm-str-resolves-both-forms
  (testing "`provider/model` resolves to that provider+model"
    (let [lm (llm/parse-lm-str "openai/gpt-5")]
      (is (= :openai (:provider lm)))
      (is (= "gpt-5" (:model lm)))))
  (testing "legacy `provider:model` still resolves"
    (let [lm (llm/parse-lm-str "openai:gpt-4o")]
      (is (= :openai (:provider lm)))
      (is (= "gpt-4o" (:model lm)))))
  (testing "blank / nil yield nil"
    (is (nil? (llm/parse-lm-str "")))
    (is (nil? (llm/parse-lm-str "   ")))
    (is (nil? (llm/parse-lm-str nil)))))

(deftest create-lm-normalizes-qualified-model
  (testing "a provider-qualified :model (slash or legacy colon) is split when the
            leading token is a registered provider"
    (doseq [spec ["claude-code/opus" "claude-code:opus"]]
      (let [lm (providers/create-lm {:model spec})]
        (is (= :claude-code (:provider lm)) (str spec " → :claude-code"))
        (is (= "opus" (:model lm)) (str spec " → bare model")))))
  (testing "an explicit :provider is honored; the embedded prefix is still stripped"
    (let [lm (providers/create-lm {:provider :claude-code :model "claude-code:opus"})]
      (is (= :claude-code (:provider lm)))
      (is (= "opus" (:model lm)))))
  (testing "a bare id whose ':' is part of the model (not a provider) is left whole"
    ;; Since the bare-Nova inference-profile rewrite (386dea0), create-lm may
    ;; prepend a region prefix ("us."/"eu."/"apac.") — region-dependent, so
    ;; assert the id survives intact as a suffix rather than pinning the
    ;; prefix. The point under test is that ':0' is NOT parsed as a
    ;; provider separator.
    (let [lm (providers/create-lm {:model "amazon.nova-lite-v1:0"})]
      (is (= :bedrock (:provider lm)))
      (is (clojure.string/ends-with? (:model lm) "amazon.nova-lite-v1:0"))))
  (testing "a plain model name is untouched"
    (let [lm (providers/create-lm {:model "gpt-4o"})]
      (is (= :openai (:provider lm)))
      (is (= "gpt-4o" (:model lm))))))

(deftest create-lm-keywordizes-string-provider
  (testing "an explicit string :provider (CLI boundary) is coerced to a keyword
            and hits the keyword-keyed registry"
    (let [lm (providers/create-lm {:provider "ollama" :model "llama3"})]
      (is (= :ollama (:provider lm)) "string provider keywordized")
      (is (= :openai (:message-format lm)) "registry config resolved (ollama → :openai format)")))
  (testing "a keyword :provider is unchanged (keyword coercion is idempotent)"
    (let [lm (providers/create-lm {:provider :ollama :model "llama3"})]
      (is (= :ollama (:provider lm))))))

(deftest format-lm-label-canonicalizes-display
  (testing "separate provider + bare model → provider/model"
    (is (= "openai/gpt-4o-mini" (providers/format-lm-label :openai "gpt-4o-mini"))))
  (testing "combined :model with no separate provider is normalized to '/'"
    (is (= "claude-code/sonnet" (providers/format-lm-label nil "claude-code:sonnet"))))
  (testing "a provider that matches the model's embedded prefix doesn't double up"
    (is (= "claude-code/opus" (providers/format-lm-label :claude-code "claude-code:opus"))))
  (testing "a bedrock id's own ':' survives in the label"
    (is (= "bedrock/amazon.nova-lite-v1:0"
           (providers/format-lm-label :bedrock "amazon.nova-lite-v1:0"))))
  (testing "missing pieces degrade gracefully"
    (is (= "opus" (providers/format-lm-label nil "opus")))
    (is (= "openai" (providers/format-lm-label :openai nil)))
    (is (= "?" (providers/format-lm-label nil nil)))))

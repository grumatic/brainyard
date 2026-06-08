;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent-tui.popup
  "Drive a single-tab radio questionnaire as an interactive `tmux display-popup`
   in Mode B.

   The popup runs a small embedded bash selector script:
     1. reads options + title + prompt from temp files,
     2. switches the popup tty to raw mode and hides the cursor,
     3. renders the option list with a reverse-video ▶ focus marker,
     4. reads keystrokes — Tab/↓ next, Shift-Tab/↑ prev, Enter to submit
        the focused option, Esc/Ctrl-C to cancel; letter/digit shortcuts
        still instant-submit for muscle-memory back-compat,
     5. writes the result (shortcut letter or 1-based digit) to a result
        file and exits — at which point `-E` tears the overlay down.

   We then map the byte against the questionnaire's option shortcuts/digits and
   return a `:popup-result` reply (`{:status :submitted | :cancelled :answers
   …}`).  Multi-tab navigation isn't supported here — for that, fall back to
   the in-stream prompt path."
  (:require [ai.brainyard.agent-tui-tmux.interface :as tmux]
            [clojure.string :as str])
  (:import [java.io File]))

(defn- pad-line [s width]
  (let [n (max 0 (- width (count s)))]
    (str s (apply str (repeat n " ")))))

(defn- render-radio-options [options]
  (->> options
       (map-indexed
        (fn [i {:keys [label shortcut]}]
          (let [bullet (or (some-> shortcut str) (str (inc i)))]
            (str "  ○ " bullet ". " label))))
       (str/join "\n")))

(defn render-text
  "Render a single-tab questionnaire as a plain ANSI-free string.

   Used by callers that want a static text representation (e.g. logs,
   the inline-fallback path).  The interactive popup itself ignores this
   and renders its own option list with focus highlighting — see
   `selector-script`."
  [{:keys [title tabs]}]
  (let [tab   (first tabs)
        body  (case (:type tab)
                (:radio :checkbox) (render-radio-options (:options tab))
                :text              (str "  > " (or (:placeholder tab) "(type your answer)"))
                :password          "  > (input hidden)")
        parts (cond-> []
                title (conj (pad-line (str " " title " ") 60))
                true  (conj "" (:prompt tab) "" body)
                true  (conj "" "  Tab/↓ next   Shift-Tab/↑ prev   Enter confirm   Esc cancel"))]
    (str/join "\n" parts)))

(defn- match-key
  "Map one keystroke string against a single-tab radio questionnaire's options
   and return the option's `:value`, or nil if no match."
  [tab keystroke]
  (let [opts (:options tab)
        digit (when (and (= 1 (count keystroke))
                         (Character/isDigit (.charAt ^String keystroke 0)))
                (let [idx (dec (Long/parseLong keystroke))]
                  (when (and (>= idx 0) (< idx (count opts)))
                    (:value (nth opts idx)))))
        shortcut (some (fn [{:keys [value shortcut]}]
                         (when (and shortcut (= (str shortcut) keystroke))
                           value))
                       opts)]
    (or shortcut digit)))

(def ^:private selector-script
  "Interactive bash 3.2+ option selector. Args: opts prompt result.

   - opts file:  pairs of lines — shortcut (possibly empty), then label.
   - prompt file: prompt text.
   - result file: written on exit — shortcut letter or 1-based digit for
     submit, empty for cancel.

   The popup title is rendered by tmux in the popup border (via
   `display-popup --title`), so the script does NOT re-render it inside
   the content area.

   Tab/↓ → next; Shift-Tab/↑ → prev; Enter → submit focused;
   Esc/Ctrl-C → cancel; letter/digit shortcuts → instant submit."
  "#!/usr/bin/env bash
set -u
opts_file=\"$1\"; prompt_file=\"$2\"; result_file=\"$3\"

labels=(); shortcuts=()
while IFS= read -r sc && IFS= read -r lbl; do
  shortcuts+=(\"$sc\"); labels+=(\"$lbl\")
done < \"$opts_file\"
n=${#labels[@]}
if [ \"$n\" -eq 0 ]; then : > \"$result_file\"; exit 0; fi

prompt=$(cat \"$prompt_file\" 2>/dev/null || echo \"\")

exec 3</dev/tty
old=$(stty -g <&3 2>/dev/null || true)
stty raw -echo -isig <&3 2>/dev/null || true
printf '\\e[?25l'  # hide cursor

cleanup() {
  printf '\\e[?25h'  # show cursor
  [ -n \"$old\" ] && stty \"$old\" <&3 2>/dev/null || true
}
trap cleanup EXIT INT TERM

sel=0

draw() {
  printf '\\e[H\\e[2J'  # cursor home + clear screen
  printf '\\r\\n'        # leading blank row for breathing room below border
  if [ -n \"$prompt\" ]; then
    printf '  %s\\r\\n\\r\\n' \"$prompt\"
  fi
  local i=0
  while [ \"$i\" -lt \"$n\" ]; do
    if [ \"$i\" -eq \"$sel\" ]; then
      printf '  \\e[1;7m\\xe2\\x96\\xb6 %s\\e[0m\\r\\n' \"${labels[$i]}\"
    else
      printf '    %s\\r\\n' \"${labels[$i]}\"
    fi
    i=$((i+1))
  done
  printf '\\r\\n  \\e[2mTab/\\xe2\\x86\\x93 next   Shift-Tab/\\xe2\\x86\\x91 prev   Enter confirm   Esc cancel\\e[0m\\r\\n'
}

submit_idx() {
  local idx=\"$1\"
  local sc=\"${shortcuts[$idx]}\"
  if [ -n \"$sc\" ]; then
    printf '%s' \"$sc\" > \"$result_file\"
  else
    printf '%d' \"$((idx + 1))\" > \"$result_file\"
  fi
  exit 0
}

cancel_exit() { : > \"$result_file\"; exit 0; }

draw

while true; do
  key=$(dd bs=1 count=1 2>/dev/null <&3)
  case \"$key\" in
    $'\\r'|$'\\n')           submit_idx \"$sel\" ;;
    $'\\x03')                cancel_exit ;;
    $'\\t')                  sel=$(( (sel + 1) % n )); draw ;;
    $'\\e')
      stty -icanon time 1 min 0 <&3 2>/dev/null || true
      seq=$(dd bs=4 count=1 2>/dev/null <&3)
      stty -icanon time 0 min 1 <&3 2>/dev/null || true
      case \"$seq\" in
        '[A'|'OA')  sel=$(( (sel - 1 + n) % n )); draw ;;
        '[B'|'OB')  sel=$(( (sel + 1) % n )); draw ;;
        '[Z')       sel=$(( (sel - 1 + n) % n )); draw ;;
        '')         cancel_exit ;;
      esac
      ;;
    *)
      matched=0
      i=0
      while [ \"$i\" -lt \"$n\" ]; do
        if [ -n \"${shortcuts[$i]}\" ] && [ \"${shortcuts[$i]}\" = \"$key\" ]; then
          submit_idx \"$i\"; matched=1; break
        fi
        i=$((i+1))
      done
      if [ \"$matched\" -eq 0 ] && [[ \"$key\" =~ ^[1-9]$ ]] && [ \"$key\" -le \"$n\" ]; then
        submit_idx \"$((key - 1))\"
      fi
      ;;
  esac
done
")

(defn- ^File write-script!
  "Write `selector-script` to a temp file, mark executable, return the
   absolute path. The file is `.deleteOnExit`-tagged so the JVM cleans it
   up; we also delete explicitly after the popup completes."
  []
  (let [f (doto (File/createTempFile "by-popup-sel-" ".sh") (.deleteOnExit))]
    (spit f selector-script)
    (.setExecutable f true)
    f))

(defn- options->opts-file
  "Pair of lines per option — shortcut (possibly empty), then label —
   so the bash side reads `read sc; read lbl` per pair without worrying
   about labels containing pipe / tab / other separator chars."
  [options]
  (str/join "\n"
            (mapcat (fn [{:keys [shortcut label]}]
                      [(if shortcut (str shortcut) "") (or label "")])
                    options)))

(def ^:private text-entry-script
  "Interactive bash 3.2+ free-text entry field. Args: prompt mask result.

   - prompt file: prompt text rendered above the field.
   - mask: non-empty → echo '*' per char (password).
   - result file (LAST arg): written on exit — 'S' followed by the typed text
     on submit (so an empty submit is the single byte 'S'); empty on cancel.

   Enter → submit; Esc / Ctrl-C → cancel; Backspace → delete last char;
   printable bytes append to the buffer (multi-byte UTF-8 accumulates byte by
   byte)."
  "#!/usr/bin/env bash
set -u
prompt_file=\"$1\"; mask=\"$2\"; result_file=\"$3\"
prompt=$(cat \"$prompt_file\" 2>/dev/null || echo \"\")

exec 3</dev/tty
old=$(stty -g <&3 2>/dev/null || true)
stty raw -echo -isig <&3 2>/dev/null || true
printf '\\e[?25h'  # show cursor for text entry

cleanup() {
  [ -n \"$old\" ] && stty \"$old\" <&3 2>/dev/null || true
}
trap cleanup EXIT INT TERM

buf=\"\"

draw() {
  printf '\\e[H\\e[2J'
  printf '\\r\\n'
  if [ -n \"$prompt\" ]; then
    printf '  %s\\r\\n\\r\\n' \"$prompt\"
  fi
  printf '  \\e[2mEnter confirm   Esc cancel   Backspace delete\\e[0m\\r\\n\\r\\n'
  if [ -n \"$mask\" ]; then
    local shown=\"\" i=0
    while [ \"$i\" -lt \"${#buf}\" ]; do shown=\"$shown*\"; i=$((i+1)); done
    printf '  > %s' \"$shown\"
  else
    printf '  > %s' \"$buf\"
  fi
}

draw

while true; do
  key=$(dd bs=1 count=1 2>/dev/null <&3)
  case \"$key\" in
    $'\\r'|$'\\n')      printf 'S%s' \"$buf\" > \"$result_file\"; exit 0 ;;
    $'\\x03')           : > \"$result_file\"; exit 0 ;;
    $'\\x7f'|$'\\x08')  buf=\"${buf%?}\"; draw ;;
    $'\\e')
      stty -icanon time 1 min 0 <&3 2>/dev/null || true
      seq=$(dd bs=4 count=1 2>/dev/null <&3)
      stty -icanon time 0 min 1 <&3 2>/dev/null || true
      if [ -z \"$seq\" ]; then : > \"$result_file\"; exit 0; fi
      draw
      ;;
    '')                 : ;;
    *)                  buf=\"$buf$key\"; draw ;;
  esac
done
")

(defn- ^File write-text-script!
  "Write `text-entry-script` to a temp executable file (deleteOnExit-tagged)."
  []
  (let [f (doto (File/createTempFile "by-popup-text-" ".sh") (.deleteOnExit))]
    (spit f text-entry-script)
    (.setExecutable f true)
    f))

(defn- show-text!
  "Show a single `:text` / `:password` tab as an interactive free-text tmux
   popup. Returns `{:status :submitted :answers {<tab-id> {:input s}}}` or
   `{:status :cancelled …}`."
  [tmux-impl questionnaire tab {:keys [width height]
                                :or {width 70 height 10}}]
  (let [prompt-file (doto (File/createTempFile "by-popup-prompt-" ".txt") (.deleteOnExit))
        result-file (doto (File/createTempFile "by-popup-result-" ".txt") (.deleteOnExit))
        script-file (write-text-script!)
        mask        (if (= :password (:type tab)) "1" "")
        _ (spit prompt-file (or (:prompt tab) ""))
        cmd (str "bash "
                 (pr-str (.getAbsolutePath script-file)) " "
                 (pr-str (.getAbsolutePath prompt-file)) " "
                 (pr-str mask) " "
                 (pr-str (.getAbsolutePath result-file)))
        exit (try (tmux/display-popup! tmux-impl
                                       {:command cmd
                                        :width width
                                        :height height
                                        :title (:title questionnaire)
                                        :close-on-exit? true})
                  (catch Throwable _ -1))
        raw  (try (slurp result-file) (catch Throwable _ ""))]
    (doseq [^java.io.File f [prompt-file result-file script-file]]
      (try (.delete f) (catch Throwable _)))
    (if (and (number? exit) (zero? exit)
             (seq raw) (= \S (.charAt ^String raw 0)))
      {:status :submitted :id (:id questionnaire)
       :answers {(:id tab) {:input (subs raw 1)}}}
      {:status :cancelled :id (:id questionnaire) :answers {}})))

(defn- show-radio!
  "Show a single `:radio` / `:checkbox` tab as the interactive option selector.
   Tab/Shift-Tab + Arrow Up/Down navigation, Enter to confirm, Esc / Ctrl-C to
   cancel; letter and digit shortcuts instant-submit. Returns a reply map
   `{:status :submitted | :cancelled :id … :answers {<tab-id> {:value v}}}`."
  [tmux-impl questionnaire {:keys [width height]
                            :or {width 70 height 16}}]
  (let [tab         (first (:tabs questionnaire))
        opts-file   (doto (File/createTempFile "by-popup-opts-" ".txt") (.deleteOnExit))
        prompt-file (doto (File/createTempFile "by-popup-prompt-" ".txt") (.deleteOnExit))
        result-file (doto (File/createTempFile "by-popup-result-" ".txt") (.deleteOnExit))
        script-file (write-script!)
        _ (spit opts-file (options->opts-file (:options tab)))
        _ (spit prompt-file (or (:prompt tab) ""))
        cmd (str "bash "
                 (pr-str (.getAbsolutePath script-file)) " "
                 (pr-str (.getAbsolutePath opts-file)) " "
                 (pr-str (.getAbsolutePath prompt-file)) " "
                 (pr-str (.getAbsolutePath result-file)))
        exit (try (tmux/display-popup! tmux-impl
                                       {:command cmd
                                        :width width
                                        :height height
                                        :title (:title questionnaire)
                                        :close-on-exit? true})
                  (catch Throwable _ -1))
        raw  (try (slurp result-file) (catch Throwable _ ""))]
    (doseq [^java.io.File f [opts-file prompt-file result-file script-file]]
      (try (.delete f) (catch Throwable _)))
    (cond
      (or (not (number? exit))
          (not (zero? exit))
          (str/blank? raw))
      {:status :cancelled :id (:id questionnaire) :answers {}}

      :else
      (if-let [v (match-key tab raw)]
        {:status :submitted :id (:id questionnaire) :answers {(:id tab) {:value v}}}
        {:status :cancelled :id (:id questionnaire) :answers {}}))))

(defn show!
  "Show `questionnaire` as an interactive tmux popup using `tmux-impl`.
   Dispatches on the first tab's `:type`:
     :radio / :checkbox — option selector (Tab/arrows + shortcuts, Enter
                          confirm); reply `{:answers {<id> {:value v}}}`.
     :text / :password  — free-text field (typed chars + Backspace, Enter
                          confirm); reply `{:answers {<id> {:input s}}}`.
   Esc / Ctrl-C cancels either. Blocks until submit/cancel; returns a reply
   map `{:status :submitted | :cancelled :id … :answers …}`."
  [tmux-impl questionnaire opts]
  (let [tab (first (:tabs questionnaire))]
    (if (contains? #{:text :password} (:type tab))
      (show-text! tmux-impl questionnaire tab opts)
      (show-radio! tmux-impl questionnaire opts))))

(defn feasible?
  "True iff this tmux server can render an interactive popup right now: the
   binary is at least 3.2 *and* the current client is tall enough to host a
   16-row popup with breathing room.  Per design doc §11.4 we fall back to
   in-stream prompts on small terminals even in Mode B."
  [tmux-impl]
  (boolean
   (and (tmux/supports-popup? tmux-impl)
        (try
          (let [s (tmux/display-message tmux-impl {:format "#{client_height}"})
                h (some-> s str/trim parse-long)]
            (and h (>= h 24)))
          (catch Throwable _ false)))))

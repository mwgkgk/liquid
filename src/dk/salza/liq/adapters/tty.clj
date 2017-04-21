(ns dk.salza.liq.adapters.tty
  (:require [dk.salza.liq.tools.util :as util]
            [dk.salza.liq.keys :as keys]
            [dk.salza.liq.editor :as editor]
            [dk.salza.liq.window :as window]
            [clojure.string :as str]))

(def old-lines (atom {}))
(def updater (ref (future nil)))

(defn reset
  []
  (reset! old-lines {})
  (print "\033[0;37m\033[2J"))

(defn rows
  []
  (let [shellinfo (with-out-str (util/cmd "/bin/sh" "-c" "stty size </dev/tty"))]
    (Integer/parseInt (re-find #"^\d+" shellinfo)))) ; (re-find #"\d+$" "50 120")

(defn columns
  []
  (let [shellinfo (with-out-str (util/cmd "/bin/sh" "-c" "stty size </dev/tty"))]
    (Integer/parseInt (re-find #"\d+$" shellinfo)))) ; (re-find #"\d+$" "50 120")

(defn print-color
  [index & strings] ;   0         1          2        3          4         5        6    7   8        9     10
  (let [colorpalette ["0;37" "38;5;131" "38;5;105" "38;5;11" "38;5;40" "38;5;117" "42" "44" "45" "48;5;235" "49"]]
    (print (str "\033[" (colorpalette index) "m" (apply str strings)))))

(defn print-lines
  [lineslist]
  ;(reset)
  ;; Redraw whole screen once in a while
  ;; (when (= (rand-int 100) 0)
  ;;  (reset! old-lines {})
  ;;  (print "\033[0;37m\033[2J"))
  (doseq [line (apply concat lineslist)]
    (let [row (line :row)
          column (line :column)
          content (line :line)
          key (str "k" row "-" column)
          oldcontent (@old-lines key)] 
    (when (not= oldcontent content)
      (let [diff (max 1 (- (count (filter #(and (string? %) (not= % "")) oldcontent))
                           (count (filter #(and (string? %) (not= % "")) content))))
            padding (format (str "%" diff "s") " ")]
        (print (str "\033[" row ";" column "H\033[s"))
        (print-color  9 " ")
        (print-color 0)
        (print-color 10)
        (doseq [ch (line :line)]
          (if (string? ch)
            (if (= ch "\t") (print (char 172)) (print ch)) 
            (do
              (cond (= (ch :face) :string) (print-color 1)
                    (= (ch :face) :comment) (print-color 2)
                    (= (ch :face) :type1) (print-color 3) ; defn
                    (= (ch :face) :type2) (print-color 4) ; function
                    (= (ch :face) :type3) (print-color 5) ; keyword
                    :else (print-color 0))
              (cond (= (ch :bgface) :cursor1) (print-color 6)
                    (= (ch :bgface) :cursor2) (print-color 7)
                    (= (ch :bgface) :selection) (print-color 8)
                    (= (ch :bgface) :statusline) (print-color 9)
                    :else (print-color 10))
            )))
        (if (= row (count (first lineslist)))
          (do
            (print (str "  " padding))
            (print-color 0))
          (print-color 10 padding)))
      (swap! old-lines assoc key content))
    ))
  (flush))


(defn view-draw
  []
  (let [windows (reverse (editor/get-windows))
        buffers (map #(editor/get-buffer (window/get-buffername %)) windows)
        lineslist (doall (map #(window/render %1 %2) windows buffers))]
        ;(when (editor/check-full-gui-update)
        ;  ((@adapter :reset)))
     (print-lines lineslist)))

(defn quit
  []
  (print "\033[0;37m\033[2J")
  (print "\033[?25h")
  (flush)
  (util/cmd "/bin/sh" "-c" "stty -echo cooked </dev/tty")
  (util/cmd "/bin/sh" "-c" "stty -echo sane </dev/tty")
  (println "")
  (System/exit 0))

(defn view-handler
  [key reference old new]
  (remove-watch editor/editor key)
  (when (future-done? @updater)
    (dosync (ref-set updater
            (future
              (loop [u @editor/updates]
                (view-draw)
                (when (not= u @editor/updates)
                  (recur @editor/updates)))))))
  (add-watch editor/updates key view-handler))

(defn model-update
  [input]
  (future (editor/handle-input input)))

(defn input-handler
  []
  (future
    (let [r (java.io.BufferedReader. *in*)
          read-input (fn [] (keys/raw2keyword (+ (.read r)
                                                 (if (.ready r) (* 256 (+ (.read r) 1)) 0)
                                                 (if (.ready r) (* 256 256 (+ (.read r) 1)) 0))))]
    (loop [input (read-input)]
      (when (= input :C-q) (quit)) ; todo: Handle unsaved files
      (model-update input)
      (recur (read-input))))))

(defn view-init
  []
  (util/cmd "/bin/sh" "-c" "stty -echo raw </dev/tty")
  (print "\033[0;37m\033[2J")
  (print "\033[?25l") ; Hide cursor
  (print "\033[?7l") ; disable line wrap
  (add-watch editor/editor "tty" view-handler))

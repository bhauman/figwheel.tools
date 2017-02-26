(ns cljs.tools.repl.io.reader-helper
  (:gen-class
   :name cljs.tools.repl.io.ReaderHelper
   :extends java.io.Reader
   :state state
   :init init
   :constructors {[] []}
   :main false
   :methods [[write [String] void]]))

(defn -init []
  [[] {:bq (java.util.concurrent.LinkedBlockingQueue.)
       :closed (atom false)}])

(defn take-as-much
  "This just blocks on the first take and then takes as much as it
  can."
  [bq]
  (cons (.take bq)
        (lazy-seq
         (when (.peek bq)
           (take-as-much bq)))))

(defn copy-to [seq' out-array off maxlen]
  (let [seq' (vec seq')
        size (count seq')]
    (dotimes [n size]
      (aset out-array (+ off n) (nth seq' n)))
    size))

(defn read-helper [bq out-array off maxlen]
  (let [res (take maxlen (take-as-much bq))]
    (if (first (filter #(= -1 %) res))
      -1
      (copy-to res out-array off maxlen))))

(defn -read-char<>-int-int [this out-array off maxlen]
  (if @(:closed (.state this))
    -1
    (read-helper (:bq (.state this)) out-array off maxlen)))

(defn -close [this]
  (let [{:keys [bq closed]} (.state this)]
    (reset! (:closed (.state this)) true)
    (.offer bq -1)))

(defn -write [this s]
  (let [{:keys [bq closed]} (.state this)]
    (when-not @closed
      (doseq [c s] (.offer bq c)))))

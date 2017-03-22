(ns figwheel.tools.repl.io.appender-reader)

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

;; this is not the best way to handle interupts but right now there are limited
;; ways of getting into the running cljs repl loop
(defn read-helper
  "This reader helper will cut short if a -1 is present anywhere in the 
read character sequence"
  [bq out-array off maxlen]
  (let [res (take maxlen (take-as-much bq))]
    (if (first (filter #(= -1 %) res))
      -1
      (copy-to res out-array off maxlen))))

(defn appender-reader []
  (let [bq (java.util.concurrent.LinkedBlockingQueue.)
        closed (atom false)
        lock-obj (Object.)]
    {:bq bq
     :closed closed
     :appender-reader
     (proxy [java.io.Reader java.lang.Appendable] []
       (read
         ([]
          (if @closed -1
              (let [c (.take bq)]
                (if (integer? c) c (int c)))))
         ([x]
          (if @closed
            -1
            (read-helper bq x 0 (count x))))
         ([^chars out-array off maxlen]
          (if @closed
            -1
            (read-helper bq out-array off maxlen))))
       (close []
         (reset! closed true)
         (.offer bq -1))
       (append
         ([c]
          (do
            (when-not @closed
              (locking lock-obj
                (cond
                  (char? c) (.offer bq c)
                  (instance? java.lang.CharSequence c) (doseq [ch c] (.offer bq ch)))))
            this))
         ([^java.lang.CharSequence csq start end]
          (do
            (when-not @closed
              (locking lock-obj
                (doseq [ch (str (.subSequence csq start end))]
                  (.offer bq ch))))
            this))))}))

(defn reader-closed? [app-read]
  @(:closed app-read))

(defn reader-empty? [app-read]
  (zero? (.size (:bq app-read))))

(defn reader-append [app-read s]
  (.append (:appender-reader app-read) s))

(def get-reader :appender-reader)


(comment
  (let [wr (:appender-reader (appender-reader))]
    (.append wr \c)
    (.append wr \c)
    (.append wr "hello")
    (.append wr "mine" 2 3)
    (.read wr (char-array 30)))

  )

(ns cljs.tools.repl.io.print-writer
  (:import
   (java.nio CharBuffer)
   (java.io Writer PrintWriter)))

;; look at print writer here
;; https://github.com/clojure/tools.nrepl/blob/master/src/main/clojure/clojure/tools/nrepl/middleware/session.clj

;; this can be all be optimized

(defn read-buf [buf]
  (.flip buf)
  (let [text (str buf)]
    (.clear buf)
    text))

(defn write-buf [^Writer writer ^CharBuffer buf ^CharSequence char-seq]
  (if (< (.length char-seq) (.remaining buf))
    (.append buf char-seq)
    (dotimes [n (.length char-seq)]
      (when-not (.hasRemaining buf)
        (.flush writer))
      (.append buf (.charAt char-seq n)))))

(defn subsequence-char-array [ch-array off len]
  (str (doto (java.lang.StringBuilder.)
         (.append ch-array off len))))

(defn print-writer [channel-type handler]
  (let [buf (CharBuffer/allocate 1024)]
    (PrintWriter. (proxy [Writer] []
                    (close [] (.flush ^Writer this))
                    (write [& [x ^Integer off ^Integer len]]
                      (when-not (.hasRemaining buf)
                        (.flush ^Writer this))
                      (locking buf
                        (cond
                          (number? x) (.append buf (char x))
                          (not off)   (.append buf x)
                          (instance? CharSequence x)
                          (write-buf this buf (.subSequence x (int off) (int (+ len off))))
                          :else
                          (write-buf this buf (str (doto (java.lang.StringBuilder.)
                                                     (.append x off len))))))
                      (when-not (.hasRemaining buf)
                        (.flush ^Writer this)))
                    (flush []
                      (let [text (locking buf
                                   (read-buf buf))]
                        (when (pos? (count text))
                          (handler {:type ::output 
                                    :channel channel-type
                                    :text text})))))
                  true)))

(comment

  (let [res (atom [])
        out (print-writer :out (fn [x] (swap! res conj x)))]
    (binding [*out* out]
      (println (slurp "project.clj")))
    (.flush out)
    @res)

  )

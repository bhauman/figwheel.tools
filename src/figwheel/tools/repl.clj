(ns figwheel.tools.repl
  (:require
   [cljs.analyzer]
   [cljs.repl]
   [cljs.env]
   [cljs.repl.nashorn :as nash]
   [figwheel.tools.repl.utils :as utils]
   [figwheel.tools.repl.io.appender-reader :refer [appender-reader] :as app-read]
   [figwheel.tools.repl.io.print-writer :refer [print-writer]]
   [clojure.java.io :as io]
   [clojure.main])
  (:import
   [clojure.lang LineNumberingPushbackReader]))

(defn handle-warnings-and-output-eval [out err warning-handler]
  (fn evaler*
    ([repl-env env form]
     (evaler* repl-env env form cljs.repl/*repl-opts*))
    ([repl-env env form opts]
     (binding [cljs.analyzer/*cljs-warning-handlers*
               [(warning-handler repl-env form opts)]
               *out* out
               *err* err]
       (#'cljs.repl/eval-cljs repl-env env form opts)))))

(defn thread-cljs-repl [repl-env-thunk options handler]
  (let [repl-eval-timeout (get :repl-eval-timeout options 30000)
        options (dissoc options :repl-eval-timeout)

        writer-reader (appender-reader)
        out (print-writer :out handler)
        err (print-writer :err handler)
        flush-out (fn [] (.flush err) (.flush out))
        repl-env  (if (fn? repl-env-thunk)
                    (binding [*out* out *err* err]
                      (repl-env-thunk))
                    repl-env-thunk)
        ;; as there is no official protocal to obtain a compiler-env
        ;; from a repl-env we have to use a hack to get it
        ;; see the print handler below
        cljs-compiler-env (atom nil)]
    {:repl-eval-timeout repl-eval-timeout
     :cljs-compiler-env cljs-compiler-env
     :repl-env repl-env
     :repl-thread
     (let [t (Thread.
              (binding [*out* out
                        *err* err
                        *in* (LineNumberingPushbackReader. (app-read/get-reader writer-reader))]
                (bound-fn []
                  (cljs.repl/repl*
                   ;; this is a thunk because certain repls like nashorn
                   ;; bind out and err on creation not setup
                   repl-env
                   (merge
                    {:need-prompt (constantly false)
                     :init (fn [])
                     :prompt (fn [])
                     :bind-err false
                     :quit-prompt (fn [])
                     :flush flush-out
                     :eval (handle-warnings-and-output-eval
                            out err
                            (fn [repl-env form opts]
                              (fn [warning-type env extra]
                                (handler
                                 (merge
                                  {:form form
                                   :type ::eval-warning}
                                  (utils/extract-warning-data warning-type env extra))))))
                     :print
                     (fn [result & rest]
                       ;; capture the compiler env
                       (when-not @cljs-compiler-env
                         (reset! cljs-compiler-env cljs.env/*compiler*))
                       (flush-out)
                       (handler {:type ::eval-value
                                 :value (or result "nil")
                                 :printed-value 1
                                 :ns cljs.analyzer/*cljs-ns*}))
                     :caught
                    (fn [err repl-env repl-options]
                      (let [root-ex (#'clojure.main/root-cause err)]
                        (when-not (instance? ThreadDeath root-ex)
                          (handler {:type ::eval-error
                                    :exception err}))))}
                    options))
                  (.close (:appender-reader writer-reader)))))]
       (.start t)
       t)
     :writer-reader writer-reader}))

(defn repl-running? [{:keys [writer-reader]}]
  (not (app-read/reader-closed? writer-reader)))

(defn empty-read-input? [thread-repl]
  (app-read/reader-empty? (:writer-reader thread-repl)))

(defn repl-eval [thread-repl s]
  ;; so given that each form is a valid single form
  ;; we will block on write
  (let [start (System/currentTimeMillis)]
    (loop []
      (cond
        ;; give up on timeout
        (> (- (System/currentTimeMillis) start) (get thread-repl :repl-eval-timeout 30000))
        (throw (ex-info "Timed out writing for REPL to accept input" {}))
        (not (empty-read-input? thread-repl))
        (do
          (Thread/sleep 100)
          (recur))
        :else
        (app-read/reader-append (:writer-reader thread-repl) (str s "\n"))))))

(defn kill-repl [thread-repl]
  ;; this will close the repl
  (.close (:appender-reader (:writer-reader thread-repl)))
  (.join (:repl-thread thread-repl) 2000)
  (.stop (:repl-thread thread-repl)))

(comment

  (prn (.state (:writer-reader repler)))

  (f/start-figwheel!)
  (f/stop-figwheel!)

  (def output (atom []))


  (def repler (thread-cljs-repl f/repl-env
                                #(swap! output conj %)))

  (def repler (thread-cljs-repl nash/repl-env
                                #(swap! output conj %)))

  (defn ev-help [txt txt2]
    (reset! output [])
    (repl-eval (deref (var repler)) txt)
    (repl-eval (deref (var repler)) txt2)
    (Thread/sleep 100)
    @output)

  (ev-help "(+ 1 2)" "(+ 15 2)")

  (ev-help "(list 1 2 3)")

  (ev-help "(prn 1)")

  (repl-eval repler ":cljs/quit")
  (repl-running? repler)
  (kill-repl repler)
  )

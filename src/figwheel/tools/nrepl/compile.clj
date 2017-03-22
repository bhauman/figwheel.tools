(ns figwheel.tools.nrepl.compile
  (:require
   [figwheel.tools.repl.utils :as utils]
   [figwheel.tools.repl.io.print-writer :refer [print-writer]]
   [cljs.build.api :as bapi]))

;; TODO include error and warning parsing code
(defn cljs-build [{:keys [source-paths options compiler-env out err handler]}]
  (binding [cljs.analyzer/*cljs-warning-handlers*
            [(fn [repl-env form opts]
               (fn [warning-type env extra]
                 (handler
                  (merge
                   {:form form
                    :type ::cljs-compile-warning}
                   (utils/extract-warning-data warning-type env extra)))))]
            *out* (print-writer :out handler)
            *err* (print-writer :err handler)]
    (try
      (bapi/build (apply bapi/inputs source-paths) options compiler-env)
      (catch Exception err
        (handler
         {:type ::cljs-compile-error
          :exception err})))))

;; create and associate a compiler process







(ns figwheel.tools.repl.utils
  (:require
   [clojure.string :as string]))

(def ^:dynamic *debug* false)

(defn log [& args]
  (when *debug*
    (apply prn args)))

(defn format-line-column [{:keys [line column]}]
  (->> (cond-> []
         line   (conj (str "line " line))
         column (conj (str "column " column)))
       (interpose ", ")
       (string/join "")))

(defn extract-warning-data [warning-type env extra]
  (when (warning-type cljs.analyzer/*cljs-warnings*)
    (when-let [s (cljs.analyzer/error-message warning-type extra)]
      {:line   (:line env)
       :column (:column env)
       :ns     (-> env :ns :name)
       :file (if (= (-> env :ns :name) 'cljs.core)
               "cljs/core.cljs"
               cljs.analyzer/*cljs-file*)
       :message s
       :extra   extra})))

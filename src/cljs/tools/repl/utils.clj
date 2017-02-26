(ns cljs.tools.repl.utils
  (:require
   [clojure.string :as string]))

(defn format-line-column [{:keys [line column]}]
  (->> (cond-> []
         line   (conj (str "line " line))
         column (conj (str "column " column)))
       (interpose ", ")
       (string/join "")))

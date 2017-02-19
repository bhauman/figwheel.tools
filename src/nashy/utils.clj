(ns nashy.utils
  (:require
   [clojure.string :as string]
   [clojure.tools.reader :as r]
   [clojure.tools.reader.reader-types :as rtyp]))

(defn format-line-column [{:keys [line column]}]
  (->> (cond-> []
         line   (conj (str "line " line))
         column (conj (str "column " column)))
       (interpose ", ")
       (string/join "")))

(defn read-identity [tag form]
  [::reader-tag tag form])

(defn read-form [{:keys [eof rdr]}]
  (let [res
        (binding [r/*default-data-reader-fn* read-identity]
          (try
            (r/read {:eof eof
                     :read-cond :preserve} rdr)
            (catch Exception e
              {:exception e})))]
    (cond
      (= res eof) eof
      (and (map? res) (:exception res)) res
      (and (map? (meta res))
           (:source (meta res))) (assoc (meta res)
                                        :read-value res)
      :else {:read-value res
             :source (pr-str res)})))

(defn read-forms [s]
  (let [eof (Object.)]
    (->> (repeat {:rdr (rtyp/source-logging-push-back-reader s)
                  :eof eof})
         (map read-form)
         (take-while #(not= eof %)))))

(comment
  
  (read-forms "(list 
1 
2 
3)

#?(:cljs a
   :clj  b)

)

(list #?(:cljs a))

:asdf
3
\"asdf\"

^:asdf (list 1)


#js {}


")



  )

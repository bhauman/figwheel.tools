(ns nashy.viez
  (:require
   [rhizome.viz :refer :all]))


(defn nodes [g]
  (distinct (concat (keys g) (apply concat (vals g)))))

(defn view [g]
  (view-graph (nodes g) g
              :node->descriptor (fn [n] {:label n})))

(def basic-graph
  {:a [:b]
   :b [:c :d]})

(view {:eval     [:compile-env :repl-js-env]
       :compile  [:compile-env]
       :repl     [:eval]
       :figwheel [:compile :repl :files-changed]
       :repl-js-env   [:browser-connect]
       :browser-connect [:client-code]
                                        ;:client-code     [:repl]
       :editor  [:compile :repl :figwheel]
       })


(defn directed-map [xs]
  (into {} (map (fn [[a b]] [a [b]]) (partition 2 1 xs))))

(defn view-list [& args]
  (view (directed-map args)))

;; Current ideas:

;; With one middleware stack that can handle client connections and editor connections

;; The connection between the browser that is represented by the repl env mirrors the
;; connection between the editor and the repl.  Both are remote both are asking for eval.

;; Both have to handle async eval and response/error and marshal print io output

(view-list
 :nrepl-socket
 :compiler-middleware
 :cljs-eval)

;; Really we need to start off with a multiplexer system that can handle routing
;; messages from the various different services

(view {:browser-dev [:nrepl-websocket]
       :browser-test [:nrepl-websocket]
       :browser-other [:nrepl-websocket]
       :nrepl-websocket [:nrepl-server]
       :editior [:nrepl-server]
       :repl-client [:nrepl-server]
       :nrepl-server [:session-manager]
       :session-manager [:cljs-eval]
       :cljs-eval [:js-eval]
       :js-client [:session-namer]
       :session-namer [:client-cljs-eval]
       :client-cljs-eval [:client-js-eval]})



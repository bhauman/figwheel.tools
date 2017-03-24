(ns figwheel.tools.connect.websocket
  (:require
   [ring.util.response :refer [resource-response] :as response]
   [org.httpkit.server :refer [run-server with-channel on-close on-receive send! open?]]))

;; so we have the idea of multiplixing connection manager
;; that manages connections/channels

(defn on-connect [connection-state request channel]
  (swap! connection-state assoc channel {:uri request :receive-listeners {}})
  (on-close channel (swap! connection-state dissoc channel))
  (on-receive channel (fn [data] (mapv #(% data) (-> @connection-state (get channel) :receive-listeners vals))))
  ;; possible keep-alive
  )

(defn filter-connections [{:keys [connection-state]} pred]
  (filter (fn [[k v]] (and (open? k) (pred v))) @connection-state))

(defn add-receive-listener [{:keys [connection-state] :as websocket-connection} listen-key pred listen-fn]
  (doseq [[ch v] (filter-connections websocket-connection pred)]
    (swap! connection-state update-in [ch :receive-listeners] assoc listen-key listen-fn)))

(defn send [{:keys [connection-state] :as websocket-connection} pred data]
  (doseq [[ch v] (filter-connections websocket-connection pred)]
    (send! ch data)))

(defn reload-handler [connection-state]
  (fn [request]
    (with-channel request channel
      (on-connect connection-state request channel))))

;; TODO takes a uri regex
(defn handle-figwheel-websocket [connection-state handler]
  (let [websocket-handler (reload-handler connection-state)]
    (fn [{:keys [request-method uri] :as request}]
      (if (and (= :get request-method) (.startsWith uri "/figwheel-ws"))
        (websocket-handler request)
        (handler request)))))

(defn basic-ring-stack [ring-handler]
  (ring-handler
   (fn [_]
     (response/not-found
      "<div><h1>Figwheeel Connect Dev Server: Resource not found</h1><h3><em>Keep on figwheelin'</em></h3></div>"))))

(defn create-websocket-connection-handler []
  (let [connection-state (atom {})]
    {:connection-state connection-state
     :ring-handler (partial handle-figwheel-websocket connection-state)}))

(defn create-websocket-connection-server* [{:keys [ip port] :as http-kit-config} websocket-connection-handler]
  (assoc websocket-connection-handler
         :server (run-server ))
  (let [connection-state (atom {})]
    {:connection-state connection-state
     :server (run-server (basic-ring-stack (:handler websocket-connection-handler)) http-kit-config)}))

(defn create-websocket-connection-server [http-kit-config]
  (create-websocket-connection-server* http-kit-config (create-websocket-connection-handler)))







#_ (defn create-websocket-connection-handler [])

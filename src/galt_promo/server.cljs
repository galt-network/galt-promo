(ns galt-promo.server
  (:require [clojure.string :as str]
            [galt-promo.location :as location]
            [galt-promo.names :as names]
            [galt-promo.presence :as presence]
            [macchiato.middleware.resource :as resource]
            [macchiato.server :as http]
            [macchiato.util.response :as response]
            [reitit.ring :as ring]))

(defn- browser-name [user-agent]
  (cond
    (str/includes? user-agent "Edg/") "Edge"
    (str/includes? user-agent "Chrome/") "Chrome"
    (str/includes? user-agent "Firefox/") "Firefox"
    (str/includes? user-agent "Safari/") "Safari"
    :else "Other"))

(defn- snapshot [registry]
  (->> @registry
       vals
       (map #(dissoc % :response :disconnected-at))
       (sort-by :name)
       vec))

(defn- broadcast! [registry]
  (let [message (str "event: users\ndata: "
                     (js/JSON.stringify (clj->js (snapshot registry)))
                     "\n\n")]
    (doseq [{:keys [response]} (vals @registry)
            :when response]
      (.write response message))))

(defn- request-params [{:keys [uri query-string]}]
  (.-searchParams (js/URL. (str "http://localhost" uri "?" query-string))))

(defn- client-ip [{:keys [headers remote-addr]}]
  (str/replace (or (some-> (get headers "x-forwarded-for") (str/split #",") first str/trim)
                   remote-addr
                   "")
               #"^::ffff:" ""))

(defn- connect [registry {:keys [headers node/request node/response] :as req} respond _]
  (let [client-id (.get (request-params req) "client-id")]
    (if (str/blank? client-id)
      (respond {:status 400 :body "client-id is required"})
      (let [existing (get @registry client-id)
            ip (client-ip req)
            taken (into #{} (map :name) (vals @registry))]
        (swap! registry assoc client-id
               {:id client-id
                :name (or (:name existing)
                          (names/generate-name taken rand))
                :browser (browser-name (get headers "user-agent" ""))
                :ip ip
                :location (or (when (= :browser (get-in existing [:location :source]))
                                (:location existing))
                              (location/ip-location ip))
                :connected? true
                :disconnected-at nil
                :response response})
        (.writeHead ^js response 200 #js {"Content-Type" "text/event-stream"
                                      "Cache-Control" "no-cache"
                                      "Connection" "keep-alive"})
        (.write ^js response "retry: 3000\n")
        (broadcast! registry)
        (.once request "close"
               (fn []
                 (swap! registry
                        (fn [users]
                          (if (identical? response (get-in users [client-id :response]))
                            (-> users
                                (assoc-in [client-id :connected?] false)
                                (assoc-in [client-id :disconnected-at] (js/Date.now))
                                (assoc-in [client-id :response] nil))
                            users)))
                 (broadcast! registry)))))))

(defn- update-location [registry request respond _]
  (let [params (request-params request)
        client-id (.get params "client-id")
        coordinates (location/parse-coordinates (.get params "lat")
                                                (.get params "lng"))]
    (cond
      (str/blank? client-id)
      (respond {:status 400 :body "client-id is required"})

      (nil? coordinates)
      (respond {:status 400 :body "valid lat and lng are required"})

      (not (contains? @registry client-id))
      (respond {:status 404 :body "Unknown client"})

      :else
      (do
        (swap! registry update client-id assoc :location (assoc coordinates :source :browser))
        (broadcast! registry)
        (respond {:status 204 :body nil})))))

(defn make-handler [registry]
  (resource/wrap-resource
   (ring/ring-handler
    (ring/router [["/connect" {:get (partial connect registry)}]
                  ["/location" {:post (partial update-location registry)}]
                  ["/" {:get (fn [_ respond _]
                               (respond (response/file "resources/public/index.html")))}]])
    (fn [_ respond _]
      (respond {:status 404 :body "Not found"})))
   "resources/public"))

(defn- maintain! [registry]
  (doto (js/setInterval
         (fn []
           (let [users (presence/prune-disconnected @registry (js/Date.now))]
             (when (not= users @registry)
               (reset! registry users)
               (broadcast! registry)))
           (doseq [{:keys [response]} (vals @registry) :when response]
             (.write ^js response ": keepalive\n\n")))
         1000)
    (.unref)))

(defn main [& _]
  (let [port (js/parseInt (or (.. js/process -env -PORT) "3000") 10)
        registry (atom {})]
    (maintain! registry)
    (http/start {:handler (make-handler registry)
                 :host "0.0.0.0"
                 :port port
                 :on-success #(println (str "Listening on http://localhost:" port))})))

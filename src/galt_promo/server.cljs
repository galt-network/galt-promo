(ns galt-promo.server
  (:require [clojure.string :as str]
            [galt-promo.location :as location]
            [galt-promo.names :as names]
            [galt-promo.presence :as presence]
            ["node:sqlite" :as sqlite]
            [macchiato.middleware.resource :as resource]
            [macchiato.server :as http]
            [macchiato.util.response :as response]
            [reitit.ring :as ring]))

(def ^:private default-database-path "galt-promo.db")
(def ^:private max-name-length 40)
(def ^:private cookie-max-age 31536000)

(defn open-db [path]
  (let [db (sqlite/DatabaseSync. path)]
    (.exec db "CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, name TEXT NOT NULL, last_seen INTEGER NOT NULL)")
    db))

(defn saved-name [^js db id]
  (some-> (.get (.prepare db "SELECT name FROM users WHERE id = ?") id)
          (aget "name")))

(defn remember-user! [^js db id name]
  (.run (.prepare db "INSERT INTO users (id, name, last_seen) VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen")
        id name (js/Date.now)))

(defn- browser-name [user-agent]
  (cond
    (str/includes? user-agent "Edg/") "Edge"
    (str/includes? user-agent "Chrome/") "Chrome"
    (str/includes? user-agent "Firefox/") "Firefox"
    (str/includes? user-agent "Safari/") "Safari"
    :else "Other"))

(defn- snapshot [registry self-id]
  (->> @registry
       vals
       (map #(-> % (dissoc :response :disconnected-at) (assoc :self? (= self-id (:id %)))))
       (sort-by :name)
       vec))

(defn- broadcast! [registry]
  (doseq [[client-id {:keys [response]}] @registry
          :when response]
    (.write ^js response
            (str "event: users\ndata: "
                 (js/JSON.stringify (clj->js (snapshot registry client-id)))
                 "\n\n"))))

(defn- request-params [{:keys [uri query-string]}]
  (.-searchParams (js/URL. (str "http://localhost" uri "?" query-string))))

(defn- query-value [request key]
  (.get (request-params request) key))

(defn- cookies [{:keys [headers]}]
  (into {}
        (keep (fn [part]
                (let [[key value] (str/split (str/trim part) #"=" 2)]
                  (when (and (not (str/blank? key)) value)
                    [key value]))))
        (str/split (or (get headers "cookie") "") #";")))

(defn- client-id [request]
  (or (not-empty (get (cookies request) "client-id"))
      (not-empty (query-value request "client-id"))))

(defn- client-ip [{:keys [headers remote-addr]}]
  (str/replace (or (some-> (get headers "x-forwarded-for") (str/split #",") first str/trim)
                   remote-addr
                   "")
               #"^::ffff:" ""))

(defn- connect [registry db {:keys [headers node/request node/response] :as req} respond _]
  (let [client-id (or (client-id req) (str (random-uuid)))
        existing (get @registry client-id)
        ip (client-ip req)
        taken (into #{} (map :name) (vals @registry))
        saved (when-not existing (saved-name db client-id))
        name (or (:name existing)
                 (when (and saved (not (taken saved))) saved)
                 (names/generate-name taken rand))]
    (remember-user! db client-id name)
    (swap! registry assoc client-id
           {:id client-id
            :name name
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
                                  "Connection" "keep-alive"
                                  "Set-Cookie" (str "client-id=" client-id
                                                    "; Path=/; Max-Age=" cookie-max-age
                                                    "; SameSite=Lax; HttpOnly")})
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
             (broadcast! registry)))))

(defn- update-location [registry request respond _]
  (let [client-id (client-id request)
        coordinates (location/parse-coordinates (query-value request "lat")
                                                (query-value request "lng"))]
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

(defn- rename [registry db request respond _]
  (let [client-id (client-id request)
        name (some-> (query-value request "name") str/trim)]
    (cond
      (str/blank? client-id)
      (respond {:status 400 :body "client-id is required"})

      (str/blank? name)
      (respond {:status 400 :body "name is required"})

      (> (count name) max-name-length)
      (respond {:status 400 :body (str "name must be at most " max-name-length " characters")})

      (not (contains? @registry client-id))
      (respond {:status 404 :body "Unknown client"})

      (some (fn [[id user]] (and (not= client-id id) (= name (:name user)))) @registry)
      (respond {:status 409 :body "name is already taken"})

      :else
      (do
        (swap! registry assoc-in [client-id :name] name)
        (remember-user! db client-id name)
        (broadcast! registry)
        (respond {:status 204 :body nil})))))

(defn make-handler
  ([registry] (make-handler registry (open-db ":memory:")))
  ([registry db]
   (resource/wrap-resource
    (ring/ring-handler
     (ring/router [["/connect" {:get (partial connect registry db)}]
                   ["/location" {:post (partial update-location registry)}]
                   ["/name" {:post (partial rename registry db)}]
                   ["/" {:get (fn [_ respond _]
                                (respond (response/file "resources/public/index.html")))}]])
     (fn [_ respond _]
       (respond {:status 404 :body "Not found"})))
    "resources/public")))

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
        registry (atom {})
        db (open-db (or (.. js/process -env -DATABASE_PATH) default-database-path))]
    (maintain! registry)
    (http/start {:handler (make-handler registry db)
                 :host "0.0.0.0"
                 :port port
                 :on-success #(println (str "Listening on http://localhost:" port))})))

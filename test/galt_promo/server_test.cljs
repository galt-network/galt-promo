(ns galt-promo.server-test
  (:require [cljs.test :refer [async deftest is]]
            [galt-promo.server :as server]
            [macchiato.server :as http]
            ["node:http" :as node-http]))

(deftest connect-streams-a-user
  (async done
         (let [instance (atom nil)
               request (atom nil)]
           (reset!
            instance
            (http/start
             {:handler (server/make-handler (atom {}))
              :host "127.0.0.1"
              :port 0
              :on-success
              (fn []
                (reset!
                 request
                 (node-http/get
                  #js {:host "127.0.0.1"
                       :port (.-port (.address @instance))
                       :path "/connect?client-id=one"
                       :headers #js {"User-Agent" "Mozilla/5.0 Firefox/123.0"
                                      "X-Forwarded-For" "203.0.113.5, 127.0.0.1"}}
                  (fn [^js response]
                    (.setEncoding response "utf8")
                    (.on response "data"
                         (fn [chunk]
                           (when (.includes chunk "event: users")
                             (let [payload (-> chunk
                                               (.split "data: ")
                                               (aget 1)
                                               (.trim)
                                               (js/JSON.parse)
                                               (js->clj :keywordize-keys true))
                                   user (first payload)]
                               (is (= 200 (.-statusCode response)))
                               (is (= "one" (:id user)))
                               (is (:connected? user))
                               (is (= "Firefox" (:browser user)))
                               (is (= "203.0.113.5" (:ip user)))
                               (.destroy @request)
                               (.close @instance)
                               (done)))))))))})))))

(deftest disconnect-broadcasts-offline-status
  (async done
         (let [instance (atom nil)
               observer (atom nil)
               leaving (atom nil)]
           (reset!
            instance
            (http/start
             {:handler (server/make-handler (atom {}))
              :host "127.0.0.1"
              :port 0
              :on-success
              (fn []
                (reset!
                 observer
                 (node-http/get
                  #js {:host "127.0.0.1"
                       :port (.-port (.address @instance))
                       :path "/connect?client-id=observer"}
                  (fn [^js response]
                    (.setEncoding response "utf8")
                    (.on response "data"
                         (fn [chunk]
                           (when (.includes chunk "event: users")
                             (let [users (-> chunk
                                             (.split "data: ")
                                             (aget 1)
                                             (.trim)
                                             (js/JSON.parse)
                                             (js->clj :keywordize-keys true))
                                   leaving-user (some #(when (= "leaving" (:id %)) %) users)]
                               (cond
                                 (not leaving-user)
                                 (do
                                   (reset! leaving
                                           (node-http/get
                                            #js {:host "127.0.0.1"
                                                 :port (.-port (.address @instance))
                                                 :path "/connect?client-id=leaving"}
                                            (fn [^js response] (.resume response))))
                                   (.on ^js @leaving "error" (fn [_])))

                                 (:connected? leaving-user)
                                 (.destroy @leaving)

                                 :else
                                 (do
                                   (is (false? (:connected? leaving-user)))
                                   (.destroy @observer)
                                   (.close @instance)
                                   (done)))))))))))})))))

(deftest browser-location-is-stored
  (async done
    (let [instance (atom nil)
          stream (atom nil)
          registry (atom {})
          posted (atom false)
          finished (atom false)
          finish! (fn []
                    (when-not @finished
                      (reset! finished true)
                      (.destroy @stream)
                      (.close @instance)
                      (done)))]
      (reset!
       instance
       (http/start
        {:handler (server/make-handler registry)
         :host "127.0.0.1"
         :port 0
         :on-success
         (fn []
           (reset!
            stream
            (node-http/get
             #js {:host "127.0.0.1"
                  :port (.-port (.address @instance))
                  :path "/connect?client-id=located"}
             (fn [^js response]
               (.setEncoding response "utf8")
               (.on response "data"
                    (fn [chunk]
                      (when (and (not @posted)
                                 (.includes chunk "event: users"))
                        (reset! posted true)
                        (let [request
                              (node-http/request
                               #js {:host "127.0.0.1"
                                    :port (.-port (.address @instance))
                                    :method "POST"
                                    :path "/location?client-id=located&lat=40.7128&lng=-74.006"})]
                          (.on request "error"
                               (fn [error]
                                 (is false (str error))
                                 (finish!)))
                          (.on request "response"
                               (fn [^js response]
                                 (is (= 204 (.-statusCode response)))
                                 (.resume response)
                                 (is (= {:lat 40.7128 :lng -74.006 :source :browser}
                                        (:location (get @registry "located"))))
                                 (finish!)))
                          (.end request)))))))))
         :error (fn [error]
                  (is false (str error))
                  (finish!))})))))

(deftest serves-the-single-page
  (async done
         (let [instance (atom nil)]
           (reset!
            instance
            (http/start
             {:handler (server/make-handler (atom {}))
              :host "127.0.0.1"
              :port 0
              :on-success
              (fn []
                (node-http/get
                 #js {:host "127.0.0.1"
                      :port (.-port (.address @instance))
                      :path "/"}
                 (fn [^js response]
                   (let [body (atom "")]
                     (.setEncoding response "utf8")
                     (.on response "data" #(swap! body str %))
                     (.on response "end"
                          (fn []
                            (is (= 200 (.-statusCode response)))
                            (is (.includes @body "id=\"app\""))
                            (.close @instance)
                            (done)))))))})))))

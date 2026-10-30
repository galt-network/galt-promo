(ns galt-promo.events
  (:require [re-frame.core :as rf]))

(rf/reg-event-db
 ::initialize
 (fn [_ _]
   {:users []
    :connection :connecting
    :tab :map
    :selected-cluster nil}))

(rf/reg-event-db
 ::tab-selected
 (fn [db [_ tab]]
   (assoc db :tab tab :selected-cluster nil)))

(rf/reg-event-db
 ::cluster-selected
 (fn [db [_ user-ids]]
   (assoc db :selected-cluster user-ids)))

(rf/reg-event-db
 ::cluster-closed
 (fn [db _]
   (assoc db :selected-cluster nil)))

(rf/reg-event-db
 ::users-received
 (fn [db [_ users]]
   (assoc db :users users)))

(rf/reg-event-db
 ::connection-changed
 (fn [db [_ status]]
   (assoc db :connection status)))

(rf/reg-sub ::users (fn [db] (:users db)))
(rf/reg-sub ::connection (fn [db] (:connection db)))
(rf/reg-sub ::tab (fn [db] (:tab db)))
(rf/reg-sub ::selected-cluster (fn [db] (:selected-cluster db)))

(defn save-name! [name on-success]
  (-> (js/fetch (str "/name?name=" (js/encodeURIComponent name)) #js {:method "POST"})
      (.then (fn [response]
               (when (.-ok response)
                 (on-success))))
      (.catch (fn [_] nil))))

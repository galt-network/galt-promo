(ns galt-promo.events-test
  (:require [cljs.test :refer [deftest is]]
            [galt-promo.events :as events]
            [re-frame.core :as rf]))

(deftest tab-from-hash
  (is (= :info (events/tab-from-hash "#info")))
  (is (= :list (events/tab-from-hash "#list")))
  (is (= :map (events/tab-from-hash "#map")))
  (is (= :map (events/tab-from-hash "")))
  (is (= :map (events/tab-from-hash "#bogus")))
  (rf/dispatch-sync [::events/initialize :info])
  (is (= :info @(rf/subscribe [::events/tab]))))

(deftest user-snapshots-update-subscriptions
  (rf/dispatch-sync [::events/initialize])
  (rf/dispatch-sync [::events/users-received [{:id "one" :name "Amber Alpaca"}]])
  (rf/dispatch-sync [::events/connection-changed :open])
  (is (= [{:id "one" :name "Amber Alpaca"}] @(rf/subscribe [::events/users])))
  (is (= :open @(rf/subscribe [::events/connection])))
  (is (= :map @(rf/subscribe [::events/tab])))
  (is (nil? @(rf/subscribe [::events/selected-cluster])))
  (rf/dispatch-sync [::events/tab-selected :map])
  (rf/dispatch-sync [::events/cluster-selected ["one" "two"]])
  (is (= :map @(rf/subscribe [::events/tab])))
  (is (= ["one" "two"] @(rf/subscribe [::events/selected-cluster])))
  (rf/dispatch-sync [::events/cluster-closed])
  (is (nil? @(rf/subscribe [::events/selected-cluster]))))

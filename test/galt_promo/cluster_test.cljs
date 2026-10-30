(ns galt-promo.cluster-test
  (:require [cljs.test :refer [deftest is testing]]
            [galt-promo.cluster :as cluster]))

(defn- user [id x y & [lat lng name]]
  {:id id :x x :y y :lat (or lat 0) :lng (or lng 0) :name (or name id)})

(deftest groups-crowded-markers
  (testing "more than three nearby users collapse into one marker"
    (let [markers (cluster/cluster-users
                   [(user "a" 0 0 10 20)
                    (user "b" 10 10 12 22)
                    (user "c" 20 20 14 24)
                    (user "d" 30 30 16 26)])]
      (is (= [:cluster] (mapv :type markers)))
      (is (= 4 (count (:users (first markers)))))
      (is (= 13 (:lat (first markers))))
      (is (= 23 (:lng (first markers))))))
  (testing "two nearby users collapse into one marker"
    (let [markers (cluster/cluster-users
                   [(user "a" 0 0)
                    (user "b" 10 10)])]
      (is (= [:cluster] (mapv :type markers)))
      (is (= 2 (count (:users (first markers)))))))
  (testing "a lone user remains an individual marker"
    (is (= [:user]
           (mapv :type (cluster/cluster-users [(user "a" 0 0)])))))
  (testing "distant users do not join a cluster"
    (let [markers (cluster/cluster-users
                   [(user "a" 0 0)
                    (user "b" 10 10)
                    (user "c" 20 20)
                    (user "d" 30 30)
                    (user "away" 500 500)])]
      (is (= #{:cluster :user} (set (map :type markers))))
      (is (= ["away"] (mapv :id
                            (filter #(= :user (:type %)) markers)))))))

(deftest clusters-only-overlapping-markers
  (testing "a label covering a neighbouring pin clusters"
    (let [markers (cluster/cluster-users
                   [(user "a" 0 0 10 20 "Clever Cougar")
                    (user "b" 95 0 12 22 "Calm Crane")])]
      (is (= [:cluster] (mapv :type markers)))
      (is (= 2 (count (:users (first markers)))))))
  (testing "the same distance vertically leaves both markers individual"
    (is (= [:user :user]
           (mapv :type (cluster/cluster-users
                        [(user "a" 0 0 10 20 "Clever Cougar")
                         (user "b" 0 100 12 22 "Calm Crane")])))))
  (testing "short names do not reach the neighbouring pin"
    (is (= [:user :user]
           (mapv :type (cluster/cluster-users
                        [(user "a" 0 0)
                         (user "b" 95 0)]))))))

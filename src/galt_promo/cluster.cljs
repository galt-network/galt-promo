(ns galt-promo.cluster
  (:require [clojure.string :as str]))

(def cluster-distance-px 80)
(def cluster-min-size 2)

(defn- close? [{x1 :x y1 :y} {x2 :x y2 :y}]
  (<= (+ (js/Math.pow (- x1 x2) 2)
         (js/Math.pow (- y1 y2) 2))
      (js/Math.pow cluster-distance-px 2)))

(defn- neighbours [users index]
  (keep-indexed (fn [other candidate]
                  (when (and (not= index other)
                             (close? (nth users index) candidate))
                    other))
                users))

(defn- component [users start]
  (loop [queue [start]
         seen #{}
         members []]
    (if-let [index (first queue)]
      (if (seen index)
        (recur (subvec queue 1) seen members)
        (recur (into (subvec queue 1) (neighbours users index))
               (conj seen index)
               (conj members (nth users index))))
      [members seen])))

(defn- cluster-marker [users]
  (let [count (count users)]
    {:type :cluster
     :id (str "cluster-" (str/join "-" (sort (map :id users))))
     :users users
     :lat (/ (reduce + (map :lat users)) count)
     :lng (/ (reduce + (map :lng users)) count)}))

(defn cluster-users [users]
  (loop [remaining (set (range (count users)))
         markers []]
    (if-let [index (first remaining)]
      (let [[members seen] (component users index)
            markers (if (>= (count members) cluster-min-size)
                      (conj markers (cluster-marker members))
                      (into markers (map #(assoc % :type :user)) members))]
        (recur (reduce disj remaining seen) markers))
      markers)))

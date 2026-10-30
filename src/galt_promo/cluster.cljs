(ns galt-promo.cluster
  (:require [clojure.string :as str]))

(def cluster-min-size 2)

(def ^:private pin-half-width 14)
(def ^:private pin-half-height 18)
(def ^:private label-offset-x 16)
(def ^:private label-padding-px 4)

(defn- label-width [name]
  (+ 16 (* 7 (count name))))

(defn- footprint [{:keys [x y name]}]
  [(- x pin-half-width)
   (- y pin-half-height)
   (+ x label-offset-x (label-width name))
   (+ y pin-half-height)])

(defn- close? [a b]
  (let [[al at ar ab] (footprint a)
        [bl bt br bb] (footprint b)]
    (and (<= (- al br) label-padding-px)
         (<= (- bl ar) label-padding-px)
         (<= (- at bb) label-padding-px)
         (<= (- bt ab) label-padding-px))))

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

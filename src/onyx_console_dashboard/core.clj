(ns onyx-console-dashboard.core
  (:require [lanterna.screen :as s]
            [onyx.system]
            [onyx.extensions :as extensions]
            [onyx.api]
            [clojure.data]
            [clojure.core.async :refer [chan >!! <!! close! alts!! timeout go]]
            [fipp.clojure :as fipp]
            [onyx.log.replica :refer [base-replica]])
  (:gen-class))

(def scr (s/get-screen))

(defn replicas
  [initial-replica peer-log]
  (vec 
    (rest 
      (reductions #(extensions/apply-log-entry %2 %1)
                  initial-replica 
                  peer-log))))

(defn diffs [replicas]
  (vec 
    (map (fn [index] 
           (clojure.data/diff (replicas (dec index)) 
                              (replicas index)))
         (range 1 (count replicas)))))

(defn sanitize-peer-log [peer-log]
  (clojure.walk/prewalk (fn [x] 
                          (cond (= x 'Infinity)
                                Double/POSITIVE_INFINITY
                                :else x)) 
                        peer-log))

(defn render-lines [scr y-start lines color]
  (run! (fn [[y line-str]]
          (s/put-string scr 0 y line-str {:fg color})) 
        (map list 
             (range y-start 100000)
             lines)))

(def state (atom nil))

(defn initialise-state! [peer-config]
  (reset! state  
          {:replicas [(assoc base-replica
                             :job-scheduler (:onyx.peer/job-scheduler peer-config)
                             :messaging {:onyx.messaging/impl (:onyx.messaging/impl peer-config)})]
           :entry 1
           :mode :replica
           :log [nil]}))

(defn to-text-lines [v cols]
  (clojure.string/split 
    (with-out-str 
      (fipp/pprint v {:width cols})) #"\n"))

(defn render-panels! [panels]
  (reduce (fn [y {:keys [lines colour]}]
            (render-lines scr y lines colour)         
            (+ y (count lines))) 
          0
          panels))

(defn flatten-map [coll]
  (cond (or (sequential? coll)
            (set? coll))
        (mapcat flatten-map coll)
        (map? coll)
        (into (mapcat flatten-map (keys coll))
              (mapcat flatten-map (vals coll)))
        :else
        [coll]))

(defn handle-paging! [key-val]
  (swap! state 
         (fn [st] 
           (let [curr-entry (:entry st)
                 ;; todo, if in filter mode, this should be a diff count
                 log-count (count (:log st))
                 new-entry (cond 
                             (= key-val :page-down)
                             (min (dec log-count)
                                  (+ curr-entry 10))

                             (and (= key-val :down))
                             (min (dec log-count)
                                  (inc curr-entry))

                             (= key-val :page-up)
                             (max 0 (- curr-entry 10))

                             (= key-val :up)
                             (max 0 (dec curr-entry))

                             :else curr-entry)]
             (assoc st :entry new-entry)))))

(defn handle-mode-switch! [key-val]
  (cond (= key-val \d)
        (swap! state assoc :mode :diff)

        (= key-val \r)
        (swap! state assoc :mode :replica)

        (= key-val \f)
        (swap! state assoc :mode :filter)))

(defn render-loop! [scr]
  (loop [[cols rows] (s/get-size scr)
         key-val :unhandled]

    (when (#{:escape \q} key-val)
      (System/exit 0)
      (s/stop scr))

    (handle-mode-switch! key-val)
    (handle-paging! key-val)
    (s/clear scr)

    (let [curr-entry (:entry @state)
          log-entry ((:log @state) curr-entry)
          replica ((:replicas @state) curr-entry)                                     
          diff ((:diffs @state) curr-entry)
          entry-lines (to-text-lines log-entry cols) 
          replica-lines (to-text-lines replica cols) 
          diff-lines (mapv #(to-text-lines % cols) diff) 
          panels (cond-> [{:lines [(str curr-entry " - " (java.util.Date. (:created-at log-entry)))] 
                           :colour :yellow}
                          {:lines entry-lines
                           :colour :blue}]
                   (= :diff (:mode @state)) (into [{:lines (diff-lines 0) 
                                                    :colour :red}
                                                   {:lines (diff-lines 1) 
                                                    :colour :green}
                                                   {:lines (diff-lines 2) 
                                                    :colour :white}])
                   (= :replica (:mode @state)) (into [{:lines replica-lines
                                                       :colour :white}]))]

      (render-panels! panels)

      (s/redraw scr)

      (recur (s/get-size scr)
             (s/get-key-blocking scr)))))

(defn import-zookeeper! [peer-config onyx-id]
  (future 
    (let [ch (chan)
          started-sub (-> peer-config
                          (assoc :onyx/id onyx-id)
                          (onyx.api/subscribe-to-log ch))] 
      (loop [entry (<!! ch)]
        (swap! state update :log conj entry)
        ;; TODO, add diff
        (swap! state update :replicas (fn [replicas] (conj replicas (extensions/apply-log-entry entry (last replicas)))))
        (recur (<!! ch))))))

(defn slurp-edn [filename]
  (read-string (slurp filename)))

;; FIXME import 
(defn import-peer-log! [peer-log]
    (swap! state (fn [st]
                   (let [initial-replica (first (:replicas @state))
                         all-replicas (into [initial-replica] (replicas initial-replica (sanitize-peer-log peer-log)))] 
                     (-> st 
                         (update :log into peer-log)
                         (assoc :replicas all-replicas)
                         (assoc :diffs (diffs all-replicas)))))))

(defn -main
  [& [type src]]
  (let [peer-config (read-string (slurp "peer-config.edn"))]
    (initialise-state! peer-config)

    (case type
      "edn" (import-peer-log! (slurp-edn src))
      "jepsen" (import-peer-log! (:peer-log (slurp-edn src)))
      "zookeeper" (import-zookeeper! peer-config src)))

    (s/start scr)
    (render-loop! scr))

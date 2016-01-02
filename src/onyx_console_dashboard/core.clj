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
  [job-scheduler messenger peer-log]
  (vec 
    (rest 
      (reductions #(extensions/apply-log-entry %2 %1)
                  (assoc base-replica
                         :job-scheduler job-scheduler
                         :messaging {:onyx.messaging/impl messenger})
                  peer-log))))

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
  {:replicas [(assoc base-replica
                     :job-scheduler (:onyx.peer/job-scheduler peer-config)
                     :messaging {:onyx.messaging/impl (:onyx.messaging/impl peer-config)})]
   :mode :replica
   :log [nil]})

(defn to-text-lines [v cols]
  (clojure.string/split 
    (with-out-str (fipp/pprint v
                               {:width cols})) #"\n"))

(defn render-panels! [panels]
  (reduce (fn [y {:keys [lines colour]}]
            (render-lines scr y lines colour)         
            (+ y (count lines))) 
          0
          panels))

(defn render-loop! [scr]
  (loop [[cols rows] (s/get-size scr)
         key-val :unhandled 
         curr-entry 1]

    (when (#{:escape \q} key-val)
      (System/exit 0)
      (s/stop scr))


    (cond (= key-val \d)
          (swap! state assoc :mode :diff)

          (= key-val \r)
          (swap! state assoc :mode :replica))

    (s/clear scr)

    (let [new-curr-entry (cond 
                           (= key-val :page-down)
                           (min (dec (count (:log @state)))
                                (+ curr-entry 10))

                           (and (= key-val :down))
                           (min (dec (count (:log @state)))
                                (inc curr-entry))

                           (= key-val :page-up)
                           (max 0 (- curr-entry 10))

                           (= key-val :up)
                           (max 0 (dec curr-entry))

                           :else curr-entry)
          log-entry ((:log @state) new-curr-entry)
          replica ((:replicas @state) new-curr-entry)                                     
          ;;; Add some children functonality
          prev-replica (get (:replicas @state) (dec new-curr-entry) base-replica)
          ;; TODO, show both
          diff (clojure.data/diff prev-replica replica)
          entry-lines (to-text-lines log-entry cols) 
          replica-lines (to-text-lines replica cols) 
          diff-lines (mapv #(to-text-lines % cols) diff) 
          panels (cond-> [{:lines [(str new-curr-entry " - " (java.util.Date. (:created-at log-entry)))] 
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
             (s/get-key-blocking scr) 
             new-curr-entry))))

(defn import-zookeeper! [peer-config onyx-id]
  (future 
    (let [ch (chan)
          started-sub (-> peer-config
                          (assoc :onyx/id onyx-id)
                          (onyx.api/subscribe-to-log ch))] 
      (loop [entry (<!! ch)]
        (swap! state update :log conj entry)
        (swap! state update :replicas (fn [replicas] (conj replicas (extensions/apply-log-entry entry (last replicas)))))
        (recur (<!! ch))))))

(defn slurp-edn [filename]
  (read-string (slurp filename)))

(defn import-peer-log! [peer-log]
  (let [replicas (replicas :onyx.job-scheduler/greedy :aeron (sanitize-peer-log peer-log))]
    (reset! state {:replicas replicas 
                   :mode :replica
                   :log peer-log})))

(defn -main
  [& [type src]]
  (let [peer-config (read-string (slurp "peer-config.edn"))]
    (initialise-state! peer-config)
    (case type
      "edn" (import-peer-log! (slurp-edn src))
      "jepsen" (import-peer-log! (:peer-log (slurp-edn src)))
      "zookeeper" 
      (import-zookeeper! peer-config src)))

    (s/start scr)
    (render-loop! scr))

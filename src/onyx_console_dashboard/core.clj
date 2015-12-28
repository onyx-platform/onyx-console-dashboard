(ns onyx-console-dashboard.core
  (:require [lanterna.screen :as s]
            [onyx.system]
            [onyx.extensions :as extensions]
            [onyx.api]
            [clojure.data :refer [diff]]
            [clojure.core.async :refer [chan >!! <!! close! alts!! timeout go]]
            [fipp.clojure :as fipp]
            [onyx.log.replica :refer [base-replica]])
  (:gen-class))

(def scr (s/get-screen))

(defn replicas
  [job-scheduler messenger peer-log]
  (vec 
    (reductions #(extensions/apply-log-entry %2 %1)
                (assoc base-replica
                       :job-scheduler job-scheduler
                       :messaging {:onyx.messaging/impl messenger})
                peer-log)))

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

(defn render-loop! [scr]
  (loop [[cols rows] (s/get-size scr)
         key-val :unhandled 
         curr-entry 0]

    (when (#{:escape \q} key-val)
      (System/exit 0)
      (s/stop scr))

    (s/clear scr)

    (let [new-curr-entry (cond 
                           (and (= key-val :down)
                                (< curr-entry 
                                   (dec (count (:log @state)))))
                           (inc curr-entry)
                           (and (= key-val :up)
                                (pos? curr-entry))
                           (dec curr-entry)
                           :else curr-entry)
          entry-lines (clojure.string/split 
                        (with-out-str (fipp/pprint 
                                        ((:log @state) new-curr-entry)
                                        {:width cols})) #"\n")
          replica-lines (clojure.string/split 
                          (with-out-str (fipp/pprint 
                                          ((:replicas @state) new-curr-entry)                                     
                                          {:width cols})) #"\n")]

      (render-lines scr 0 [(str new-curr-entry)] :red)
      (render-lines scr 1 entry-lines :green)
      (render-lines scr (+ 2 (count entry-lines)) replica-lines :white)

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

(defn import-edn-log! [filename]
  (let [peer-log (->> (slurp filename)
                      read-string
                      sanitize-peer-log)
        replicas (replicas :onyx.job-scheduler/greedy :aeron peer-log)]
    (reset! state {:replicas replicas :log peer-log})))

(defn -main
  "I don't do a whole lot ... yet."
  [& [type src]]

  (let [peer-config (read-string (slurp "peer-config.edn"))]
    (reset! state {:replicas [(assoc base-replica
                                     :job-scheduler (:onyx.peer/job-scheduler peer-config)
                                     :messaging {:onyx.messaging/impl (:onyx.messaging/impl peer-config)})]

                   :log [nil]})
    (case type
      "edn" (import-edn-log! src)
      "zookeeper" (import-zookeeper! peer-config src))

    (s/start scr)
    (render-loop! scr)))

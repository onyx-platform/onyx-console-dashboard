(ns onyx-console-dashboard.core
  (:require [lanterna.screen :as s]
            [onyx.system]
            [onyx.extensions :as extensions]
            [clojure.data :refer [diff]]
            [fipp.clojure :as fipp]
            [onyx.log.replica :refer  [base-replica]])
  (:gen-class))

(def scr (s/get-screen))

;; Put into lib-onyx
(defn replica-at
  [job-scheduler messenger peer-log message-id]
  (reduce #(extensions/apply-log-entry %2 %1)
          (assoc base-replica
                 :job-scheduler job-scheduler
                 :messaging {:onyx.messaging/impl messenger})
          (take-while (fn [entry] (<= (:message-id entry) message-id))
                      peer-log)))

(def peer-log 
  (clojure.walk/prewalk (fn [x] 
                          (cond (= x 'Infinity)
                                Double/POSITIVE_INFINITY
                                :else x)) 
                        (:peer-log (read-string (slurp "/Users/lucas/20151224T044824.000Z/results.edn")))))

(comment 
  (def joiners 
  (set (map :joiner (map :args (filter #(= (:fn %) :prepare-join-cluster) (:peer-log (read-string (slurp "/Users/lucas/20151224T044824.000Z/results.edn"))))))))

(def accepters
  (set (map :subject (map :args (filter #(= (:fn %) :accept-join-cluster) (:peer-log (read-string (slurp "/Users/lucas/20151224T044824.000Z/results.edn"))))))))

(def abort
  (set (map :id (map :args (filter #(= (:fn %) :abort-join-cluster) (:peer-log (read-string (slurp "/Users/lucas/20151224T044824.000Z/results.edn"))))))))

(def leaves
  (set (map :id (map :args (filter #(= (:fn %) :leave-cluster) (:peer-log (read-string (slurp "/Users/lucas/20151224T044824.000Z/results.edn"))))))))
(def final-replica 
  (replica-at :onyx.job-scheduler/greedy :aeron peer-log 7791))
)




(defn render-lines [scr y-start lines color]
  (run! (fn [[y line-str]]
          (s/put-string scr 0 y line-str {:fg color})) 
        (map list 
             (range y-start 100000)
             lines)))

(defn render-loop! [scr]
  (loop [[cols rows] (s/get-size scr)
         key-val :unhandled 
         curr-entry 0]

    (when (= key-val :escape)
      (System/exit 0)
      (s/stop scr))

    (s/clear scr)

    (let [new-curr-entry (cond 
                           (= key-val :down)
                           (inc curr-entry)
                           (= key-val :up)
                           (dec curr-entry)
                           :else curr-entry)
          entry-lines (clojure.string/split 
                        (with-out-str (fipp/pprint 
                                        (nth peer-log new-curr-entry)
                                        {:width cols})) #"\n")
          replica-lines (clojure.string/split 
                          (with-out-str (fipp/pprint 
                                          (replica-at :onyx.job-scheduler/greedy 
                                                      :aeron
                                                      peer-log
                                                      new-curr-entry)                                     
                                          {:width cols})) #"\n")
          
          ]

      (render-lines scr 0 entry-lines :green)
      (render-lines scr (inc (count entry-lines)) replica-lines :white)

      (s/redraw scr)

      (recur (s/get-size scr)
             (s/get-key-blocking scr) 
             new-curr-entry))))


(defn -main
  "I don't do a whole lot ... yet."
  [& args]

  (s/start scr)
  (render-loop! scr))

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

(defn render-loop! [scr peer-log replicas]
  (loop [[cols rows] (s/get-size scr)
         key-val :unhandled 
         curr-entry 0]

    (when (= key-val :escape)
      (System/exit 0)
      (s/stop scr))

    (s/clear scr)

    (let [new-curr-entry (cond 
                           (and (= key-val :down)
                                (< curr-entry 
                                   (dec (count peer-log))))
                           (inc curr-entry)
                           (and (= key-val :up)
                                (pos? curr-entry))
                           (dec curr-entry)
                           :else curr-entry)
          entry-lines (clojure.string/split 
                        (with-out-str (fipp/pprint 
                                        (nth peer-log new-curr-entry)
                                        {:width cols})) #"\n")
          replica-lines (clojure.string/split 
                          (with-out-str (fipp/pprint 
                                          (replicas new-curr-entry)                                     
                                          {:width cols})) #"\n")]

      (render-lines scr 0 [(str new-curr-entry)] :red)
      (render-lines scr 1 entry-lines :green)
      (render-lines scr (+ 2 (count entry-lines)) replica-lines :white)

      (s/redraw scr)

      (recur (s/get-size scr)
             (s/get-key-blocking scr) 
             new-curr-entry))))


(defn -main
  "I don't do a whole lot ... yet."
  [& args]

  (s/start scr)
  (let [peer-log (->> (slurp "/Users/lucas/onyx/onyx/peer.edn")
                      read-string
                      sanitize-peer-log)
        
        replicas (replicas :onyx.job-scheduler/greedy :aeron peer-log)] 
    (render-loop! scr peer-log replicas)))

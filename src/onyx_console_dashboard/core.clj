(ns onyx-console-dashboard.core
  (:gen-class)
  (:require [lanterna.screen :as s]
            [onyx.system]
            [onyx.extensions :as extensions]
            [onyx.api]
            [clojure.data]
            [clojure.core.async :refer [chan >!! <!! close! alts!! timeout go]]
            [fipp.clojure :as fipp]
            [onyx.log.replica :refer [base-replica]])
  (:import [java.awt Toolkit]
           [java.awt.datatransfer StringSelection]))

(def scr (s/get-screen))

(defn replicas
  [initial-replica peer-log]
  (vec 
    (reductions #(extensions/apply-log-entry %2 %1)
                initial-replica 
                peer-log)))

(defn diffs [replicas]
  (vec 
    (pmap (fn [index] 
           (clojure.data/diff (replicas index) 
                              (replicas (inc index))))
         (range 0 (dec (count replicas))))))

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
           :entry 0
           :scroll-offset 0
           :mode :replica
           :diffs [nil]
           :log [nil]}))

(defn to-text-lines [v cols]
  (clojure.string/split 
    (with-out-str 
      (fipp/pprint v {:width cols})) #"\n"))

(defn render-panels! [panels scroll-offset]
  (reduce (fn [[scroll-offset y] {:keys [lines colour]}]
            (let [rendered-lines (drop scroll-offset lines)
                  rendered-count (count rendered-lines)] 
              (render-lines scr y rendered-lines colour)         
              [(max 0 (- scroll-offset rendered-count)) (+ y rendered-count)])) 
          [scroll-offset 0]
          panels))

(defn flatten-anything [coll]
  (cond (or (sequential? coll)
            (set? coll))
        (mapcat flatten-anything coll)
        (map? coll)
        (into (mapcat flatten-anything (keys coll))
              (mapcat flatten-anything (vals coll)))
        :else
        [coll]))

(defn handle-paging! [key-val]
  (swap! state update :scroll-offset (fn [y] 
                                       (case key-val
                                         \j (max 0 (inc y))
                                         \k (dec y)
                                         y))))

(defn handle-copy! [key-val replica log-entry]
  (let [clipboard (.getSystemClipboard (Toolkit/getDefaultToolkit))]
    (cond (= key-val \c)
          (let [sel (StringSelection. (pr-str replica))]
            (.setContents clipboard sel nil))

          (= key-val \l)
          (let [sel (StringSelection. (pr-str log-entry))]
            (.setContents clipboard sel nil)))))

(defn handle-playback! [key-val]
  (swap! state 
         (fn [st] 
           (let [curr-entry (:entry st)
                 log-count (count (:replicas st))
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
    (handle-playback! key-val)
    (handle-paging! key-val)
    (s/clear scr)

    (let [curr-entry (:entry @state)
          log-entry ((:log @state) curr-entry)
          replica ((:replicas @state) curr-entry)                                     
          diff ((:diffs @state) curr-entry)
          entry-lines (to-text-lines log-entry cols) 
          replica-lines (to-text-lines replica cols) 
          diff-lines (mapv #(to-text-lines % cols) diff) 
          panels (cond-> [{:lines [(str (when-let [message-id (:message-id log-entry)]
                                          message-id) 
                                        " - " 
                                        (when-let [created-at (:created-at log-entry)] 
                                          (java.util.Date. created-at)))] 
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

      (handle-copy! key-val replica log-entry)

      (render-panels! panels (:scroll-offset @state))
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
        (swap! state (fn [st]
                       (let [prev-replica (last (:replicas st))
                             new-replica (extensions/apply-log-entry entry prev-replica)]
                         (-> st 
                             (update :log conj entry)
                             (update :diffs conj (clojure.data/diff prev-replica new-replica))
                             (update :replicas conj new-replica)))))
        (recur (<!! ch))))))

(defn dump-peer-log! [peer-config onyx-id]
  (println "Dumping peer log to peer-log.edn")
  (let [ch (chan)
        started-sub (-> peer-config
                        (assoc :onyx/id onyx-id)
                        (onyx.api/subscribe-to-log ch))

        log (loop [entries [] 
                   [entry ch] (alts!! [ch (timeout 1000)] :priority true)]
              (println "Read entry" entry)
              (if entry
                (recur (conj entries entry) 
                       (alts!! [ch (timeout 1000)] :priority true))
                entries))]
    (spit "peer-log.edn" (pr-str log)))
  (println "Done dumping peer log"))

(defn slurp-edn [filename]
  (read-string (slurp filename)))

(defn import-peer-log! [peer-log]
  {:post [(= (count (:replicas @state)) (count (:diffs @state)))]}
  (swap! state (fn [st]
                 (let [initial-replica (first (:replicas st))
                       all-replicas (replicas initial-replica (sanitize-peer-log peer-log))] 
                   (-> st 
                       (assoc :replicas all-replicas)
                       (update :log into peer-log)
                       (update :diffs into (diffs all-replicas)))))))

(defn filter! [value]
  (swap! state 
         (fn [st] 
           (let [filtered (filter (fn [[replica diff]]
                                    (get (set (map str (flatten-anything (butlast diff)))) 
                                         (str value))) 
                                  (map list (:replicas st) (:diffs st) (:log st)))
                 new-replicas (mapv first filtered)
                 new-diffs (mapv second filtered)
                 new-logs (mapv last filtered)]
             (assoc st :replicas new-replicas :diffs new-diffs :log new-logs)))))

(defn start! [type src filtered zookeeper-addr job-scheduler]
  (let [peer-config {:zookeeper/address zookeeper-addr
                     :onyx.peer/job-scheduler job-scheduler
                     :onyx.messaging/impl :aeron}]
    (initialise-state! peer-config)

    (case type
      "edn" (import-peer-log! (slurp-edn src))
      "jepsen" (import-peer-log! (:peer-log (:information (:cluster-invariants (slurp-edn src)))))
      "zookeeper" (import-zookeeper! peer-config src)))

  (when filtered 
    (filter! filtered))

  (s/start scr)
   ;; work around initial draw issues
  (s/get-key scr) (s/redraw scr)
  (render-loop! scr))

(defn -main
  [& [zookeeper-addr onyx-id job-scheduler]]
  (dump-peer-log! {:zookeeper/address zookeeper-addr
                   :onyx.peer/job-scheduler (keyword job-scheduler)
                   :onyx.messaging/bind-addr "127.0.0.1"
                   :onyx.messaging/impl :aeron}
                  onyx-id)
  (System/exit 0))

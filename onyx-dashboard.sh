#!/usr/bin/env boot

(set-env! :dependencies '[[fipp "0.6.2"]
                          [clojure-lanterna "0.9.4"]
                          [org.onyxplatform/onyx-console-dashboard "0.1.0-SNAPSHOT"]])
(require '[onyx-console-dashboard.core :refer [start!]])
(require '[boot.cli :refer [defclifn]])

(defclifn -main
  [s src TYPE str "input type: jepsen, zookeeper, edn"
   e edn PATH str "input filename"
   f filter FILTER str "only show replicas that change by the supplied value"
   o onyx-version ONYXVERSION str "Onyx dependency version to use to play the log"]

  (set-env! :dependencies [['org.onyxplatform/onyx (or onyx-version "0.8.4-SNAPSHOT")]])

  (start! src edn filter))

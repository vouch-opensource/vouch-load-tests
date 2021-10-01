(ns io.vouch.load-tests.task.terminate-scenario
  (:require
    [clojure.core.async :refer [go]]
    [clojure.tools.logging :as log]
    [io.vouch.load-tests.executor :as executor]))

(defmethod executor/execute-task :terminate-scenario
  [{:keys [id terminate-scenario]} msg]
  (go
    (log/info id msg)
    (terminate-scenario)))

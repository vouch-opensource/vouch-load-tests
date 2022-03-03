(ns com.example.task.default
  (:require
    [clojure.core.async :refer [go]]
    [clojure.tools.logging :as log]
    [io.vouch.load-tests.executor :as executor]))

(defmethod executor/execute-task :default
  [{:keys [id]} msg]
  (go
    (log/info id msg)))

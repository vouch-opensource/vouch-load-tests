(ns io.vouch.load-tests.task.wait
  (:require
    [clojure.core.async :refer [<! go timeout thread]]
    [clojure.tools.logging :as log]
    [io.vouch.load-tests.executor :as executor]
    [io.vouch.load-tests.task.definition.converter :as definition-converter]))

(defmethod executor/execute-task :wait
  [{:keys [id]} {:keys [duration] :as msg}]
  (go
    (log/info id msg)
    (<! (timeout duration))))

(defmethod definition-converter/task-definition->task :wait
  [definition]
  (-> definition
    (update :duration definition-converter/duration-definition->number (get definition :unit :seconds))
    (dissoc :unit)))

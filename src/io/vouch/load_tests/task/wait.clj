(ns io.vouch.load-tests.task.wait
  (:require
    [clojure.core.async :refer [<! go timeout]]
    [clojure.tools.logging :as log]
    [io.vouch.load-tests.executor :as executor]
    [io.vouch.load-tests.task.definition.converter :as definition-converter]))

(defmethod executor/execute-task :wait
  [{:keys [id index get-executors]} {:keys [duration multiply-by] :as msg}]
  (go
    (let [duration (cond-> duration
                           (= :index multiply-by) (* index)
                           (= :reversed-index multiply-by) (* (- (count (get-executors)) 1 index)))]
      (log/info id "waiting for" duration msg)
      (<! (timeout duration)))))

(defmethod definition-converter/task-definition->task :wait
  [definition]
  (-> definition
    (update :duration definition-converter/duration-definition->number (get definition :unit :seconds))
    (dissoc :unit)))

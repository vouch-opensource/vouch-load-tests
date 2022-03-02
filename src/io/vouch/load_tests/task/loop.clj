(ns io.vouch.load-tests.task.loop
  (:require
    [clojure.core.async :refer [<! go]]
    [clojure.tools.logging :as log]
    [io.vouch.load-tests.executor :as executor]
    [io.vouch.load-tests.task.definition.converter :as definition-converter]))

(defmethod definition-converter/task-definition->task :loop
  [definition]
  (let [{:keys [duration times tasks unit]} definition]
    (when (some? (and times duration))
      (throw (ex-info "Loop task may not have both :times and :duration parameters defined" {:definition definition})))
    (cond-> definition
      tasks (update :tasks (partial map definition-converter/task-definition->task))
      times (update :times definition-converter/number-definition->number)
      duration (update :duration definition-converter/duration-definition->number (or unit :seconds))
      true (dissoc :unit))))

(defmethod executor/execute-task :loop
  [{:keys [id] :as executor} {:keys [tasks duration times] :as msg}]
  (go
    (try
      (log/info id msg)
      (let [child-executor (executor/create (update executor :id str "-loop"))
            start-time     (System/currentTimeMillis)]
        (if (some? duration)
          (loop []
            (<! (executor/schedule child-executor tasks))
            (let [now       (System/currentTimeMillis)
                  diff      (- now start-time)
                  continue? (> duration diff)]
              (when continue?
                (recur))))
          (doseq [_ (range times)]
            (<! (executor/schedule child-executor tasks))))
        (executor/stop child-executor))
      (catch Exception e e))))

(ns io.vouch.load-tests.task.loop
  (:require
    [clojure.core.async :refer [<! go]]
    [clojure.tools.logging :as log]
    [io.vouch.load-tests.executor :as executor]
    [io.vouch.load-tests.task.definition.converter :as definition-converter]))

(defmethod definition-converter/task-definition->task :loop
  [definition]
  (-> definition
    (update :tasks (partial map definition-converter/task-definition->task))
    (update :times definition-converter/number-definition->number)))

(defmethod executor/execute-task :loop
  [{:keys [id] :as executor} {:keys [tasks times] :as msg}]
  (go
    (try
      (log/info id msg)
      (let [child-executor (executor/create (update executor :id str "-loop"))]
        (doseq [_ (range times)]
          (<! (executor/schedule child-executor tasks)))
        (executor/stop child-executor))
      (catch Exception e e))))

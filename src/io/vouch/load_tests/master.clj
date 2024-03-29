(ns io.vouch.load-tests.master
  (:require
    [clojure.core.async :refer [<! chan close! go]]
    [clojure.tools.logging :as log]
    [io.vouch.load-tests.executor :as executor]
    [io.vouch.load-tests.task.definition.converter :refer [task-definition->task]]))

(defn stop
  [master]
  (log/info "Stopping" 'io.vouch.digital-key.load-tests.master)
  (close! master))

(defn start
  [{:keys [scenario create-executor-state reporter] :as config}]
  (let [close      (chan)
        terminator (chan)]
    (go
      (<! terminator)
      (stop close))
    (go
      (let [{:keys [workflows actor-pools]} scenario
            executors            (atom [])
            schedule-completions (atom [])]
        (doseq [{:keys [workflow actors] :as pool} actor-pools]
          (doseq [_ (range actors)]
            (let [index    (count @executors)
                  executor (executor/create
                             (-> config
                                 (assoc :reporter reporter)
                                 (assoc :terminate-scenario #(close! terminator))
                                 (dissoc :create-executor-state)
                                 (merge (dissoc pool :actors))
                                 (assoc :id (str "executor-" index "-" workflow)
                                        :index index
                                        :get-executors #(deref executors)
                                        :state (atom (merge
                                                       {}
                                                       (and create-executor-state (create-executor-state)))))))
                  steps    (get workflows workflow)]
              (swap! executors conj executor)
              (swap! schedule-completions conj
                     (executor/schedule executor (map task-definition->task steps))))))
        (go
          (doseq [completion @schedule-completions]
            (<! completion))
          (log/info "All executors finished, terminating scenario")
          (close! terminator))
        (<! close)
        (close! reporter)
        (close! terminator)
        (doseq [executor @executors] (executor/stop executor))
        (log/info "Master stopped")))
    close))

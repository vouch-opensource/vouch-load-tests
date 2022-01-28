(ns io.vouch.load-tests.executor
  (:require
    [clojure.core.async :refer [<! >! chan close! go]]
    [clojure.set :refer [intersection subset?]]
    [clojure.tools.logging :as log]))

(def stop-event ::stop)

(def ^:private accepts-keys-behavior :accepts-keys)

(defn add-listener
  [executor event listener]
  (swap! (:state executor) update-in [:listeners event] conj listener))

(defn get-behavior
  [executor]
  (merge {accepts-keys-behavior true} (:behavior executor)))

(defn accepts-keys?
  [executor]
  (boolean (get (get-behavior executor) accepts-keys-behavior)))

(defn schedule
  [executor task]
  (letfn [(do-schedule [executor task]
            (go (let [ch (chan)]
                  (>! (:inbound executor) [task ch])
                  (<! ch))))]
    (go
      (if (or (vector? task) (seq? task))
        (doseq [task task]
          (<! (do-schedule executor task)))
        (<! (do-schedule executor task))))))

(defmulti execute-task (fn [_ msg] (:task msg)))

(defn create
  [{:keys [id reporter state] :as config}]
  (let [ch       (chan)
        executor (assoc config :inbound ch :state (or state (atom {})))]
    (go
      (loop []
        (when-let [[task done] (<! ch)]
          (try
            (let [start  (System/currentTimeMillis)
                  result (<! (execute-task executor task))
                  end    (System/currentTimeMillis)]
              (>! reporter (cond-> {:duration (- end start) :task task :executor id}
                             (instance? Throwable result) (assoc :error result))))
            (finally (close! done)))
          (recur)))
      (log/info "Stopping executor" id))
    executor))

(defn filter-executors
  ([current-executor criteria]
   (filter-executors current-executor criteria nil))
  ([current-executor {:keys [behavior tags workflow]} filter-fn]
   (filter
     (fn [executor]
       (letfn [(matching-behavior []
                 (or (nil? behavior) (= behavior (select-keys (get-behavior executor) (keys behavior)))))
               (matching-workflow []
                 (or (nil? workflow) (= workflow (:workflow executor))))
               (matching-tags []
                 (if (nil? tags)
                   true
                   (let [expected (into #{} tags)
                         actual   (into #{} (:tags executor))]
                     (subset? expected actual))))
               (enrolled? []
                 (or (nil? filter-fn) true (filter-fn executor)))]
         (and (not= current-executor executor) (enrolled?) (matching-workflow) (matching-behavior) (matching-tags))))
     ((:get-executors current-executor)))))

(defn stop
  [executor]
  (when-let [listeners (some-> executor :state deref (get-in [:listeners stop-event]))]
    (doseq [listener listeners] (listener)))
  (close! (:inbound executor)))

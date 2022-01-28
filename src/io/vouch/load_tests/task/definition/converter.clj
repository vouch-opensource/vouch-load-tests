(ns io.vouch.load-tests.task.definition.converter)

(defmulti task-definition->task (fn [definition] (:task definition)))

(defmethod task-definition->task :default [definition] definition)

(defn number-definition->number
  [definition]
  (if (vector? definition)
    (let [[low high] definition]
      (+ low (rand-int (- (inc high) low))))
    definition))

(defn- to-millis
  [unit value]
  (case unit
    :hours (* value 1000 60 60)
    :minutes (* value 1000 60)
    :seconds (* value 1000)
    value))

(defn duration-definition->number
  [definition unit]
  (if (vector? definition)
    (let [[low high] (map (partial to-millis unit) definition)]
      (+ low (rand-int (- (inc high) low))))
    (to-millis unit definition)))

(ns com.example.random)

(defn random-email
  []
  (str "tester-" (rand) "@example.com"))

(defn create-unique-generator
  [generator]
  (let [generated-values-ref (ref #{})]
    (fn []
      (dosync
        (let [generated-values @generated-values-ref
              max-attempts     1000]
          (loop [i 0]
            (when (> i max-attempts)
              (throw (ex-info (str "Unable to generate unique value within " max-attempts " attempts") {})))
            (let [value (generator)]
              (if (contains? generated-values value)
                (recur (inc i))
                (do
                  (alter generated-values-ref conj value)
                  value)))))))))

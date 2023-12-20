# Vouch Load Tests

This is a framework for building tools to load test arbitrary systems. There is one master that spawns executors that
execute your workflow.

```clojure
(io.vouch.load-tests.master/start
  {:scenario              scenario
   :create-executor-state create-executor-state
   :reporter              reporter})
```

Scenario describes workflows (you may think of a workflow as of user that acts in certain way)
and how many actors (users) should take part in given scenario.

```clojure
(def scenario
  {:workflows   {:listener [{:task :register-user}
                            {:task :wait :duration 5}
                            {:task :listen-to-friend-requests}]
                 :inviter  [{:task :register-user}
                            {:task :send-friend-request}]}
   :actor-pools [{:workflow :listener :actors 100}
                 {:workflow :inviter :actors 10}]})
```

Workflow composes of tasks. Each task definition must have at least `:task` key to link the task to
implementation `defmethod`. The task may contain additional configuration data that your task may need. You are
responsible for providing implementation of the task. The framework ships with a few most common tasks.

I.e. `{:task :wait :duration 5}` the `wait` task requires `duration` property.

```clojure
(defmethod io.vouch.load-tests.executor/execute-task :wait
  [{:keys [id] :as executor} {:keys [duration] :as task}]
  (go
    (log/info id task)
    (<! (timeout (* 1000 duration)))))
```

## Handling task failure

Task failures, by default, are merely reported and the workflow proceeds as usual.
However, you can alter this default behavior by incorporating the `:on-error` attribute into the task.
The permissible values for this attribute are:

* `:stop-executor`
* `:terminate-workflow`

## Implementing a task

In order to implement a new type of task you have to provide `defmethod io.vouch.load-tests.executor/execute-task`
with appropriate selector. Let's suppose we want to implement `:register-user` task.

```clojure
(defmethod io.vouch.load-tests.executor/execute-task :register-user
  [executor msg]
  (go
    (let [email    (str "tester-" (rand) "@example.com")
          password (str (rand))
          chan     (chan)]
      (http/post (str "http://example.com/api/user/register")
        {:body    (json/encode {:email email :password password})
         :async?  true
         :headers {:content-type "application/json"
                   :accept       "application/json"}}
        #(if-let [result (some-> % :body (json/decode true))]
           (put! chan result)
           (close! chan))
        #(put! chan %))
      chan)))
```

> It is important to note that `execute-task` function must return a channel that signals when task is finished.

### Executor config

There are two problems with the above implementation. The email should usually be unique and with `(rand)` we still have
a chance of getting duplicates. Problem number two is that the url is hardcoded, and we may want to reuse the same step
against different environments.

The executor inherits config passed when starting the master. Let's pass API URL to master's config.

```clojure
(io.vouch.load-tests.master/start
  {...
   :api-url (System/getenv "API_URL")})
```

With that we can access the `api-url` inside the task:

```clojure
(defmethod io.vouch.load-tests.executor/execute-task :register-user
  [{:keys [api-url]} msg]
  (go
    (let [email    (str "tester-" (rand) "@example.com")
          password (str (rand))
          chan     (chan)]
      (http/post (str api-url "/user/register")
        {:body    (json/encode {:email email :password password})
         :async?  true
         :headers {:content-type "application/json"
                   :accept       "application/json"}}
        #(if-let [result (some-> % :body (json/decode true))]
           (put! chan result)
           (close! chan))
        #(put! chan %))
      chan)))
```

Similarly, we can tackle generation of random but unique emails.

```clojure
(defn random-email
  []
  (str "tester-" (rand) "@example.com"))

(io.vouch.load-tests.master/start
  {...
   :api-url (System/getenv "API_URL")
   :unique-email (create-unique-generator random-email)})

(defmethod io.vouch.load-tests.executor/execute-task :register-user
  [{:keys [api-url unique-email]} msg]
  (go
    (let [email    (unique-email)
          password (str (rand))
          chan     (chan)]
      (http/post (str api-url "/user/register")
        {:body    (json/encode {:email email :password password})
         :async?  true
         :headers {:content-type "application/json"
                   :accept       "application/json"}}
        #(if-let [result (some-> % :body (json/decode true))]
           (put! chan result)
           (close! chan))
        #(put! chan %))
      chan)))
```

> The `create-unique-generator` function is not so important for this article, but if you're interested here's the code:
> ```clojure
>(defn create-unique-generator
>  [generator]
>  (let [generated-values-ref (ref #{})]
>    (fn []
>      (dosync
>        (let [generated-values @generated-values-ref
>              max-attempts     1000]
>          (loop [i 0]
>            (when (> i max-attempts)
>              (throw (ex-info (str "Unable to generate unique value within " max-attempts " attempts") {})))
>            (let [value (generator)]
>              (if (contains? generated-values value)
>                (recur (inc i))
>                (do
>                  (alter generated-values-ref conj value)
>                  value)))))))))
>```  

### Executor state

Now our executors can register user accounts, but how do they authenticate subsequent requests? Our sample backend
returns an auth token as a response to successful registration. How can the executor access that token between tasks?
The answer is: through state. Each executor has its own state where your tasks can store data that should be accessible
for subsequent tasks.

```clojure
(defn- register-user
  [api-url email password]
  (let [chan (chan)]
    (http/post (str api-url "/user/register")
      {:body    (json/encode {:email email :password password})
       :async?  true
       :headers {:content-type "application/json"
                 :accept       "application/json"}}
      #(if-let [result (some-> % :body (json/decode true) :token)]
         (put! chan result)
         (close! chan))
      #(put! chan %))
    chan))

(defmethod io.vouch.load-tests.executor/execute-task :register-user
  [{:keys [api-url id unique-email state]} msg]
  (go
    (log/info id msg)
    (let [email    (unique-email)
          password (str (rand))
          token    (<! (register-user api-url email password))]
      (if (instance? Exception token)
        token
        (swap! state assoc :auth-token token)))))
```

You can see on the last line that we're updating executor's state with `auth-token`. We've extracted the logic
responsible for making http request and parsing response into separate function for readability.

Now we can access the token form another task:

```clojure
(defn- friend-requests
  [api-url auth-token]
  (let [chan (chan)]
    (http/get (str api-url "/user/friend-requests")
      {:async?  true
       :headers {:authorization (str "Bearer " auth-token)
                 :content-type  "application/json"
                 :accept        "application/json"}}
      #(if-let [result (some-> % :body (json/decode true))]
         (put! chan result)
         (close! chan))
      #(put! chan %))
    chan))

(defmethod io.vouch.load-tests.executor/execute-task :listen-to-friend-requests
  [{:keys [api-url id state]} msg]
  (go
    (log/info id msg)
    (let [auth-token (-> state deref :auth-token)]
      (friend-requests api-url auth-token))))
```

### Accessing other executors from within a task

It may happen that you need to access some information about other executors. Consider a scenario where actors interact
with each other through the backend. I.e. actor A sends friend request to actor B and actor B accepts or rejects the
invitation. You can access other executors using `get-executors` function.

```clojure
(defmethod io.vouch.load-tests.executor/execute-task :send-friend-request
  [{:keys [api-url state get-executors]} msg]
  (go
    (let [auth-token (-> state deref :auth-token)
          email      (-> (get-executors) shuffle first :state deref :email)]
      (send-friend-request api-url auth-token email))))
```

#### Filtering executors

In the example above we shuffled list of executors and picked first one. But what if we want to define different pools
of actors that behave differently? Each executor inherits 3 items from the actor-pool they belong to:

```clojure
[:behavior :tags :workflow]
```

Let's consider this scenario:

```clojure
{:workflows   {:listeners [{:task :register-user}
                           {:task :listen-to-friend-requests}]
               :inviter   [{:task :register-user}
                           {:task :wait :duration 1}
                           {:task :send-friend-request :to {:behavior {:accept-friend-request true}}}
                           {:task :send-friend-request :to {:accept-friend-request false}}
                           {:task :send-friend-request :to {:workflow :listeners}}
                           {:task :send-friend-request :to {:tags [:singleton]}}]}
 :actor-pools [{:workflow :listeners :actors 10 :behavior {:accept-friend-request true}}
               {:workflow :listeners :actors 10 :behavior {:accept-friend-request false}}
               {:workflow :listeners :actors 1 :tags [:singleton]}
               {:workflow :inviter :actors 10}]}
```

Some users are listening to friend requests (perhaps polling the backend for invitations)
and some users are sending friend requests. The `:listen-to-friend-requests` task polls the backend for invitations and
if any are found it accepts or rejects based on `:behavior` of the `:actor-pool` the executor belongs to. This allows us
to have only one implementation of the task, but customize the behavior in a declarative way on the scenario level. That
also saves us on the number of workflows we have to define, because multiple actor pools can use the same workflow, but
still have a little different behavior.

Now the `:inviter` is supposed to send a friend request. We want first request to be sent to somebody that will accept
it. We do it by augmenting the task with `{:to {:behavior true}}`.

The second requests should be sent to somebody that will reject it.

The third request should be sent to anybody that is going to act on it. We know that actors executing `:listeners`
workflow will do something with friend requests hence we use `{:to {:workflow :listeners}}`
selector.

The fourth request must go from all inviters to the same user. That's why we have actor pool tagged as `:singleton`
that has only one actor.

Here is the code for the task:

```clojure
(defmethod io.vouch.load-tests.executor/execute-task :send-friend-request
  [{:keys [api-url state] :as executor} {:keys [to]}]
  (go
    (if-let [email (some-> executor
                     (io.vouch.load-tests.executor/filter-executors to #(some-> % :state deref :email))
                     shuffle first :state deref :email)]
      (let [auth-token (-> state deref :auth-token)]
        (send-friend-request api-url auth-token email))
      (log/warn "No executor matching following criteria" to))))
```

## Built-in tasks

Framework provides a few built-in tasks.

### Wait task

Pause executor for a duration of time. Default unit is seconds.

```clojure
{:task :wait :duration 2} ; wait 2 seconds
{:task :wait :duration 500 :unit :milliseconds} ; wait 500 milliseconds
{:task :wait :duration 1 :unit :minutes} ; wait 1 minute
{:task :wait :duration [3 8]} ; wait random duration between 3 and 8 seconds
```

### Loop task

Repeat a sequence of tasks given number of times or withing some time duration.

```clojure
{:task  :loop
 :tasks [{:task :send-friend-request}
         {:task :wait :duration [1 2]}]
 :times 3}
```

The above example sends 3 friend requests waiting between 1 and 2 seconds in between.

```clojure
{:task     :loop
 :duration [200 500]
 :unit     :milliseconds
 :tasks    [{:task :register-user}]}
```

Here we attempt to register users as many users as possible between 200 and 500 milliseconds.

### Terminate scenario task

Stops master and all executors.

```clojure
{:task :terminate-scenario}
```

### Caveats

#### Starving core.async thread pool

Be careful not to use blocking operations when implementing tasks, otherwise you could easily starve `core.async` thread
pool if server takes longer time to respond, and you spawn many actors:

```clojure
(defmethod io.vouch.load-tests.executor/execute-task :register-user
  [{:keys [api-url]} msg]
  (go
    (let [email    (str "tester-" (rand) "@example.com")
          password (str (rand))]
      (clj-http.client/post (str api-url "/user/register")
        {:body    (json/encode {:email email :password password})
         :headers {:content-type "application/json"
                   :accept       "application/json"}}))))
```

The `clj-http.client/post` is by default blocking, so even though this happens in a `go` block, the thread that executes
that block is actually getting blocked, and `core.async` has limited number of threads in its pool. Here is proper
implementation:

```clojure
(let [chan (async/chan)]
  (http/post (str "http://example.com/api/user/register")
    {:body    (json/encode {:email email :password password})
     :async?  true
     :headers {:content-type "application/json"
               :accept       "application/json"}}
    #(if-let [result (some-> % :body (json/decode true))]
       (put! chan result)
       (close! chan))
    #(put! chan %))
  chan)
```

#### Reporter

When starting `io.vouch.load-tests.master` one of required config elements is reporter. It must be a `core.async`
channel. Keep in mind that you can put only 1024 pending messages on the channel, so if you have more actors than you
should provide a buffer to the channel.

This results in an exception:

```clojure
(let [c (clojure.core.async/chan)]
  (doseq [_ (range 1025)]
    (clojure.core.async/go (clojure.core.async/put! c 1))))
```

While this is perfectly fine:

```clojure
(let [c (clojure.core.async/chan 2222)]
  (doseq [_ (range 2222)]
    (clojure.core.async/go (clojure.core.async/put! c 1))))
```

## Development

In order to run sample tests:

    clj -A:dev
    (dev)
    (reset)
             

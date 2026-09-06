(ns effect-test-entrypoint
  (:require [db :as db])
  (:require [main :as main])
  (:require [telegram :as telegram]))

(def- processed-update-ids (Set.))

(defn- database [effects]
  {:prepare (fn [sql]
              (.push effects {:type "d1.prepare" :sql sql})
              {:all (fn []
                      (.resolve
                       Promise
                       {:results (if (.startsWith sql "SELECT id, telegram_user_id")
                                   [{:id 1 :telegram_user_id "user-1" :text "https://t.me/serbia" :cursor 10}
                                    {:id 2 :telegram_user_id "user-2" :text "https://t.me/broken" :cursor 20}
                                    {:id 3 :telegram_user_id "user-3" :text "https://t.me/cursorfail" :cursor 30}
                                    {:id 4 :telegram_user_id "user-4" :text "https://t.me/quiet" :cursor 40}]
                                   [])}))
               :bind (fn [first second third]
                       (.push effects {:type "d1.bind"
                                       :params (if third [first second third] (if second [first second] [first]))})
                       {:run (fn []
                               (if (and (.startsWith sql "UPDATE") (= 3 second))
                                 (.reject Promise (Error. "cursor write failed"))
                                 (.resolve Promise {})))
                        :all (fn []
                               (.resolve
                                Promise
                                {:results (if (.startsWith sql "INSERT")
                                            (if (.startsWith sql "INSERT OR IGNORE INTO processed_updates")
                                              (if (.has processed-update-ids first)
                                                []
                                                (do
                                                  (.add processed-update-ids first)
                                                  [{:update_id first}]))
                                              (if (= "duplicate-user" first) [] [{:id 3}]))
                                            (if (= "empty-user" first)
                                              []
                                              (if (.startsWith sql "DELETE")
                                                [{:id 1}]
                                                [{:text "first"}
                                                 {:text "second"}])))}))})})})

(defn- external-fetch [effects]
  (fn [url props]
    (.push effects {:type "fetch" :url url :props props})
    (if (.startsWith url "https://t.me/s/broken")
      (.resolve Promise (Response. "unavailable" {:status 500}))
      (if (.startsWith url "https://t.me/s/quiet")
        (.resolve Promise (Response. ""))
        (if (.startsWith url "https://t.me/s/")
          (.resolve
           Promise
           (Response.
            (if (.startsWith url "https://t.me/s/cursorfail")
              "<div data-post=\"cursorfail/32\"></div><div data-post=\"cursorfail/31\"></div>"
              (if (.includes url "?after=")
                "<div data-post=\"serbia/13\"></div><div data-post=\"serbia/11\"></div>"
                "<div data-post=\"serbia/4\"></div><div data-post=\"serbia/9\"></div>"))))
          (let [body (if props (JSON.parse (get props "body")) nil)]
            (.resolve
             Promise
             (Response.
              (JSON.stringify {:ok (if (= "https://t.me/serbia/11" (get body "text")) false true)})
              {:headers {"content-type" "application/json"}}))))))))

(export-default
 {:fetch (fn [request env ctx]
           (let [effects (Array.)]
             (telegram/with_fetch
              (external-fetch effects)
              (fn []
                (db/with_db
                 (database effects)
                 (fn []
                   (.then
                    (.resolve Promise (main/handle-fetch request env))
                    (fn [response]
                      (.then
                       (.text response)
                       (fn [body]
                         (Response.
                          (JSON.stringify {:effects effects
                                           :response body})
                          {:headers {"content-type" "application/json"}})))))))))))
  :scheduled (fn [controller env ctx]
               (let [effects (Array.)]
                 (telegram/with_fetch
                  (external-fetch effects)
                  (fn []
                    (db/with_db
                     (database effects)
                     (fn []
                       (.then
                        (main/handle-scheduled env)
                        (fn []
                          (globalThis.console.log
                           (JSON.stringify {:event "scheduled_effects" :effects effects}))))))))))})

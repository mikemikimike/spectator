(ns main-test
  (:require ["node:assert/strict" :as assert])
  (:require ["node:test" :as t])
  (:require ["wrangler" :as wrangler]))

(def- server
  (wrangler/createTestHarness
   {:root "."
    :workers [{:config {:name "spectator-effect-test"
                        :main "bin/test/effect_test_entrypoint.js"
                        :compatibility_date "2026-08-24"}
               :vars {"TELEGRAM_BOT_TOKEN" "test-token"
                      "TELEGRAM_CHAT_ID" "test-chat"
                      "TELEGRAM_WEBHOOK_SECRET" "test-secret"}}]}))

(t/before (fn [] (.listen server)))
(t/after (fn [] (.close server)))

(t/test "worker sends help for /start"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/start"
                                                     :chat {:id "test-chat"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "test-chat"
                                                    :text "Доступные команды:
/add https://t.me/<канал> - добавить канал
/tasks - показать каналы
/delete <номер> - удалить канал"})}}])))))))))

(t/test "worker stores a Telegram channel with its latest post cursor"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/add https://t.me/Serbia"
                                                     :chat {:id "chat-1" :type "private"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "fetch"
                     :url "https://t.me/s/serbia"
                     :props {}}
                    {:type "d1.prepare"
                     :sql "INSERT OR IGNORE INTO tasks (telegram_user_id, text, cursor) VALUES (?1, ?2, ?3) RETURNING id"}
                    {:type "d1.bind" :params ["user-1" "https://t.me/serbia" 9]}
                    {:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Канал добавлен."})}}])))))))))

(t/test "worker rejects non-Telegram tasks in a private chat"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/add Buy milk"
                                                     :chat {:id "chat-1" :type "private"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Поддерживается только ссылка вида https://t.me/<канал>."})}}])))))))))

(t/test "worker rejects an unreadable Telegram channel"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/add https://t.me/broken"
                                                     :chat {:id "chat-1" :type "private"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "fetch" :url "https://t.me/s/broken" :props {}}
                    {:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Не удалось прочитать Telegram-канал."})}}])))))))))

(t/test "worker ignores /add outside a private chat"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/add https://t.me/serbia"
                                                     :chat {:id "group-1" :type "group"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual (get body "effects") [])))))))

(t/test "worker reports a duplicate lowercased channel"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/add https://t.me/Serbia"
                                                     :chat {:id "duplicate-user" :type "private"}
                                                     :from {:id "duplicate-user"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/equal
                 (get
                  (JSON.parse (get (get (get (get body "effects") 3) "props") "body"))
                  "text")
                 "Канал уже добавлен.")))))))

(t/test "worker ignores a repeated mutating update"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:update_id 100
                                           :message {:text "/delete 2"
                                                     :chat {:id "chat-1"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (let [effects (get body "effects")]
                  (assert/equal
                   (count (.filter effects
                                   (fn [effect]
                                     (= "DELETE FROM tasks WHERE id = (SELECT id FROM tasks WHERE telegram_user_id = ?1 ORDER BY id LIMIT 1 OFFSET ?2) AND telegram_user_id = ?1 RETURNING id"
                                        (get effect "sql")))))
                   1)
                  (.then
                   (.fetch server "/"
                           {:method "POST"
                            :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                            :body (JSON.stringify {:update_id 100
                                                   :message {:text "/delete 2"
                                                             :chat {:id "chat-1"}
                                                             :from {:id "user-1"}}})})
                   (fn [response]
                     (.then
                      (.json response)
                      (fn [body]
                        (let [effects (get body "effects")]
                          (assert/equal
                           (count (.filter effects
                                           (fn [effect]
                                             (= "DELETE FROM tasks WHERE id = (SELECT id FROM tasks WHERE telegram_user_id = ?1 ORDER BY id LIMIT 1 OFFSET ?2) AND telegram_user_id = ?1 RETURNING id"
                                                (get effect "sql")))))
                           0)
                          (.then
                           (.fetch server "/"
                                   {:method "POST"
                                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                                    :body (JSON.stringify {:update_id 101
                                                           :message {:text "/delete 2"
                                                                     :chat {:id "chat-1"}
                                                                     :from {:id "user-1"}}})})
                           (fn [response]
                             (.then
                              (.json response)
                              (fn [body]
                                (assert/equal
                                 (count (.filter (get body "effects")
                                                 (fn [effect]
                                                   (= "DELETE FROM tasks WHERE id = (SELECT id FROM tasks WHERE telegram_user_id = ?1 ORDER BY id LIMIT 1 OFFSET ?2) AND telegram_user_id = ?1 RETURNING id"
                                                      (get effect "sql")))))
                                 1)))))))))))))))))

(t/test "scheduled handler advances ordered posts and isolates failures"
        (fn []
          (.clearLogs server)
          (.then
           (.scheduled (.getWorker server) {:cron "* * * * *"})
           (fn []
             (let [logs (.getLogs server)
                   {:message message} (.find logs
                                             (fn [{:level level :message message}]
                                               (and (= "log" level)
                                                    (.includes message "scheduled_effects"))))
                   {:effects effects} (JSON.parse message)]
               (assert/deepStrictEqual
                effects
                (JSON.parse
                 (JSON.stringify
                  [{:type "d1.prepare" :sql "SELECT id, telegram_user_id, text, cursor FROM tasks ORDER BY id"}
                   {:type "fetch" :url "https://t.me/s/serbia?after=10" :props {}}
                   {:type "fetch"
                    :url "https://api.telegram.org/bottest-token/sendMessage"
                    :props {:method "POST"
                            :headers {"content-type" "application/json"}
                            :body (JSON.stringify {:chat_id "user-1" :text "https://t.me/serbia/11"})}}
                   {:type "d1.prepare" :sql "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"}
                   {:type "d1.bind" :params [11 1]}
                   {:type "fetch"
                    :url "https://api.telegram.org/bottest-token/sendMessage"
                    :props {:method "POST"
                            :headers {"content-type" "application/json"}
                            :body (JSON.stringify {:chat_id "user-1" :text "https://t.me/serbia/13"})}}
                   {:type "d1.prepare" :sql "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"}
                   {:type "d1.bind" :params [13 1]}
                   {:type "fetch" :url "https://t.me/s/broken?after=20" :props {}}
                   {:type "fetch" :url "https://t.me/s/cursorfail?after=30" :props {}}
                   {:type "fetch"
                    :url "https://api.telegram.org/bottest-token/sendMessage"
                    :props {:method "POST"
                            :headers {"content-type" "application/json"}
                            :body (JSON.stringify {:chat_id "user-3" :text "https://t.me/cursorfail/31"})}}
                   {:type "d1.prepare" :sql "UPDATE tasks SET cursor = ?1 WHERE id = ?2 AND cursor < ?1"}
                   {:type "d1.bind" :params [31 3]}
                   {:type "fetch" :url "https://t.me/s/quiet?after=40" :props {}}])))
               (assert/equal
                (count (.filter logs (fn [{:level level}] (= "error" level))))
                3))))))

(t/test "worker lists tasks for the Telegram user"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/tasks"
                                                     :chat {:id "chat-1"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "d1.prepare"
                     :sql "SELECT text FROM tasks WHERE telegram_user_id = ?1 ORDER BY id"}
                    {:type "d1.bind" :params ["user-1"]}
                    {:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "1. first\n2. second"})}}])))))))))

(t/test "worker deletes the numbered task for the Telegram user"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/delete 2"
                                                     :chat {:id "chat-1"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "d1.prepare"
                     :sql "DELETE FROM tasks WHERE id = (SELECT id FROM tasks WHERE telegram_user_id = ?1 ORDER BY id LIMIT 1 OFFSET ?2) AND telegram_user_id = ?1 RETURNING id"}
                    {:type "d1.bind" :params ["user-1" 1]}
                    {:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Задача удалена."})}}])))))))))

(t/test "worker reports a missing numbered task"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/delete 1"
                                                     :chat {:id "chat-1"}
                                                     :from {:id "empty-user"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "d1.prepare"
                     :sql "DELETE FROM tasks WHERE id = (SELECT id FROM tasks WHERE telegram_user_id = ?1 ORDER BY id LIMIT 1 OFFSET ?2) AND telegram_user_id = ?1 RETURNING id"}
                    {:type "d1.bind" :params ["empty-user" 0]}
                    {:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Задача не найдена."})}}])))))))))

(t/test "worker explains how to use /delete without a task number"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/delete"
                                                     :chat {:id "chat-1"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Использование: /delete <номер>"})}}])))))))))

(t/test "worker explains how to use /delete with an invalid task number"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/delete second"
                                                     :chat {:id "chat-1"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Использование: /delete <номер>"})}}])))))))))

(t/test "worker explains how to use /delete with zero"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/delete 0"
                                                     :chat {:id "chat-1"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Использование: /delete <номер>"})}}])))))))))

(t/test "worker explains how to use empty /add"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/add"
                                                     :chat {:id "chat-1" :type "private"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Укажите ссылку: /add https://t.me/<канал>"})}}])))))))))

(t/test "worker does not store whitespace-only /add"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/add   "
                                                     :chat {:id "chat-1" :type "private"}
                                                     :from {:id "user-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Укажите ссылку: /add https://t.me/<канал>"})}}])))))))))

(t/test "worker reports no tasks for an empty list"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/tasks"
                                                     :chat {:id "chat-1"}
                                                     :from {:id "empty-user"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual
                 (get body "effects")
                 (JSON.parse
                  (JSON.stringify
                   [{:type "d1.prepare"
                     :sql "SELECT text FROM tasks WHERE telegram_user_id = ?1 ORDER BY id"}
                    {:type "d1.bind" :params ["empty-user"]}
                    {:type "fetch"
                     :url "https://api.telegram.org/bottest-token/sendMessage"
                     :props {:method "POST"
                             :headers {"content-type" "application/json"}
                             :body (JSON.stringify {:chat_id "chat-1"
                                                    :text "Задач пока нет."})}}])))))))))

(t/test "worker ignores non-text Telegram updates"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:chat {:id "chat-1"}
                                                     :from {:id "user-1"}
                                                     :sticker {:emoji "!"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual (get body "effects") [])))))))

(t/test "worker ignores task commands without a Telegram user ID"
        (fn []
          (.then
           (.fetch server "/"
                   {:method "POST"
                    :headers {"X-Telegram-Bot-Api-Secret-Token" "test-secret"}
                    :body (JSON.stringify {:message {:text "/add Buy milk"
                                                     :chat {:id "chat-1"}}})})
           (fn [response]
             (.then
              (.json response)
              (fn [body]
                (assert/deepStrictEqual (get body "effects") [])))))))

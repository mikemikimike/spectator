(ns commands.start
  (:require [telegram :as telegram]))

(defn handle [env message]
  (if (= "/start" (:text message))
    (.then
     (telegram/send-message
      env
      (get-in message [:chat :id])
      "Доступные команды:\n/add https://t.me/<канал> - добавить канал\n/filter <номер> +нужное -запрещённое - настроить фильтр\n/tasks - показать каналы\n/delete <номер> - удалить канал")
     (fn [] (Response. "OK")))
    nil))

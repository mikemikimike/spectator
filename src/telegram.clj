(ns telegram
  (:require ["node:async_hooks" :as async_hooks]))

(def- fetch-fx (async_hooks/AsyncLocalStorage.))

(defn with-fetch [fetch f]
  (.run fetch-fx fetch f))

(defn- fetch! [url options]
  ((.getStore fetch-fx) url options))

(defn channel [text]
  (if-let [canonical (if text (-> text .trim .toLowerCase) "")
           match (.match canonical (RegExp. "^https://t[.]me/([a-z0-9_]+)/?$"))]
    {:text canonical :username (get match 1)}))

(defn preview-url [channel]
  (str "https://t.me/s/" (get channel "username")))

;; ponytail: Telegram does not document preview markup; update these selectors only when they break.
(defn- decode-entities [text]
  (let [decoded (-> text
                    (.replace (RegExp. "&#x([0-9a-f]+);" "gi")
                              (fn [match code] (String.fromCodePoint (Number.parseInt code 16))))
                    (.replace (RegExp. "&#([0-9]+);" "g")
                              (fn [match code] (String.fromCodePoint (Number.parseInt code 10))))
                    (.replaceAll "&nbsp;" " ")
                    (.replaceAll "&quot;" "\"")
                    (.replaceAll "&apos;" "'")
                    (.replaceAll "&lt;" "<")
                    (.replaceAll "&gt;" ">")
                    (.replaceAll "&amp;" "&"))]
    (if (.test (RegExp. "&(?:#[0-9]+|#x[0-9a-f]+|[a-z][a-z0-9]+);" "i") decoded)
      nil
      decoded)))

(defn- parse-posts [response]
  (let [posts (Array.)
        current (atom nil)
        append-text (fn [text]
                      (if-let [post (deref current)]
                        (Reflect.set post "text" (str (or (get post "text") "") text))))
        rewriter (-> (HTMLRewriter.)
                     (.on "[data-post]"
                          {:element (fn [element]
                                      (let [data-post (.getAttribute element "data-post")
                                            post {:id (Number (.slice data-post (+ 1 (.lastIndexOf data-post "/"))))
                                                  :text nil}]
                                        (reset! current post)
                                        (.push posts post)))})
                     (.on "[data-post] .js-message_text"
                          {:text (fn [chunk] (append-text (get chunk "text")))})
                     (.on "[data-post] .js-message_text br"
                          {:element (fn [element] (append-text " "))}))]
    (.then
     (.text (.transform rewriter response))
     (fn []
       (.forEach posts
                 (fn [post]
                   (if-let [text (get post "text")]
                     (Reflect.set post "text" (decode-entities (.trim text))))))
       posts))))

(defn fetch-posts [url require-post]
  (.then
   (fetch! url {})
   (fn [response]
     (if (and response (get response "ok"))
       (.then
        (parse-posts response)
        (fn [channel-posts]
          (if (or (> (count channel-posts) 0) (= false require-post))
            channel-posts
            (.reject Promise (Error. "Telegram preview has no posts")))))
       (.reject Promise (Error. "Telegram preview request failed"))))))

(defn send-message [env chat-id text]
  (.then
   (fetch!
    (str "https://api.telegram.org/bot" (get env "TELEGRAM_BOT_TOKEN") "/sendMessage")
    {:method "POST"
     :headers {"content-type" "application/json"}
     :body (JSON.stringify {:chat_id chat-id :text text})})
   (fn [response]
     (if (and response (get response "ok"))
       (.then
        (.json response)
        (fn [result]
          (if (get result "ok")
            result
            (.reject Promise (Error. "Telegram sendMessage failed")))))
       (.reject Promise (Error. "Telegram sendMessage request failed"))))))

(ns topiq.core
  (:gen-class :main true)
  (:require [clojure.core.async :refer [timeout sub chan <!! >!! <! >! go go-loop] :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [info warn error]]
            [ring.util.response :as resp]
            [compojure.core :refer [GET POST defroutes]]
            [compojure.handler :refer [site api]]
            [compojure.route :refer [resources]]
            [kabel.middleware.block-detector :refer [block-detector]]
            [kabel.middleware.log :refer [logger]]
            [kabel.platform :refer [create-http-kit-handler!]]
            [konserve.core :as k]
            [konserve.filestore :refer [new-fs-store]]
            [konserve.memory :refer [new-mem-store]]
            [net.cgrand.enlive-html :refer [deftemplate set-attr substitute html] :as enlive]
            [org.httpkit.server :refer [run-server]]
            [replikativ.p2p.fetch :refer [fetch]]
            [replikativ.p2p.hash :refer [ensure-hash]]
            [replikativ.p2p.hooks :refer [hook]]
            [replikativ.peer :refer [server-peer client-peer]]
            [replikativ.stage :as s]))


(def server-state (atom nil))


(deftemplate static-page
  (io/resource "public/index.html")
  []
  [:#bootstrap-css] (set-attr "href" "static/bootstrap/bootstrap-3.1.1-dist/css/bootstrap.min.css")
  [:#bootstrap-theme-css] (set-attr "href" "static/bootstrap/bootstrap-3.1.1-dist/css/bootstrap-theme.min.css")
  [:#react-js] (set-attr "src" "static/react/react-0.9.0.min.js")
  [:#jquery-js] (set-attr "src" "static/jquery/jquery-1.11.0.min.js")
  [:#bootstrap-js] (set-attr "src" "static/bootstrap/bootstrap-3.1.1-dist/js/bootstrap.min.js")
  [:#js-files] (substitute (html [:script {:src "js/main.js" :type "text/javascript"}])))


(defn create-store
  "Creates a konserve store"
  [state]
  (swap! state assoc :store (<!! #_(new-mem-store) (new-fs-store "store")))
  state)

(def hooks (atom {[#".*"
                   #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"
                   "master"]
                  [["eve@topiq.es"
                    #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"
                    "master"]]}))

(def err-ch (chan))

(go-loop [e (<! err-ch)]
  (when e
    (println "TOPIQ UNCAUGHT" e)
    (recur (<! err-ch))))

(defn create-peer
  "Creates replikativ server peer"
  [state]
  (let [{:keys [proto host port build tag-table store trusted-hosts]} @state]
    (swap! state
           (fn [old new] (assoc-in old [:peer] new))
           (server-peer (create-http-kit-handler!
                         (str (if (= proto "https") "wss" "ws") ;; should always be wss with auth
                              "://" host
                              (when (= :dev build)
                                (str ":" port))
                              "/replikativ/ws")
                         err-ch)
                        "topiq dev server"
                        store
                        err-ch
                        :middleware (comp (partial block-detector :server)
                                          (partial hook hooks store)
                                          (partial fetch store (atom {}) err-ch)
                                          ensure-hash
                                          ))))
  state)

(defroutes handler
  (resources "/")

  (GET "/replikativ/ws" [] (-> @server-state :peer deref :volatile :handler))

  (GET "/*" [] (if (= (:build @server-state) :prod)
                 (static-page)
                 (io/resource "public/index.html"))))


(defn read-config [state path]
  (let [config (-> path slurp read-string)]
    (swap! state merge config))
  state)


(defn init
  "Read in config file, create sync store and peer"
  [state path]
  (-> state
      (read-config path)
      create-store
      create-peer))


(defn start-server [port]
  (do
    (info (str "Starting server @ port " port))
    (run-server (site #'handler) {:port port :join? false})))


(defn -main [& args]
  (init server-state (first args))
  (info (clojure.pprint/pprint (dissoc @server-state :store :peer)))
  (start-server (:port @server-state)))


(comment
  (read-config (atom {}) "resources/server-config.edn")

  (init server-state "resources/server-config.edn")

  (def server (start-server (:port @server-state)))

  (server)

  (def commit-eval {'(fn replace [old params] params) (fn replace [old params] [params])
                    '(fn [old params] (d/db-with old params)) (fn [old params] (conj old params))})

  (count (<!! (s/commit-value (:store @server-state) commit-eval
                              (:causal-order (<!! (k/get-in (:store @server-state) [["eve@topiq.es" #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"]])))
                              #uuid "05b162ca-b6a6-5106-838f-00e30a1a5b9b")))

  (require '[replikativ.crdt.materialize :refer [key->crdt]])


  (<!! (k/get-in (:store @server-state) [["eve@topiq.es" #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"]]))

  (:subscriptions @(:peer @server-state))

  (count (keys (:causal-order (<!! (k/get-in (:store @server-state) [["eve@topiq.es" #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"]])))))

  (count (keys (:causal-order (<!! (k/get-in (:store @server-state) [["foo@bar.com" #uuid "26558dfe-59bb-4de4-95c3-4028c56eb5b5"]])))))






  )

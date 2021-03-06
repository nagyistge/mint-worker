(ns org.zalando.stups.mint.worker.external.scopes
  (:require [org.zalando.stups.friboo.ring :refer [conpath]]
            [clj-http.client :as client]
            [org.zalando.stups.friboo.system.oauth2 :as oauth2]
            [clojure.string :as str]))

(defn get-scope
  "GET /resource-types/{resource_type_id}/scopes/{scope_id}"
  [essentials-url resource-type-id scope-id tokens]
  {:pre [(not (str/blank? resource-type-id))
         (not (str/blank? scope-id))]}
  (:body (client/get (conpath essentials-url "/resource-types/" resource-type-id "/scopes/" scope-id)
                     {:oauth-token (oauth2/access-token :essentials-ro-api tokens)
                      :as          :json})))

(defn get-resource-type
  "GET /resource-types/{resource_type_id}"
  [essentials-url resource-type-id tokens]
  {:pre [(not (str/blank? resource-type-id))]}
  (:body (client/get (conpath essentials-url "/resource-types/" resource-type-id)
                     {:oauth-token (oauth2/access-token :essentials-ro-api tokens)
                      :as          :json})))

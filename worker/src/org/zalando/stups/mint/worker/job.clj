(ns org.zalando.stups.mint.worker.job
  (:require [org.zalando.stups.friboo.system.cron :refer [def-cron-component]]
            [org.zalando.stups.friboo.log :as log]
            [org.zalando.stups.friboo.config :as config]
            [overtone.at-at :refer [every]]
            [org.zalando.stups.mint.worker.external.storage :as storage]
            [org.zalando.stups.mint.worker.external.services :as services]
            [org.zalando.stups.mint.worker.external.apps :as apps]
            [org.zalando.stups.mint.worker.external.s3 :as s3]
            [clj-time.core :as time]
            [clj-time.coerce :refer [from-date to-date]]))

(def default-configuration
  {:jobs-cpu-count        1
   :jobs-every-ms         10000
   :jobs-initial-delay-ms 1000})

(defn sync-user
  "If neccessary, creates or deletes service users."
  [app kio-app configuration]
  (let [storage-url (config/require-config configuration :storage-url)
        service-user-url (config/require-config configuration :storage-url)
        app-id (:id app)
        team-id (:team_id kio-app)
        username (:username app)]
    (if-not (:active kio-app)
      ; inactive app, check if deletion is required
      (let [users (services/list-users service-user-url)]
        (if (contains? users username)
          (do
            (log/info "App %s is inactive; deleting user %s..." app-id username)
            (services/delete-user service-user-url username))
          (log/debug "App %s is inactive and has no user." app-id)))

      ; active app, check for last sync
      (if (time/after? (:last_modified app) (:last_synced app))
        (do
          (log/info "Synchronizing app %s..." app)
          (services/create-or-update-user service-user-url
                                          username
                                          {:id            username
                                           :name          (:name kio-app)
                                           :owner         team-id
                                           :client_config {:redirect_urls [(:redirect_url app)]
                                                           :scopes        []} ; TODO add realm specific scopes here
                                           :user_config   {:scopes []}}) ; TODO add scopes
          (log/info "Updating last_synced time of application %s..." app-id)
          (storage/update-status storage-url app-id {:last_synced (time/now)})
          (log/info "Successfully synced application %s" app-id))

        ; else
        (log/debug "App %s has not been modified since last sync. Skip sync." app-id)))))

(defn sync-password
  "If neccessary, creates and syncs a new password for the given app."
  [app kio-app configuration]
  (let [storage-url (config/require-config configuration :storage-url)
        service-user-url (config/require-config configuration :storage-url)
        username (:username app)
        app-id (:id app)
        team-id (:team_id kio-app)]
    (if (or (nil? (:last_password_rotation app))
            (time/after? (time/now)
                         (-> (:last_password_rotation app) (time/plus (time/hours 2)))))
      (do
        (log/info "Acquiring new password for %s..." username)
        (let [generate-pw-response (services/generate-new-password service-user-url username)
              password (:password generate-pw-response)
              txid (:txid generate-pw-response)]
          ((try
             (do
               (log/info "Saving the new password for %s to S3..." app-id)
               (s3/save-user team-id app-id username password)
               (services/commit-password service-user-url username {:txid txid})
               (log/info "Successfully rotated password for app %s" app-id))
             (catch Exception e
               (log/error e "Could not rotate password for app %s" app-id)
               (storage/update-status storage-url app-id {:has_problems true})))))) ; todo when to recover has_problems?

      ; else
      (log/debug "Password for app %s is still valid. Skip password rotation." app-id))))

(defn sync-client
  "If neccessary, creates and syncs new client credentials for the given app"
  [app]
  (let [app-id (:id app)]
    (if (or (nil? (:last_client_rotation app))
            (time/after? (time/now) (time/plus (from-date (:last_client_rotation app)) (time/months 1))))
      (do
        ; TODO handle applications, that require a constant client (e.g. mobile apps)
        ; TODO do the client rotation
        (log/debug "TODO"))

      ; else
      (log/info "Client for app %s is still valid. Skip client rotation." app-id))))

(defn sync-app
  "Syncs the application with the given app-id."
  [configuration app-id]
  (let [storage-url (config/require-config configuration :storage-url)
        kio-url (config/require-config configuration :storage-url)
        app (storage/get-app storage-url app-id)
        kio-app (apps/get-app kio-url app-id)]
    ; TODO handle 404 from kio for app
    (sync-user app kio-app configuration)
    (sync-password app kio-app configuration)
    (sync-client app)))

(defn run-sync
  "Creates and deletes applications, rotates and distributes their credentials."
  [configuration]
  (let [storage-url (config/require-config configuration :storage-url)]
    (log/info "Starting new synchronisation run with %s..." configuration)
    (try
      (let [apps (storage/list-apps storage-url)]
        (doseq [app apps]
          (try
            (sync-app configuration (:id app))
            (catch Exception e
              (log/error e "Could not synchronize app %s because %s." app (str e))))))
      (catch Exception e
        (log/error e "Could not synchronize apps because %s." (str e))))))

(def-cron-component
  Jobs []

  (let [{:keys [every-ms initial-delay-ms]} configuration]

    (every every-ms #(run-sync configuration) pool :initial-delay initial-delay-ms :desc "synchronisation")))
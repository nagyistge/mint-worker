(ns org.zalando.stups.mint.worker.job.run-test
  (:require [clojure.test :refer :all]
            [org.zalando.stups.mint.worker.test-helpers :refer [throwing
                                                                track
                                                                third
                                                                test-tokens
                                                                one?
                                                                test-config]]
            [org.zalando.stups.mint.worker.external.apps :as apps]
            [org.zalando.stups.mint.worker.external.s3 :as s3]
            [org.zalando.stups.mint.worker.external.storage :as storage]
            [org.zalando.stups.mint.worker.external.etcd :as etcd]
            [org.zalando.stups.mint.worker.job.sync-app :refer [sync-app]]
            [org.zalando.stups.mint.worker.job.run :as run]))

(def test-app
  {:id "kio"
   :s3_buckets ["test-bucket"]
   :s3_errors 0})

(def test-kio-app
  {:id "kio"
   :active true})

; test nothing bad happens without apps
(deftest resiliency-nothing
  (with-redefs [storage/list-apps (constantly [])]
    (run/run-sync test-config test-tokens)))

; test nothing bad happens on exception fetching apps
(deftest resiliency-error-on-fetch
  (with-redefs [storage/list-apps (throwing "mint-storage down")]
    (run/run-sync test-config test-tokens)))

; test nothing bad happens on exception processing an app
; test s3 counter does not get increased after non-s3 exception
(deftest resiliency-error-on-sync-app
  (let [calls (atom {})]
    (with-redefs [apps/list-apps (constantly (list test-kio-app))
                  storage/list-apps (constantly (list test-app))
                  storage/delete-app (track calls :delete)
                  storage/update-status (track calls :update-status)
                  sync-app (throwing "error in sync-app")]
      (run/run-sync test-config test-tokens)
      (is (one? (count (:update-status @calls))))
      ; should not call delete on error O.O
      (is (zero? (count (:delete @calls))))
      (let [call (first (:update-status @calls))
            app (second call)
            args (third call)]
        (is (= app (:id test-app)))
        (is (= (:has_problems args) true))
        (is (= (:s3_errors args) nil))))))

; https://github.com/zalando-stups/mint-worker/issues/16
(deftest increase-s3-errors-when-unwritable-buckets
  (let [calls (atom {})]
    (with-redefs [apps/list-apps (constantly (list test-kio-app))
                  storage/list-apps (constantly (list test-app))
                  storage/get-app (constantly test-app)
                  storage/delete-app (track calls :delete)
                  s3/writable? (constantly false)
                  storage/update-status (track calls :update-status)]
      (run/run-sync test-config test-tokens)
      (is (one? (count (:update-status @calls))))
      ; should not call delete on s3 error O.O
      (is (zero? (count (:delete @calls))))
      (let [call (first (:update-status @calls))
            app (second call)
            args (third call)]
        (is (= app (:id test-app)))
        (is (= (:has_problems args) true))
        ; https://github.com/zalando-stups/mint-worker/issues/17
        (is (= false (.contains (:message args) "LazySeq")))
        (is (one? (:s3_errors args)))))))

(deftest use-correct-kio-app
  (let [calls (atom {})]
    (with-redefs [apps/list-apps (constantly (list test-kio-app))
                  storage/list-apps (constantly (list test-app))
                  storage/update-status (constantly nil)
                  storage/delete-app (track calls :delete)
                  sync-app (track calls :sync)]
      (run/run-sync test-config test-tokens)
      (is (one? (count (:sync @calls))))
      (is (zero? (count (:delete @calls))))
      (let [call-param (first (:sync @calls))
            kio-app (third call-param)]
        (is (= test-kio-app
               kio-app))))))

(deftest delete-inactive
  "it should delete inactive applications"
  (let [calls (atom {})
        inactive-app (assoc test-kio-app :active false)]
    (with-redefs [apps/list-apps (constantly (list inactive-app))
                  s3/writable? (constantly true)
                  storage/list-apps (constantly (list test-app))
                  storage/update-status (track calls :update)
                  storage/delete-app (track calls :delete)
                  sync-app (track calls :sync)]
      (run/run-sync test-config test-tokens)
      ; should not update the status
      (is (zero? (count (:update @calls))))
      ; should try to sync one last time
      ; in order to delete the service user
      (is (one? (count (:sync @calls))))
      ; should call delete
      (is (one? (count (:delete @calls))))
      ; should delete the correct app O.O
      (let [call-param (first (:delete @calls))
            app (second call-param)]
        (is (= (:id test-app) app))))))


(deftest no-sync-when-locked
  (let [calls (atom {})]
    (with-redefs [apps/list-apps (constantly (list test-kio-app))
                  storage/list-apps (constantly (list test-app))
                  storage/update-status (track calls :update-status)
                  etcd/refresh-lock (constantly false)
                  sync-app (throwing "error in sync-app")]
      (run/run-sync (merge test-config {:etcd-lock-url "someurl"}) test-tokens)
      (is (zero? (count (:update-status @calls)))))))

(deftest sync-when-not-locked
  (let [calls (atom {})]
    (with-redefs [apps/list-apps (constantly (list test-kio-app))
                  storage/list-apps (constantly (list test-app))
                  storage/update-status (track calls :update-status)
                  etcd/refresh-lock (constantly true)
                  sync-app (throwing "error in sync-app")]
      (run/run-sync (merge test-config {:etcd-lock-url "someurl"}) test-tokens)
      (is (one? (count (:update-status @calls)))))))

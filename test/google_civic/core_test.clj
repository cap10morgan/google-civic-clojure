(ns google-civic.core-test
  (:require [google-civic.core :refer :all]
            [clojure.test :refer :all]
            [clj-http.fake :refer [with-fake-routes-in-isolation]]
            [cheshire.core :as json]))

(deftest api-url-test
  (testing "appends endpoint arg to api-root"
    (is (= "http://foo/bar"
           (with-redefs [api-root "http://foo"]
             (api-url "/bar"))))))

(deftest api-response-test
  (testing "adds api-key to request"
    (with-fake-routes-in-isolation {(str (api-url "/foo") "?key=FAKE-KEY")
                                    (fn [_] {:status 200 :headers {} :body ""})}
      (is (= 200
             (:status (api-response "FAKE-KEY" "/foo"))))))
  (testing "merges other query params into request"
    (with-fake-routes-in-isolation {(str (api-url "/foo")
                                         "?key=FAKE-KEY&bar=baz&qux=quux")
                                    (fn [_] {:status 200 :headers {} :body ""})}
      (is (= 200
             (:status (api-response "FAKE-KEY" "/foo"
                                    {:bar "baz" :qux "quux"})))))))

(deftest temporary-rate-limit?-test
  (testing "returns false if the code isn't 403"
    (is (not (temporary-rate-limit? {:status 404
                                     :body
                                     {:error {:errors
                                              [{:reason "notFound"}]}}}))))
  (testing "returns false if the reason isn't in the set"
    (is (not (temporary-rate-limit? {:status 403
                                     :body
                                     {:error {:errors
                                              [{:reason "quotaLimit"}]}}}))))
  (testing "returns false if there are any non set reasons"
    (is (not (temporary-rate-limit? {:status 403
                                     :body
                                     {:error
                                      {:errors
                                       [{:reason "rateLimitExceeded"}
                                        {:reason "quotaLimit"}]}}}))))
  (testing "returns true with only temporary rate limits"
    (is (temporary-rate-limit? {:status 403
                                :body
                                {:error
                                 {:errors
                                  [{:reason "rateLimitExceeded"}]}}}))
    (is (temporary-rate-limit? {:status 403
                                :body
                                {:error
                                 {:errors
                                  [{:reason "rateLimitExceeded"}
                                   {:reason "userRateLimitExceeded"}]}}}))))

(deftest can-retry?-test
  (testing "true if code is server error"
    (is (can-retry? {:status 500
                     :body {:error {:errors [{:reason "serverError"}]}}})))
  (testing "true if temporary rate limit"
    (is (can-retry?
         {:status 403
          :body {:error {:errors [{:reason "rateLimitExceeded"}]}}})))
  (testing "false for anything else"
    (is (not (can-retry?
              {:status 403
               :body {:error {:errors [{:reason "quotaExceeded"}]}}})))
    (is (not (can-retry? {:status 302 :body nil})))))

(deftest api-req-test
  (testing "returns response on success - no query params"
    (let [body {:yay "it worked"}
          call-counter (atom 0)]
      (with-fake-routes-in-isolation {(str (api-url "/foo")
                                           "?key=FAKE-KEY")
                                      (fn [_] (swap! call-counter inc)
                                              {:status 200
                                               :headers {"Content-Type"
                                                         "application/json"}
                                               :body (json/generate-string body)})}
        (let [response (api-req "FAKE-KEY" "/foo")]
          (is (= 200 (:status response)))
          (is (= body (:body response))))
        (is (= 1 @call-counter)))))
  (testing "returns response on success - with query params"
    (let [body {:yay "it worked"}
          call-counter (atom 0)]
      (with-fake-routes-in-isolation {(str (api-url "/foo")
                                           "?key=FAKE-KEY&extra=thingy")
                                      (fn [_] (swap! call-counter inc)
                                              {:status 200
                                               :headers {"Content-Type"
                                                         "application/json"}
                                               :body (json/generate-string body)})}
        (let [response (api-req "FAKE-KEY" "/foo" {:extra "thingy"})]
          (is (= 200 (:status response)))
          (is (= body (:body response))))
        (is (= 1 @call-counter)))))
  (testing "returns parsed error response"
    (let [error {:error {:errors [{:bad "error"}]}}
          call-counter (atom 0)]
      (with-fake-routes-in-isolation {(str (api-url "/foo")
                                           "?key=FAKE-KEY&bad=error")
                                      (fn [_] (swap! call-counter inc)
                                        {:status 400
                                         :headers {"Content-Type"
                                                   "application/json"}
                                         :body (json/generate-string
                                                error)})}
        (is (= {:status 400 :body error :headers {"Content-Type"
                                                  "application/json"}}
               (select-keys (api-req "FAKE-KEY" "/foo" {:bad "error"})
                            [:status :body :headers])))
        (is (= 1 @call-counter)))))
  (testing "will retry with back-off"
    (let [error {:error {:errors [{:reason "rateLimitExceeded"}]}}
          body {:yay "it worked"}
          call-counter (atom 0)]
      (with-fake-routes-in-isolation
        {(str (api-url "/foo")
              "?key=FAKE-KEY&extra=thingy")
         (fn [_]
           (let [c @call-counter]
             (swap! call-counter inc)
             (if (> c 0)
               {:status 200
                :headers {"Content-Type"
                          "application/json"}
                :body (json/generate-string body)}
               {:status 403
                :headers {"Content-Type"
                          "application/json"}
                :body (json/generate-string error)})))}
        (let [start (System/currentTimeMillis)
              response (api-req "FAKE-KEY" "/foo"
                                {:extra "thingy"}
                                {:tries 5 :sleep 1000})
              stop (System/currentTimeMillis)]
          (is (= 200 (:status response)))
          (is (= body (:body response)))
          (is (= 2 @call-counter))
          (is (<= 1000 (- stop start)))))))
  (testing "will retry until limit"
    (let [error {:error {:errors [{:reason "rateLimitExceeded"}]}}
          body {:yay "it worked"}
          call-counter (atom 0)]
      (with-fake-routes-in-isolation
        {(str (api-url "/foo")
              "?key=FAKE-KEY&extra=thingy")
         (fn [_]
           (let [c @call-counter]
             (swap! call-counter inc)
             {:status 403
              :headers {"Content-Type"
                        "application/json"}
              :body (json/generate-string error)}))}
        (let [start (System/currentTimeMillis)
              response (api-req "FAKE-KEY" "/foo" {:extra "thingy"}
                                {:tries 10 :sleep 100})
              stop (System/currentTimeMillis)]
          (is (= error (:body response)))
          (is (= 403 (:status response)))
          (is (= 10 @call-counter))
          (is (<= 900 (- stop start))))))))

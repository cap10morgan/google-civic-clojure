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
    (is (not (temporary-rate-limit? {:status 404}
                                    {:error {:errors
                                             [{:reason "notFound"}]}}))))
  (testing "returns false if the reason isn't in the set"
    (is (not (temporary-rate-limit? {:status 403}
                                    {:error {:errors
                                             [{:reason "quotaLimit"}]}}))))
  (testing "returns false if there are any non set reasons"
    (is (not (temporary-rate-limit? {:status 403}
                                    {:error
                                     {:errors
                                      [{:reason "rateLimitExceeded"}
                                       {:reason "quotaLimit"}]}}))))
  (testing "returns true with only temporary rate limits"
    (is (temporary-rate-limit? {:status 403}
                               {:error
                                {:errors
                                 [{:reason "rateLimitExceeded"}]}}))
    (is (temporary-rate-limit? {:status 403}
                               {:error
                                {:errors
                                 [{:reason "rateLimitExceeded"}
                                  {:reason "userRateLimitExceeded"}]}}))))

(deftest api-req-test
  (testing "returns parsed response body on success"
    (let [body {:yay "it worked"}]
      (with-fake-routes-in-isolation {(str (api-url "/foo")
                                           "?key=FAKE-KEY&extra=thingy")
                                      (fn [_] {:status 200
                                               :headers {"Content-Type"
                                                         "application/json"}
                                               :body (json/generate-string body)})}
        (is (= body
               (api-req "FAKE-KEY" "/foo" {:extra "thingy"}))))))
  (testing "returns parsed error response"
    (let [error {:error {:errors [{:bad "error"}]}}]
      (with-fake-routes-in-isolation {(str (api-url "/foo")
                                           "?key=FAKE-KEY&bad=error")
                                      (fn [_] {:status 400
                                               :headers {"Content-Type"
                                                         "application/json"}
                                               :body (json/generate-string
                                                      error)})}
        (is (= {:status 400 :body error :headers {"Content-Type"
                                                  "application/json"}}
               (select-keys (api-req "FAKE-KEY" "/foo" {:bad "error"})
                            [:status :body :headers])))))))

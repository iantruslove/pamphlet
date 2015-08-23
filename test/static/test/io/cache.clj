(ns static.test.io.cache
  (:require [clojure.test :refer :all]
            [static.io.cache :refer :all]))

(defn clear-cache-fixture [f]
  (reset! file-cache (new-file-cache))
  (f)
  (reset! file-cache (new-file-cache)))

(use-fixtures :once clear-cache-fixture)

(deftest test-cache
  (testing "basic cache hit and miss functionality"
    (let [call-counter (atom 0)
          f (fn [x] (swap! call-counter inc) (str "calc " x))]
      (is (= 0 @call-counter))
      (is (= "calc foo" (read-cached-file [:part1 "foo"] f)))
      (is (= 1 @call-counter))
      (read-cached-file [:part1 "foo"] f)
      (is (= 1 @call-counter) "generator function should not get called.")
      (invalidate-cache [:part1 "foo"])
      (read-cached-file [:part1 "foo"] f)
      (is (= 2 @call-counter))))
  (testing "partitioning"
    (let [call-counter-f (atom 0)
          f (fn [x] (swap! call-counter-f inc) (str "calc " x))
          call-counter-g (atom 0)
          g (fn [x] (swap! call-counter-g inc) (str "calc " x))]
      (is (= 0 @call-counter-f))
      (is (= 0 @call-counter-g))
      (read-cached-file [:part-f "file.txt"] f)
      (is (= 1 @call-counter-f))
      (is (= 0 @call-counter-g))
      (read-cached-file [:part-g "file.txt"] g)
      (is (= 1 @call-counter-f))
      (is (= 1 @call-counter-g)))))

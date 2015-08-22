(ns static.test.io
  (:require [clojure.test :refer :all]
            [static.io :as io]))

(deftest test-prepare-metadata
  (is (= {} (#'io/prepare-metadata "")))
  (is (= {} (#'io/prepare-metadata "\n\n")))
  (is (thrown? NullPointerException
               (#'io/prepare-metadata nil)))
  (is (= {:a "b"} (#'io/prepare-metadata "a: b")))
  (is (= {:a "b"} (#'io/prepare-metadata "a: b\n\n")))
  (is (= {:a "b" :c "d e f"} (#'io/prepare-metadata "a: b\nc: d e f")))
  (is (= {:a "b"} (#'io/prepare-metadata "a: b\na: d e f")))
  (is (= {:keyword-tags '(:foo)
          :tags "foo"} (#'io/prepare-metadata "tags: foo\n")))
  (is (= {:keyword-tags '(:foo :bar)
          :tags "foo bar"} (#'io/prepare-metadata "tags: foo bar\n")))
  (is (= {:keyword-keywords '(:foo)
          :keywords "foo"} (#'io/prepare-metadata "keywords: foo\n")))
  (is (= {:keyword-keywords '(:foo :bar)
          :keywords "foo bar"} (#'io/prepare-metadata "keywords: foo bar\n")))
  (is (= {:title "Hello, world!"
          :tags "programming blog"
          :keyword-tags '(:programming :blog)
          :keywords "simple easy"
          :keyword-keywords '(:simple :easy)
          :alias "[\"/2015/01/index.html\"]"}
         (#'io/prepare-metadata (str "title: Hello, world!\n"
                                     "tags: programming blog\n"
                                     "keywords: simple easy\n"
                                     "alias: [\"/2015/01/index.html\"]")))
      "Markdown frontmatter section")
  (is (= {:title "Hello, world!"
          :tags "programming blog"
          :keyword-tags '(:programming :blog)
          :keywords "simple easy"
          :keyword-keywords '(:simple :easy)
          :alias "[\"/2015/01/index.html\"]"}
         (#'io/prepare-metadata (str "#+title: Hello, world!\n"
                                     "#+tags: programming blog\n"
                                     "#+keywords: simple easy\n"
                                     "#+alias: [\"/2015/01/index.html\"]")))
      "Org metadata section"))

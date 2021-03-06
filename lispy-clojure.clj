;;; lispy-clojure.clj --- lispy support for Clojure.

;; Copyright (C) 2015-2017 Oleh Krehel

;; This file is not part of GNU Emacs

;; This file is free software; you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation; either version 3, or (at your option)
;; any later version.

;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.

;; For a full copy of the GNU General Public License
;; see <http://www.gnu.org/licenses/>.

(ns lispy-clojure
  (:require [clojure.repl :as repl]
            [clojure.java.io :as io])
  (:use [clojure.test :only (deftest is)])
  (:import (java.io File LineNumberReader InputStreamReader
                    PushbackReader FileInputStream)
           (clojure.lang RT Reflector)))

(defn expand-home
  [path]
  (if (.startsWith path "~")
    (let [sep (.indexOf path File/separator)]
      (str (io/file (System/getProperty "user.home")
                    (subs path (inc sep)))))
    path))

(defn source-fn
  "Returns a string of the source code for the given symbol, if it can
  find it.  This requires that the symbol resolve to a Var defined in
  a namespace for which the .clj is in the classpath.  Returns nil if
  it can't find the source.

  Example: (source-fn 'filter)"
  [x]
  (when-let [v (resolve x)]
    (when-let [filepath (expand-home (:file (meta v)))]
      (when-let [strm (or (.getResourceAsStream (RT/baseLoader) filepath)
                          (FileInputStream. filepath))]
        (with-open [rdr (LineNumberReader. (InputStreamReader. strm))]
          (dotimes [_ (dec (:line (meta v)))] (.readLine rdr))
          (let [text (StringBuilder.)
                pbr (proxy [PushbackReader] [rdr]
                      (read [] (let [i (proxy-super read)]
                                 (.append text (char i))
                                 i)))]
            (if (= :unknown *read-eval*)
              (throw (IllegalStateException. "Unable to read source while *read-eval* is :unknown."))
              (read (PushbackReader. pbr)))
            (str text)))))))

(defn symbol-function
  "Return the source code for function SYM."
  [sym]
  (read-string
   (source-fn
    sym)))

(defn macro? [x]
  (:macro (meta (resolve x))))

(defn arity [args]
  (if (some #{'&} args)
    1000
    (count args)))

(defn flatten-expr
  "Flatten a function call EXPR by substituting the arguments."
  [expr]
  (let [func-name (first expr)
        args (rest expr)
        func-def (lispy-clojure/symbol-function func-name)
        func-doc (when (string? (nth func-def 2))
                   (nth func-def 2))
        func-rest (drop (if func-doc 3 2) func-def)
        func-rest (if (map? (first func-rest))
                    (rest func-rest)
                    func-rest)
        func-bodies (if (vector? (first func-rest))
                      (list func-rest)
                      func-rest)
        func-body (first (filter #(>= (lispy-clojure/arity (first %)) (count args))
                                 (sort (fn [a b] (< (lispy-clojure/arity (first a))
                                                    (lispy-clojure/arity (first b))))
                                       func-bodies)))
        func-args (first func-body)
        func-impl (rest func-body)]
    (cons 'let
          (cons (vec (if (some #{'&} [func-args])
                       (vector func-args (vec args))
                       (apply concat
                              (filter (fn [[a b]]
                                        (not (= a b)))
                                      (partition
                                       2 (interleave func-args args))))))
                func-impl))))

(defn quote-maybe
  "Quote X that isn't self-quoting, like symbol or list."
  [x]
  (if (fn? x)
    x
    (if (or (symbol? x)
            (list? x))
      (list 'quote x)
      x)))

(defn dest
  "Transform `let'-style BINDINGS into a sequence of `def's."
  [bindings]
  (let [bs (partition 2 (destructure bindings))
        as (filterv
            #(not (re-matches #"^(vec|map)__.*" (name %)))
            (map first bs))]
    (concat '(do)
            (map (fn [[name val]]
                   `(def ~name ~val))
                 bs)
            [(zipmap (map keyword as) as)])))

(defn debug-step-in
  "Evaluate the function call arugments and sub them into function arguments."
  [expr]
  (let [func-name (first expr)
        args (rest expr)
        func-def (lispy-clojure/symbol-function func-name)
        func-doc (when (string? (nth func-def 2))
                   (nth func-def 2))
        func-rest (drop (if func-doc 3 2) func-def)
        func-rest (if (map? (first func-rest))
                    (rest func-rest)
                    func-rest)
        func-bodies (if (vector? (first func-rest))
                      (list func-rest)
                      func-rest)
        func-body (first (filter #(>= (lispy-clojure/arity (first %)) (count args))
                                 (sort (fn [a b] (< (lispy-clojure/arity (first a))
                                                    (lispy-clojure/arity (first b))))
                                       func-bodies)))
        func-args (first func-body)]
    (if (lispy-clojure/macro? func-name)
      (cons 'do
            (cons `(def ~'args ~(lispy-clojure/quote-maybe args))
                  (map (fn [[name val]]
                         `(def ~name ~val))
                       (partition 2 (destructure [func-args 'args])))))
      (lispy-clojure/dest (vector func-args (vec (rest expr)))))))

(defn object-methods [sym]
  (when (instance? java.lang.Object sym)
    (distinct
     (map #(.getName %)
          (.getMethods (type sym))))))

(defn object-fields [sym]
  (map #(str "-" (.getName %))
       (.getFields (type sym))))

(defn object-members [sym]
  (concat (object-fields sym)
          (object-methods sym)))

(defn get-meth [obj method-name]
  (first (filter #(= (.getName %) method-name)
                 (.getMethods (type obj)))))

(defn method-signature [obj method-name]
  (str (get-meth obj method-name)))

(defn get-ctors [obj]
  (. obj getDeclaredConstructors))

(defn format-ctor [s]
  (let [[_ name args] (re-find #"public (.*)\((.*)\)" s)]
    (str name
         "."
         (if (= args "")
           ""
           (str " " (clojure.string/replace args #"," " "))))))

(defn ctor-args [sym]
  (clojure.string/join
   "\n"
   (map #(str "(" % ")")
        (map format-ctor
             (map str (get-ctors sym))))))

(defn resolve-sym [sym]
  (cond (symbol? sym)
        (if (special-symbol? sym)
          'special
          (or
           (resolve sym)
           (first (keep #(ns-resolve % sym) (all-ns)))
           (if-let [val (try (load-string (str sym)) (catch Exception e))]
             (list 'variable (str val)))))

        (keyword? sym) 'keyword

        :else 'unknown))

(defn arglist [sym]
  (let [rsym (resolve-sym sym)]
    (cond (= 'special rsym)
          (->> (with-out-str
                 (eval (list 'clojure.repl/doc sym)))
               (re-find #"\(.*\)")
               read-string rest
               (map str)
               (clojure.string/join " ")
               (format "[%s]")
               list)

          :else
          (let [args (map str (:arglists (meta rsym)))]
            (if (empty? args)
              (condp #(%1 %2) (eval sym)
                map? "[key]"
                set? "[key]"
                vector? "[idx]"
                "is uncallable")
              args)))))

(deftest dest-test
  (is (= (eval (dest '[[x y] (list 1 2 3)]))
         {:x 1, :y 2}))
  (is (= (eval (dest '[[x & y] [1 2 3]]))
         {:x 1, :y '(2 3)}))
  (is (= (eval (dest '[[x y] (list 1 2 3) [a b] [y x]]))
         {:x 1, :y 2, :a 2, :b 1}))
  (is (= (eval (dest '[[x y z] [1 2]]))
         {:x 1, :y 2, :z nil}))
  (is (= (eval (dest '[[x & tail :as all] [1 2 3]]))
         {:x 1,
          :tail '(2 3),
          :all [1 2 3]}))
  (is (= (eval (dest '[[x & tail :as all] "Clojure"]))
         {:x \C,
          :tail '(\l \o \j \u \r \e),
          :all "Clojure"}))
  (is (= (eval (dest '[{x 1 y 2} {1 "one" 2 "two" 3 "three"}]))
         {:x "one", :y "two"}))
  (is (= (eval (dest '[{x 1 y 2 :or {x "one" y "two"} :as all} {2 "three"}]))
         {:all {2 "three"},
          :x "one",
          :y "three"}))
  (is (= (eval (dest '[{:keys [x y]} {:x "one" :z "two"}]))
         {:x "one", :y nil}))
  (is (= (eval (dest '[{:strs [x y]} {"x" "one" "z" "two"}]))
         {:x "one", :y nil}))
  (is (= (eval (dest '[{:syms [x y]} {'x "one" 'z "two"}]))
         {:x "one", :y nil})))

(deftest debug-step-in-test
  (is (= (eval (debug-step-in
                '(expand-home (str "/foo" "/bar"))))
         {:path "/foo/bar"})))

(defmacro ok
  "On getting an Exception, just print it."
  [& body]
  `(try
     (eval '~@body)
     (catch Exception ~'e (.getMessage ~'e))))

;; (clojure.test/run-tests 'lispy-clojure)

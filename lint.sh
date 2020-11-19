#! /usr/bin/env sh
lein eastwood "{:add-linters [:keyword-typos :unused-locals :unused-namespaces :unused-private-vars :unused-fn-args]}"

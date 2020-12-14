# instant-website

Actual backend for instant-website



### End-to-end Testing Suite (E2E)

The E2E testing suite ensures that our Figma -> website converter (figcup) keeps working at all times, with no visible changes for scenarios that are confirmed. There are multiple ways to run it, depending on what you need.

The E2E testing suite is based on the "Golden Master Testing" methodology, where PNG files in `e2e/expected` decides what the rendered result of a JSON payload from `e2e/json-payloads` should look like.

###### Run as part of all tests

```
# Download specific version of chromium to be used in E2E tests
cd chromedist && ./download.sh

# Run the E2E tests
lein with-profile e2e test
```

This command runs all the E2E tests and unit tests, marking cases as failing if there is any difference from what the websites was confirmed to be looking like before.

###### Run as standalone HTTP server with individual scenarios

```
lein with-profile e2e run
```

This will start a HTTP server listening on localhost:8378 and also run all tests.

If you navigate to the HTTP server, you'll see a list of all the test cases and their difference in the last test run. You can see images outlining the difference in red, in case there are differences. If you're sure that the difference is on purpose, you can mark it as the future baseline for how that test should be.

###### Run within a REPL

If you have a REPL running, you can run the E2E tests from there directly.

Please note that you'll need to have the core-api running before you run the test, otherwise it'll fail (as it couldn't actually create the test)

Main functions for development within a REPL:

```clojure
;; Run core-api server
(instant-website.core/-main)

;; Start the E2E HTTP server
(require 'e2e-tests.core)
(e2e-tests.core/start-server)

;; Runs all active E2E tests
(e2e-tests.core/run-all-tests)

;; Runs a specific E2E test and save results
(e2e-tests.core/run-all-tests "auto-layout")
```

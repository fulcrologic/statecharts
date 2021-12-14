tests:
	clojure -A:dev:test:clj-tests -J-Dguardrails.config=guardrails-test.edn -J-Dguardrails.enabled
	yarn
	npx shadow-cljs -A:dev compile ci-tests
	npx karma start --single-run

dev:
	clojure -A:dev:test:clj-tests -J-Dguardrails.config=guardrails-test.edn -J-Dguardrails.enabled --watch --fail-fast --no-capture-output

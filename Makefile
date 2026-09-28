docs/index.html: Guide.adoc
	asciidoctor -o docs/index.html -b html5 -r asciidoctor-diagram Guide.adoc 

tests:
	clojure -A:dev:test:clj-tests -J-Dguardrails.config=guardrails-test.edn -J-Dguardrails.enabled
	pnpm install --frozen-lockfile
	pnpm exec shadow-cljs -A:dev compile ci-tests
	pnpm exec karma start --single-run
	cd test-bb && bb smoke.clj && bb --config bb-tests.edn -f run-tests.clj

dev:
	clojure -A:dev:test:clj-tests -J-Dguardrails.config=guardrails-test.edn -J-Dguardrails.enabled --watch --fail-fast --no-capture-output


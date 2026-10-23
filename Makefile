.PHONY: up down logs reset evals test query-test tools observability

up:              ## Build and start the full stack
	docker compose up --build -d
	@echo "UI http://localhost:3000  API http://localhost:8080  MCP http://localhost:8080/mcp/"

down:
	docker compose down

reset:           ## Wipe the database volume and start fresh
	docker compose down -v

logs:
	docker compose logs -f worker query

tools:           ## Kafka UI on :8081
	docker compose --profile tools up -d kafka-ui

observability:   ## Jaeger on :16686 (also set OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4318 in .env, then `make up`)
	docker compose --profile observability up -d jaeger

test:            ## Ingestion plane unit tests (Java)
	cd ingestion && mvn -q test

query-test:      ## Query plane tests (needs: pip install -r query/requirements-dev.txt)
	cd query && python3 -m pytest -q

evals:           ## Golden-set evals against the running stack
	python3 evals/run_evals.py --api http://localhost:8080 --golden evals/golden.jsonl

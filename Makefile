.PHONY: up down logs reset evals test tools

up:            ## Build and start the full stack
	docker compose up --build -d
	@echo "UI http://localhost:3000  API http://localhost:8080"

down:
	docker compose down

reset:         ## Wipe the database volume and start fresh
	docker compose down -v

logs:
	docker compose logs -f worker query-api

tools:         ## Start Kafka UI on :8081
	docker compose --profile tools up -d kafka-ui

test:          ## Backend unit tests
	cd backend && mvn -q test

evals:         ## Run the golden-set evals against the running stack
	python3 evals/run_evals.py --api http://localhost:8080 --golden evals/golden.jsonl

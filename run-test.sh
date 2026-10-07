#!/bin/bash
set -e

# Default values
TYPE="baseline"
STRAT="no-cache"
N="600"
TTL="30"
DELAY="0"
JITTER="0.10"
TARGET_URL="http://localhost:8080/api/items/42"

# Parse arguments
for arg in "$@"; do
  key=$(echo $arg | cut -f1 -d=)
  val=$(echo $arg | cut -f2 -d=)
  case $key in
    type) TYPE=$val ;;
    strat) STRAT=$val ;;
    n) N=$val ;;
    ttl) TTL=$val ;;
    delay) DELAY=$val ;;
    jitter) JITTER=$val ;;
    target) TARGET_URL=$val ;;
    *) echo "Unknown argument: $key"; exit 1 ;;
  esac
done

SCRIPT="k6-load-tests/${TYPE}.js"
if [ ! -f "$SCRIPT" ]; then
    echo "Error: Test script '$SCRIPT' not found."
    exit 1
fi

echo "Running $TYPE test with strategy=$STRAT, n=$N, ttl=$TTL, delay=$DELAY, jitter=$JITTER"

# Function to get query count from a specific instance, defaults to 0 if instance is down
get_query_count() {
    local port=$1
    local res=$(curl -s "http://localhost:${port}/debug/db-queries" || echo '{"dbQueryCount":0}')
    echo $res | grep -o '"dbQueryCount":[0-9]*' | cut -d':' -f2 || echo 0
}

# Snapshot before
before_1=$(get_query_count 8081)
before_2=$(get_query_count 8082)
before_3=$(get_query_count 8083)

echo "Before DB Queries - API 1: $before_1, API 2: $before_2, API 3: $before_3"

# Run k6
export TARGET_URL="$TARGET_URL"
export STRATEGY="$STRAT"
export DELAY="$DELAY"
export TTL="$TTL"
export JITTER="$JITTER"
export VUS="$N"

k6 run "$SCRIPT"

# Snapshot after
after_1=$(get_query_count 8081)
after_2=$(get_query_count 8082)
after_3=$(get_query_count 8083)

echo "After DB Queries  - API 1: $after_1, API 2: $after_2, API 3: $after_3"

delta_1=$((after_1 - before_1))
delta_2=$((after_2 - before_2))
delta_3=$((after_3 - before_3))
total_delta=$((delta_1 + delta_2 + delta_3))

echo "======================================================"
echo "Repository Query Evidence (Total Delta): $total_delta"
echo " (API 1: +$delta_1 | API 2: +$delta_2 | API 3: +$delta_3)"
echo "======================================================"

if [[ "$STRAT" == "distributed-lock" && "$TYPE" == "stampede" && $total_delta -gt 1 ]]; then
    echo "WARNING: Distributed lock test expected exactly 1 query, but saw $total_delta."
fi

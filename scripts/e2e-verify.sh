#!/usr/bin/env bash
# 端到端复核：通过对外 HTTP 接口完成 录入 -> 查询 -> 修正 -> 再查询 -> 归因 全链路。
# 使用独立的临时数据目录，不影响 ./data 下的既有数据。
# 用法： scripts/e2e-verify.sh   （需要先执行 mvn package -DskipTests）
set -euo pipefail

PORT="${E2E_PORT:-18080}"
BASE="http://localhost:${PORT}/api"
JAR="target/portfolio-attribution-java-1.0-SNAPSHOT.jar"
DATA_DIR="$(mktemp -d)"
LOG_FILE="${DATA_DIR}/app.log"

if [ ! -f "${JAR}" ]; then
  echo "jar not found, building..." >&2
  mvn -q package -DskipTests
fi

java -jar "${JAR}" \
  --server.port="${PORT}" \
  --spring.datasource.url="jdbc:h2:file:${DATA_DIR}/e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE" \
  > "${LOG_FILE}" 2>&1 &
APP_PID=$!

cleanup() {
  kill "${APP_PID}" 2>/dev/null || true
  wait "${APP_PID}" 2>/dev/null || true
  rm -rf "${DATA_DIR}"
}
trap cleanup EXIT

echo "waiting for service on port ${PORT} ..."
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w "%{http_code}" \
    "${BASE}/returns?portfolioId=__ping__&start=2024-01-01&end=2024-01-02" || true)
  if [ "${code}" != "000" ]; then
    break
  fi
  sleep 1
  if [ "${i}" = "60" ]; then
    echo "service did not start; log follows" >&2
    cat "${LOG_FILE}" >&2
    exit 1
  fi
done
echo "service is up."

fail=0
check() { # name expected actual
  if [ "$2" = "$3" ]; then
    echo "PASS: $1 ($3)"
  else
    echo "FAIL: $1 expected=$2 actual=$3"
    fail=1
  fi
}

post() { # path json
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${BASE}$1" \
    -H 'Content-Type: application/json' -d "$2")
  if [ "${code}" != "204" ]; then
    echo "FAIL: POST $1 -> HTTP ${code}"
    exit 1
  fi
}

# ---------- 1. 录入 ----------
post /valuations '{"portfolioId":"E2E-P","date":"2024-01-01","totalValue":100.0,"sectors":[{"sectorId":"A","marketValue":60.0},{"sectorId":"B","marketValue":40.0}]}'
post /valuations '{"portfolioId":"E2E-P","date":"2024-01-08","totalValue":110.0,"sectors":[{"sectorId":"A","marketValue":66.0},{"sectorId":"B","marketValue":44.0}]}'
post /valuations '{"portfolioId":"E2E-P","date":"2024-01-15","totalValue":126.5,"sectors":[{"sectorId":"A","marketValue":75.9},{"sectorId":"B","marketValue":50.6}]}'
post /valuations '{"portfolioId":"E2E-B","date":"2024-01-01","totalValue":100.0,"sectors":[{"sectorId":"A","marketValue":50.0},{"sectorId":"B","marketValue":50.0}]}'
post /valuations '{"portfolioId":"E2E-B","date":"2024-01-08","totalValue":104.0,"sectors":[{"sectorId":"A","marketValue":52.0},{"sectorId":"B","marketValue":52.0}]}'
post /valuations '{"portfolioId":"E2E-B","date":"2024-01-15","totalValue":110.0,"sectors":[{"sectorId":"A","marketValue":55.0},{"sectorId":"B","marketValue":55.0}]}'
post /cash-flows '{"portfolioId":"E2E-P","externalFlowId":"f1","date":"2024-01-08","amount":5.0,"sectorId":"A"}'
echo "recording done."

# ---------- 2. 收益查询 ----------
resp=$(curl -s "${BASE}/returns?portfolioId=E2E-P&start=2024-01-01&end=2024-01-15")
check "TWR with flow on snapshot date" "0.2075" "$(echo "${resp}" | jq -r '.timeWeightedReturn')"
check "MWR with flow on snapshot date" "0.209756" "$(echo "${resp}" | jq -r '.moneyWeightedReturn')"

# ---------- 3. 归因查询（恒等式） ----------
attr=$(curl -s "${BASE}/attribution?portfolioId=E2E-P&benchmarkId=E2E-B&start=2024-01-01&end=2024-01-15")
check "attribution excessReturn" "0.1075" "$(echo "${attr}" | jq -r '.excessReturn')"
check "attribution identity |A+S+I-excess| <= 2e-6" "true" \
  "$(echo "${attr}" | jq '((.allocationEffect + .selectionEffect + .interactionEffect - .excessReturn) | fabs) <= 0.000002')"

# ---------- 4. 修正现金流，复核受影响区间 ----------
post /cash-flows '{"portfolioId":"E2E-P","externalFlowId":"f1","date":"2024-01-08","amount":10.0,"sectorId":"A"}'
resp=$(curl -s "${BASE}/returns?portfolioId=E2E-P&start=2024-01-01&end=2024-01-15")
check "TWR after correction" "0.15" "$(echo "${resp}" | jq -r '.timeWeightedReturn')"
resp=$(curl -s "${BASE}/returns?portfolioId=E2E-P&start=2024-01-08&end=2024-01-15")
check "TWR of later sub-interval after correction" "0.15" "$(echo "${resp}" | jq -r '.timeWeightedReturn')"
resp=$(curl -s "${BASE}/returns?portfolioId=E2E-P&start=2024-01-01&end=2024-01-08")
check "TWR of first sub-interval after correction" "0.0" "$(echo "${resp}" | jq -r '.timeWeightedReturn')"

# ---------- 5. 无估值快照日的现金流：两种口径都计入 ----------
post /cash-flows '{"portfolioId":"E2E-P","externalFlowId":"f2","date":"2024-01-10","amount":6.0,"sectorId":"B"}'
resp=$(curl -s "${BASE}/returns?portfolioId=E2E-P&start=2024-01-01&end=2024-01-15")
check "TWR counts flow on non-snapshot date" "0.095455" "$(echo "${resp}" | jq -r '.timeWeightedReturn')"
check "MWR counts flow on non-snapshot date" "0.098" "$(echo "${resp}" | jq -r '.moneyWeightedReturn')"
attr=$(curl -s "${BASE}/attribution?portfolioId=E2E-P&benchmarkId=E2E-B&start=2024-01-01&end=2024-01-15")
check "attribution identity with non-snapshot flow" "true" \
  "$(echo "${attr}" | jq '((.allocationEffect + .selectionEffect + .interactionEffect - .excessReturn) | fabs) <= 0.000002')"

# ---------- 6. 稳定拒绝：缺少端点估值 ----------
code=$(curl -s -o /dev/null -w "%{http_code}" "${BASE}/returns?portfolioId=E2E-P&start=2024-01-01&end=2024-01-31")
check "missing endpoint valuation -> HTTP 400" "400" "${code}"
body=$(curl -s "${BASE}/returns?portfolioId=E2E-P&start=2024-01-01&end=2024-01-31")
check "missing endpoint valuation -> code" "MISSING_ENDPOINT_VALUATION" "$(echo "${body}" | jq -r '.code')"

if [ "${fail}" != "0" ]; then
  echo "E2E VERIFICATION FAILED"
  exit 1
fi
echo "E2E VERIFICATION PASSED"

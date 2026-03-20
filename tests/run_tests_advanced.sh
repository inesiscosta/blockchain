#!/usr/bin/env bash
set -uo pipefail
shopt -s extglob nullglob
# Ensure background job notifications do not pollute output on cleanup.
set +m

#
# Runs integration tests for the BlockchainIST client.
# Supports both simple sequential tests and concurrent multi-node scenarios.
#

################################################
### ARGUMENTS
################################################
DEBUG_FLAG=0
NO_SEQUENCER=0
NO_SIMPLE=0
SKIP_BUILD=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    -d|--debug)
      DEBUG_FLAG=1
      ;;
    --no-sequencer|-NoSequencer)
      NO_SEQUENCER=1
      ;;
    --no-simple|-NoSimple)
      NO_SIMPLE=1
      ;;
    --skip-build|-SkipBuild)
      SKIP_BUILD=1
      ;;
    -h|--help)
      cat <<'EOF'
Usage: ./run_tests_advanced.sh [options]

Options:
  -d, --debug          Pass -Ddebug=true to node/sequencer launches
      --no-sequencer   Do not start sequencer
      --no-simple      Skip simple tests under tests/inputs
      --skip-build     Skip "mvn install -DskipTests"
  -h, --help           Show this help message
EOF
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
  shift
done

################################################
### CONFIGURATION
################################################
SEQUENCER_HOST="localhost"
SEQUENCER_PORT=3001

# MULTIPLE NODES
NODE_HOSTS=("localhost" "localhost" "localhost")
NODE_PORTS=(2001 2002 2003)
NODE_ORGS=("Org1" "Org2" "Org3")

CLIENT_MODULE="client"

### PATHS
TESTS_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MVN_ROOT_DIR="$(cd -- "${TESTS_DIR}/.." && pwd)"
MVN_ROOT_POM="${MVN_ROOT_DIR}/pom.xml"

# Folders for simple tests
INPUTS="${TESTS_DIR}/inputs"
EXPECTED_OUTPUTS="${TESTS_DIR}/outputs"

# Folders for concurrent scenario tests
SCENARIOS_INPUTS="${TESTS_DIR}/scenarios-inputs"
SCENARIOS_EXPECTED="${TESTS_DIR}/scenarios-outputs"

TEST_OUTPUTS="${TESTS_DIR}/test-outputs"
RUN_SUFFIX="$(date +%Y%m%d-%H%M%S-%3N 2>/dev/null || date +%Y%m%d-%H%M%S)"
MVN_EXE="$(command -v mvn)"
DEBUG_ARGS=""
if [[ ${DEBUG_FLAG} -eq 1 ]]; then
  DEBUG_ARGS="-Ddebug=true"
fi

################################################
### UI helpers
################################################
UI_WIDTH=80

C_CYAN='\033[0;36m'
C_DCYAN='\033[0;96m'
C_DGRAY='\033[1;30m'
C_GREEN='\033[0;32m'
C_RED='\033[0;31m'
C_YELLOW='\033[0;33m'
C_RESET='\033[0m'

write_banner() {
  local title="  BlockchainIST  -  Integration & Scenario Tests  "
  local border
  border="$(printf '%*s' "${UI_WIDTH}" '' | tr ' ' '=')"
  echo
  printf '%b%s%b\n' "${C_DCYAN}" "${border}" "${C_RESET}"
  printf '%b%s%s%b\n' "${C_CYAN}" "$(printf '%*s' $(( (UI_WIDTH - ${#title}) / 2 )) '')" "${title}" "${C_RESET}"
  printf '%b%s%b\n\n' "${C_DCYAN}" "${border}" "${C_RESET}"
}

write_section() {
  local text="$1"
  local dash
  dash="$(printf '%*s' $((UI_WIDTH - 2)) '' | tr ' ' '-')"
  printf '\n%b  %s%b\n' "${C_DCYAN}" "${text}" "${C_RESET}"
  printf '%b  %s%b\n' "${C_DGRAY}" "${dash}" "${C_RESET}"
}

wait_for_port_spinner() {
  local svc_name="$1"
  local host_name="$2"
  local port="$3"
  local timeout_sec="${4:-60}"
  local start_ts now_ts elapsed

  start_ts="$(date +%s)"
  printf '%b  Waiting for %s (%s:%s)...%b' "${C_DGRAY}" "${svc_name}" "${host_name}" "${port}" "${C_RESET}"

  while true; do
    if (echo >"/dev/tcp/${host_name}/${port}") >/dev/null 2>&1; then
      now_ts="$(date +%s)"
      elapsed=$((now_ts - start_ts))
      printf ' %bready (%ss)%b\n' "${C_GREEN}" "${elapsed}" "${C_RESET}"
      return 0
    fi

    now_ts="$(date +%s)"
    if (( now_ts - start_ts >= timeout_sec )); then
      printf ' %bTIMEOUT%b\n' "${C_RED}" "${C_RESET}"
      return 1
    fi

    sleep 0.3
  done
}

assert_service_started() {
  local name="$1"
  local host_name="$2"
  local port="$3"
  local log_file="$4"

  if ! wait_for_port_spinner "${name}" "${host_name}" "${port}" 60; then
    printf '\n%b  !! %s log: %s%b\n' "${C_YELLOW}" "${name}" "${log_file}" "${C_RESET}"
    echo "${name} did not start within 60 s" >&2
    exit 1
  fi
}

kill_process_tree() {
  local pid="$1"
  [[ -z "${pid}" ]] && return 0
  kill -0 "${pid}" >/dev/null 2>&1 || return 0

  local child
  for child in $(pgrep -P "${pid}" 2>/dev/null || true); do
    kill_process_tree "${child}"
  done

  # Try graceful shutdown first, then force kill if needed.
  kill "${pid}" >/dev/null 2>&1 || true
  for _ in 1 2 3 4 5; do
    if ! kill -0 "${pid}" >/dev/null 2>&1; then
      wait "${pid}" 2>/dev/null || true
      return 0
    fi
    sleep 0.1
  done

  kill -9 "${pid}" >/dev/null 2>&1 || true
  wait "${pid}" 2>/dev/null || true
}

start_mvn_background() {
  local module="$1"
  local exec_args="$2"
  local extra_args="$3"

  local clean_module log_file dbg_file module_selector
  clean_module="$(echo "${module}" | sed 's/[^a-zA-Z0-9]/-/g')"
  log_file="${TEST_OUTPUTS}/${clean_module}-${RUN_SUFFIX}.log"
  dbg_file="${TEST_OUTPUTS}/${clean_module}-${RUN_SUFFIX}.dbg"
  module_selector="${module%%-*}"

  local arg_list=(--quiet -f "${MVN_ROOT_POM}" -pl "${module_selector}" exec:java)
  if [[ -n "${exec_args}" ]]; then
    arg_list+=("-Dexec.args=${exec_args}")
  fi
  if [[ -n "${extra_args//[[:space:]]/}" ]]; then
    # shellcheck disable=SC2206
    local split_extra=( ${extra_args} )
    arg_list+=("${split_extra[@]}")
  fi

  "${MVN_EXE}" "${arg_list[@]}" >"${log_file}" 2>"${dbg_file}" &
  STARTED_BG_PID=$!
  STARTED_BG_LOG_FILE="${log_file}"
  STARTED_BG_DBG_FILE="${dbg_file}"
}

start_client_async() {
  local input_file="$1"
  local client_exec_args="$2"
  local out_file="$3"
  local err_file="$4"
  local extra_args="${5:-}"

  local arg_list=(--quiet -f "${MVN_ROOT_POM}" -pl "${CLIENT_MODULE}" exec:java "-Dexec.args=${client_exec_args}")
  if [[ -n "${extra_args//[[:space:]]/}" ]]; then
    local split_extra=( ${extra_args} )
    arg_list+=("${split_extra[@]}")
  fi

  "${MVN_EXE}" "${arg_list[@]}" \
    <"${input_file}" >"${out_file}" 2>"${err_file}" &
  STARTED_CLIENT_PID=$!
}

stop_stale_infrastructure_processes() {
  local count=0
  local line pid cmd

  while IFS= read -r line; do
    [[ -z "${line}" ]] && continue
    pid="${line%% *}"
    cmd="${line#* }"

    if [[ "${pid}" == "$$" ]]; then
      continue
    fi

    if [[ "${cmd}" == *"${MVN_ROOT_POM}"* && "${cmd}" == *"exec:java"* ]]; then
      kill -9 "${pid}" >/dev/null 2>&1 || true
      count=$((count + 1))
    fi
  done < <(ps -eo pid=,args=)

  echo "${count}"
}

initialize_test_outputs() {
  if [[ ! -d "${TEST_OUTPUTS}" ]]; then
    mkdir -p "${TEST_OUTPUTS}"
    return
  fi

  local max_attempts=3 attempt killed
  for ((attempt = 1; attempt <= max_attempts; attempt++)); do
    if rm -rf "${TEST_OUTPUTS}" >/dev/null 2>&1; then
      break
    fi

    if (( attempt == 1 )); then
      killed="$(stop_stale_infrastructure_processes)"
      if (( killed > 0 )); then
        printf '%b  Clean-up: stopped %s stale process(es) from previous run.%b\n' "${C_YELLOW}" "${killed}" "${C_RESET}"
      fi
    fi

    if (( attempt == max_attempts )); then
      printf '%b  Clean-up: some files are locked; continuing without full cleanup.%b\n' "${C_YELLOW}" "${C_RESET}"
      break
    fi

    sleep 0.6
  done

  mkdir -p "${TEST_OUTPUTS}"
}

normalize_output() {
  local text="$1"
  text="${text//$'\r'/}"
  text="${text##+([[:space:]])}"
  text="${text%%+([[:space:]])}"
  printf '%s' "${text}"
}

normalize_file_to() {
  local src="$1"
  local dst="$2"
  local raw
  raw="$(cat "${src}")"
  normalize_output "${raw}" >"${dst}"
}

show_diff() {
  local got_file="$1"
  local exp_file="$2"
  local norm_got norm_exp

  norm_got="$(mktemp)"
  norm_exp="$(mktemp)"
  normalize_file_to "${got_file}" "${norm_got}"
  normalize_file_to "${exp_file}" "${norm_exp}"

  if ! diff -u "${norm_got}" "${norm_exp}"; then
    true
  fi

  rm -f "${norm_got}" "${norm_exp}"
}

# Parses alternatives.txt into files:
#   <dest_dir>/alt_0/<client_name>
#   <dest_dir>/alt_1/<client_name>
# Returns the number of alternatives via stdout.
get_scenario_alternatives() {
  local scenario_expected_dir="$1"
  local dest_dir="$2"

  local manifest_path=""
  if [[ -f "${scenario_expected_dir}/alternatives.txt" ]]; then
    manifest_path="${scenario_expected_dir}/alternatives.txt"
  elif [[ -f "${scenario_expected_dir}/expected-alternatives.txt" ]]; then
    manifest_path="${scenario_expected_dir}/expected-alternatives.txt"
  else
    echo "0"
    return
  fi

  local current_alt=0
  local current_client=""
  local buffer=""
  local has_content=0

  mkdir -p "${dest_dir}"

  flush_client_block() {
    if [[ -n "${current_client}" ]]; then
      local alt_dir="${dest_dir}/alt_${current_alt}"
      mkdir -p "${alt_dir}"
      local target="${alt_dir}/${current_client}"
      if [[ -e "${target}" ]]; then
        echo "Duplicate client '${current_client}' found in scenario alternatives file: ${manifest_path}" >&2
        exit 1
      fi
      printf '%s' "$(normalize_output "${buffer}")" >"${target}"
      has_content=1
      current_client=""
      buffer=""
    fi
  }

  local line
  while IFS= read -r line || [[ -n "${line}" ]]; do
    if [[ "${line}" =~ ^[[:space:]]*# ]]; then
      continue
    fi

    if [[ "${line}" =~ ^[[:space:]]*or[[:space:]]*$ ]]; then
      flush_client_block
      if (( has_content == 1 )); then
        current_alt=$((current_alt + 1))
        has_content=0
      fi
      continue
    fi

    if [[ "${line}" =~ ^[[:space:]]*---[[:space:]]*CLIENT:[[:space:]]*(.+)[[:space:]]*---[[:space:]]*$ ]]; then
      flush_client_block
      current_client="${BASH_REMATCH[1]}"
      current_client="${current_client##+([[:space:]])}"
      current_client="${current_client%%+([[:space:]])}"
      continue
    fi

    if [[ -n "${current_client}" ]]; then
      if [[ -z "${buffer}" ]]; then
        buffer="${line}"
      else
        buffer+=$'\n'
        buffer+="${line}"
      fi
    elif [[ -n "${line//[[:space:]]/}" ]]; then
      echo "Invalid content outside client block in scenario alternatives file: ${manifest_path}" >&2
      exit 1
    fi
  done <"${manifest_path}"

  flush_client_block
  if (( has_content == 1 )); then
    current_alt=$((current_alt + 1))
  fi

  echo "${current_alt}"
}

find_matching_alternative_index() {
  local alt_count="$1"
  local alts_dir="$2"
  local actual_dir="$3"

  local i
  for ((i = 0; i < alt_count; i++)); do
    local candidate_dir="${alts_dir}/alt_${i}"
    [[ -d "${candidate_dir}" ]] || continue

    local candidate_count=0
    local actual_count=0
    candidate_count=$(find "${candidate_dir}" -maxdepth 1 -type f | wc -l | tr -d ' ')
    actual_count=$(find "${actual_dir}" -maxdepth 1 -type f | wc -l | tr -d ' ')
    [[ "${candidate_count}" == "${actual_count}" ]] || continue

    local all_match=1
    local actual_file client_name
    for actual_file in "${actual_dir}"/*; do
      [[ -f "${actual_file}" ]] || continue
      client_name="$(basename "${actual_file}")"
      if [[ ! -f "${candidate_dir}/${client_name}" ]]; then
        all_match=0
        break
      fi
      if ! cmp -s "${actual_file}" "${candidate_dir}/${client_name}"; then
        all_match=0
        break
      fi
    done

    if (( all_match == 1 )); then
      echo "${i}"
      return
    fi
  done

  echo "-1"
}

################################################
### Results tracking
################################################
RESULT_LABELS=()
RESULT_STATUS=()
RESULT_TIME=()
RESULT_REASON=()

add_result() {
  RESULT_LABELS+=("$1")
  RESULT_STATUS+=("$2")
  RESULT_TIME+=("$3")
  RESULT_REASON+=("$4")
}

################################################
### Main
################################################
ACTIVE_PROCS=()
START_TIME="$(date +%s)"

cleanup() {
  printf '\n%b  Stopping infrastructure...%b' "${C_DGRAY}" "${C_RESET}"
  local pid
  for pid in "${ACTIVE_PROCS[@]:-}"; do
    kill_process_tree "${pid}"
  done
  printf ' %bdone.%b\n\n' "${C_DGRAY}" "${C_RESET}"
}
trap cleanup EXIT

write_banner

initialize_test_outputs

if [[ ${SKIP_BUILD} -eq 1 ]]; then
  write_section "Building project"
  printf '%b  Skipping build due to --skip-build flag.%b\n' "${C_DGRAY}" "${C_RESET}"
else
  write_section "Building project"
  printf '%b  Running mvn install...%b\n' "${C_DGRAY}" "${C_RESET}"
  if ! "${MVN_EXE}" --quiet -f "${MVN_ROOT_POM}" install -DskipTests; then
    echo "mvn install failed" >&2
    exit 1
  fi
  printf '%b  Build complete%b\n' "${C_GREEN}" "${C_RESET}"
fi

write_section "Infrastructure"
if [[ ${NO_SEQUENCER} -eq 0 ]]; then
  start_mvn_background "sequencer" "${SEQUENCER_PORT}" "${DEBUG_ARGS}"
  seq_pid="${STARTED_BG_PID}"
  seq_log="${STARTED_BG_LOG_FILE}"
  ACTIVE_PROCS+=("${seq_pid}")
  assert_service_started "sequencer" "${SEQUENCER_HOST}" "${SEQUENCER_PORT}" "${seq_log}"
fi

for i in "${!NODE_PORTS[@]}"; do
  node_host="${NODE_HOSTS[i]}"
  node_port="${NODE_PORTS[i]}"
  node_org="${NODE_ORGS[i]}"

  node_args="${node_port} ${node_org} ${SEQUENCER_HOST}:${SEQUENCER_PORT}"
  start_mvn_background "node-${node_port}" "${node_args}" "${DEBUG_ARGS}"
  node_pid="${STARTED_BG_PID}"
  node_log="${STARTED_BG_LOG_FILE}"
  ACTIVE_PROCS+=("${node_pid}")
  assert_service_started "node-${node_port}" "${node_host}" "${node_port}" "${node_log}"
done

CLIENT_ARGS_STR=""
for i in "${!NODE_PORTS[@]}"; do
  part="${NODE_HOSTS[i]}:${NODE_PORTS[i]}:${NODE_ORGS[i]}"
  if [[ -z "${CLIENT_ARGS_STR}" ]]; then
    CLIENT_ARGS_STR="${part}"
  else
    CLIENT_ARGS_STR+=" ${part}"
  fi
done

##########################################
# 1. RUN SIMPLE TESTS
##########################################
if [[ ${NO_SIMPLE} -eq 1 ]]; then
  write_section "Simple tests skipped"
  printf '%b  Skipping simple tests due to --no-simple flag.%b\n' "${C_DGRAY}" "${C_RESET}"
elif [[ -d "${INPUTS}" ]]; then
  simple_files=("${INPUTS}"/*.txt)
  if (( ${#simple_files[@]} > 0 )); then
    write_section "Running ${#simple_files[@]} Simple Test(s)"

    for sf in "${simple_files[@]}"; do
      t0="$(date +%s)"
      sf_name="$(basename "${sf}")"
      sf_base="${sf_name%.txt}"
      out_file="${TEST_OUTPUTS}/out-${sf_name}"
      err_file="${TEST_OUTPUTS}/err-${sf_name}"

      expected_name="${sf_name/input/out}"
      exp_file="${EXPECTED_OUTPUTS}/${expected_name}"

      start_client_async "${sf}" "${CLIENT_ARGS_STR}" "${out_file}" "${err_file}" "${DEBUG_ARGS}"
      client_pid="${STARTED_CLIENT_PID}"
      if wait "${client_pid}"; then
        exit_code=0
      else
        exit_code=$?
      fi

      if [[ ! -s "${err_file}" ]]; then
        rm -f "${err_file}"
      fi

      elapsed=$(( $(date +%s) - t0 ))
      time_str="${elapsed}s"
      prefix="  [Simple] ${sf_base}"

      if (( exit_code != 0 )); then
        printf '%-35s  %bFAILED%b\n' "${prefix}" "${C_RED}" "${C_RESET}"
        add_result "Simple/${sf_base}" "FAILED" "${time_str}" "exit ${exit_code}"
        continue
      fi

      if [[ -f "${exp_file}" ]]; then
        norm_out="$(mktemp)"
        norm_exp="$(mktemp)"
        normalize_file_to "${out_file}" "${norm_out}"
        normalize_file_to "${exp_file}" "${norm_exp}"

        if cmp -s "${norm_out}" "${norm_exp}"; then
          printf '%-35s  %bPASSED%b\n' "${prefix}" "${C_GREEN}" "${C_RESET}"
          add_result "Simple/${sf_base}" "PASSED" "${time_str}" ""
        else
          printf '%-35s  %bFAILED%b\n' "${prefix}" "${C_RED}" "${C_RESET}"
          show_diff "${out_file}" "${exp_file}"
          add_result "Simple/${sf_base}" "FAILED" "${time_str}" "output mismatch"
        fi

        rm -f "${norm_out}" "${norm_exp}"
      else
        printf '%-35s  %bNO EXPECTED FILE%b\n' "${prefix}" "${C_YELLOW}" "${C_RESET}"
        printf '%b      (Checked: %s)%b\n' "${C_DGRAY}" "${exp_file}" "${C_RESET}"
      fi
    done
  fi
fi

##########################################
# 2. RUN SCENARIO TESTS
##########################################
if [[ -d "${SCENARIOS_INPUTS}" ]]; then
  scenarios=("${SCENARIOS_INPUTS}"/*)
  if (( ${#scenarios[@]} > 0 )); then
    write_section "Running ${#scenarios[@]} Scenario(s)"

    for scenario in "${scenarios[@]}"; do
      [[ -d "${scenario}" ]] || continue

      scenario_name="$(basename "${scenario}")"
      printf '%b  Scenario: %s%b\n' "${C_CYAN}" "${scenario_name}" "${C_RESET}"

      scenario_expected_dir="${SCENARIOS_EXPECTED}/${scenario_name}"
      scenario_alts_dir="${TEST_OUTPUTS}/.alts-${scenario_name}-${RUN_SUFFIX}"
      scenario_alt_count="$(get_scenario_alternatives "${scenario_expected_dir}" "${scenario_alts_dir}")"
      if (( scenario_alt_count > 0 )); then
        printf '%b    -> Using %s expected alternative(s)%b\n' "${C_DGRAY}" "${scenario_alt_count}" "${C_RESET}"
      fi

      client_files=("${scenario}"/*.txt)
      running_pids=()
      running_names=()
      running_out=()
      running_err=()
      running_exp=()
      completed_ok_names=()
      completed_ok_labels=()
      completed_ok_prefix=()
      completed_ok_times=()
      completed_ok_out=()
      completed_ok_norm=()

      t0="$(date +%s)"
      for cf in "${client_files[@]}"; do
        cf_name="$(basename "${cf}")"
        printf '%b    -> Starting client: %s%b\n' "${C_DGRAY}" "${cf_name}" "${C_RESET}"

        out_file="${TEST_OUTPUTS}/${scenario_name}-${cf_name}"
        err_file="${TEST_OUTPUTS}/${scenario_name}-${cf_name}.err"
        exp_file="${scenario_expected_dir}/${cf_name}"

        start_client_async "${cf}" "${CLIENT_ARGS_STR}" "${out_file}" "${err_file}" "${DEBUG_ARGS}"
        pid="${STARTED_CLIENT_PID}"

        running_pids+=("${pid}")
        running_names+=("${cf_name}")
        running_out+=("${out_file}")
        running_err+=("${err_file}")
        running_exp+=("${exp_file}")
      done

      for i in "${!running_pids[@]}"; do
        pid="${running_pids[i]}"
        name="${running_names[i]}"
        out_file="${running_out[i]}"
        err_file="${running_err[i]}"
        exp_file="${running_exp[i]}"

        if wait "${pid}"; then
          exit_code=0
        else
          exit_code=$?
        fi

        if [[ ! -s "${err_file}" ]]; then
          rm -f "${err_file}"
        fi

        elapsed=$(( $(date +%s) - t0 ))
        time_str="${elapsed}s"
        test_label="Scenario/${scenario_name}/${name}"
        prefix="    [Client] ${name}"

        if (( exit_code != 0 )); then
          printf '%-35s  %bFAILED%b\n' "${prefix}" "${C_RED}" "${C_RESET}"
          add_result "${test_label}" "FAILED" "${time_str}" "exit ${exit_code}"
          continue
        fi

        norm_file="${TEST_OUTPUTS}/.norm-${scenario_name}-${name}-${RUN_SUFFIX}"
        normalize_file_to "${out_file}" "${norm_file}"

        completed_ok_names+=("${name}")
        completed_ok_labels+=("${test_label}")
        completed_ok_prefix+=("${prefix}")
        completed_ok_times+=("${time_str}")
        completed_ok_out+=("${out_file}")
        completed_ok_norm+=("${norm_file}")
      done

      if (( scenario_alt_count > 0 )); then
        if (( ${#completed_ok_names[@]} != ${#running_names[@]} )); then
          for i in "${!completed_ok_names[@]}"; do
            printf '%-35s  %bFAILED%b\n' "${completed_ok_prefix[i]}" "${C_RED}" "${C_RESET}"
            add_result "${completed_ok_labels[i]}" "FAILED" "${completed_ok_times[i]}" "scenario incomplete (client failed)"
          done
          continue
        fi

        actual_by_client_dir="${TEST_OUTPUTS}/.actual-${scenario_name}-${RUN_SUFFIX}"
        mkdir -p "${actual_by_client_dir}"

        for i in "${!completed_ok_names[@]}"; do
          cp "${completed_ok_norm[i]}" "${actual_by_client_dir}/${completed_ok_names[i]}"
        done

        matched_index="$(find_matching_alternative_index "${scenario_alt_count}" "${scenario_alts_dir}" "${actual_by_client_dir}")"
        if (( matched_index >= 0 )); then
          for i in "${!completed_ok_names[@]}"; do
            printf '%-35s  %bPASSED%b\n' "${completed_ok_prefix[i]}" "${C_GREEN}" "${C_RESET}"
            add_result "${completed_ok_labels[i]}" "PASSED" "${completed_ok_times[i]}" "alternative #$((matched_index + 1))"
          done
        else
          for i in "${!completed_ok_names[@]}"; do
            printf '%-35s  %bFAILED%b\n' "${completed_ok_prefix[i]}" "${C_RED}" "${C_RESET}"
            add_result "${completed_ok_labels[i]}" "FAILED" "${completed_ok_times[i]}" "no alternative matched"
          done
        fi

        continue
      fi

      for i in "${!completed_ok_names[@]}"; do
        name="${completed_ok_names[i]}"
        label="${completed_ok_labels[i]}"
        prefix="${completed_ok_prefix[i]}"
        time_str="${completed_ok_times[i]}"
        out_file="${completed_ok_out[i]}"
        norm_file="${completed_ok_norm[i]}"
        exp_file="${scenario_expected_dir}/${name}"

        if [[ -f "${exp_file}" ]]; then
          exp_norm="$(mktemp)"
          normalize_file_to "${exp_file}" "${exp_norm}"

          if cmp -s "${norm_file}" "${exp_norm}"; then
            printf '%-35s  %bPASSED%b\n' "${prefix}" "${C_GREEN}" "${C_RESET}"
            add_result "${label}" "PASSED" "${time_str}" ""
          else
            printf '%-35s  %bFAILED%b\n' "${prefix}" "${C_RED}" "${C_RESET}"
            show_diff "${out_file}" "${exp_file}"
            add_result "${label}" "FAILED" "${time_str}" "output mismatch"
          fi

          rm -f "${exp_norm}"
        else
          printf '%-35s  %bNO EXPECTED FILE%b\n' "${prefix}" "${C_YELLOW}" "${C_RESET}"
        fi
      done
    done
  fi
fi

##########################################
# 3. RUN B2 INFRASTRUCTURE TESTS
##########################################
# Stop shared infrastructure, then run B2-specific tests that need
# to control node lifecycle (late-join, failover, dedup).
printf '\n%b  Stopping shared infrastructure for B2 tests...%b\n' "${C_DGRAY}" "${C_RESET}"
for pid in "${ACTIVE_PROCS[@]:-}"; do
  kill_process_tree "${pid}"
done
ACTIVE_PROCS=()
sleep 1

B2_SEQUENCER_PORT=3001
B2_NODE_PORT_1=2011
B2_NODE_PORT_2=2012
B2_NODE_PORT_3=2013
B2_FO_NODE_PORT_1=2021
B2_FO_NODE_PORT_2=2022

# Synchronous client runner — returns path to stdout file.
run_client_sync() {
  local input="$1" nodes="$2"
  local out="${TEST_OUTPUTS}/b2-client-out-${RUN_SUFFIX}-$$.txt"
  local err="${TEST_OUTPUTS}/b2-client-err-${RUN_SUFFIX}-$$.txt"
  local arg_list=(--quiet -f "${MVN_ROOT_POM}" -pl client exec:java "-Dexec.args=${nodes}")
  if [[ -n "${DEBUG_ARGS//[[:space:]]/}" ]]; then
    arg_list+=("${DEBUG_ARGS}")
  fi
  "${MVN_EXE}" "${arg_list[@]}" <"${input}" >"${out}" 2>"${err}"
  echo "${out}"
}

extract_b2_balances() {
  grep "^balance:" "$1" || true
}

write_section "B2 Infrastructure Tests"

#-----------------------------------------
# B2-1: Late-join node sync
#-----------------------------------------
# Two nodes run transactions; a brand-new node 3 joins and must sync
# the full blockchain before serving queries. Results must match node 1.
t0_b2="$(date +%s)"

start_mvn_background "sequencer-b2" "${B2_SEQUENCER_PORT}" "${DEBUG_ARGS}"
b2_seq_pid="${STARTED_BG_PID}"
ACTIVE_PROCS+=("${b2_seq_pid}")
assert_service_started "sequencer-b2" "localhost" "${B2_SEQUENCER_PORT}" "${STARTED_BG_LOG_FILE}"

start_mvn_background "node-b2-${B2_NODE_PORT_1}" "${B2_NODE_PORT_1} Org1 localhost:${B2_SEQUENCER_PORT}" "${DEBUG_ARGS}"
b2_node1_pid="${STARTED_BG_PID}"
ACTIVE_PROCS+=("${b2_node1_pid}")
assert_service_started "node-b2-${B2_NODE_PORT_1}" "localhost" "${B2_NODE_PORT_1}" "${STARTED_BG_LOG_FILE}"

start_mvn_background "node-b2-${B2_NODE_PORT_2}" "${B2_NODE_PORT_2} Org2 localhost:${B2_SEQUENCER_PORT}" "${DEBUG_ARGS}"
ACTIVE_PROCS+=("${STARTED_BG_PID}")
assert_service_started "node-b2-${B2_NODE_PORT_2}" "localhost" "${B2_NODE_PORT_2}" "${STARTED_BG_LOG_FILE}"

B2_SEED_INPUT="$(mktemp)"
cat >"${B2_SEED_INPUT}" <<'EOF'
C Alice cA 0 0
C Bob cB 0 0
T BC bc cA 200 0 0
T BC bc cB 150 1 0
T Alice cA cB 50 0 0
X
EOF
run_client_sync "${B2_SEED_INPUT}" "localhost:${B2_NODE_PORT_1}:Org1 localhost:${B2_NODE_PORT_2}:Org2" >/dev/null
rm -f "${B2_SEED_INPUT}"

printf '%b    Starting late-join node on port %s...%b\n' "${C_DGRAY}" "${B2_NODE_PORT_3}" "${C_RESET}"
start_mvn_background "node-b2-${B2_NODE_PORT_3}" "${B2_NODE_PORT_3} Org3 localhost:${B2_SEQUENCER_PORT}" "${DEBUG_ARGS}"
ACTIVE_PROCS+=("${STARTED_BG_PID}")
assert_service_started "node-b2-${B2_NODE_PORT_3}" "localhost" "${B2_NODE_PORT_3}" "${STARTED_BG_LOG_FILE}"

B2_CHECK_INPUT="$(mktemp)"
cat >"${B2_CHECK_INPUT}" <<'EOF'
S cA 0 0
S cB 0 0
S bc 0 0
X
EOF
B2_OUT_N1="$(run_client_sync "${B2_CHECK_INPUT}" "localhost:${B2_NODE_PORT_1}:Org1")"
B2_OUT_N3="$(run_client_sync "${B2_CHECK_INPUT}" "localhost:${B2_NODE_PORT_3}:Org3")"
rm -f "${B2_CHECK_INPUT}"

B2_BAL_N1="$(extract_b2_balances "${B2_OUT_N1}")"
B2_BAL_N3="$(extract_b2_balances "${B2_OUT_N3}")"
elapsed_b21=$(( $(date +%s) - t0_b2 ))

if [[ "${B2_BAL_N1}" == "${B2_BAL_N3}" ]] && [[ -n "${B2_BAL_N1}" ]]; then
  add_result "B2/late-join-sync" "PASSED" "${elapsed_b21}s" "balances match node 1"
else
  add_result "B2/late-join-sync" "FAILED" "${elapsed_b21}s" "node1=[${B2_BAL_N1}] node3=[${B2_BAL_N3}]"
fi

B2_EXPECTED_SYNC="balance: 150
balance: 200
balance: 650"
if [[ "${B2_BAL_N3}" == "${B2_EXPECTED_SYNC}" ]]; then
  add_result "B2/late-join-balances" "PASSED" "${elapsed_b21}s" "cA=150 cB=200 bc=650"
else
  add_result "B2/late-join-balances" "FAILED" "${elapsed_b21}s" "expected [$(echo "${B2_EXPECTED_SYNC}" | tr '\n' '|')] got [$(echo "${B2_BAL_N3}" | tr '\n' '|')]"
fi

#-----------------------------------------
# B2-2: Client failover (dead node → retry)
#-----------------------------------------
# Node 1 is killed. Client configured with [node1, node2] must succeed
# by automatically retrying on node 2.
for pid in "${ACTIVE_PROCS[@]:-}"; do
  kill_process_tree "${pid}"
done
ACTIVE_PROCS=()
sleep 1

start_mvn_background "sequencer-b2" "${B2_SEQUENCER_PORT}" "${DEBUG_ARGS}"
b2_seq_pid="${STARTED_BG_PID}"
ACTIVE_PROCS+=("${b2_seq_pid}")
assert_service_started "sequencer-b2" "localhost" "${B2_SEQUENCER_PORT}" "${STARTED_BG_LOG_FILE}"

start_mvn_background "node-b2-${B2_FO_NODE_PORT_1}" "${B2_FO_NODE_PORT_1} Org1 localhost:${B2_SEQUENCER_PORT}" "${DEBUG_ARGS}"
b2_fo1_pid="${STARTED_BG_PID}"
ACTIVE_PROCS+=("${b2_fo1_pid}")
assert_service_started "node-b2-${B2_FO_NODE_PORT_1}" "localhost" "${B2_FO_NODE_PORT_1}" "${STARTED_BG_LOG_FILE}"

start_mvn_background "node-b2-${B2_FO_NODE_PORT_2}" "${B2_FO_NODE_PORT_2} Org2 localhost:${B2_SEQUENCER_PORT}" "${DEBUG_ARGS}"
b2_fo2_pid="${STARTED_BG_PID}"
ACTIVE_PROCS+=("${b2_fo2_pid}")
assert_service_started "node-b2-${B2_FO_NODE_PORT_2}" "localhost" "${B2_FO_NODE_PORT_2}" "${STARTED_BG_LOG_FILE}"

B2_FO_NODES="localhost:${B2_FO_NODE_PORT_1}:Org1 localhost:${B2_FO_NODE_PORT_2}:Org2"

B2_INIT_INPUT="$(mktemp)"
cat >"${B2_INIT_INPUT}" <<'EOF'
C Alice cA 0 0
T BC bc cA 500 0 0
X
EOF
run_client_sync "${B2_INIT_INPUT}" "${B2_FO_NODES}" >/dev/null
rm -f "${B2_INIT_INPUT}"

printf '%b    Killing fo-node1 (port %s)...%b\n' "${C_DGRAY}" "${B2_FO_NODE_PORT_1}" "${C_RESET}"
kill_process_tree "${b2_fo1_pid}"
# Remove from active procs so cleanup doesn't re-kill it
ACTIVE_PROCS=("${b2_seq_pid}" "${b2_fo2_pid}")
sleep 1

t0_fo="$(date +%s)"
B2_FAILOVER_INPUT="$(mktemp)"
cat >"${B2_FAILOVER_INPUT}" <<'EOF'
T Alice cA bc 100 0 0
S cA 0 0
S bc 0 0
X
EOF
B2_FO_OUT="$(run_client_sync "${B2_FAILOVER_INPUT}" "${B2_FO_NODES}")"
rm -f "${B2_FAILOVER_INPUT}"
elapsed_fo=$(( $(date +%s) - t0_fo ))

B2_FO_BALANCES="$(extract_b2_balances "${B2_FO_OUT}")"
B2_EXPECTED_FO="balance: 400
balance: 600"

if echo "${B2_FO_BALANCES}" | grep -q "balance: 400"; then
  add_result "B2/failover-transfer" "PASSED" "${elapsed_fo}s" "transfer succeeded despite dead node"
else
  add_result "B2/failover-transfer" "FAILED" "${elapsed_fo}s" "output: $(cat "${B2_FO_OUT}")"
fi

if [[ "${B2_FO_BALANCES}" == "${B2_EXPECTED_FO}" ]]; then
  add_result "B2/failover-balances" "PASSED" "${elapsed_fo}s" "cA=400 bc=600"
else
  add_result "B2/failover-balances" "FAILED" "${elapsed_fo}s" "expected [$(echo "${B2_EXPECTED_FO}" | tr '\n' '|')] got [$(echo "${B2_FO_BALANCES}" | tr '\n' '|')]"
fi

#-----------------------------------------
# B2-3: Deduplication / cross-node consistency
#-----------------------------------------
# Normal operations through the surviving node must produce correct final
# state — no phantom duplicate entries from the sequencer's request-ID dedup.
t0_dedup="$(date +%s)"
B2_DEDUP_INPUT="$(mktemp)"
cat >"${B2_DEDUP_INPUT}" <<'EOF'
C Carol cC 1 0
T BC bc cC 300 0 0
T Carol cC bc 300 1 0
E Carol cC 1 0
S bc 0 0
S bc 1 0
X
EOF
B2_DEDUP_OUT="$(run_client_sync "${B2_DEDUP_INPUT}" "${B2_FO_NODES}")"
rm -f "${B2_DEDUP_INPUT}"
elapsed_dedup=$(( $(date +%s) - t0_dedup ))

B2_DEDUP_BAL="$(extract_b2_balances "${B2_DEDUP_OUT}")"
B2_EXPECTED_DEDUP="balance: 600
balance: 600"

if [[ "${B2_DEDUP_BAL}" == "${B2_EXPECTED_DEDUP}" ]]; then
  add_result "B2/dedup-consistency" "PASSED" "${elapsed_dedup}s" "state consistent after create/transfer/delete cycle"
else
  add_result "B2/dedup-consistency" "FAILED" "${elapsed_dedup}s" "expected [$(echo "${B2_EXPECTED_DEDUP}" | tr '\n' '|')] got [$(echo "${B2_DEDUP_BAL}" | tr '\n' '|')]"
fi

######################################

##########################################
# Summary generation
##########################################
TOTAL_ELAPSED=$(( $(date +%s) - START_TIME ))
PASSED=0
FAILED=0

for status in "${RESULT_STATUS[@]:-}"; do
  if [[ "${status}" == "PASSED" ]]; then
    PASSED=$((PASSED + 1))
  elif [[ "${status}" == "FAILED" ]]; then
    FAILED=$((FAILED + 1))
  fi
done

write_section "SUMMARY"
for i in "${!RESULT_LABELS[@]}"; do
  color="${C_RED}"
  if [[ "${RESULT_STATUS[i]}" == "PASSED" ]]; then
    color="${C_GREEN}"
  fi

  printf '    %-45s ' "${RESULT_LABELS[i]}"
  printf '%b%-6s%b' "${color}" "${RESULT_STATUS[i]}" "${C_RESET}"
  printf '  %-8s %s\n' "${RESULT_TIME[i]}" "${RESULT_REASON[i]}"
done

printf '%b  %s%b\n' "${C_DGRAY}" "$(printf '%*s' $((UI_WIDTH - 2)) '' | tr ' ' '-')" "${C_RESET}"

if (( FAILED == 0 )); then
  printf '%b  Passed  : %s%b\n' "${C_GREEN}" "${PASSED}" "${C_RESET}"
  printf '%b  Failed  : %s%b\n' "${C_DGRAY}" "${FAILED}" "${C_RESET}"
else
  printf '  Passed  : %s\n' "${PASSED}"
  printf '%b  Failed  : %s%b\n' "${C_RED}" "${FAILED}" "${C_RESET}"
fi

printf '%b  Duration: %ss%b\n' "${C_DGRAY}" "${TOTAL_ELAPSED}" "${C_RESET}"
printf '%b  Output dir: %s%b\n' "${C_DGRAY}" "${TEST_OUTPUTS}" "${C_RESET}"
#!/usr/bin/env bash
# Record an anonymized INUBIT fixture from a NON-PRODUCTION server (research R-18, tasks.md T005).
#
# Usage:
#   tools/record-fixtures.sh <group> <node> <case>
#   tools/record-fixtures.sh --list
#
#   <group>   group (stage) name from the config; it must be listed in RECORD_ALLOWED_GROUPS
#             (any other group is refused)
#   <node>    node (server) name of that group in the config, e.g. node1
#   <case>    one of the cases in the catalogue below (--list prints them)
#
# Only catalogued cases can be recorded. All of them are read-only; the CLI cases that name a
# write command (processErrorStart, kill) use the process id 999999999, and the script first
# checks through REST that no Queue Manager entry with this id exists. The export cases only
# write a ZIP into the private temporary directory.
#
# Inputs:
#   config      ${INUBIT_MCP_CONFIG:-~/.config/inubit-mcp/config.yaml}: baseUrl of the node,
#               defaults.cliHome, defaults.cliJavaHome, tls.trustStore (format of feature 002
#               with groups/nodes, or of feature 001 with stages/servers)
#   required    RECORD_ALLOWED_GROUPS space-separated NON-PRODUCTION groups that may be recorded
#               RECORD_PINNED_PUBKEY  curl --pinnedpubkey value of the node's certificate
#                                     (sha256//<base64>)
#   credentials <PREFIX>_<GROUP>_<NODE>_{USERNAME,PASSWORD},
#               else <PREFIX>_<GROUP>_{USERNAME,PASSWORD}; <PREFIX> is RECORD_ENV_PREFIX
#               (default INUBIT, the credentials.envPrefix of the profile).
#               Never printed, never passed as a process argument: curl reads them from stdin
#               (-K -), StartCLI reads the password from stdin (the username is not a secret and
#               goes to -u). StartCLI gets an allowlisted environment only: PATH, HOME, TMPDIR,
#               LANG, LC_ALL, USER, JAVA_HOME, JAVA_TOOL_OPTIONS (research R-6).
#   optional    RECORD_TRUSTSTORE     StartCLI trust store (default: tls.trustStore of the config)
#               RECORD_CLI_LOCALE     "en" (default) forces English StartCLI messages through
#                                     JAVA_TOOL_OPTIONS; "default" keeps the JVM default locale
#               RECORD_ANON_STATE     anonymizer mapping file shared between runs (holds the
#                                     original values; refused inside the repository)
#
# Output (anonymized with tools/anonymize.py, checked for leftovers):
#   REST  src/test/resources/fixtures/v8_1/rest/<case>.{json|xml|html|txt}, <case>.http
#         (status and content type), <case>.request.xml for POST requests
#   CLI   src/test/resources/fixtures/v8_1/cli/<case>.stdout, <case>.stderr, <case>.exit
#   ZIP   <case>.zip (REST download or CLI export) with only the allow-listed entries, trimmed
#         to a few elements
#   sample objects (no defaults; required by the cases that use them, see --list):
#         RECORD_OWNER (inventory owner user group; also kept by the anonymizer),
#         RECORD_SAMPLE_WORKFLOW, RECORD_SAMPLE_DIAGRAM, RECORD_SAMPLE_GROUP; each must match
#         ^[A-Za-z0-9_.-]{1,200}$. RECORD_KEEP: further user-group names the anonymizer keeps
#         (comma-separated).
#
# Raw, unanonymized output only lives in a private temporary directory that is removed on exit.
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly FIXTURE_DIR="${PROJECT_DIR}/src/test/resources/fixtures/v8_1"
readonly ANONYMIZE="${SCRIPT_DIR}/anonymize.py"
readonly CONFIG_FILE="${INUBIT_MCP_CONFIG:-${HOME}/.config/inubit-mcp/config.yaml}"
readonly PINNED_PUBKEY="${RECORD_PINNED_PUBKEY:-}"
readonly ENV_PREFIX="${RECORD_ENV_PREFIX:-INUBIT}"
readonly ACCEPT_HEADER='Accept: application/json, application/xml;q=0.9, */*;q=0.8'
readonly UNKNOWN_PID=999999999
readonly UNREACHABLE_CLI_URL='https://127.0.0.1:9/ibis/servlet/IBISSoapServlet'
readonly DUMMY_PASSWORD='dummy-wrong-password'
readonly LOG_TYPES=(processLog systemLog queueLog connectionLog schedulerLog auditLog
                    keyManagerLog webserviceManager)

die() { printf 'record-fixtures: %s\n' "$*" >&2; exit 1; }
info() { printf 'record-fixtures: %s\n' "$*" >&2; }

# ----- case catalogue -------------------------------------------------------------------------
# Sets: kind (rest|cli); rest: method, path, body; cli: command, cli_url, password_mode, locale;
# zip_entries (keep only these ZIP entries) and anon_extra (extra anonymize.py options).

# Sample objects used by the read-only cases: no defaults (they name objects of a customer),
# required by the cases that use them, validated like CLI values (R-11).
readonly OWNER="${RECORD_OWNER:-}"
readonly SAMPLE_WORKFLOW="${RECORD_SAMPLE_WORKFLOW:-}"
readonly SAMPLE_DIAGRAM="${RECORD_SAMPLE_DIAGRAM:-}"
readonly SAMPLE_GROUP="${RECORD_SAMPLE_GROUP:-}"
readonly EXPORT_FILE_TOKEN='@EXPORT_FILE@'

list_cases() {
    local type sel
    # Unset sample objects are shown as the variable that has to be set.
    local owner="${OWNER:-<RECORD_OWNER>}" workflow="${SAMPLE_WORKFLOW:-<RECORD_SAMPLE_WORKFLOW>}"
    local diagram="${SAMPLE_DIAGRAM:-<RECORD_SAMPLE_DIAGRAM>}"
    local group="${SAMPLE_GROUP:-<RECORD_SAMPLE_GROUP>}"
    for type in "${LOG_TYPES[@]}"; do
        printf 'log_%-38s REST GET  /ibis/rest/log/%s?format=json&noOfItems=5\n' "$type" "$type"
    done
    printf '%-42s REST POST /ibis/rest/log/systemLog (BETWEEN last 7 days, LIKE message)\n' \
        log_systemLog_filtered
    printf '%-42s REST POST /ibis/rest/log/queueLog (status EQUAL Error)\n' log_queueLog_error
    printf '%-42s REST GET  /ibis/rest/metrics?format=json\n' metrics
    printf '%-42s REST GET  /ibis/rest/healthcheck (no authentication)\n' healthcheck
    printf '%-42s REST GET  /ibis/rest/ready (no authentication)\n' ready
    printf '%-42s REST GET  /ibis/rest/system/info\n' system_info
    printf '%-42s CLI  startcli.sh -v (no server, no credentials)\n' version
    printf '%-42s REST GET  /ibis/rest/model/models?user=%s\n' model_models_owner "${owner}"
    printf '%-42s REST GET  /ibis/rest/model/modelByName/%s?user=%s\n' modelByName_sample \
        "${diagram}" "${owner}"
    printf '%-42s REST GET  /ibis/rest/model/export/%s (workflow.xml, archive.properties)\n' \
        model_export_sample "${diagram}"
    printf '%-42s CLI  processErrorStart %s\n' processErrorStart_unknown "${UNKNOWN_PID}"
    printf '%-42s CLI  processErrorStart %s (JVM default locale)\n' \
        processErrorStart_unknown_default_locale "${UNKNOWN_PID}"
    printf '%-42s CLI  kill %s\n' kill_unknown "${UNKNOWN_PID}"
    printf '%-42s CLI  noSuchCommand\n' unknown_command
    printf '%-42s CLI  uptime with the password "%s"\n' login_failed "${DUMMY_PASSWORD}"
    printf '%-42s CLI  uptime against %s\n' unreachable "${UNREACHABLE_CLI_URL}"
    for sel in error waiting all queued; do
        printf 'ps_%-39s CLI  ps -csv -%s -l 5\n' "${sel}" "${sel:0:1}"
    done
    printf '%-42s CLI  ps -csv -l 5 (no state selector)\n' ps_no_selector
    printf "%-42s CLI  ps -csv -e -l 5 -f 'workflow=%s'\n" ps_filter_workflow "${workflow}"
    printf "%-42s CLI  ps -csv -e -l 5 -f 'workflow=No Such Workflow'\n" ps_filter_space
    printf '%-42s CLI  export group %s --includeHistory (versionHistory.xml, 3 workflows)\n' \
        export_history_sample "${group}"
    printf '%-42s CLI  export of all modules of %s (module/module.xml, 20 modules)\n' \
        export_modules_sample "${owner}"
}

# Values inserted into --execCommand or a URL path must pass the allowlist of research R-11.
require_safe_value() {
    [[ "$1" =~ ^[A-Za-z0-9_.-]{1,200}$ ]] || die "unsafe sample value '$1' (allowed: [A-Za-z0-9_.-])"
}

# require_sample <VARIABLE> <value>: the case needs this sample object (no default).
require_sample() {
    [[ -n "$2" ]] || die "case ${case_name} needs $1 (no default)"
    require_safe_value "$2"
}

log_request() {
    local filters="$1" sort_field="$2"
    cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<logRequest>
    <startIndex>0</startIndex>
    <noOfItems>5</noOfItems>
${filters}
    <sorting>
        <field>${sort_field}</field>
        <order>DESCENDING</order>
    </sorting>
</logRequest>
EOF
}

filtered_system_log_request() {
    local now_ms=$(( $(date +%s) * 1000 ))
    local week_ago_ms=$(( now_ms - 7 * 24 * 3600 * 1000 ))
    log_request "    <filtering>
        <field>startTime</field>
        <comparison>BETWEEN</comparison>
        <min>${week_ago_ms}</min>
        <max>${now_ms}</max>
    </filtering>
    <filtering>
        <field>message</field>
        <value>error</value>
        <comparison>LIKE</comparison>
    </filtering>" startTime
}

error_queue_log_request() {
    log_request "    <filtering>
        <field>status</field>
        <value>Error</value>
        <comparison>EQUAL</comparison>
    </filtering>" startTime
}

define_case() {
    local case_name="$1"
    kind='' method='GET' path='' body='' command='' cli_url='' password_mode='real'
    locale="${RECORD_CLI_LOCALE:-en}"
    zip_entries=() anon_extra=() auth=basic preflight_unknown_pid=no
    local sel
    case "${case_name}" in
        log_systemLog_filtered)
            kind=rest method=POST path='/ibis/rest/log/systemLog?format=json'
            body="$(filtered_system_log_request)" ;;
        log_queueLog_error)
            kind=rest method=POST path='/ibis/rest/log/queueLog?format=json'
            body="$(error_queue_log_request)" ;;
        log_*)
            local type="${case_name#log_}"
            [[ " ${LOG_TYPES[*]} " == *" ${type} "* ]] || die "unknown log type: ${type}"
            kind=rest path="/ibis/rest/log/${type}?format=json&noOfItems=5" ;;
        metrics)
            kind=rest path='/ibis/rest/metrics?format=json' ;;
        healthcheck)
            kind=rest path='/ibis/rest/healthcheck' auth=none ;;
        ready)
            kind=rest path='/ibis/rest/ready' auth=none ;;
        system_info)
            kind=rest path='/ibis/rest/system/info' ;;
        version)
            kind=cli_version ;;
        model_models_owner)
            require_sample RECORD_OWNER "${OWNER}"
            kind=rest path="/ibis/rest/model/models?user=${OWNER}" ;;
        modelByName_sample)
            require_sample RECORD_OWNER "${OWNER}"; require_sample RECORD_SAMPLE_DIAGRAM "${SAMPLE_DIAGRAM}"
            kind=rest path="/ibis/rest/model/modelByName/${SAMPLE_DIAGRAM}?user=${OWNER}" ;;
        model_export_sample)
            require_sample RECORD_SAMPLE_DIAGRAM "${SAMPLE_DIAGRAM}"
            kind=rest path="/ibis/rest/model/export/${SAMPLE_DIAGRAM}"
            zip_entries=(workflow/workflow.xml archive.properties)
            anon_extra=(--xml-drop WorkflowModule) ;;
        processErrorStart_unknown)
            kind=cli command="processErrorStart ${UNKNOWN_PID}" preflight_unknown_pid=yes ;;
        processErrorStart_unknown_default_locale)
            kind=cli command="processErrorStart ${UNKNOWN_PID}" locale=default
            preflight_unknown_pid=yes ;;
        kill_unknown)
            kind=cli command="kill ${UNKNOWN_PID}" preflight_unknown_pid=yes ;;
        unknown_command)
            kind=cli command='noSuchCommand' ;;
        login_failed)
            kind=cli command='uptime' password_mode=dummy ;;
        unreachable)
            kind=cli command='uptime' cli_url="${UNREACHABLE_CLI_URL}" password_mode=dummy ;;
        ps_error|ps_waiting|ps_all|ps_queued)
            sel="${case_name#ps_}"
            kind=cli command="ps -csv -${sel:0:1} -l 5" ;;
        ps_no_selector)
            kind=cli command='ps -csv -l 5' ;;
        ps_filter_workflow)
            require_sample RECORD_SAMPLE_WORKFLOW "${SAMPLE_WORKFLOW}"
            kind=cli command="ps -csv -e -l 5 -f 'workflow=${SAMPLE_WORKFLOW}'" ;;
        ps_filter_space)
            kind=cli command="ps -csv -e -l 5 -f 'workflow=No Such Workflow'" ;;
        export_history_sample)
            require_sample RECORD_OWNER "${OWNER}"; require_sample RECORD_SAMPLE_GROUP "${SAMPLE_GROUP}"
            kind=cli
            command="export --exportWorkflowUser '${OWNER}' --exportWorkflowType 'technical'"
            command+=" --exportWorkflowGroup '${SAMPLE_GROUP}' --includeHistory"
            command+=" --exportFile '${EXPORT_FILE_TOKEN}'"
            zip_entries=(versionHistory.xml)
            anon_extra=(--xml-limit Workflow=3 --xml-limit Module=3) ;;
        export_modules_sample)
            require_sample RECORD_OWNER "${OWNER}"
            kind=cli
            command="export --exportModule '' --exportModuleGroup '' --exportModuleUser '${OWNER}'"
            command+=" --exportFile '${EXPORT_FILE_TOKEN}'"
            zip_entries=(module/module.xml)
            anon_extra=(--xml-limit Module=20) ;;
        *)
            die "unknown case '${case_name}' (see --list)" ;;
    esac
}

# ----- configuration --------------------------------------------------------------------------

# Prints one value from the config: base_url <stage> <server> | cli_home | cli_java_home |
# trust_store. Line-based reader for the documented config layout (no YAML library needed).
config_value() {
    python3 - "${CONFIG_FILE}" "$@" <<'PY'
import os, re, sys
path, what, *args = sys.argv[1:]
stage = server = None
values, trust_stores, hostname_flags = {}, set(), set()
section = None
for raw in open(path, encoding="utf-8"):
    line = raw.split(" #", 1)[0].rstrip()
    if not line.strip() or line.lstrip().startswith("#"):
        continue
    indent = len(line) - len(line.lstrip())
    if indent == 0:
        section = line.split(":", 1)[0]
    text = line.strip()
    m = re.match(r"-?\s*(\w+):\s*(.*)$", text)
    if not m:
        continue
    key, value = m.group(1), m.group(2).strip().strip("'\"")
    if key == "trustStore":
        trust_stores.add(value)
    if key == "disableHostnameVerification":
        hostname_flags.add(value.lower())
    if section == "defaults" and key in ("cliHome", "cliJavaHome"):
        values[key] = value
    if section in ("groups", "stages") and key == "name" and text.startswith("-"):
        if indent <= 2:
            stage, server = value, None
        else:
            server = value
    if section in ("groups", "stages") and key == "baseUrl" and stage and server:
        values[f"baseUrl:{stage}/{server}"] = value
expand = lambda v: os.path.expanduser(v) if v else ""
if what == "base_url":
    print(values.get(f"baseUrl:{args[0]}/{args[1]}", ""))
elif what == "cli_home":
    print(expand(values.get("cliHome")))
elif what == "cli_java_home":
    print(expand(values.get("cliJavaHome")))
elif what == "trust_store":
    print(expand(next(iter(trust_stores))) if len(trust_stores) == 1 else "")
elif what == "disable_hostname_verification":
    # "true" only if EVERY disableHostnameVerification entry in the file is true (e.g. one TLS
    # block shared by all groups via a YAML anchor); mixed or missing values give "false", so the
    # flag is never passed to StartCLI by accident.
    print("true" if hostname_flags == {"true"} else "false")
PY
}

# Resolves <PREFIX>_<GROUP>_<NODE>_<KIND>, falling back to <PREFIX>_<GROUP>_<KIND> (research R-12).
credential() {
    local kind="$1" stage_part server_part specific general
    stage_part="$(printf '%s' "${stage}" | tr '[:lower:]' '[:upper:]' | tr -c 'A-Z0-9' '_')"
    server_part="$(printf '%s' "${server}" | tr '[:lower:]' '[:upper:]' | tr -c 'A-Z0-9' '_')"
    specific="${ENV_PREFIX}_${stage_part}_${server_part}_${kind}"
    general="${ENV_PREFIX}_${stage_part}_${kind}"
    if [[ -n "${!specific:-}" ]]; then
        printf '%s' "${!specific}"
    elif [[ -n "${!general:-}" ]]; then
        printf '%s' "${!general}"
    else
        die "missing credential: set ${specific} or ${general}"
    fi
}

# Escapes a value for a double-quoted curl config entry.
curl_config_escape() {
    local value="${1//\\/\\\\}"
    printf '%s' "${value//\"/\\\"}"
}

# ----- recording ------------------------------------------------------------------------------

anonymize() {
    ANONYMIZE_KEEP="${RECORD_KEEP:-}" python3 "${ANONYMIZE}" "${anon_args[@]}" "$@"
}

# Anonymizes <raw> into <target>: a ZIP keeps only zip_entries; anon_extra trims XML.
# (The ${a[@]+...} form keeps bash 3.2 happy with empty arrays under set -u.)
anonymize_case_output() {
    local raw="$1" target="$2" entry
    local -a options=(${anon_extra[@]+"${anon_extra[@]}"})
    for entry in ${zip_entries[@]+"${zip_entries[@]}"}; do
        options+=(--zip-keep "${entry}")
    done
    anonymize ${options[@]+"${options[@]}"} "$@"
}

# rest_call <basic|none> <url> <curl options...>: credentials only through stdin (-K -).
rest_call() {
    local mode="$1" url="$2"
    shift 2
    if [[ "${mode}" == none ]]; then
        curl "$@" "${url}" < /dev/null
    else
        printf 'user = "%s:%s"\n' "$(curl_config_escape "${username}")" \
            "$(curl_config_escape "${password}")" | curl --config - "$@" "${url}"
    fi
}

# Refuses to run a write command on 999999999 unless the Queue Manager has no such entry.
require_unknown_pid_absent() {
    local base_url response
    base_url="$(config_value base_url "${stage}" "${server}")"
    printf '%s\n' "<logRequest><startIndex>0</startIndex><noOfItems>1</noOfItems><filtering><field>workflowId</field><value>${UNKNOWN_PID}</value><comparison>EQUAL</comparison></filtering></logRequest>" \
        > "${work_dir}/preflight.xml"
    response="$(rest_call basic "${base_url%/}/ibis/rest/log/queueLog?format=json" \
        --silent --show-error --insecure --pinnedpubkey "${PINNED_PUBKEY}" --request POST \
        --header "${ACCEPT_HEADER}" --header 'Content-Type: application/xml' --max-time 30 \
        --data-binary "@${work_dir}/preflight.xml")" || die "preflight request failed"
    [[ "${response}" =~ \"total\":0[,}] ]] \
        || die "process id ${UNKNOWN_PID} exists in the Queue Manager (or unexpected response); refused"
}

# Sets child_env: the allowlisted environment for StartCLI (research R-6).
build_cli_env() {
    local java_home="$1" name
    child_env=(env -i)
    for name in PATH HOME TMPDIR LANG LC_ALL USER; do
        if [[ -n "${!name:-}" ]]; then
            child_env+=("${name}=${!name}")
        fi
    done
    child_env+=("JAVA_HOME=${java_home}")
    if [[ "${locale}" == en ]]; then
        child_env+=("JAVA_TOOL_OPTIONS=-Duser.language=en -Duser.country=US")
    fi
}

record_cli_version() {
    local cli_home java_home exit_code
    cli_home="$(config_value cli_home)"
    java_home="$(config_value cli_java_home)"
    [[ -x "${cli_home}/bin/startcli.sh" ]] || die "no bin/startcli.sh under cliHome '${cli_home}'"
    build_cli_env "${java_home}"
    set +e
    (cd "${cli_home}" && "${child_env[@]}" ./bin/startcli.sh -v < /dev/null \
        > "${work_dir}/stdout" 2> "${work_dir}/stderr")
    exit_code=$?
    set -e
    mkdir -p "${FIXTURE_DIR}/cli"
    local prefix="${FIXTURE_DIR}/cli/${case_name}"
    anonymize --format text "${work_dir}/stdout" "${prefix}.stdout"
    anonymize --format text "${work_dir}/stderr" "${prefix}.stderr"
    printf '%s\n' "${exit_code}" > "${prefix}.exit"
    outputs=("${prefix}.stdout" "${prefix}.stderr" "${prefix}.exit")
    info "${case_name}: exit code ${exit_code}"
}

record_rest() {
    local base_url target raw_body status_line http_code content_type extension
    base_url="$(config_value base_url "${stage}" "${server}")"
    [[ -n "${base_url}" ]] || die "no baseUrl for ${stage}/${server} in ${CONFIG_FILE}"
    target="${base_url%/}${path}"
    raw_body="${work_dir}/body"

    local -a curl_args=(--silent --show-error --insecure --pinnedpubkey "${PINNED_PUBKEY}"
        --request "${method}" --header "${ACCEPT_HEADER}" --max-time 120
        --output "${raw_body}" --write-out '%{http_code} %{content_type}')
    if [[ -n "${body}" ]]; then
        printf '%s\n' "${body}" > "${work_dir}/request.xml"
        curl_args+=(--header 'Content-Type: application/xml'
                    --data-binary "@${work_dir}/request.xml")
    fi

    status_line="$(rest_call "${auth}" "${target}" "${curl_args[@]}")" \
        || die "curl failed for ${case_name}"
    http_code="${status_line%% *}"
    content_type="${status_line#* }"
    if [[ "$(head -c 2 "${raw_body}")" == PK ]]; then
        content_type="${content_type:-application/zip (no Content-Type header)}"
    fi
    case "${content_type}" in
        *zip*|*octet-stream*) extension=zip ;;
        *json*) extension=json ;;
        *html*) extension=html ;;
        *xml*) extension=xml ;;
        *) extension=txt ;;
    esac

    mkdir -p "${FIXTURE_DIR}/rest"
    local prefix="${FIXTURE_DIR}/rest/${case_name}"
    rm -f "${prefix}".{json,html,xml,txt,zip,http,request.xml}
    if [[ ${#zip_entries[@]} -gt 0 && "${extension}" != zip ]]; then
        die "${case_name}: expected a ZIP, got ${content_type} (HTTP ${http_code})"
    fi
    anonymize_case_output "${raw_body}" "${prefix}.${extension}"
    printf 'status: %s\ncontent-type: %s\n' "${http_code}" "${content_type}" > "${prefix}.http"
    outputs=("${prefix}.${extension}" "${prefix}.http")
    if [[ -n "${body}" ]]; then
        cp "${work_dir}/request.xml" "${prefix}.request.xml"
        outputs+=("${prefix}.request.xml")
    fi
    info "${case_name}: HTTP ${http_code} (${content_type})"
}

record_cli() {
    local cli_home java_home trust_store url cli_password exit_code
    cli_home="$(config_value cli_home)"
    java_home="$(config_value cli_java_home)"
    trust_store="${RECORD_TRUSTSTORE:-$(config_value trust_store)}"
    [[ -x "${cli_home}/bin/startcli.sh" ]] || die "no bin/startcli.sh under cliHome '${cli_home}'"
    [[ -d "${java_home}" ]] || die "cliJavaHome '${java_home}' not found"
    if [[ -n "${trust_store}" && ! -f "${trust_store}" ]]; then
        die "trust store '${trust_store}' not found (set RECORD_TRUSTSTORE)"
    fi
    url="${cli_url:-$(config_value base_url "${stage}" "${server}")/ibis/servlet/IBISSoapServlet}"
    local export_file="${work_dir}/export.zip"
    [[ "${export_file}" =~ ^[A-Za-z0-9_./-]+$ ]] || die "temporary path not usable in --execCommand"
    command="${command//${EXPORT_FILE_TOKEN}/${export_file}}"
    if [[ "${password_mode}" == dummy ]]; then
        cli_password="${DUMMY_PASSWORD}"
    else
        cli_password="${password}"
    fi

    # TLS flags only as configured (research R-6).
    local -a tls_args=()
    if [[ -n "${trust_store}" ]]; then
        tls_args+=(--trustStoreFilePath "${trust_store}")
    fi
    if [[ "$(config_value disable_hostname_verification)" == true ]]; then
        tls_args+=(--disableHostNameVerification)
    fi
    if [[ "${preflight_unknown_pid}" == yes ]]; then
        require_unknown_pid_absent
    fi
    build_cli_env "${java_home}"

    set +e
    (cd "${cli_home}" && printf '%s\n' "${cli_password}" | "${child_env[@]}" ./bin/startcli.sh \
        -u "${username}" ${tls_args[@]+"${tls_args[@]}"} \
        --execCommand "${command}" "${url}" > "${work_dir}/stdout" 2> "${work_dir}/stderr")
    exit_code=$?
    set -e

    mkdir -p "${FIXTURE_DIR}/cli"
    local prefix="${FIXTURE_DIR}/cli/${case_name}"
    anonymize --format text "${work_dir}/stdout" "${prefix}.stdout"
    anonymize --format text "${work_dir}/stderr" "${prefix}.stderr"
    printf '%s\n' "${exit_code}" > "${prefix}.exit"
    outputs=("${prefix}.stdout" "${prefix}.stderr" "${prefix}.exit")
    if [[ ${#zip_entries[@]} -gt 0 ]]; then
        rm -f "${prefix}.zip"
        [[ -f "${export_file}" ]] || die "${case_name}: StartCLI wrote no export file"
        anonymize_case_output "${export_file}" "${prefix}.zip"
        outputs+=("${prefix}.zip")
    fi
    info "${case_name}: exit code ${exit_code}"
}

# Fails (and deletes the outputs) if a credential or an identifying value survived.
check_outputs() {
    local token
    token="$(printf '%s:%s' "${username}" "${password}" | base64 | tr -d '\n')"
    # Secrets go to the checker on stdin; it also looks inside ZIP entries.
    if ! printf '%s\n' "${password}" "${token}" ${host_terms[@]+"${host_terms[@]}"} \
        | anonymize --verify --secrets-stdin "${outputs[@]}"; then
        rm -f "${outputs[@]}"
        die "${case_name}: anonymization check failed, outputs removed"
    fi
}

main() {
    if [[ "${1:-}" == --list ]]; then
        list_cases
        exit 0
    fi
    [[ $# -eq 3 ]] || die "usage: tools/record-fixtures.sh <group> <node> <case> | --list"
    stage="$1" server="$2" case_name="$3"
    [[ -n "${RECORD_ALLOWED_GROUPS:-}" ]] \
        || die "set RECORD_ALLOWED_GROUPS to the non-production groups that may be recorded"
    [[ " ${RECORD_ALLOWED_GROUPS} " == *" ${stage} "* ]] \
        || die "group '${stage}' refused: not listed in RECORD_ALLOWED_GROUPS"
    [[ "${ENV_PREFIX}" =~ ^[A-Z][A-Z0-9_]*$ ]] || die "RECORD_ENV_PREFIX must match ^[A-Z][A-Z0-9_]*\$"
    [[ -f "${CONFIG_FILE}" ]] || die "config not found: ${CONFIG_FILE}"
    define_case "${case_name}"
    if [[ "${kind}" == rest || "${preflight_unknown_pid}" == yes ]]; then
        [[ -n "${PINNED_PUBKEY}" ]] \
            || die "set RECORD_PINNED_PUBKEY (sha256//<base64> of the node's public key)"
    fi

    username="$(credential USERNAME)"
    password="$(credential PASSWORD)"

    if [[ -n "${RECORD_ANON_STATE:-}" ]]; then
        local state_abs
        state_abs="$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' \
            "${RECORD_ANON_STATE}")"
        [[ "${state_abs}" != "$(cd "${PROJECT_DIR}" && pwd -P)"/* ]] \
            || die "RECORD_ANON_STATE must be outside the repository (it holds original values)"
    fi

    # The server's host name and its parent domain are replaced and must not survive.
    local base_url host
    base_url="$(config_value base_url "${stage}" "${server}")"
    host="${base_url#*://}"
    host="${host%%[:/]*}"
    host_terms=()
    if [[ -n "${host}" ]]; then
        host_terms+=("${host}")
        if [[ "${host#*.}" == *.* ]]; then
            host_terms+=("${host#*.}")
        fi
    fi

    work_dir="$(mktemp -d "${TMPDIR:-/tmp}/record-fixtures.XXXXXX")"
    chmod 700 "${work_dir}"
    trap 'rm -rf "${work_dir}"' EXIT
    anon_args=(--state "${RECORD_ANON_STATE:-${work_dir}/anonymize-state.json}")
    anon_args+=(--user "${username}")
    if [[ -n "${OWNER}" ]]; then
        anon_args+=(--keep "${OWNER}")
    fi
    if [[ -n "${host}" ]]; then
        anon_args+=(--host "${host}")
    fi

    outputs=()
    "record_${kind}"
    check_outputs
    info "${case_name}: written ${outputs[*]#"${PROJECT_DIR}/"}"
}

main "$@"

#!/usr/bin/env bash
# Record an anonymized INUBIT fixture from a NON-PRODUCTION server (research R-18, tasks.md T005).
#
# Usage:
#   [RECORD_INUBIT_LINE=9.0] tools/record-fixtures.sh <group> <node> <case>
#   [RECORD_INUBIT_LINE=9.0] tools/record-fixtures.sh --list
#
#   <group>   group (stage) name from the config; it must be listed in RECORD_ALLOWED_GROUPS
#             (any other group is refused)
#   <node>    node (server) name of that group in the config, e.g. node1
#   <case>    one of the cases in the catalogue below (--list prints them)
#
# Only catalogued cases can be recorded. All of them are read-only; the CLI cases that name a
# write command (processErrorStart, kill) use the process id 999999999, and the script first
# checks through REST that no Queue Manager entry with this id exists. The export cases only
# write a ZIP into the private temporary directory. The v61_* cases (INUBIT 6.1, feature 012,
# research D-16) use StartCLI only, without any REST step: local runs (-h, -v) or one
# interactive session per case (research D-5) whose commands come from the allowlist of
# CliSessionCommand.
#
# RECORD_INUBIT_LINE (feature 017, research D-10, D-11): "8.1" (default, unchanged) or "9.0". With
# 9.0 only the 9.0 catalogue (V90_CASES, --list prints it) can be recorded: REST and POSIX StartCLI,
# all read-only, plus the 9.0 cases help (-h), rest_auth_failed (GET /system/info with the dummy
# password, 401 body) and rest_not_found (modelByName of a name that does not exist, 404 body).
# StartCLI connects to <baseUrl> (9.0 has no SOAP servlet), every REST case sends the Accept
# header of its production adapter (D-11). The 9.0 client (research D-1, D-3): RECORD_CLI_HOME,
# else the home of the defaults.cliClients "9.0" entry, else defaults.cliHome; its JDK:
# RECORD_CLI_JAVA_HOME, else the entry's javaHome (only with the entry's home), else
# defaults.cliJavaHome. Before anything is recorded, every CLI case asks that client for its
# version (startcli -v, "CLI 9.0.x") and every case with a node asks the node (/system/info);
# both must report 9.0, else the case is refused. The .origin names the node's version, for -h
# and -v the client's. 8.1 ignores cliClients and both overrides.
#
# Inputs:
#   config      ${INUBIT_MCP_CONFIG:-~/.config/inubit-mcp/config.yaml}: baseUrl of the node,
#               defaults.cliHome, defaults.cliJavaHome, defaults.cliClients (9.0 only: the
#               "9.0" entry, flow or block form), tls.trustStore (format of feature 002
#               with groups/nodes, or of feature 001 with stages/servers)
#   required    RECORD_ALLOWED_GROUPS space-separated NON-PRODUCTION groups that may be recorded
#               RECORD_PINNED_PUBKEY  curl --pinnedpubkey value of the node's certificate
#                                     (sha256//<base64>)
#   credentials <PREFIX>_<GROUP>_<NODE>_{USERNAME,PASSWORD},
#               else <PREFIX>_<GROUP>_{USERNAME,PASSWORD}; <PREFIX> is RECORD_ENV_PREFIX
#               (default INUBIT, the credentials.envPrefix of the profile).
#               Never printed, never passed as a process argument: curl reads them from stdin
#               (-K -), StartCLI reads the password from stdin (the username is not a secret and
#               goes to -u; a 6.1 session reads the password line, then its commands).
#               StartCLI gets an allowlisted environment only: PATH, HOME, TMPDIR, LANG, LC_ALL,
#               USER, JAVA_HOME, JAVA_TOOL_OPTIONS (research R-6).
#   optional    RECORD_TRUSTSTORE     StartCLI trust store (default: tls.trustStore of the config)
#               RECORD_CLI_LOCALE     "en" (default) forces English StartCLI messages through
#                                     JAVA_TOOL_OPTIONS; "default" keeps the JVM default locale
#               RECORD_ANON_STATE     anonymizer mapping file shared between runs (holds the
#                                     original values; refused inside the repository)
#               RECORD_INUBIT_LINE    "8.1" (default) or "9.0" (see above); any other value is
#                                     refused
#               RECORD_CLI_HOME       9.0 only: the StartCLI client home; wins over
#                                     defaults.cliClients "9.0" and defaults.cliHome
#               RECORD_CLI_JAVA_HOME  9.0 only: its JDK; wins over the entry's javaHome and
#                                     defaults.cliJavaHome
#
# Output (anonymized with tools/anonymize.py, checked for leftovers):
#   REST  src/test/resources/fixtures/v8_1/rest/<case>.{json|xml|html|txt}, <case>.http
#         (status and content type), <case>.request.xml for POST requests
#   CLI   src/test/resources/fixtures/v8_1/cli/<case>.stdout, <case>.stderr, <case>.exit
#         (v61_* cases: src/test/resources/fixtures/v6_1/cli/)
#   9.0   RECORD_INUBIT_LINE=9.0: the same files under src/test/resources/fixtures/v9_0/rest|cli
#         and <case>.origin = "recorded <date> <INUBIT version>" (research D-10)
#   ZIP   <case>.zip (REST download or CLI export) with only the allow-listed entries, trimmed
#         to a few elements
#   sample objects (no defaults; required by the cases that use them, see --list):
#         RECORD_OWNER (inventory owner user group; also kept by the anonymizer),
#         RECORD_SAMPLE_WORKFLOW, RECORD_SAMPLE_DIAGRAM, RECORD_SAMPLE_GROUP,
#         RECORD_SAMPLE_SYSTEMDIAGRAM (a system diagram that carries a Partner Management),
#         RECORD_SAMPLE_SYSTEMDIAGRAM_GROUP (its diagram group); each must match
#         ^[A-Za-z0-9_.-]{1,200}$. RECORD_KEEP: further user-group names the anonymizer keeps
#         (comma-separated).
#   system diagrams (feature 018, research D-1; 8.1 only, read-only): model_export_systemdiagram
#         (REST export of RECORD_SAMPLE_SYSTEMDIAGRAM) and export_systemdiagram (StartCLI export of
#         the system diagrams of RECORD_SAMPLE_SYSTEMDIAGRAM_GROUP) keep workflow/workflow.xml and
#         archive.properties; anonymize.py replaces every value of the PartnerManagementSettings
#         subtree (partners, hosts, logins, secrets, workflow references) by synthetic values and
#         keeps the first 5 partners (--partner-limit 5). Record both with the same
#         RECORD_ANON_STATE, so that equal values get equal placeholders in both fixtures (D-1
#         compares them). Diagram, group and workflow names outside that subtree are replaced
#         afterwards like for every fixture (synthesize_names.py and neutralize.py with the local
#         maps).
#
# Raw, unanonymized output only lives in a private temporary directory that is removed on exit.
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly FIXTURE_DIR="${PROJECT_DIR}/src/test/resources/fixtures/v8_1"
readonly V61_FIXTURE_DIR="${PROJECT_DIR}/src/test/resources/fixtures/v6_1"
readonly V90_FIXTURE_DIR="${PROJECT_DIR}/src/test/resources/fixtures/v9_0"
readonly INUBIT_LINE="${RECORD_INUBIT_LINE:-8.1}"
readonly ANONYMIZE="${SCRIPT_DIR}/anonymize.py"
readonly CONFIG_FILE="${INUBIT_MCP_CONFIG:-${HOME}/.config/inubit-mcp/config.yaml}"
readonly PINNED_PUBKEY="${RECORD_PINNED_PUBKEY:-}"
readonly ENV_PREFIX="${RECORD_ENV_PREFIX:-INUBIT}"
readonly ACCEPT_HEADER='Accept: application/json, application/xml;q=0.9, */*;q=0.8'
# 9.0: the media ranges of InubitHttpClient.MediaRange (research D-11).
readonly ACCEPT_XML='application/xml, */*;q=0.1'
readonly ACCEPT_JSON='application/json, */*;q=0.1'
readonly ACCEPT_ANY='*/*'
readonly UNKNOWN_PID=999999999
readonly UNREACHABLE_CLI_URL='https://127.0.0.1:9/ibis/servlet/IBISSoapServlet'
readonly UNREACHABLE_V90_CLI_URL='https://127.0.0.1:9'
readonly MISSING_DIAGRAM='NoSuchDiagram017'
readonly DUMMY_PASSWORD='dummy-wrong-password'
readonly LOG_TYPES=(processLog systemLog queueLog connectionLog schedulerLog auditLog
                    keyManagerLog webserviceManager)

die() { printf 'record-fixtures: %s\n' "$*" >&2; exit 1; }
info() { printf 'record-fixtures: %s\n' "$*" >&2; }

# ----- case catalogue -------------------------------------------------------------------------
# Sets: kind (rest|cli|cli_local|cli_session); rest: method, path, body; cli: command, cli_url,
# password_mode, locale; cli_local: local_option; cli_session: session (the commands after the
# password line); fixture_dir; zip_entries (keep only these ZIP entries) and anon_extra (extra
# anonymize.py options).

# Sample objects used by the read-only cases: no defaults (they name objects of a customer),
# required by the cases that use them, validated like CLI values (R-11).
readonly OWNER="${RECORD_OWNER:-}"
readonly SAMPLE_WORKFLOW="${RECORD_SAMPLE_WORKFLOW:-}"
readonly SAMPLE_DIAGRAM="${RECORD_SAMPLE_DIAGRAM:-}"
readonly SAMPLE_GROUP="${RECORD_SAMPLE_GROUP:-}"
readonly SAMPLE_SYSTEMDIAGRAM="${RECORD_SAMPLE_SYSTEMDIAGRAM:-}"
readonly SAMPLE_SYSTEMDIAGRAM_GROUP="${RECORD_SAMPLE_SYSTEMDIAGRAM_GROUP:-}"
# Partner entries kept per Partners list of a system diagram's Partner Management (feature 018).
readonly PARTNER_LIMIT=5
readonly EXPORT_FILE_TOKEN='@EXPORT_FILE@'

# The INUBIT 9.0 catalogue (RECORD_INUBIT_LINE=9.0; research D-10): REST and POSIX StartCLI, all
# read-only. NOT_RECORDABLE.txt of fixtures/v9_0 lists the 9.0 cases the recorder cannot produce.
readonly V90_CASES=(healthcheck ready system_info metrics log_systemLog log_systemLog_filtered
    model_models_owner modelByName_sample model_export_sample rest_auth_failed rest_not_found
    version help processErrorStart_unknown kill_unknown unknown_command login_failed unreachable
    ps_all export_history_sample export_modules_sample)
# Cases of the 9.0 catalogue only; the 8.1 default refuses them.
readonly V90_ONLY_CASES=(help rest_auth_failed rest_not_found)

# The 9.0 catalogue in the order of V90_CASES, with the Accept header of each REST case (D-11).
list_v90_cases() {
    local owner="${OWNER:-<RECORD_OWNER>}" diagram="${SAMPLE_DIAGRAM:-<RECORD_SAMPLE_DIAGRAM>}"
    local group="${SAMPLE_GROUP:-<RECORD_SAMPLE_GROUP>}"
    printf '%-42s REST GET  /ibis/rest/healthcheck (no authentication; JSON)\n' healthcheck
    printf '%-42s REST GET  /ibis/rest/ready (no authentication; JSON)\n' ready
    printf '%-42s REST GET  /ibis/rest/system/info (XML)\n' system_info
    printf '%-42s REST GET  /ibis/rest/metrics?format=json (JSON)\n' metrics
    printf '%-42s REST GET  /ibis/rest/log/systemLog?format=json&noOfItems=5 (JSON)\n' \
        log_systemLog
    printf '%-42s REST POST /ibis/rest/log/systemLog (BETWEEN last 7 days, LIKE message; JSON)\n' \
        log_systemLog_filtered
    printf '%-42s REST GET  /ibis/rest/model/models?user=%s (XML)\n' model_models_owner \
        "${owner}"
    printf '%-42s REST GET  /ibis/rest/model/modelByName/%s?user=%s (XML)\n' \
        modelByName_sample "${diagram}" "${owner}"
    printf '%-42s REST GET  /ibis/rest/model/export/%s (any; workflow.xml, archive.properties)\n' \
        model_export_sample "${diagram}"
    printf '%-42s REST GET  /ibis/rest/system/info with the password "%s" (401 body; XML)\n' \
        rest_auth_failed "${DUMMY_PASSWORD}"
    printf '%-42s REST GET  /ibis/rest/model/modelByName/%s?user=%s (404 body; XML)\n' \
        rest_not_found "${MISSING_DIAGRAM}" "${owner}"
    printf '%-42s CLI  startcli.sh -v (no server, no credentials)\n' version
    printf '%-42s CLI  startcli.sh -h (no server, no credentials)\n' help
    printf '%-42s CLI  processErrorStart %s\n' processErrorStart_unknown "${UNKNOWN_PID}"
    printf '%-42s CLI  kill %s\n' kill_unknown "${UNKNOWN_PID}"
    printf '%-42s CLI  noSuchCommand\n' unknown_command
    printf '%-42s CLI  uptime with the password "%s"\n' login_failed "${DUMMY_PASSWORD}"
    printf '%-42s CLI  uptime against %s\n' unreachable "${UNREACHABLE_V90_CLI_URL}"
    printf '%-42s CLI  ps -csv -a -l 5\n' ps_all
    printf '%-42s CLI  export group %s --includeHistory (versionHistory.xml, 3 workflows)\n' \
        export_history_sample "${group}"
    printf '%-42s CLI  export of all modules of %s (module/module.xml, 20 modules)\n' \
        export_modules_sample "${owner}"
}

list_cases() {
    if [[ "${INUBIT_LINE}" == 9.0 ]]; then
        list_v90_cases
        return
    fi
    local type sel
    # Unset sample objects are shown as the variable that has to be set.
    local owner="${OWNER:-<RECORD_OWNER>}" workflow="${SAMPLE_WORKFLOW:-<RECORD_SAMPLE_WORKFLOW>}"
    local diagram="${SAMPLE_DIAGRAM:-<RECORD_SAMPLE_DIAGRAM>}"
    local group="${SAMPLE_GROUP:-<RECORD_SAMPLE_GROUP>}"
    local system_diagram="${SAMPLE_SYSTEMDIAGRAM:-<RECORD_SAMPLE_SYSTEMDIAGRAM>}"
    local system_group="${SAMPLE_SYSTEMDIAGRAM_GROUP:-<RECORD_SAMPLE_SYSTEMDIAGRAM_GROUP>}"
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
    printf '%-42s REST GET  /ibis/rest/model/export/%s (system diagram; workflow.xml, %s)\n' \
        model_export_systemdiagram "${system_diagram}" "archive.properties, ${PARTNER_LIMIT} partners"
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
    printf '%-42s 6.1 CLI startcli.sh -h (no server, no credentials)\n' v61_help
    printf '%-42s 6.1 CLI startcli.sh -v (no server, no credentials)\n' v61_version
    printf '%-42s 6.1 CLI session: uname, uptime, maintenance, free -m, ps -c -e|-w|-q|-a\n' \
        v61_health_session
    printf '%-42s 6.1 CLI session: ps -a -l 10000\n' v61_ps_all
    printf '%-42s 6.1 CLI session: ps -e -l 10000\n' v61_ps_error
    printf '%-42s 6.1 CLI session: ps -w -l 10000\n' v61_ps_waiting
    printf '%-42s 6.1 CLI session: ps -q -l 10000\n' v61_ps_queued
    printf '%-42s 6.1 CLI session: ps -a -l 5\n' v61_ps_limit5
    printf '%-42s CLI  export group %s --includeHistory (versionHistory.xml, 3 workflows)\n' \
        export_history_sample "${group}"
    printf '%-42s CLI  export of all modules of %s (module/module.xml, 20 modules)\n' \
        export_modules_sample "${owner}"
    printf '%-42s CLI  export systemdiagram group %s of %s (workflow.xml, %s)\n' \
        export_systemdiagram "${system_group}" "${owner}" \
        "archive.properties, ${PARTNER_LIMIT} partners"
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
    local_option='' session=() fixture_dir="${FIXTURE_DIR}"
    locale="${RECORD_CLI_LOCALE:-en}"
    zip_entries=() anon_extra=() auth=basic preflight_unknown_pid=no accept="${ACCEPT_HEADER}"
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
            kind=cli_local local_option=-v ;;
        help)
            kind=cli_local local_option=-h ;;
        rest_auth_failed)
            kind=rest path='/ibis/rest/system/info' auth=dummy ;;
        rest_not_found)
            require_sample RECORD_OWNER "${OWNER}"
            kind=rest path="/ibis/rest/model/modelByName/${MISSING_DIAGRAM}?user=${OWNER}" ;;
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
        model_export_systemdiagram)
            # feature 018 (research D-1): the REST channel candidate for the Partner Management
            require_sample RECORD_SAMPLE_SYSTEMDIAGRAM "${SAMPLE_SYSTEMDIAGRAM}"
            kind=rest path="/ibis/rest/model/export/${SAMPLE_SYSTEMDIAGRAM}"
            zip_entries=(workflow/workflow.xml archive.properties)
            anon_extra=(--partner-limit "${PARTNER_LIMIT}") ;;
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
        export_systemdiagram)
            # feature 018 (research D-1): the StartCLI channel candidate for the Partner Management
            require_sample RECORD_OWNER "${OWNER}"
            require_sample RECORD_SAMPLE_SYSTEMDIAGRAM_GROUP "${SAMPLE_SYSTEMDIAGRAM_GROUP}"
            kind=cli
            command="export --exportWorkflowUser '${OWNER}' --exportWorkflowType 'systemdiagram'"
            command+=" --exportWorkflowGroup '${SAMPLE_SYSTEMDIAGRAM_GROUP}'"
            command+=" --exportFile '${EXPORT_FILE_TOKEN}'"
            zip_entries=(archive.properties workflow/workflow.xml)
            anon_extra=(--partner-limit "${PARTNER_LIMIT}") ;;
        v61_help)
            kind=cli_local local_option=-h ;;
        v61_version)
            kind=cli_local local_option=-v ;;
        v61_health_session)
            kind=cli_session
            session=(uname uptime maintenance 'free -m' 'ps -c -e' 'ps -c -w' 'ps -c -q'
                     'ps -c -a') ;;
        v61_ps_all)
            kind=cli_session session=('ps -a -l 10000') ;;
        v61_ps_error)
            kind=cli_session session=('ps -e -l 10000') ;;
        v61_ps_waiting)
            kind=cli_session session=('ps -w -l 10000') ;;
        v61_ps_queued)
            kind=cli_session session=('ps -q -l 10000') ;;
        v61_ps_limit5)
            kind=cli_session session=('ps -a -l 5') ;;
        *)
            die "unknown case '${case_name}' (see --list)" ;;
    esac
    if [[ "${INUBIT_LINE}" == 9.0 ]]; then
        [[ " ${V90_CASES[*]} " == *" ${case_name} "* ]] \
            || die "case '${case_name}' is not in the 9.0 catalogue (RECORD_INUBIT_LINE=9.0 --list)"
        fixture_dir="${V90_FIXTURE_DIR}"
        case "${case_name}" in
            healthcheck|ready|metrics|log_*) accept="Accept: ${ACCEPT_JSON}" ;;
            model_export_sample) accept="Accept: ${ACCEPT_ANY}" ;;
            *) accept="Accept: ${ACCEPT_XML}" ;;
        esac
        if [[ "${case_name}" == unreachable ]]; then
            cli_url="${UNREACHABLE_V90_CLI_URL}"
        fi
    elif [[ " ${V90_ONLY_CASES[*]} " == *" ${case_name} "* ]]; then
        die "case '${case_name}' needs RECORD_INUBIT_LINE=9.0"
    elif [[ "${case_name}" == v61_* ]]; then
        fixture_dir="${V61_FIXTURE_DIR}"
    fi
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
# defaults.cliClients (feature 017, research D-1): "<major>.<minor>" -> {home, javaHome}, as flow
# entries ("9.0": { home: ..., javaHome: ... }) or block entries (9.0: then home:/javaHome: lines)
clients, clients_indent, entry = {}, None, None
entry_pattern = r"""(['"]?)([0-9]+\.[0-9]+)\1:\s*(\{[^}]*\})?"""
def flow_entries(text):
    for _, key, body in re.findall(entry_pattern, text):
        clients[key] = dict((k, v.strip().strip("'\"")) for k, v in
                            re.findall(r"(\w+):\s*([^,}]*)", body))
for raw in open(path, encoding="utf-8"):
    line = raw.split(" #", 1)[0].rstrip()
    if not line.strip() or line.lstrip().startswith("#"):
        continue
    indent = len(line) - len(line.lstrip())
    if indent == 0:
        section = line.split(":", 1)[0]
    text = line.strip()
    if clients_indent is not None and (indent <= clients_indent or section != "defaults"):
        clients_indent = entry = None
    if clients_indent is not None:
        head = re.match(entry_pattern, text)
        if head:
            entry = head.group(2)
            flow_entries(text) if head.group(3) else clients.setdefault(entry, {})
        elif entry and (m := re.match(r"(\w+):\s*(.*)$", text)):
            clients[entry][m.group(1)] = m.group(2).strip().strip("'\"")
        continue
    m = re.match(r"-?\s*(\w+):\s*(.*)$", text)
    if not m:
        continue
    key, value = m.group(1), m.group(2).strip().strip("'\"")
    if section == "defaults" and key == "cliClients" and indent > 0:
        clients_indent = indent
        flow_entries(value)
        continue
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
elif what == "cli_client_home":
    print(expand(clients.get(args[0], {}).get("home")))
elif what == "cli_client_java_home":
    print(expand(clients.get(args[0], {}).get("javaHome")))
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

# rest_call <basic|dummy|none> <url> <curl options...>: credentials only through stdin (-K -);
# dummy sends the username with DUMMY_PASSWORD (rest_auth_failed).
rest_call() {
    local mode="$1" url="$2" secret
    shift 2
    if [[ "${mode}" == none ]]; then
        curl "$@" "${url}" < /dev/null
    else
        secret="${password}"
        if [[ "${mode}" == dummy ]]; then
            secret="${DUMMY_PASSWORD}"
        fi
        printf 'user = "%s:%s"\n' "$(curl_config_escape "${username}")" \
            "$(curl_config_escape "${secret}")" | curl --config - "$@" "${url}"
    fi
}

# Refuses to run a write command on 999999999 unless the Queue Manager has no such entry.
require_unknown_pid_absent() {
    local base_url response accept_header="${ACCEPT_HEADER}"
    if [[ "${INUBIT_LINE}" == 9.0 ]]; then
        accept_header="Accept: ${ACCEPT_JSON}"
    fi
    base_url="$(config_value base_url "${stage}" "${server}")"
    printf '%s\n' "<logRequest><startIndex>0</startIndex><noOfItems>1</noOfItems><filtering><field>workflowId</field><value>${UNKNOWN_PID}</value><comparison>EQUAL</comparison></filtering></logRequest>" \
        > "${work_dir}/preflight.xml"
    response="$(rest_call basic "${base_url%/}/ibis/rest/log/queueLog?format=json" \
        --silent --show-error --insecure --pinnedpubkey "${PINNED_PUBKEY}" --request POST \
        --header "${accept_header}" --header 'Content-Type: application/xml' --max-time 30 \
        --data-binary "@${work_dir}/preflight.xml")" || die "preflight request failed"
    [[ "${response}" =~ \"total\":0[,}] ]] \
        || die "process id ${UNKNOWN_PID} exists in the Queue Manager (or unexpected response); refused"
}

# Sets child_env: the allowlisted environment for StartCLI (research R-6); the optional second
# argument is appended to JAVA_TOOL_OPTIONS.
build_cli_env() {
    local java_home="$1" tool_options="${2:-}" name
    child_env=(env -i)
    for name in PATH HOME TMPDIR LANG LC_ALL USER; do
        if [[ -n "${!name:-}" ]]; then
            child_env+=("${name}=${!name}")
        fi
    done
    child_env+=("JAVA_HOME=${java_home}")
    if [[ "${locale}" == en ]]; then
        tool_options="-Duser.language=en -Duser.country=US${tool_options:+ ${tool_options}}"
    fi
    if [[ -n "${tool_options}" ]]; then
        child_env+=("JAVA_TOOL_OPTIONS=${tool_options}")
    fi
}

# Sets cli_home and java_home of the caller. 8.1 (unchanged): defaults.cliHome and
# defaults.cliJavaHome. 9.0 (research D-1, D-3): RECORD_CLI_HOME, else the home of the
# defaults.cliClients "9.0" entry, else defaults.cliHome; the JDK: RECORD_CLI_JAVA_HOME, else the
# entry's javaHome (only with the entry's home, as in the server), else defaults.cliJavaHome.
choose_cli() {
    cli_home="$(config_value cli_home)"
    java_home="$(config_value cli_java_home)"
    if [[ "${INUBIT_LINE}" == 9.0 ]]; then
        local entry_home entry_java_home=''
        entry_home="$(config_value cli_client_home 9.0)"
        if [[ -n "${RECORD_CLI_HOME:-}" ]]; then
            cli_home="${RECORD_CLI_HOME}"
        elif [[ -n "${entry_home}" ]]; then
            cli_home="${entry_home}"
            entry_java_home="$(config_value cli_client_java_home 9.0)"
        fi
        java_home="${RECORD_CLI_JAVA_HOME:-${entry_java_home:-${java_home}}}"
    fi
}

# A local StartCLI run with one option (-h, -v): no server, no credentials, no stdin.
record_cli_local() {
    local cli_home java_home exit_code
    choose_cli
    [[ -x "${cli_home}/bin/startcli.sh" ]] || die "no bin/startcli.sh under cliHome '${cli_home}'"
    build_cli_env "${java_home}"
    set +e
    (cd "${cli_home}" && "${child_env[@]}" ./bin/startcli.sh "${local_option}" < /dev/null \
        > "${work_dir}/stdout" 2> "${work_dir}/stderr")
    exit_code=$?
    set -e
    mkdir -p "${fixture_dir}/cli"
    local prefix="${fixture_dir}/cli/${case_name}"
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
        --request "${method}" --header "${accept}" --max-time 120
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

    mkdir -p "${fixture_dir}/rest"
    local prefix="${fixture_dir}/rest/${case_name}"
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
    choose_cli
    trust_store="${RECORD_TRUSTSTORE:-$(config_value trust_store)}"
    [[ -x "${cli_home}/bin/startcli.sh" ]] || die "no bin/startcli.sh under cliHome '${cli_home}'"
    [[ -d "${java_home}" ]] || die "cliJavaHome '${java_home}' not found"
    if [[ -n "${trust_store}" && ! -f "${trust_store}" ]]; then
        die "trust store '${trust_store}' not found (set RECORD_TRUSTSTORE)"
    fi
    if [[ -n "${cli_url}" ]]; then
        url="${cli_url}"
    elif [[ "${INUBIT_LINE}" == 9.0 ]]; then
        # 9.0 has no SOAP servlet: StartCLI connects to the base URL (research D-3).
        url="$(config_value base_url "${stage}" "${server}")"
        url="${url%/}"
    else
        url="$(config_value base_url "${stage}" "${server}")/ibis/servlet/IBISSoapServlet"
    fi
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

    mkdir -p "${fixture_dir}/cli"
    local prefix="${fixture_dir}/cli/${case_name}"
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

# JKS for a file that starts with the JKS magic number, else PKCS12 (CliRunner.trustStoreType).
trust_store_type() {
    if [[ "$(head -c 4 "$1" | od -An -tx1 | tr -d ' \n')" == feedfeed ]]; then
        printf 'JKS'
    else
        printf 'PKCS12'
    fi
}

# An interactive StartCLI session of INUBIT 6.1 (research D-5) with the arguments and the trust
# handling of CliRunner.runSession (research D-8): stdin gets the secret line, the session
# commands and exit; no --execCommand, no REST step. The client options come from a local -h run.
record_cli_session() {
    local cli_home java_home trust_store base_url url help exit_code tool_options=''
    choose_cli
    trust_store="${RECORD_TRUSTSTORE:-$(config_value trust_store)}"
    [[ -x "${cli_home}/bin/startcli.sh" ]] || die "no bin/startcli.sh under cliHome '${cli_home}'"
    [[ -d "${java_home}" ]] || die "cliJavaHome '${java_home}' not found"
    if [[ -n "${trust_store}" && ! -f "${trust_store}" ]]; then
        die "trust store '${trust_store}' not found (set RECORD_TRUSTSTORE)"
    fi
    base_url="$(config_value base_url "${stage}" "${server}")"
    [[ -n "${base_url}" ]] || die "no baseUrl for ${stage}/${server} in ${CONFIG_FILE}"
    url="${base_url%/}/ibis/servlet/IBISSoapServlet"
    build_cli_env "${java_home}"
    help="$(cd "${cli_home}" && "${child_env[@]}" ./bin/startcli.sh -h < /dev/null 2>&1)" || true

    local -a tls_args=()
    if [[ -n "${trust_store}" && "${help}" == *--trustStoreFilePath* ]]; then
        tls_args+=(--trustStoreFilePath "${trust_store}")
    elif [[ -n "${trust_store}" ]]; then
        [[ "${trust_store}" != *[[:space:]]* ]] \
            || die "trust store path with whitespace cannot be passed in JAVA_TOOL_OPTIONS"
        tool_options="-Djavax.net.ssl.trustStore=${trust_store}"
        tool_options+=" -Djavax.net.ssl.trustStoreType=$(trust_store_type "${trust_store}")"
    fi
    if [[ "$(config_value disable_hostname_verification)" == true ]]; then
        [[ "${help}" == *--disableHostNameVerification* ]] \
            || die "the client has no --disableHostNameVerification (see v61_help)"
        tls_args+=(--disableHostNameVerification)
    fi
    build_cli_env "${java_home}" "${tool_options}"

    set +e
    (cd "${cli_home}" && printf '%s\n' "${password}" "${session[@]}" exit | "${child_env[@]}" \
        ./bin/startcli.sh -u "${username}" ${tls_args[@]+"${tls_args[@]}"} "${url}" \
        > "${work_dir}/stdout" 2> "${work_dir}/stderr")
    exit_code=$?
    set -e

    mkdir -p "${fixture_dir}/cli"
    local prefix="${fixture_dir}/cli/${case_name}"
    anonymize --format text "${work_dir}/stdout" "${prefix}.stdout"
    anonymize --format text "${work_dir}/stderr" "${prefix}.stderr"
    printf '%s\n' "${exit_code}" > "${prefix}.exit"
    outputs=("${prefix}.stdout" "${prefix}.stderr" "${prefix}.exit")
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

# 9.0 only (research D-10): sets inubit_version for the .origin file and refuses another line before
# anything is recorded. Every CLI case first asks the chosen client (choose_cli) for its
# "CLI <version>"; every case with a node then asks the node's /system/info for its Version, which
# the .origin names. The local runs (-h, -v) name the client's version.
detect_inubit_version() {
    local reply
    if [[ "${kind}" != rest ]]; then
        local cli_home java_home
        choose_cli
        [[ -x "${cli_home}/bin/startcli.sh" ]] \
            || die "no bin/startcli.sh under cliHome '${cli_home}'"
        build_cli_env "${java_home}"
        reply="$(cd "${cli_home}" && "${child_env[@]}" ./bin/startcli.sh -v < /dev/null 2>&1)" \
            || true
        require_v90 client 'CLI ([0-9]+(\.[0-9]+)+)' "${reply}"
    fi
    if [[ "${kind}" != cli_local ]]; then
        local base_url
        base_url="$(config_value base_url "${stage}" "${server}")"
        [[ -n "${base_url}" ]] || die "no baseUrl for ${stage}/${server} in ${CONFIG_FILE}"
        reply="$(rest_call basic "${base_url%/}/ibis/rest/system/info" --silent --show-error \
            --insecure --pinnedpubkey "${PINNED_PUBKEY}" --header "Accept: ${ACCEPT_XML}" \
            --max-time 30)" || die "version request (GET /ibis/rest/system/info) failed"
        require_v90 node 'name="Version" value="([0-9]+(\.[0-9]+)+)"' "${reply}"
    fi
}

# Sets inubit_version from the reply of the reporter (client or node); refuses another line.
require_v90() {
    local reporter="$1" pattern="$2" reply="$3"
    [[ "${reply}" =~ ${pattern} ]] || die "the ${reporter} reports no INUBIT version"
    inubit_version="${BASH_REMATCH[1]}"
    [[ "${inubit_version}" == 9.0.* ]] \
        || die "the ${reporter} reports INUBIT ${inubit_version}, not 9.0 (RECORD_INUBIT_LINE=9.0)"
}

# 9.0 only: the provenance of the recorded case next to its fixture files (research D-10).
write_origin() {
    local directory=cli
    if [[ "${kind}" == rest ]]; then
        directory=rest
    fi
    printf 'recorded %s %s\n' "$(date -u +%Y-%m-%d)" "${inubit_version}" \
        > "${fixture_dir}/${directory}/${case_name}.origin"
    outputs+=("${fixture_dir}/${directory}/${case_name}.origin")
}

main() {
    case "${INUBIT_LINE}" in
        8.1|9.0) ;;
        *) die "RECORD_INUBIT_LINE must be 8.1 (default) or 9.0, not '${INUBIT_LINE}'" ;;
    esac
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
    if [[ "${kind}" == rest || "${preflight_unknown_pid}" == yes \
        || ( "${INUBIT_LINE}" == 9.0 && "${kind}" != cli_local ) ]]; then
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

    if [[ "${INUBIT_LINE}" == 9.0 ]]; then
        detect_inubit_version
    fi
    outputs=()
    "record_${kind}"
    check_outputs
    if [[ "${INUBIT_LINE}" == 9.0 ]]; then
        write_origin
    fi
    info "${case_name}: written ${outputs[*]#"${PROJECT_DIR}/"}"
}

main "$@"

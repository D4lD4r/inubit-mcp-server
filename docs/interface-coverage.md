# Interface coverage

<!-- Generated from src/main/resources/coverage/interface-coverage.tsv by
InterfaceCoverageDocsTest; change the list, not this page. -->

Every documented entry of the INUBIT StartCLI and REST interfaces with its state on each
version line: `reachable` (a tool sends it, shown as tool/action), `covered` (a tool reads
the same facts through another entry), `planned` (not yet verified with a recording, with
what it waits for), `undocumented` (not documented by the vendor for this version) or
`excluded` (never sent, with the reason).

Source: vendor pages "Command Line Interface" (with subpages) and "REST API" (every endpoint page), INUBIT 8.1 and 9.0, retrieved 2026-11-12

## Counts

| State | 8.1 | 9.0 |
| --- | ---: | ---: |
| reachable | 42 | 41 |
| covered | 39 | 39 |
| planned | 103 | 102 |
| undocumented | 3 | 1 |
| excluded | 63 | 67 |
| all entries | 250 | 250 |

## A. Server state and settings

| Entry | Kind | Story | 8.1 | 9.0 |
| --- | --- | --- | --- | --- |
| CLI `license -td` | READ | US1 | covered `get_server_settings` | covered `get_server_settings` |
| CLI `license -ts` | READ | US1 | covered `get_server_settings` | covered `get_server_settings` |
| CLI `threads showNumberOfWorkflowThreads` | READ | - | excluded: needs the INUBIT root user | excluded: needs the INUBIT root user |
| CLI `privilegedWorkflows showThreadsPercentage` | READ | - | excluded: needs the INUBIT root user | excluded: needs the INUBIT root user |
| CLI `privilegedWorkflows showWorkflowList` | READ | - | excluded: needs the INUBIT root user | excluded: needs the INUBIT root user |
| CLI `dbconnectiontolerance show` | READ | US1 | reachable `get_server_settings/default` | reachable `get_server_settings/default` |
| CLI `date` | READ | US1 | reachable `get_server_settings/default` | reachable `get_server_settings/default` |
| CLI `getBlockingQueueSize` | READ | - | covered `get_health` | covered `get_health` |
| CLI `-sv` | READ | - | covered `get_health` | covered `get_health` |
| CLI `-pctmem` | READ | - | covered `get_health` | covered `get_health` |
| CLI `-maintenanceState` | READ | - | covered `get_health` | covered `get_health` |
| CLI `-c` | READ | - | covered `get_health` | covered `get_health` |
| CLI `uname` | READ | - | covered `get_health` | covered `get_health` |
| CLI `uptime` | READ | - | covered `get_health` | covered `get_health` |
| CLI `free -i` | READ | - | covered `get_health` | covered `get_health` |
| CLI `free -k` | READ | - | covered `get_health` | covered `get_health` |
| CLI `free -m` | READ | - | covered `get_health` | covered `get_health` |
| CLI `free -p` | READ | - | covered `get_health` | covered `get_health` |
| CLI `maintenance` | READ | - | covered `get_health` | covered `get_health` |
| CLI `maintenance -s` | READ | - | covered `get_health` | covered `get_health` |
| CLI `trace` | READ | - | covered `get_health` | covered `get_health` |
| CLI `maintenance on\|off` | WRITE | US4 | planned | planned |
| CLI `maintenance -eq true\|false` | WRITE | US4 | planned | planned |
| CLI `trace on\|off` | WRITE | US4 | planned | planned |
| CLI `trace -l` | WRITE | US4 | planned | planned |
| CLI `debug on\|off` | WRITE | US4 | planned | planned |
| CLI `triggerMidnightTask` | WRITE | US4 | planned | planned |
| CLI `threads setNumberOfWorkflowThreads` | WRITE | US6 | planned | planned |
| CLI `privilegedWorkflows setThreadsPercentage` | WRITE | US6 | planned | planned |
| CLI `dbconnectiontolerance set` | WRITE | US6 | planned | planned |
| CLI `cache mode` | WRITE | - | excluded: reserved for the vendor's support, which calls it dangerous | excluded: reserved for the vendor's support, which calls it dangerous |
| CLI `validateMigrationBackup` | READ | - | excluded: interactive only | excluded: interactive only |
| CLI `lls` | READ | - | excluded: lists the local directory of the StartCLI client | excluded: lists the local directory of the StartCLI client |
| CLI `trace --download` | EXPORT | - | reachable `download_server_log/default` | reachable `download_server_log/default` |
| REST `GET /healthcheck` | READ | - | reachable `get_health/default` | reachable `get_health/default` |
| REST `GET /metrics` | READ | - | reachable `get_health/default` | reachable `get_health/default` |
| REST `GET /system/info` | READ | - | reachable `get_health/default` | reachable `get_health/default` |
| REST `GET /ready` | READ | - | reachable `get_health/default` | reachable `get_health/default` |
| REST `GET /configurations` | READ | US1 | reachable `get_server_settings/default` | reachable `get_server_settings/default` |
| REST `GET /license` | READ | US1 | reachable `get_server_settings/default` | reachable `get_server_settings/default` |
| REST `POST /ready/{status}` | WRITE | US4 | planned | planned |
| REST `PUT /license` | WRITE | US6 | planned | planned |
| REST `PUT /configurations` | WRITE | US6 | planned | planned |
| REST `POST /configurations` | WRITE | US6 | planned | planned |

## B. Users and groups

| Entry | Kind | Story | 8.1 | 9.0 |
| --- | --- | --- | --- | --- |
| CLI `finger` | READ | US2 | reachable `read_users/users` | reachable `read_users/users` |
| CLI `user` | READ | US2 | reachable `read_users/users` | reachable `read_users/users` |
| CLI `-who` | READ | US2 | reachable `read_users/logins` | excluded: the 9.0 listing has no Toolset row, not even for StartCLI's own connection, so no login can be read |
| CLI `-nwho` | READ | US2 | covered `read_users` | covered `read_users` |
| CLI `msglist` | READ | US2 | planned, waits `L-17` | planned, waits `L-17` |
| CLI `msgread` | READ | US2 | planned, waits `L-17` | planned, waits `L-17` |
| CLI `create -cu` | WRITE | US7 | planned | planned |
| CLI `create -cug` | WRITE | US7 | planned | planned |
| CLI `create -cur` | WRITE | US7 | planned | planned |
| CLI `create --parentgroup` | WRITE | US7 | planned | planned |
| CLI `create --email` | WRITE | US7 | planned | planned |
| CLI `create --group` | WRITE | US7 | planned | planned |
| CLI `create --password` | WRITE | US7 | planned | planned |
| CLI `create --processrole` | WRITE | US7 | planned | planned |
| CLI `create --role` | WRITE | US7 | planned | planned |
| CLI `create --basedon` | WRITE | US7 | planned | planned |
| CLI `create -o` | WRITE | US7 | planned | planned |
| CLI `user --setGroup` | WRITE | US7 | planned | planned |
| CLI `user --setAdditionalGroups` | WRITE | US7 | planned | planned |
| CLI `user --addAdditionalGroups` | WRITE | US7 | planned | planned |
| CLI `user --removeAdditionalGroups` | WRITE | US7 | planned | planned |
| CLI `passwd` | WRITE | US7 | planned | planned |
| CLI `passwd --password` | WRITE | US7 | planned | planned |
| CLI `passwd --encryptedPassword` | WRITE | US7 | planned | planned |
| CLI `delete --deleteUser` | WRITE | US7 | planned | planned |
| CLI `delete --deleteUserGroup` | WRITE | US7 | planned | planned |
| CLI `import --importUserGroup` | WRITE | US7 | planned | planned |
| CLI `import --overwriteUserRoles` | WRITE | US7 | planned | planned |
| CLI `import --importLDAPDetails` | WRITE | US7 | planned | planned |
| CLI `import --syncLDAPUsers` | WRITE | US7 | planned | planned |
| CLI `msgsend -s` | WRITE | US7 | planned | planned |
| CLI `msgsend -f` | WRITE | US7 | planned | planned |
| CLI `msgdel` | WRITE | US7 | planned | planned |
| CLI `msgdel -a` | WRITE | US7 | planned | planned |
| REST `DELETE /user/users/{name}` | WRITE | US7 | planned | planned |
| REST `GET /user/users` | READ | US2 | reachable `read_users/users` | reachable `read_users/users` |

## C. Libraries, plug-ins, EDI rules, keys

| Entry | Kind | Story | 8.1 | 9.0 |
| --- | --- | --- | --- | --- |
| CLI `library --list` | READ | US3 | planned | planned |
| CLI `library --type` | READ | US3 | planned | planned |
| CLI `library --upload` | WRITE | US6 | planned | planned |
| CLI `library --delete` | WRITE | US6 | planned | planned |
| CLI `library --deleteAll` | WRITE | US6 | planned | planned |
| CLI `plugin --register` | WRITE | US6 | planned | planned |
| CLI `plugin --pluginName` | WRITE | US6 | planned | planned |
| CLI `plugin --pluginGroup` | WRITE | US6 | planned | planned |
| CLI `plugin --serverClass` | WRITE | US6 | planned | planned |
| CLI `plugin --clientClass` | WRITE | US6 | planned | planned |
| CLI `plugin --icon` | WRITE | US6 | planned | planned |
| CLI `plugin --deregister` | WRITE | US6 | planned | planned |
| REST `PUT /edirules` | WRITE | US6 | planned | planned |
| REST `POST /edirules` | WRITE | US6 | planned | planned |
| REST `DELETE /libraries/{name}` | WRITE | US6 | planned | planned |
| REST `PUT /libraries` | WRITE | US6 | planned | planned |
| REST `PUT /keys` | WRITE | US6 | planned | planned |

## D. Processes and run-time data

| Entry | Kind | Story | 8.1 | 9.0 |
| --- | --- | --- | --- | --- |
| CLI `ps -f` | READ | - | covered `find_processes` | covered `find_processes` |
| CLI `ps -a` | READ | - | covered `find_processes` | covered `find_processes` |
| CLI `ps -e` | READ | - | covered `find_processes` | covered `find_processes` |
| CLI `ps -q` | READ | - | covered `find_processes` | covered `find_processes` |
| CLI `ps -w` | READ | - | covered `find_processes` | covered `find_processes` |
| CLI `ps -l` | READ | - | covered `find_processes` | covered `find_processes` |
| CLI `ps -csv` | READ | - | covered `find_processes` | covered `find_processes` |
| CLI `-q` | READ | - | covered `find_processes` | covered `find_processes` |
| CLI `ps -c` | READ | - | covered `get_health` | covered `get_health` |
| CLI `-ne` | READ | - | covered `get_health` | covered `get_health` |
| CLI `-nx` | READ | - | covered `get_health` | covered `get_health` |
| CLI `-nw` | READ | - | covered `get_health` | covered `get_health` |
| CLI `-nq` | READ | - | covered `get_health` | covered `get_health` |
| CLI `ps -u` | READ | US3 | planned | planned |
| CLI `processErrorStart` | WRITE | - | reachable `restart_process/default` | reachable `restart_process/default` |
| CLI `kill` | WRITE | - | reachable `kill_process/default` | reachable `kill_process/default` |
| CLI `processdelete error` | WRITE | US4 | planned | planned |
| CLI `process listBlocked` | READ | US5 | planned | planned |
| CLI `process validateExport` | READ | US5 | planned | planned |
| CLI `process export` | WRITE | US5 | planned | planned |
| CLI `process export --excludeModel` | WRITE | US5 | planned | planned |
| CLI `process export --filterExpression` | WRITE | US5 | planned | planned |
| CLI `process export --filterExpression inFile` | WRITE | - | excluded: reads a file on the INUBIT host | excluded: reads a file on the INUBIT host |
| CLI `process export --filterProcessIds` | WRITE | - | excluded: reads a file on the INUBIT host | excluded: reads a file on the INUBIT host |
| CLI `process import` | WRITE | US5 | planned | planned |
| CLI `process resume` | WRITE | US5 | planned | planned |
| CLI `process deleteBlocked` | WRITE | US5 | planned | planned |
| REST `GET /log/{name}` | READ | - | covered `query_logs` | covered `query_logs` |
| REST `GET /log/processLog` | READ | - | excluded: the log processLog fails on INUBIT 8.1.17 (vendor side) | excluded: the log processLog fails on INUBIT 8.1.17 (vendor side) |
| REST `POST /log/{name}` | READ | - | reachable `query_logs/default` | reachable `query_logs/default` |
| REST `POST /log/processLog` | READ | - | excluded: the log processLog fails on INUBIT 8.1.17 (vendor side) | excluded: the log processLog fails on INUBIT 8.1.17 (vendor side) |

## E. Diagrams, modules, repository, tags

| Entry | Kind | Story | 8.1 | 9.0 |
| --- | --- | --- | --- | --- |
| CLI `export --exportWorkflowUser` | EXPORT | - | reachable `export_archives/default` | reachable `export_archives/default` |
| CLI `export --exportWorkflowType` | EXPORT | - | reachable `export_archives/default` | reachable `export_archives/default` |
| CLI `export --exportWorkflowGroup` | EXPORT | - | reachable `export_archives/default` | reachable `export_archives/default` |
| CLI `export --exportFile` | EXPORT | - | reachable `export_archives/default` | reachable `export_archives/default` |
| CLI `export --includeHistory` | EXPORT | - | reachable `get_inventory_item/default` | reachable `get_inventory_item/default` |
| CLI `export --exportModule` | EXPORT | - | reachable `export_artifacts/default` | reachable `export_artifacts/default` |
| CLI `export --exportModuleGroup` | EXPORT | - | reachable `export_artifacts/default` | reachable `export_artifacts/default` |
| CLI `export --exportModuleUser` | EXPORT | - | reachable `export_artifacts/default` | reachable `export_artifacts/default` |
| CLI `export --exportRepositoryPath` | EXPORT | - | reachable `export_artifacts/default` | reachable `export_artifacts/default` |
| CLI `export --exportTag` | EXPORT | - | reachable `deploy_release/default` | reachable `deploy_release/default` |
| CLI `export --exportWorkflowGroupExclude` | EXPORT | US3 | planned | planned |
| CLI `export --exportMetadata` | EXPORT | US3 | planned | planned |
| CLI `export --exportMetadataNames` | EXPORT | US3 | planned | planned |
| CLI `fetchXSLT1.0` | EXPORT | US3 | planned | planned |
| CLI `fetchXSLT1.0 --exportFilename` | EXPORT | US3 | planned | planned |
| CLI `import --importFile` | WRITE | - | reachable `import_artifacts/default` | reachable `import_artifacts/default` |
| CLI `import --importWorkflow` | WRITE | - | reachable `import_artifacts/default` | reachable `import_artifacts/default` |
| CLI `import --importWorkflowActive` | WRITE | - | reachable `import_artifacts/default` | reachable `import_artifacts/default` |
| CLI `import --importWorkflowInactive` | WRITE | - | reachable `import_artifacts/default` | reachable `import_artifacts/default` |
| CLI `import --importModule` | WRITE | - | reachable `import_artifacts/default` | reachable `import_artifacts/default` |
| CLI `import --importUser` | WRITE | - | reachable `import_artifacts/default` | reachable `import_artifacts/default` |
| CLI `import --importRepositoryPath` | WRITE | - | reachable `import_artifacts/default` | reachable `import_artifacts/default` |
| CLI `import --returnProtocol` | WRITE | - | reachable `import_artifacts/default` | reachable `import_artifacts/default` |
| CLI `import --importMetadata` | WRITE | US6 | planned | planned |
| CLI `deploy --deployWorkflowArchive` | EXPORT | - | covered `deploy_release` | covered `deploy_release` |
| CLI `deploy --deploySystemName` | EXPORT | - | covered `deploy_release` | covered `deploy_release` |
| CLI `deploy --deployConfiguration` | EXPORT | - | covered `deploy_release` | covered `deploy_release` |
| CLI `deploy --deployCreateProperties` | EXPORT | - | covered `deploy_release` | covered `deploy_release` |
| CLI `deploy --deployMergedProperties` | EXPORT | - | covered `deploy_release` | covered `deploy_release` |
| CLI `deploy --deployMergedArchive` | EXPORT | - | covered `deploy_release` | covered `deploy_release` |
| CLI `tag --tagMove` | WRITE | - | reachable `tag_artifacts/default` | reachable `tag_artifacts/default` |
| CLI `tag --tagWorkflowGroup` | WRITE | - | reachable `tag_artifacts/default` | reachable `tag_artifacts/default` |
| CLI `tag --tagWorkflowType` | WRITE | - | reachable `tag_artifacts/default` | reachable `tag_artifacts/default` |
| CLI `tag --tagUser` | WRITE | - | reachable `tag_artifacts/default` | reachable `tag_artifacts/default` |
| CLI `tag --tagSetActive` | WRITE | US4 | planned | planned |
| CLI `tag --tagRemoveActive` | WRITE | US4 | planned | planned |
| CLI `tag --tagDelete` | WRITE | US4 | planned | planned |
| CLI `tag --tagRemove` | WRITE | US4 | planned | planned |
| CLI `tag --tagRepositoryPath` | WRITE | US4 | planned | planned |
| CLI `tag --tagDiagram` | WRITE | - | excluded: ignored by INUBIT, tags every diagram of the owner | excluded: ignored by INUBIT, tags every diagram of the owner |
| CLI `tag --tagWorkflowGroupExclude` | WRITE | - | excluded: acts on every diagram group of the owner but the listed ones | excluded: acts on every diagram group of the owner but the listed ones |
| CLI `delete --deleteWorkflow` | WRITE | US6 | planned | planned |
| CLI `delete --deleteModule` | WRITE | US6 | planned | planned |
| CLI `rename --renameWorkflow` | WRITE | US6 | planned | planned |
| CLI `rename --renameModule` | WRITE | US6 | planned | planned |
| CLI `rename --renameMetadata` | WRITE | US6 | planned | planned |
| CLI `rename --renameFile` | WRITE | US6 | planned | planned |
| CLI `rename --renameDirectory` | WRITE | US6 | planned | planned |
| CLI `rename --renameTo` | WRITE | US6 | planned | planned |
| REST `GET /process/metadata/{name}` | READ | US3 | planned | planned |
| REST `DELETE /diagrams/{name}` | WRITE | US6 | planned | planned |
| REST `DELETE /diagrams/{name}/version/{version}` | WRITE | US6 | planned | planned |
| REST `DELETE /modules/{id}` | WRITE | US6 | planned | planned |
| REST `DELETE /modules` | WRITE | US6 | planned | planned |
| REST `DELETE /modules/{id}/version/{version}` | WRITE | US6 | planned | planned |
| REST `GET /model/models` | READ | - | reachable `list_inventory/default` | reachable `list_inventory/default` |
| REST `GET /model/modelByName/{name}` | READ | - | reachable `get_inventory_item/default` | reachable `get_inventory_item/default` |
| REST `GET /model/nodeByModel/{model}/{node}` | READ | US3 | planned | planned |
| REST `GET /model/export/{name}` | EXPORT | - | reachable `get_inventory_item/default` | reachable `get_inventory_item/default` |
| REST `GET /model/nodeByName/{diagram}/{module}` | READ | US3 | planned | undocumented |
| REST `PUT /model/import` | WRITE | - | covered `import_artifacts` | covered `import_artifacts` |
| REST `DELETE /repository` | WRITE | US6 | planned | planned |
| REST `GET /task/formProxyServlet` | READ | - | covered `export_archives` | covered `export_archives` |
| REST `GET /isconnector` | READ | US3 | planned | planned |
| REST `POST /isconnector` | WRITE | US6 | planned | planned |

## Portal functions

| Entry | Kind | Story | 8.1 | 9.0 |
| --- | --- | --- | --- | --- |
| REST `GET /process/search` | READ | - | excluded: portal function | excluded: portal function |
| REST `GET /process/processes` | READ | - | excluded: portal function | excluded: portal function |
| REST `GET /process/processes/{type}` | READ | - | excluded: portal function | excluded: portal function |
| REST `GET /process/processes/{type}/{group}` | READ | - | excluded: portal function | excluded: portal function |
| REST `GET /process/processes/{type}/{group}/{name}` | READ | - | excluded: portal function | excluded: portal function |
| REST `GET /process/processes/{type}/{group}/{name}/history` | READ | - | undocumented | excluded: portal function |
| REST `GET /process/tags/{tag}` | READ | - | undocumented | excluded: portal function |
| REST `GET /process/tree` | READ | - | undocumented | excluded: portal function |
| REST `GET /report/reports` | READ | - | excluded: portal function | excluded: portal function |
| REST `GET /report/reports/{folder}/{subfolder}` | READ | - | excluded: portal function | excluded: portal function |
| REST `GET /report/reportdata` | READ | - | excluded: portal function | excluded: portal function |
| REST `GET /report/reportdata/{id}` | READ | - | excluded: portal function | excluded: portal function |
| REST `PUT /report` | WRITE | - | excluded: portal function | excluded: portal function |
| REST `DELETE /task/tasks/{id}` | WRITE | - | excluded: portal function | excluded: portal function |
| REST `DELETE /task/tasks` | WRITE | - | excluded: portal function | excluded: portal function |
| REST `GET /task/adhoc/{user}` | READ | - | excluded: portal function | excluded: portal function |
| REST `GET /task/next` | READ | - | excluded: portal function | excluded: portal function |
| REST `GET /task/data/{id}` | READ | - | excluded: portal function | excluded: portal function |
| REST `GET /task/delegation/{id}` | READ | - | excluded: portal function | excluded: portal function |
| REST `GET /task/tasks/{id}` | READ | - | excluded: portal function | excluded: portal function |
| REST `GET /task/widgets/xslt` | READ | - | excluded: portal function | excluded: portal function |
| REST `GET /task/tasklist` | READ | - | excluded: portal function | excluded: portal function |
| REST `GET /task/taskfilter` | READ | - | excluded: portal function | excluded: portal function |
| REST `GET /task/tasks` | READ | - | excluded: portal function | excluded: portal function |
| REST `GET /task/units/{user}` | READ | - | excluded: portal function | excluded: portal function |
| REST `POST /task/adhoc/{user}/{id}` | WRITE | - | excluded: portal function | excluded: portal function |
| REST `POST /task/baseFilterTasks` | READ | - | excluded: portal function | excluded: portal function |
| REST `POST /task/filtered` | READ | - | excluded: portal function | excluded: portal function |
| REST `POST /task/submit/{id}` | WRITE | - | excluded: portal function | excluded: portal function |
| REST `POST /task/tasks/{id}` | READ | - | excluded: portal function | excluded: portal function |
| REST `POST /task/help` | READ | - | excluded: portal function | excluded: portal function |
| REST `POST /task/widgets/xslt` | READ | - | excluded: portal function | excluded: portal function |
| REST `PUT /task/tasks/{id}` | WRITE | - | excluded: portal function | excluded: portal function |
| REST `GET /webapp/{id}/resources/{resource}` | READ | - | excluded: portal function | excluded: portal function |
| REST `GET /webapp/standalone/webapps` | READ | - | excluded: portal function | excluded: portal function |
| REST `GET /webapp/widgets/jump/{widget}/{id}` | READ | - | excluded: portal function | excluded: portal function |
| REST `POST /webapp/session/instances` | WRITE | - | excluded: portal function | excluded: portal function |
| REST `POST /webapp/standalone/submit/{id}` | WRITE | - | excluded: portal function | excluded: portal function |
| REST `POST /webapp/standalone/view/{id}` | READ | - | excluded: portal function | excluded: portal function |
| REST `POST /webapp/{id}` | READ | - | excluded: portal function | excluded: portal function |

## Start options

| Entry | Kind | Story | 8.1 | 9.0 |
| --- | --- | --- | --- | --- |
| CLI `-v` | READ | - | reachable `get_health/default` | reachable `get_health/default` |
| CLI `-a` | READ | - | excluded: lists the network interfaces of the StartCLI client's host, not of the server | excluded: lists the network interfaces of the StartCLI client's host, not of the server |
| CLI `--debug` | WRITE | - | excluded: starts an interactive StartCLI session with the client trace; the switch is debug on\|off | excluded: starts an interactive StartCLI session with the client trace; the switch is debug on\|off |
| CLI `--encryptString` | READ | - | excluded: takes a secret on the command line and prints the result | excluded: takes a secret on the command line and prints the result |
| CLI `--backupFile` | WRITE | US6 | planned | planned |
| CLI `--backupRepository` | WRITE | US6 | planned | planned |
| CLI `backup.sh` | WRITE | - | excluded: offline script, needs a stopped process engine on the INUBIT host | excluded: offline script, needs a stopped process engine on the INUBIT host |
| CLI `restore.sh -f` | WRITE | - | excluded: offline script, needs a stopped process engine on the INUBIT host | excluded: offline script, needs a stopped process engine on the INUBIT host |
| CLI `restore.sh -r` | WRITE | - | excluded: offline script, needs a stopped process engine on the INUBIT host | excluded: offline script, needs a stopped process engine on the INUBIT host |
| CLI `restore.sh -a` | WRITE | - | excluded: offline script, needs a stopped process engine on the INUBIT host | excluded: offline script, needs a stopped process engine on the INUBIT host |
| CLI `restore.sh -le` | WRITE | - | excluded: offline script, needs a stopped process engine on the INUBIT host | excluded: offline script, needs a stopped process engine on the INUBIT host |
| CLI `restore.sh -ls` | WRITE | - | excluded: offline script, needs a stopped process engine on the INUBIT host | excluded: offline script, needs a stopped process engine on the INUBIT host |
| CLI `restore.sh -m` | WRITE | - | excluded: offline script, needs a stopped process engine on the INUBIT host | excluded: offline script, needs a stopped process engine on the INUBIT host |
| CLI `restore.sh -R` | WRITE | - | excluded: offline script, needs a stopped process engine on the INUBIT host | excluded: offline script, needs a stopped process engine on the INUBIT host |
| CLI `restore.sh -t` | WRITE | - | excluded: offline script, needs a stopped process engine on the INUBIT host | excluded: offline script, needs a stopped process engine on the INUBIT host |
| CLI `restore.sh -U` | WRITE | - | excluded: offline script, needs a stopped process engine on the INUBIT host | excluded: offline script, needs a stopped process engine on the INUBIT host |
| CLI `restore.sh -u` | WRITE | - | excluded: offline script, needs a stopped process engine on the INUBIT host | excluded: offline script, needs a stopped process engine on the INUBIT host |

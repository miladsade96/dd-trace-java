[ {
  "content" : {
    "duration" : ${content_duration},
    "error" : 0,
    "meta" : {
      "_dd.ci.itr.tests_skipped" : "true",
      "_dd.p.tid" : ${content_meta__dd_p_tid},
      "_dd.test.is_user_provided_service" : "true",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "ci.workspace_path" : ${content_meta_ci_workspace_path},
      "component" : "maven",
      "env" : "integration-test",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version},
      "span.kind" : "test_session_end",
      "test.code_coverage.backfilled" : "true",
      "test.code_coverage.enabled" : "true",
      "test.command" : "mvn -B test",
      "test.framework" : "junit5",
      "test.framework_version" : "5.9.2",
      "test.itr.tests_skipping.enabled" : "true",
      "test.itr.tests_skipping.type" : "test",
      "test.status" : "pass",
      "test.toolchain" : ${content_meta_test_toolchain},
      "test.type" : "test",
      "test_session.name" : "mvn -B test"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id},
      "test.code_coverage.lines_pct" : 67,
      "test.itr.tests_skipping.count" : 1
    },
    "name" : "maven.test_session",
    "resource" : "Maven Smoke Tests Project",
    "service" : "test-maven-service",
    "start" : ${content_start},
    "test_session_id" : ${content_test_session_id}
  },
  "type" : "test_session_end",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_2},
    "error" : 0,
    "meta" : {
      "_dd.ci.itr.tests_skipped" : "true",
      "_dd.p.tid" : ${content_meta__dd_p_tid_2},
      "_dd.test.is_user_provided_service" : "true",
      "ci.workspace_path" : ${content_meta_ci_workspace_path},
      "component" : "maven",
      "env" : "integration-test",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version},
      "span.kind" : "test_module_end",
      "test.code_coverage.backfilled" : "true",
      "test.code_coverage.enabled" : "true",
      "test.command" : "mvn -B test",
      "test.execution" : "maven-surefire-plugin:test:default-test",
      "test.framework" : "junit5",
      "test.framework_version" : "5.9.2",
      "test.itr.tests_skipping.enabled" : "true",
      "test.itr.tests_skipping.type" : "test",
      "test.module" : "Maven Smoke Tests Project maven-surefire-plugin default-test",
      "test.status" : "pass",
      "test.type" : "test",
      "test_session.name" : "mvn -B test"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_2},
      "test.code_coverage.lines_pct" : 67,
      "test.itr.tests_skipping.count" : 1
    },
    "name" : "maven.test_module",
    "resource" : "Maven Smoke Tests Project maven-surefire-plugin default-test",
    "service" : "test-maven-service",
    "start" : ${content_start_2},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id}
  },
  "type" : "test_module_end",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_3},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_3},
      "_dd.test.is_user_provided_service" : "true",
      "env" : "integration-test",
      "execution" : "default",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "plugin" : "jacoco-maven-plugin",
      "project" : "Maven Smoke Tests Project",
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version}
    },
    "metrics" : { },
    "name" : "Maven_Smoke_Tests_Project_jacoco_maven_plugin_default",
    "parent_id" : ${content_test_session_id},
    "resource" : "Maven_Smoke_Tests_Project_jacoco_maven_plugin_default",
    "service" : "test-maven-service",
    "span_id" : ${content_span_id},
    "start" : ${content_start_3},
    "trace_id" : ${content_test_session_id}
  },
  "type" : "span",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_4},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_4},
      "_dd.test.is_user_provided_service" : "true",
      "env" : "integration-test",
      "execution" : "default-compile",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "plugin" : "maven-compiler-plugin",
      "project" : "Maven Smoke Tests Project",
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version}
    },
    "metrics" : { },
    "name" : "Maven_Smoke_Tests_Project_maven_compiler_plugin_default_compile",
    "parent_id" : ${content_test_session_id},
    "resource" : "Maven_Smoke_Tests_Project_maven_compiler_plugin_default_compile",
    "service" : "test-maven-service",
    "span_id" : ${content_span_id_2},
    "start" : ${content_start_4},
    "trace_id" : ${content_test_session_id}
  },
  "type" : "span",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_5},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_5},
      "_dd.test.is_user_provided_service" : "true",
      "env" : "integration-test",
      "execution" : "default-testCompile",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "plugin" : "maven-compiler-plugin",
      "project" : "Maven Smoke Tests Project",
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version}
    },
    "metrics" : { },
    "name" : "Maven_Smoke_Tests_Project_maven_compiler_plugin_default_testCompile",
    "parent_id" : ${content_test_session_id},
    "resource" : "Maven_Smoke_Tests_Project_maven_compiler_plugin_default_testCompile",
    "service" : "test-maven-service",
    "span_id" : ${content_span_id_3},
    "start" : ${content_start_5},
    "trace_id" : ${content_test_session_id}
  },
  "type" : "span",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_6},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_6},
      "_dd.test.is_user_provided_service" : "true",
      "env" : "integration-test",
      "execution" : "default-resources",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "plugin" : "maven-resources-plugin",
      "project" : "Maven Smoke Tests Project",
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version}
    },
    "metrics" : { },
    "name" : "Maven_Smoke_Tests_Project_maven_resources_plugin_default_resources",
    "parent_id" : ${content_test_session_id},
    "resource" : "Maven_Smoke_Tests_Project_maven_resources_plugin_default_resources",
    "service" : "test-maven-service",
    "span_id" : ${content_span_id_4},
    "start" : ${content_start_6},
    "trace_id" : ${content_test_session_id}
  },
  "type" : "span",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_7},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_7},
      "_dd.test.is_user_provided_service" : "true",
      "env" : "integration-test",
      "execution" : "default-testResources",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "plugin" : "maven-resources-plugin",
      "project" : "Maven Smoke Tests Project",
      "runtime-id" : ${content_meta_runtime_id},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version}
    },
    "metrics" : { },
    "name" : "Maven_Smoke_Tests_Project_maven_resources_plugin_default_testResources",
    "parent_id" : ${content_test_session_id},
    "resource" : "Maven_Smoke_Tests_Project_maven_resources_plugin_default_testResources",
    "service" : "test-maven-service",
    "span_id" : ${content_span_id_5},
    "start" : ${content_start_7},
    "trace_id" : ${content_test_session_id}
  },
  "type" : "span",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_8},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_8},
      "_dd.test.is_user_provided_service" : "true",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "ci.workspace_path" : ${content_meta_ci_workspace_path},
      "component" : "junit",
      "env" : "integration-test",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "runtime-id" : ${content_meta_runtime_id_2},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version},
      "span.kind" : "test_suite_end",
      "test.framework" : "junit5",
      "test.framework_version" : "5.9.2",
      "test.module" : "Maven Smoke Tests Project maven-surefire-plugin default-test",
      "test.source.file" : "src/test/java/datadog/smoke/TestSucceed.java",
      "test.status" : "pass",
      "test.suite" : "datadog.smoke.TestSucceed",
      "test.type" : "test",
      "test_session.name" : "mvn -B test"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_3},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id_2},
      "test.source.end" : 18,
      "test.source.start" : 7
    },
    "name" : "junit.test_suite",
    "resource" : "datadog.smoke.TestSucceed",
    "service" : "test-maven-service",
    "start" : ${content_start_8},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id},
    "test_suite_id" : ${content_test_suite_id}
  },
  "type" : "test_suite_end",
  "version" : 1
}, {
  "content" : {
    "duration" : ${content_duration_9},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_9},
      "_dd.test.is_user_provided_service" : "true",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "ci.workspace_path" : ${content_meta_ci_workspace_path},
      "component" : "junit",
      "env" : "integration-test",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "runtime-id" : ${content_meta_runtime_id_2},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version},
      "span.kind" : "test",
      "test.framework" : "junit5",
      "test.framework_version" : "5.9.2",
      "test.module" : "Maven Smoke Tests Project maven-surefire-plugin default-test",
      "test.name" : "test_succeed",
      "test.source.file" : "src/test/java/datadog/smoke/TestSucceed.java",
      "test.source.method" : "test_succeed()V",
      "test.status" : "pass",
      "test.suite" : "datadog.smoke.TestSucceed",
      "test.type" : "test",
      "test_session.name" : "mvn -B test"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_4},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id_2},
      "test.source.end" : 12,
      "test.source.start" : 9
    },
    "name" : "junit.test",
    "parent_id" : ${content_parent_id},
    "resource" : "datadog.smoke.TestSucceed.test_succeed",
    "service" : "test-maven-service",
    "span_id" : ${content_span_id_6},
    "start" : ${content_start_9},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id},
    "test_suite_id" : ${content_test_suite_id},
    "trace_id" : ${content_trace_id}
  },
  "type" : "test",
  "version" : 2
}, {
  "content" : {
    "duration" : ${content_duration_10},
    "error" : 0,
    "meta" : {
      "_dd.p.tid" : ${content_meta__dd_p_tid_10},
      "_dd.test.is_user_provided_service" : "true",
      "_dd.tracer_host" : ${content_meta__dd_tracer_host},
      "ci.workspace_path" : ${content_meta_ci_workspace_path},
      "component" : "junit",
      "env" : "integration-test",
      "language" : "jvm",
      "library_version" : ${content_meta_library_version},
      "os.architecture" : ${content_meta_os_architecture},
      "os.platform" : ${content_meta_os_platform},
      "os.version" : ${content_meta_os_version},
      "runtime-id" : ${content_meta_runtime_id_2},
      "runtime.name" : ${content_meta_runtime_name},
      "runtime.vendor" : ${content_meta_runtime_vendor},
      "runtime.version" : ${content_meta_runtime_version},
      "span.kind" : "test",
      "test.framework" : "junit5",
      "test.framework_version" : "5.9.2",
      "test.module" : "Maven Smoke Tests Project maven-surefire-plugin default-test",
      "test.name" : "test_to_skip_with_itr",
      "test.skip_reason" : "Skipped by Datadog Intelligent Test Runner",
      "test.skipped_by_itr" : "true",
      "test.source.file" : "src/test/java/datadog/smoke/TestSucceed.java",
      "test.source.method" : "test_to_skip_with_itr()V",
      "test.status" : "skip",
      "test.suite" : "datadog.smoke.TestSucceed",
      "test.type" : "test",
      "test_session.name" : "mvn -B test"
    },
    "metrics" : {
      "_dd.host.vcpu_count" : ${content_metrics__dd_host_vcpu_count_5},
      "_dd.profiling.enabled" : 0,
      "_dd.trace_span_attribute_schema" : 0,
      "process_id" : ${content_metrics_process_id_2},
      "test.source.end" : 17,
      "test.source.start" : 14
    },
    "name" : "junit.test",
    "parent_id" : ${content_parent_id},
    "resource" : "datadog.smoke.TestSucceed.test_to_skip_with_itr",
    "service" : "test-maven-service",
    "span_id" : ${content_span_id_7},
    "start" : ${content_start_10},
    "test_module_id" : ${content_test_module_id},
    "test_session_id" : ${content_test_session_id},
    "test_suite_id" : ${content_test_suite_id},
    "trace_id" : ${content_trace_id_2}
  },
  "type" : "test",
  "version" : 2
} ]
package com.datadog.appsec.powerwaf

import com.datadog.appsec.AppSecModule
import com.datadog.appsec.config.AppSecConfig
import com.datadog.appsec.config.AppSecData
import com.datadog.appsec.config.AppSecModuleConfigurer
import com.datadog.appsec.config.AppSecUserConfig
import com.datadog.appsec.config.CurrentAppSecConfig
import com.datadog.appsec.config.TraceSegmentPostProcessor
import com.datadog.appsec.event.ChangeableFlow
import com.datadog.appsec.event.DataListener
import com.datadog.appsec.event.data.Address
import com.datadog.appsec.event.data.CaseInsensitiveMap
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.MapDataBundle
import com.datadog.appsec.gateway.AppSecRequestContext
import com.datadog.appsec.gateway.GatewayContext
import com.datadog.appsec.report.AppSecEvent
import datadog.trace.api.telemetry.RuleType
import datadog.trace.util.stacktrace.StackTraceEvent
import com.datadog.appsec.test.StubAppSecConfigService
import datadog.communication.monitor.Monitoring
import datadog.trace.api.ConfigDefaults
import datadog.trace.api.internal.TraceSegment
import datadog.appsec.api.blocking.BlockingContentType
import datadog.trace.api.gateway.Flow
import datadog.trace.api.telemetry.WafMetricCollector
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification
import io.sqreen.powerwaf.Additive
import io.sqreen.powerwaf.Powerwaf
import io.sqreen.powerwaf.PowerwafContext
import io.sqreen.powerwaf.PowerwafMetrics
import spock.lang.Shared
import spock.lang.Unroll

import java.util.concurrent.CountDownLatch

import static datadog.trace.api.config.AppSecConfig.APPSEC_OBFUSCATION_PARAMETER_KEY_REGEXP
import static datadog.trace.api.config.AppSecConfig.APPSEC_OBFUSCATION_PARAMETER_VALUE_REGEXP
import static org.hamcrest.Matchers.hasSize

class PowerWAFModuleSpecification extends DDSpecification {
  @Shared
  protected static final AgentTracer.TracerAPI ORIGINAL_TRACER = AgentTracer.get()

  @Shared
  protected static final ORIGINAL_METRIC_COLLECTOR = WafMetricCollector.get()

  private static final DataBundle ATTACK_BUNDLE = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
  new CaseInsensitiveMap<List<String>>(['user-agent': 'Arachni/v0']))

  protected AgentTracer.TracerAPI tracer = Mock(AgentTracer.TracerAPI) {
    activeSpan() >> Mock(AgentSpan) {
      getSpanId() >> 777
      getLocalRootSpan() >> Mock(AgentSpan)
    }
    getSpanId() >> 777
  }

  AppSecRequestContext ctx = Spy()
  GatewayContext gwCtx = new GatewayContext(false)

  StubAppSecConfigService service
  PowerWAFModule pwafModule = new PowerWAFModule()
  DataListener dataListener

  Additive pwafAdditive
  PowerwafMetrics metrics

  WafMetricCollector wafMetricCollector = Mock(WafMetricCollector)

  void setup() {
    WafMetricCollector.INSTANCE = wafMetricCollector
    AgentTracer.forceRegister(tracer)
  }

  void cleanup() {
    WafMetricCollector.INSTANCE  = ORIGINAL_METRIC_COLLECTOR
    AgentTracer.forceRegister(ORIGINAL_TRACER)
    pwafAdditive?.close()
    release pwafModule
  }

  private static void release(PowerWAFModule pwafModule) {
    pwafModule?.ctxAndAddresses?.get()?.ctx?.close()
  }

  private void setupWithStubConfigService(String location = "test_multi_config.json") {
    service = new StubAppSecConfigService(location)
    service.init()
    pwafModule.config(service)
    dataListener = pwafModule.dataSubscriptions.first()
  }

  void 'use default actions if none defined in config'() {
    when:
    setupWithStubConfigService'no_actions_config.json'

    then:
    pwafModule.ctxAndAddresses.get().actionInfoMap.size() == 1
    pwafModule.ctxAndAddresses.get().actionInfoMap.get('block') != null
    pwafModule.ctxAndAddresses.get().actionInfoMap.get('block').parameters == [
      status_code: 403,
      type:'auto',
      grpc_status_code: 10
    ]
  }

  void 'override default actions by config'() {
    when:
    setupWithStubConfigService('override_actions_config.json')

    then:
    pwafModule.ctxAndAddresses.get().actionInfoMap.size() == 1
    pwafModule.ctxAndAddresses.get().actionInfoMap.get('block') != null
    pwafModule.ctxAndAddresses.get().actionInfoMap.get('block').parameters == [
      status_code: 500,
      type:'html',
    ]
  }

  void 'override actions through reconfiguration'() {
    when:
    setupWithStubConfigService('override_actions_config.json')

    def actions = [
      [
        id: 'block',
        type: 'block_request',
        parameters: [
          status_code: 501,
          type: 'json'
        ]
      ]
    ]
    AppSecModuleConfigurer.Reconfiguration reconf = Stub()
    service.currentAppSecConfig.with {
      def dirtyStatus = userConfigs.addConfig(
        new AppSecUserConfig('b', [], actions, [], []))
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }

    then:
    pwafModule.ctxAndAddresses.get().actionInfoMap.size() == 1
    pwafModule.ctxAndAddresses.get().actionInfoMap.get('block') != null
    pwafModule.ctxAndAddresses.get().actionInfoMap.get('block').parameters == [
      status_code: 501,
      type: 'json',
    ]
  }

  void 'override on_match through reconfiguration'() {
    ChangeableFlow flow = Mock()
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()

    when:
    setupWithStubConfigService('override_actions_config.json')
    dataListener = pwafModule.dataSubscriptions.first()

    def actions = [
      [
        id: 'block2',
        type: 'block_request',
        parameters: [
          status_code: 501,
          type: 'json'
        ]
      ]
    ]
    def ruleOverrides = [
      [
        rules_target: [[
            rule_id: 'ip_match_rule',
          ],],
        on_match: ['block2']
      ]
    ]
    def ipData = new AppSecData(rules: [
      [
        id  : 'ip_data',
        type: 'ip_with_expiration',
        data: [[
            value     : '1.2.3.4',
            expiration: '0',
          ]]
      ]
    ])
    service.currentAppSecConfig.with {
      def dirtyStatus = userConfigs.addConfig(
        new AppSecUserConfig('b', ruleOverrides, actions, [], []))
      mergedAsmData.addConfig('c', ipData)
      it.dirtyStatus.data = true
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }
    def newBundle = MapDataBundle.of(
      KnownAddresses.REQUEST_INFERRED_CLIENT_IP,
      '1.2.3.4'
      )
    dataListener.onDataAvailable(flow, ctx, newBundle, gwCtx)
    ctx.closeAdditive()

    then:
    1 * wafMetricCollector.wafInit(Powerwaf.LIB_VERSION, _, true)
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    1 * flow.setAction({ Flow.Action.RequestBlockingAction rba ->
      rba.statusCode == 501 &&
        rba.blockingContentType == BlockingContentType.JSON
    })
    1 * ctx.getOrCreateAdditive(_ as PowerwafContext, true, false) >> {
      pwafAdditive = it[0].openAdditive()
    }
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.getWafMetrics()
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive()
    1 * flow.isBlocking()
    1 * ctx.isThrottled(null)
    0 * _
  }

  void 'provide data through the initial config'() {
    ChangeableFlow flow = Mock()
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()

    when:
    setupWithStubConfigService('rules_with_data_config.json')
    dataListener = pwafModule.dataSubscriptions.first()
    ctx.closeAdditive()

    def bundle = MapDataBundle.of(
      KnownAddresses.USER_ID,
      'user-to-block-1'
      )
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeAdditive()

    then:
    1 * wafMetricCollector.wafInit(Powerwaf.LIB_VERSION, _, true)
    1 * flow.setAction({ Flow.Action.RequestBlockingAction rba ->
      rba.statusCode == 403 &&
        rba.blockingContentType == BlockingContentType.AUTO
    })
    1 * ctx.getOrCreateAdditive(_ as PowerwafContext, true, false) >> {
      pwafAdditive = it[0].openAdditive()
    }
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.getWafMetrics()
    1 * ctx.isAdditiveClosed() >> false
    2 * ctx.closeAdditive()
    1 * flow.isBlocking()
    1 * ctx.isThrottled(null)
    0 * _

    when: 'merges new waf data with the one in the rules config'
    def newData = new AppSecData(rules: [
      [
        id  : 'blocked_users',
        type: 'data_with_expiration',
        data: [
          [
            value     : 'user-to-block-2',
            expiration: '0',
          ]
        ]
      ]
    ])
    service.currentAppSecConfig.with {
      mergedAsmData.addConfig('c', newData)
      it.dirtyStatus.data = true

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }

    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeAdditive()

    then:
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    1 * flow.setAction({ Flow.Action.RequestBlockingAction rba ->
      rba.statusCode == 403 &&
        rba.blockingContentType == BlockingContentType.AUTO
    })
    1 * ctx.getOrCreateAdditive(_ as PowerwafContext, true, false) >> {
      pwafAdditive = it[0].openAdditive()
    }
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.getWafMetrics()
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive()
    1 * flow.isBlocking()
    1 * ctx.isThrottled(null)
    0 * _

    when:
    bundle = MapDataBundle.of(
      KnownAddresses.USER_ID,
      'user-to-block-2'
      )
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeAdditive()

    then:
    1 * flow.setAction({ Flow.Action.RequestBlockingAction rba ->
      rba.statusCode == 403 &&
        rba.blockingContentType == BlockingContentType.AUTO
    })
    1 * ctx.getOrCreateAdditive(_ as PowerwafContext, true, false) >> {
      pwafAdditive = it[0].openAdditive()
    }
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.getWafMetrics()
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive()
    1 * flow.isBlocking()
    1 * ctx.isThrottled(null)
    0 * _

    when: 'changes the rules config'
    def newCfg = [
      version: '2.1',
      rules: [
        [
          id: 'block-users',
          name: 'Block User Addresses',
          tags: [
            type: 'block_user',
            category: 'security_response'
          ],
          conditions: [
            [
              parameters: [
                inputs: [[ address: 'usr.id' ]],
                data: 'blocked_users'
              ],
              operator: 'exact_match'
            ]
          ],
          on_match: ['block'] ]
      ], ]

    service.currentAppSecConfig.with {
      setDdConfig(AppSecConfig.valueOf(newCfg))
      dirtyStatus.markAllDirty()

      service.listeners['waf'].onNewSubconfig(it, reconf)
      dirtyStatus.clearDirty()
    }

    and:
    bundle = MapDataBundle.of(
      KnownAddresses.USER_ID,
      'user-to-block-2'
      )
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeAdditive()

    then:
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    1 * flow.setAction({ Flow.Action.RequestBlockingAction rba ->
      rba.statusCode == 403 &&
        rba.blockingContentType == BlockingContentType.AUTO
    })
    1 * ctx.getOrCreateAdditive(_ as PowerwafContext, true, false) >> {
      pwafAdditive = it[0].openAdditive()
    }
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.getWafMetrics()
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive()
    1 * flow.isBlocking()
    1 * ctx.isThrottled(null)
    0 * _

    when:
    bundle = MapDataBundle.of(
      KnownAddresses.USER_ID,
      'user-to-block-1'
      )
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeAdditive()

    then:
    1 * ctx.getOrCreateAdditive(_ as PowerwafContext, true, false) >> {
      pwafAdditive = it[0].openAdditive()
    }
    1 * ctx.getWafMetrics()
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive()
    0 * _
  }

  void 'add exclusions through reconfiguration'() {
    ChangeableFlow flow = new ChangeableFlow()
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()

    when:
    setupWithStubConfigService()

    def exclusions = [
      [
        id          : '1',
        rules_target: [
          [
            tags: [
              type    : 'security_scanner',
            ]
          ]
        ],
        conditions  : [
          [
            operator  : 'exact_match',
            parameters: [
              inputs: [[
                  address: 'http.client_ip'
                ]],
              list  : ['192.168.0.1']
            ]
          ]
        ]
      ]
    ]

    service.currentAppSecConfig.with {
      def dirtyStatus = userConfigs.addConfig(
        new AppSecUserConfig('b', [], [], exclusions, []))
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }

    then:
    1 * wafMetricCollector.wafInit(Powerwaf.LIB_VERSION, _, true)
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    0 * _

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeAdditive()

    then:
    1 * ctx.getOrCreateAdditive(_, true, false) >> {
      pwafAdditive = it[0].openAdditive()
    }
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.getWafMetrics()
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive() >> { pwafAdditive.close() }
    1 * ctx.setBlocked()
    1 * ctx.isThrottled(null)
    0 * _

    when:
    def newBundle = MapDataBundle.of(
      KnownAddresses.HEADERS_NO_COOKIES,
      new CaseInsensitiveMap<List<String>>(['user-agent': 'Arachni/v0']),
      KnownAddresses.REQUEST_INFERRED_CLIENT_IP,
      '192.168.0.1'
      )
    dataListener.onDataAvailable(flow, ctx, newBundle, gwCtx)
    ctx.closeAdditive()

    then:
    1 * ctx.getOrCreateAdditive(_, true, false) >> {
      pwafAdditive = it[0].openAdditive()
    }
    1 * ctx.getWafMetrics()
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive()
    0 * _
  }

  void 'add custom rule through reconfiguration'() {
    ChangeableFlow flow = new ChangeableFlow()
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()

    when:
    setupWithStubConfigService()

    def customRules = [
      [
        id: 'ua0-600-12x-copy',
        name: 'Arachni',
        tags: [
          category: 'attack_attempt',
          type: 'security_scanner2'
        ],
        conditions: [
          [
            operator: 'match_regex',
            parameters: [
              inputs: [
                [
                  address: 'server.request.headers.no_cookies',
                  key_path:['user-agent']
                ]
              ],
              regex: '^Arachni/v'
            ]
          ]
        ],
        on_match: ['block']
      ]
    ]

    service.currentAppSecConfig.with {
      def dirtyStatus = userConfigs.addConfig(
        new AppSecUserConfig('b', [], [], [], customRules))
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }

    then:
    1 * wafMetricCollector.wafInit(Powerwaf.LIB_VERSION, _, true)
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    0 * _

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeAdditive()

    then:
    1 * ctx.getOrCreateAdditive(_, true, false) >> {
      pwafAdditive = it[0].openAdditive()
    }
    3 * tracer.activeSpan()
    // we get two events: one for origin rule, and one for the custom one
    1 * ctx.reportEvents(hasSize(2))
    1 * ctx.getWafMetrics()
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive()
    1 * ctx.setBlocked()
    1 * ctx.isThrottled(null)
    0 * _
  }

  void 'append actions in addition to default'() {
    when:
    PowerWAFModule powerWAFModule = new PowerWAFModule()
    StubAppSecConfigService confService = new StubAppSecConfigService("another_actions_config.json")
    confService.init()
    powerWAFModule.config(confService)

    then:
    powerWAFModule.ctxAndAddresses.get().actionInfoMap.size() == 2
    powerWAFModule.ctxAndAddresses.get().actionInfoMap.get('block') != null
    powerWAFModule.ctxAndAddresses.get().actionInfoMap.get('block').parameters == [
      status_code: 403,
      type:'auto',
      grpc_status_code: 10
    ]
    powerWAFModule.ctxAndAddresses.get().actionInfoMap.get('test') != null
    powerWAFModule.ctxAndAddresses.get().actionInfoMap.get('test').parameters == [
      status_code: 302,
      type:'xxx'
    ]

    cleanup:
    release powerWAFModule
  }

  void 'replace actions through runtime configuration'() {
    ChangeableFlow flow = Mock()
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()

    when:
    setupWithStubConfigService()
    // first initialization to exercise the update path
    service.listeners['waf'].onNewSubconfig(service.currentAppSecConfig, reconf)
    service.currentAppSecConfig.dirtyStatus.clearDirty()

    def actions = [
      [
        id: 'block',
        type: 'block_request',
        parameters: [
          status_code: 401,
        ]
      ]
    ]
    service.currentAppSecConfig.with {
      def dirtyStatus = userConfigs.addConfig(
        new AppSecUserConfig('new config', [], actions, [], []))
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }

    then:
    1 * wafMetricCollector.wafInit(Powerwaf.LIB_VERSION, _, true)
    2 * wafMetricCollector.wafUpdates(_, true)
    2 * reconf.reloadSubscriptions()
    0 * _

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeAdditive()

    then:
    // original rule is replaced; no attack
    1 * flow.setAction({ Flow.Action.RequestBlockingAction rba ->
      rba.statusCode == 401 &&
        rba.blockingContentType == BlockingContentType.AUTO
    })
    1 * ctx.getOrCreateAdditive(_, true, false) >> { it[0].openAdditive() }
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.getWafMetrics()
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive()
    1 * flow.isBlocking()
    1 * ctx.isThrottled(null)
    0 * _
  }

  void 'redirect actions are correctly processed expected variant redirect#variant'(int variant, int statusCode) {
    when:
    setupWithStubConfigService('redirect_actions.json')
    DataBundle bundle = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
      new CaseInsensitiveMap<List<String>>(['user-agent': 'redirect' + variant]))
    def flow = new ChangeableFlow()
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeAdditive()

    then:
    1 * ctx.getOrCreateAdditive(_, true, false) >> {
      PowerwafContext pwCtx = it[0] as PowerwafContext
      pwafAdditive = pwCtx.openAdditive()
      metrics = pwCtx.createMetrics()
      pwafAdditive
    }
    1 * ctx.getWafMetrics() >> metrics
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive()
    1 * ctx.reportEvents(_)
    1 * ctx.setBlocked()
    1 * ctx.isThrottled(null)
    0 * ctx._(*_)
    flow.blocking == true
    flow.action instanceof Flow.Action.RequestBlockingAction
    with(flow.action as Flow.Action.RequestBlockingAction) {
      assert it.statusCode == statusCode
      assert it.extraHeaders == [Location: "https://example${variant}.com/"]
    }

    where:
    variant | statusCode
    1       | 303
    2       | 301
    3       | 303
  }

  void 'is named powerwaf'() {
    expect:
    pwafModule.name == 'powerwaf'
  }

  void 'report waf stats on first span'() {
    setup:
    TraceSegment segment = Mock()
    TraceSegmentPostProcessor pp

    when:
    setupWithStubConfigService()
    pp = service.traceSegmentPostProcessors.first()
    pp.processTraceSegment(segment, ctx, [])

    then:
    1 * segment.setTagTop('_dd.appsec.waf.version', _ as String)
    1 * segment.setTagTop('_dd.appsec.event_rules.loaded', 116)
    1 * segment.setTagTop('_dd.appsec.event_rules.error_count', 1)
    1 * segment.setTagTop('_dd.appsec.event_rules.errors', { it =~ /\{"[^"]+":\["bad rule"\]\}/})
    1 * segment.setTagTop('asm.keep', true)
    0 * segment._(*_)

    when:
    pp.processTraceSegment(segment, ctx, [])

    then:
    0 * segment._(*_)
  }

  void 'triggers a rule through the user agent header'() {
    setupWithStubConfigService()
    ChangeableFlow flow = new ChangeableFlow()

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeAdditive()

    then:
    1 * ctx.getOrCreateAdditive(_, true, false) >> {
      PowerwafContext pwCtx = it[0] as PowerwafContext
      pwafAdditive = pwCtx.openAdditive()
      metrics = pwCtx.createMetrics()
      pwafAdditive
    }
    1 * ctx.getWafMetrics() >> metrics
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive()
    1 * ctx.reportEvents(_)
    1 * ctx.setBlocked()
    1 * ctx.isThrottled(null)
    0 * ctx._(*_)
    flow.blocking == true
    flow.action.statusCode == 418
    flow.action.blockingContentType == BlockingContentType.HTML
  }

  void 'no metrics are set if waf metrics are off'() {
    setup:
    injectSysConfig('appsec.waf.metrics', 'false')
    pwafModule = new PowerWAFModule() // replace the one created too soon
    setupWithStubConfigService()
    ChangeableFlow flow = new ChangeableFlow()

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeAdditive()

    then:
    1 * ctx.getOrCreateAdditive(_, false, false) >> {
      pwafAdditive = it[0].openAdditive()
    }
    1 * ctx.getWafMetrics() >> null
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive()
    1 * ctx.reportEvents(_)
    1 * ctx.setBlocked()
    1 * ctx.isThrottled(null)
    0 * ctx._(*_)
    metrics == null
  }

  void 'reports waf metrics'() {
    setup:
    TraceSegment segment = Mock()
    TraceSegmentPostProcessor pp
    Flow flow = new ChangeableFlow()

    when:
    setupWithStubConfigService()
    pp = service.traceSegmentPostProcessors[1]
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeAdditive()
    pp.processTraceSegment(segment, ctx, [])

    then:
    1 * ctx.getOrCreateAdditive(_, true, false) >> {
      PowerwafContext pwCtx = it[0] as PowerwafContext
      pwafAdditive = pwCtx.openAdditive()
      metrics = pwCtx.createMetrics()
      pwafAdditive
    }
    1 * ctx.closeAdditive()
    2 * ctx.getWafMetrics() >> { metrics.with { totalDdwafRunTimeNs = 1000; totalRunTimeNs = 2000; it} }

    1 * segment.setTagTop('_dd.appsec.waf.duration', 1)
    1 * segment.setTagTop('_dd.appsec.waf.duration_ext', 2)
    1 * segment.setTagTop('_dd.appsec.event_rules.version', '0.42.0')

    0 * segment._(*_)
  }

  void 'can trigger a nonadditive waf run'() {
    setupWithStubConfigService()
    ChangeableFlow flow = new ChangeableFlow()

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)

    then:
    1 * ctx.getOrCreateAdditive(_, true, false) >> {
      PowerwafContext pwCtx = it[0] as PowerwafContext
      pwafAdditive = pwCtx.openAdditive()
      metrics = pwCtx.createMetrics()
      pwafAdditive
    }
    1 * ctx.getWafMetrics() >> metrics
    1 * ctx.reportEvents(*_)
    1 * ctx.setBlocked()
    1 * ctx.isThrottled(null)
    1 * ctx.isAdditiveClosed() >> false
    0 * ctx._(*_)
    flow.blocking == true
  }

  void 'reports events'() {
    setup:
    setupWithStubConfigService()
    AppSecEvent event
    StackTraceEvent stackTrace
    pwafModule = new PowerWAFModule() // replace the one created too soon
    def attackBundle = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
      new CaseInsensitiveMap<List<String>>(['user-agent': 'Arachni/generate-stacktrace']))

    when:
    dataListener.onDataAvailable(Stub(ChangeableFlow), ctx, attackBundle, gwCtx)
    ctx.closeAdditive()

    then:
    ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    ctx.reportEvents(_ as Collection<AppSecEvent>) >> { event = it[0].iterator().next() }
    ctx.reportStackTrace(_ as StackTraceEvent) >> { stackTrace = it[0] }

    event.rule.id == 'generate-stacktrace-on-scanner'
    event.rule.name == 'Arachni'
    event.rule.tags == [type: 'security_scanner', category: 'attack_attempt']

    event.ruleMatches[0].operator == 'match_regex'
    event.ruleMatches[0].operator_value == '^Arachni\\/generate-stacktrace'
    event.ruleMatches[0].parameters[0].address == 'server.request.headers.no_cookies'
    event.ruleMatches[0].parameters[0].highlight == ['Arachni/generate-stacktrace']
    event.ruleMatches[0].parameters[0].key_path == ['user-agent']
    event.ruleMatches[0].parameters[0].value == 'Arachni/generate-stacktrace'

    event.spanId == 777

    stackTrace.language == 'java'
    stackTrace.message == 'Exploit detected'
    stackTrace.frames.size() >= 1
    stackTrace.frames[0].class_name == 'org.codehaus.groovy.runtime.callsite.CallSiteArray'
    stackTrace.frames[0].function == 'defaultCall'

  }

  void 'redaction with default settings'() {
    setupWithStubConfigService()
    AppSecEvent event

    when:
    def bundle = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
      new CaseInsensitiveMap<List<String>>(['user-agent': [password: 'Arachni/v0']]))
    dataListener.onDataAvailable(Stub(ChangeableFlow), ctx, bundle, gwCtx)
    ctx.closeAdditive()

    then:
    ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    ctx.reportEvents(_ as Collection<AppSecEvent>) >> { event = it[0].iterator().next() }

    event.ruleMatches[0].parameters[0].address == 'server.request.headers.no_cookies'
    event.ruleMatches[0].parameters[0].highlight == ['<Redacted>']
    event.ruleMatches[0].parameters[0].key_path == ['user-agent', 'password']
    event.ruleMatches[0].parameters[0].value == '<Redacted>'
  }

  void 'disabling of key regex'() {
    injectSysConfig(APPSEC_OBFUSCATION_PARAMETER_KEY_REGEXP, '')
    setupWithStubConfigService()
    AppSecEvent event

    when:
    def bundle = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
      new CaseInsensitiveMap<List<String>>(['user-agent': [password: 'Arachni/v0']]))
    dataListener.onDataAvailable(Stub(ChangeableFlow), ctx, bundle, gwCtx)
    ctx.closeAdditive()

    then:
    ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    ctx.reportEvents(_ as Collection<AppSecEvent>) >> { event = it[0].iterator().next() }

    event.ruleMatches[0].parameters[0].address == 'server.request.headers.no_cookies'
    event.ruleMatches[0].parameters[0].highlight == ['Arachni/v']
    event.ruleMatches[0].parameters[0].key_path == ['user-agent', 'password']
    event.ruleMatches[0].parameters[0].value == 'Arachni/v0'
  }

  void 'redaction of values'() {
    injectSysConfig(APPSEC_OBFUSCATION_PARAMETER_VALUE_REGEXP, 'Arachni')

    setupWithStubConfigService()
    AppSecEvent event

    when:
    dataListener.onDataAvailable(Stub(ChangeableFlow), ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeAdditive()

    then:
    ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    ctx.reportEvents(_ as Collection<AppSecEvent>) >> { event = it[0].iterator().next() }

    event.ruleMatches[0].parameters[0].address == 'server.request.headers.no_cookies'
    event.ruleMatches[0].parameters[0].highlight == ['<Redacted>']
    event.ruleMatches[0].parameters[0].key_path == ['user-agent']
    event.ruleMatches[0].parameters[0].value == '<Redacted>'
  }

  void 'triggers no rule'() {
    setupWithStubConfigService()
    ChangeableFlow flow = new ChangeableFlow()
    DataBundle db = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
      new CaseInsensitiveMap<List<String>>(['user-agent': 'Harmless']))

    when:
    dataListener.onDataAvailable(flow, ctx, db, gwCtx)

    then:
    ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    flow.blocking == false
  }

  void 'non-string types work'() {
    setupWithStubConfigService()
    ChangeableFlow flow = new ChangeableFlow()
    DataBundle db = MapDataBundle.of(KnownAddresses.REQUEST_BODY_OBJECT,
      [
        [key: [
            true,
            (byte)1,
            (short)2,
            (int)3,
            (long)4,
            (float)5.0,
            (double)6.0,
            (char)'7',
            (BigDecimal)8.0G,
            (BigInteger)9.0G
          ]]
      ])

    when:
    dataListener.onDataAvailable(flow, ctx, db, gwCtx)

    then:
    ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    flow.blocking == false
  }

  void 'powerwaf exceptions do not propagate'() {
    setupWithStubConfigService()
    ChangeableFlow flow = new ChangeableFlow()
    DataBundle db = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES, [get: { null }] as List)

    when:
    dataListener.onDataAvailable(flow, ctx, db, gwCtx)

    then:
    ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive()
    }
    assert !flow.blocking
  }

  void 'timeout is honored (waf)'() {
    setup:
    injectSysConfig('appsec.waf.timeout', '1')
    PowerWAFModule.createLimitsObject()
    setupWithStubConfigService()
    DataBundle db = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
      new CaseInsensitiveMap<List<String>>(['user-agent': 'Arachni/v' + ('a' * 4000)]))
    ChangeableFlow flow = new ChangeableFlow()

    TraceSegment segment = Mock()
    TraceSegmentPostProcessor pp = service.traceSegmentPostProcessors.last()

    when:
    dataListener.onDataAvailable(flow, ctx, db, gwCtx)

    then:
    ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive() }
    assert !flow.blocking
    1 * ctx.isAdditiveClosed()
    1 * ctx.getOrCreateAdditive(_, true, false) >> {
      pwafAdditive = it[0].openAdditive() }
    1 * ctx.getWafMetrics()
    1 * ctx.increaseWafTimeouts()
    1 * wafMetricCollector.get().wafRequestTimeout()
    0 * _

    when:
    pp.processTraceSegment(segment, ctx, [])

    then:
    1 * segment.setTagTop('_dd.appsec.waf.timeouts', 1L)
    _ * segment.setTagTop(_, _)

    cleanup:
    injectSysConfig('appsec.waf.timeout', ConfigDefaults.DEFAULT_APPSEC_WAF_TIMEOUT as String)
    PowerWAFModule.createLimitsObject()
  }

  void 'timeout is honored (rasp)'() {
    setup:
    injectSysConfig('appsec.waf.timeout', '1')
    PowerWAFModule.createLimitsObject()
    setupWithStubConfigService()
    DataBundle db = MapDataBundle.of(KnownAddresses.HEADERS_NO_COOKIES,
      new CaseInsensitiveMap<List<String>>(['user-agent': 'Arachni/v' + ('a' * 4000)]))
    ChangeableFlow flow = new ChangeableFlow()

    TraceSegment segment = Mock()
    TraceSegmentPostProcessor pp = service.traceSegmentPostProcessors.last()

    gwCtx = new GatewayContext(false, RuleType.SQL_INJECTION)

    when:
    dataListener.onDataAvailable(flow, ctx, db, gwCtx)

    then:
    ctx.getOrCreateAdditive(_, true) >> {
      pwafAdditive = it[0].openAdditive() }
    assert !flow.blocking
    1 * ctx.isAdditiveClosed()
    1 * ctx.getOrCreateAdditive(_, true, true) >> {
      pwafAdditive = it[0].openAdditive() }
    1 * ctx.getRaspMetrics()
    1 * ctx.getRaspMetricsCounter()
    1 * ctx.increaseRaspTimeouts()
    1 * wafMetricCollector.get().raspTimeout(gwCtx.raspRuleType)
    1 * wafMetricCollector.raspRuleEval(RuleType.SQL_INJECTION)
    0 * _

    when:
    pp.processTraceSegment(segment, ctx, [])

    then:
    1 * segment.setTagTop('_dd.appsec.rasp.timeout', 1L)
    _ * segment.setTagTop(_, _)

    cleanup:
    injectSysConfig('appsec.waf.timeout', ConfigDefaults.DEFAULT_APPSEC_WAF_TIMEOUT as String)
    PowerWAFModule.createLimitsObject()
    gwCtx = new GatewayContext(false)
  }

  void 'configuration can be given later'() {
    def cfgService = new StubAppSecConfigService([waf: null])
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()

    when:
    cfgService.init()
    pwafModule.config(cfgService)

    then:
    thrown AppSecModule.AppSecModuleActivationException
    0 * _

    when:
    cfgService.listeners['waf'].onNewSubconfig(defaultConfig['waf'], reconf)
    dataListener = pwafModule.dataSubscriptions.first()
    dataListener.onDataAvailable(Stub(ChangeableFlow), ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeAdditive()

    then:
    1 * ctx.getOrCreateAdditive(_, true, false) >> {
      pwafAdditive = it[0].openAdditive() }
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.isAdditiveClosed()
    1 * ctx.getWafMetrics()
    1 * ctx.isThrottled(null)
    1 * ctx.closeAdditive()
    2 * tracer.activeSpan()
    1 * wafMetricCollector.wafInit(Powerwaf.LIB_VERSION, _, true)
    1 * reconf.reloadSubscriptions()
    0 * _
  }

  void 'rule data given through configuration'() {
    setupWithStubConfigService()
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()
    ChangeableFlow flow = Mock()
    def ipData = new AppSecData(rules: [
      [
        id  : 'ip_data',
        type: 'ip_with_expiration',
        data: [[
            value     : '1.2.3.4',
            expiration: '0',
          ]]
      ]
    ])

    when:
    service.currentAppSecConfig.with {
      mergedAsmData.addConfig('my_config', ipData)
      it.dirtyStatus.data = true
      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }

    dataListener = pwafModule.dataSubscriptions.first()
    def bundle = MapDataBundle.of(KnownAddresses.REQUEST_INFERRED_CLIENT_IP, '1.2.3.4')
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeAdditive()

    then:
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    1 * ctx.getOrCreateAdditive(_, true, false) >> { pwafAdditive = it[0].openAdditive() }
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.getWafMetrics()
    1 * flow.setAction({ it.blocking })
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive()
    1 * flow.isBlocking()
    1 * ctx.isThrottled(null)
    0 * _
  }

  private static List toggleById(String id, boolean enabled) {
    [
      [
        rules_target: [[
            rule_id: id
          ]],
        enabled: enabled
      ]
    ]
  }

  void 'reloading rules clears waf data and rule toggling'() {
    setupWithStubConfigService()
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()
    ChangeableFlow flow = Mock()
    def ipData = new AppSecData(rules: [
      [
        id  : 'ip_data',
        type: 'ip_with_expiration',
        data: [[
            value     : '1.2.3.4',
            expiration: '0',
          ]]
      ]
    ])

    when: 'reconfigure with data and toggling'
    service.currentAppSecConfig.with {
      mergedAsmData.addConfig('my_config', ipData)
      it.dirtyStatus.data = true
      def dirtyStatus = userConfigs.addConfig(
        new AppSecUserConfig('my_config', toggleById('ip_match_rule', false), [], [], []))
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }

    dataListener = pwafModule.dataSubscriptions.first()
    def bundle = MapDataBundle.of(KnownAddresses.REQUEST_INFERRED_CLIENT_IP, '1.2.3.4')
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeAdditive()

    then: 'no match; rule is disabled'
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    1 * ctx.getOrCreateAdditive(_, true, false) >> {
      pwafAdditive = it[0].openAdditive() }
    1 * ctx.getWafMetrics()
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive() >> { pwafAdditive.close() }
    _ * ctx.increaseWafTimeouts()
    _ * ctx.increaseRaspTimeouts()
    0 * _

    when: 'removing data and override config'
    service.currentAppSecConfig.with {
      mergedAsmData.removeConfig('my_config')
      it.dirtyStatus.data = true
      def dirtyStatus = userConfigs.removeConfig('my_config')
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }

    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeAdditive()

    then: 'no match; data was cleared (though rule is no longer disabled)'
    1 * ctx.getOrCreateAdditive(_, true, false) >> {
      pwafAdditive = it[0].openAdditive() }
    1 * ctx.getWafMetrics()
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive() >> {pwafAdditive.close()}
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    _ * ctx.increaseWafTimeouts()
    _ * ctx.increaseRaspTimeouts()
    0 * _

    when: 'data is readded'
    service.currentAppSecConfig.with {
      mergedAsmData.addConfig('my_config', ipData)
      it.dirtyStatus.data = true
      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }

    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeAdditive()

    then: 'now we have match'
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    1 * ctx.getOrCreateAdditive(_, true, false) >> {
      pwafAdditive = it[0].openAdditive() }
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.getWafMetrics()
    1 * flow.setAction({ it.blocking })
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive() >> {pwafAdditive.close()}
    1 * flow.isBlocking()
    1 * ctx.isThrottled(null)
    _ * ctx.increaseWafTimeouts()
    _ * ctx.increaseRaspTimeouts()
    0 * _

    when: 'toggling the rule off'
    service.currentAppSecConfig.with {
      def dirtyStatus = userConfigs.addConfig(
        new AppSecUserConfig('my_config', toggleById('ip_match_rule', false), [], [], []))
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }

    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeAdditive()

    then: 'nothing again; we disabled the rule'
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    1 * ctx.getOrCreateAdditive(_, true, false) >> { pwafAdditive = it[0].openAdditive() }
    1 * ctx.getWafMetrics()
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive()
    _ * ctx.increaseWafTimeouts()
    _ * ctx.increaseRaspTimeouts()
    0 * _
  }

  void 'rule toggling data given through configuration'() {
    setupWithStubConfigService()
    AppSecModuleConfigurer.Reconfiguration reconf = Mock()
    ChangeableFlow flow = Mock()

    when: 'rule disabled in config b'
    service.currentAppSecConfig.with {
      def dirtyStatus = userConfigs.addConfig(
        new AppSecUserConfig('b', toggleById('ua0-600-12x', false), [], [], []))

      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }
    dataListener = pwafModule.dataSubscriptions.first()
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeAdditive()

    then:
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    // no attack
    1 * ctx.getOrCreateAdditive(_, true, false) >> { pwafAdditive = it[0].openAdditive() }
    1 * ctx.getWafMetrics()
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive() >> {pwafAdditive.close()}
    _ * ctx.increaseWafTimeouts()
    _ * ctx.increaseRaspTimeouts()
    0 * _

    when: 'rule enabled in config a has no effect'
    // later configurations have precedence (b > a)
    service.currentAppSecConfig.with {
      def dirtyStatus = userConfigs.addConfig(
        new AppSecUserConfig('a', toggleById('ua0-600-12x', true), [], [], []))

      it.dirtyStatus.mergeFrom(dirtyStatus)
      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeAdditive()

    then:
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    // no attack
    1 * ctx.getOrCreateAdditive(_, true, false) >> {
      pwafAdditive = it[0].openAdditive() }
    1 * ctx.getWafMetrics()
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive() >> {pwafAdditive.close()}
    _ * ctx.increaseWafTimeouts()
    _ * ctx.increaseRaspTimeouts()
    0 * _

    when: 'rule enabled in config c overrides b'
    // later configurations have precedence (c > a)
    service.currentAppSecConfig.with {
      def dirtyStatus = userConfigs.addConfig(
        new AppSecUserConfig('c', toggleById('ua0-600-12x', true), [], [], []))
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeAdditive()

    then:
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    // attack found
    1 * ctx.getOrCreateAdditive(_, true, false) >> {
      pwafAdditive = it[0].openAdditive() }
    1 * ctx.getWafMetrics()
    1 * flow.isBlocking()
    1 * flow.setAction({ it.blocking })
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive() >> {pwafAdditive.close()}
    _ * ctx.increaseWafTimeouts()
    _ * ctx.increaseRaspTimeouts()
    1 * ctx.isThrottled(null)
    0 * _

    when: 'removing c restores the state before c was added (rule disabled)'
    service.currentAppSecConfig.with {
      def dirtyStatus = userConfigs.removeConfig('c')
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeAdditive()

    then:
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    // no attack
    1 * ctx.getOrCreateAdditive(_, true, false) >> {
      pwafAdditive = it[0].openAdditive() }
    1 * ctx.getWafMetrics()
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive()
    _ * ctx.increaseWafTimeouts()
    _ * ctx.increaseRaspTimeouts()
    0 * _
  }

  void 'initial configuration has unknown addresses'() {
    Address<String> doesNotExistAddress = new Address<>("server.request.headers.does-not-exist")
    def cfgService = new StubAppSecConfigService(waf:
    new CurrentAppSecConfig(
    ddConfig: AppSecConfig.valueOf([
      version: '2.1',
      rules: [
        [
          id: 'ua0-600-12x',
          name: 'Arachni',
          tags: [
            type: 'security_scanner',
            category: 'attack_attempt'
          ],
          conditions: [
            [
              parameters: [
                inputs: [
                  [
                    address: doesNotExistAddress.key,
                    key_path: ['user-agent']]
                ],
                regex: '^Arachni\\/v'
              ],
              operator: 'match_regex'
            ]
          ],
        ]
      ]
    ])))

    when:
    cfgService.init()
    pwafModule.config(cfgService)

    then:
    1 * wafMetricCollector.wafInit(Powerwaf.LIB_VERSION, _, true)
    !pwafModule.dataSubscriptions.first().subscribedAddresses.contains(doesNotExistAddress)
    0 * _
  }

  void 'bad initial configuration is given results in no subscriptions'() {
    def cfgService = new StubAppSecConfigService([waf: [:]])

    when:
    cfgService.init()
    pwafModule.config(cfgService)

    then:
    thrown AppSecModule.AppSecModuleActivationException
    pwafModule.dataSubscriptions.empty
    0 * _
  }

  void 'rule data not a config'() {
    def confService = new StubAppSecConfigService(waf: [])

    when:
    confService.init()
    pwafModule.config(confService)

    then:
    thrown AppSecModule.AppSecModuleActivationException
    pwafModule.ctxAndAddresses.get() == null
    0 * _
  }

  void 'bad ResultWithData - empty list'() {
    def waf = new PowerWAFModule()
    Powerwaf.ResultWithData rwd = new Powerwaf.ResultWithData(null, "[]", null, null)
    Collection ret

    when:
    ret = waf.buildEvents(rwd)

    then:
    ret.isEmpty()
  }

  void 'bad ResultWithData - empty object'() {
    def waf = new PowerWAFModule()
    Powerwaf.ResultWithData rwd = new Powerwaf.ResultWithData(null, "[{}]", null, null)
    Collection ret

    when:
    ret = waf.buildEvents(rwd)

    then:
    ret.isEmpty()
  }

  void 'ephemeral and persistent addresses'() {
    setupWithStubConfigService()
    ChangeableFlow flow = Mock()

    when:
    def transientBundle = MapDataBundle.of(
      KnownAddresses.REQUEST_BODY_OBJECT,
      '/cybercop'
      )
    dataListener.onDataAvailable(flow, ctx, transientBundle, gwCtx)

    then:
    1 * ctx.getOrCreateAdditive(_, true, false) >> {
      pwafAdditive = it[0].openAdditive() }
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>) >> {
      it[0].iterator().next().ruleMatches[0].parameters[0].value == '/cybercop'
    }
    1 * ctx.getWafMetrics()
    1 * flow.isBlocking()
    1 * ctx.isThrottled(null)
    1 * ctx.isAdditiveClosed() >> false
    0 * _

    when:
    dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
    ctx.closeAdditive()

    then:
    1 * ctx.getOrCreateAdditive(_, true, false) >> {
      pwafAdditive }
    1 * flow.setAction({ it.blocking })
    2 * tracer.activeSpan()
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>) >> {
      it[0].iterator().next().ruleMatches[0].parameters[0].value == 'user-to-block-1'
    }
    1 * ctx.getWafMetrics()
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.closeAdditive()
    1 * ctx.isThrottled(null)
    1 * flow.isBlocking()
    0 * _
  }

  /**
   * This test simulates double REQUEST_END with increasing interval
   * The race condition shouldn't happen when closing Additive
   */
  @Unroll("test repeated #n time")
  void 'parallel REQUEST_END should not cause race condition'() {
    setupWithStubConfigService()
    ChangeableFlow flow = new ChangeableFlow()
    AppSecRequestContext ctx = new AppSecRequestContext()

    when:
    for (int t = 0; t < 20; t++) {
      CountDownLatch latch = new CountDownLatch(1)
      dataListener.onDataAvailable(flow, ctx, ATTACK_BUNDLE, gwCtx)
      Thread thread = new Thread({ p ->
        latch.countDown()
        ctx.closeAdditive()
      })
      thread.start()
      latch.await()
      sleep(t)
      ctx.close()
      thread.join()
    }

    then:
    // java.lang.IllegalStateException: This Additive is no longer online
    // Should not be thrown
    noExceptionThrown()

    where:
    n << (1..3)
  }

  void 'honors appsec.trace.rate.limit'() {
    setup:
    injectSysConfig('dd.appsec.trace.rate.limit', '5')
    def monitoring = Mock(Monitoring)

    when:
    def waf = new PowerWAFModule(monitoring)

    then:
    waf.rateLimiter.limitPerSec == 5

  }

  void 'suspicious attacker blocking'() {
    given:
    final flow = Mock(ChangeableFlow)
    final reconf = Mock(AppSecModuleConfigurer.Reconfiguration)
    final suspiciousIp = '34.65.27.85'
    setupWithStubConfigService('rules_suspicious_attacker_blocking.json')
    dataListener = pwafModule.dataSubscriptions.first()
    ctx.closeAdditive()
    final bundle = MapDataBundle.of(
      KnownAddresses.REQUEST_INFERRED_CLIENT_IP,
      suspiciousIp,
      KnownAddresses.HEADERS_NO_COOKIES,
      new CaseInsensitiveMap<List<String>>(['user-agent': ['Arachni/v1.5.1']])
      )

    when:
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeAdditive()

    then:
    1 * ctx.isAdditiveClosed()
    1 * ctx.getOrCreateAdditive(_ as PowerwafContext, true, false) >> {
      pwafAdditive = it[0].openAdditive()
    }
    1 * ctx.getWafMetrics()
    1 * ctx.isThrottled(null)
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.closeAdditive()
    2 * tracer.activeSpan()
    1 * flow.isBlocking()
    0 * flow.setAction(_)
    0 * _

    when:
    final ipData = new AppSecData(exclusion: [
      [
        id  : 'suspicious_ips_data_id',
        type: 'ip_with_expiration',
        data: [[value: suspiciousIp]]
      ]
    ])
    service.currentAppSecConfig.with {
      mergedAsmData.addConfig('suspicious_ips', ipData)
      it.dirtyStatus.data = true
      it.dirtyStatus.mergeFrom(dirtyStatus)

      service.listeners['waf'].onNewSubconfig(it, reconf)
      it.dirtyStatus.clearDirty()
    }
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeAdditive()

    then:
    1 * wafMetricCollector.wafUpdates(_, true)
    1 * reconf.reloadSubscriptions()
    1 * flow.setAction({ Flow.Action.RequestBlockingAction rba ->
      rba.statusCode == 402 && rba.blockingContentType == BlockingContentType.AUTO
    })
    1 * flow.isBlocking()
    1 * ctx.isAdditiveClosed() >> false
    1 * ctx.getOrCreateAdditive(_ as PowerwafContext, true, false) >> {
      pwafAdditive = it[0].openAdditive()
    }
    1 * ctx.getWafMetrics()
    1 * ctx.isThrottled(null)
    1 * ctx.reportEvents(_ as Collection<AppSecEvent>)
    1 * ctx.closeAdditive()
    2 * tracer.activeSpan()
    0 * _
  }

  void 'http endpoint fingerprint support'() {
    given:
    final flow = Mock(ChangeableFlow)
    final fingerprint = '_dd.appsec.fp.http.endpoint'
    setupWithStubConfigService 'fingerprint_config.json'
    dataListener = pwafModule.dataSubscriptions.first()
    ctx.closeAdditive()
    final bundle = MapDataBundle.ofDelegate([
      (KnownAddresses.WAF_CONTEXT_PROCESSOR): [fingerprint: true],
      (KnownAddresses.REQUEST_METHOD): 'GET',
      (KnownAddresses.REQUEST_URI_RAW): 'http://localhost:8080/test',
      (KnownAddresses.REQUEST_BODY_OBJECT): [:],
      (KnownAddresses.REQUEST_QUERY): [name: ['test']],
      (KnownAddresses.HEADERS_NO_COOKIES): new CaseInsensitiveMap<List<String>>(['user-agent': ['Arachni/v1.5.1']])
    ])

    when:
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeAdditive()

    then:
    1 * flow.setAction({ it.blocking })
    1 * ctx.reportDerivatives({ Map<String, String> map ->
      map.containsKey(fingerprint) && map.get(fingerprint).matches('http-get-.*')
    })
  }

  void 'http session fingerprint support'() {
    given:
    final flow = Mock(ChangeableFlow)
    final fingerprint = '_dd.appsec.fp.session'
    final sessionId = UUID.randomUUID().toString()
    setupWithStubConfigService 'fingerprint_config.json'
    dataListener = pwafModule.dataSubscriptions.first()
    ctx.closeAdditive()
    final bundle = MapDataBundle.ofDelegate([
      (KnownAddresses.WAF_CONTEXT_PROCESSOR): [fingerprint: true],
      (KnownAddresses.REQUEST_COOKIES): [JSESSIONID: [sessionId]],
      (KnownAddresses.SESSION_ID): sessionId,
      (KnownAddresses.USER_ID): 'admin',
    ])

    when:
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)
    ctx.closeAdditive()

    then:
    1 * ctx.reportDerivatives({ Map<String, String> map ->
      map.containsKey(fingerprint) && map.get(fingerprint).matches('ssn-.*')
    })
  }

  void 'retrieve used addresses'() {
    when:
    setupWithStubConfigService('small_config.json')
    def ctx0 = pwafModule.ctxAndAddresses.get().ctx
    def addresses = pwafModule.getUsedAddresses(ctx0)

    then:
    addresses.size() == 6
    addresses.contains(KnownAddresses.REQUEST_INFERRED_CLIENT_IP)
    addresses.contains(KnownAddresses.REQUEST_QUERY)
    addresses.contains(KnownAddresses.REQUEST_PATH_PARAMS)
    addresses.contains(KnownAddresses.HEADERS_NO_COOKIES)
    addresses.contains(KnownAddresses.REQUEST_URI_RAW)
    addresses.contains(KnownAddresses.REQUEST_BODY_OBJECT)
  }

  void 'waf not used if the context is closed'() {
    ChangeableFlow flow = Mock()

    when:
    setupWithStubConfigService('rules_with_data_config.json')
    dataListener = pwafModule.dataSubscriptions.first()

    def bundle = MapDataBundle.of(
      KnownAddresses.USER_ID,
      'legit-user'
      )
    ctx.closeAdditive()
    dataListener.onDataAvailable(flow, ctx, bundle, gwCtx)

    then:
    1 * ctx.closeAdditive()
    1 * ctx.isAdditiveClosed() >> true
    1 * wafMetricCollector.wafInit(Powerwaf.LIB_VERSION, _, true)
    0 * _
  }

  private Map<String, Object> getDefaultConfig() {
    def service = new StubAppSecConfigService()
    service.init()
    service.lastConfig
  }
}

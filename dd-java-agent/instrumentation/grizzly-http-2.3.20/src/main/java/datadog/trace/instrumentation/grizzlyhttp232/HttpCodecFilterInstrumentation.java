package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import java.util.Collections;

@AutoService(InstrumenterModule.class)
public final class HttpCodecFilterInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public HttpCodecFilterInstrumentation() {
    super("grizzly-filterchain");
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.grizzly.http.HttpCodecFilter";
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isIntegrationEnabled(Collections.singleton("mule"), false);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".GrizzlyDecorator",
      packageName + ".GrizzlyDecorator$GrizzlyHttpBlockResponseFunction",
      packageName + ".GrizzlyHttpBlockingHelper",
      packageName + ".GrizzlyHttpBlockingHelper$CloseCompletionHandler",
      packageName + ".GrizzlyHttpBlockingHelper$JustCompleteProcessor",
      packageName + ".HTTPRequestPacketURIDataAdapter",
      packageName + ".ExtractAdapter"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("handleRead")
            .and(takesArgument(0, named("org.glassfish.grizzly.filterchain.FilterChainContext")))
            .and(takesArgument(1, named("org.glassfish.grizzly.http.HttpHeader")))
            .and(isPublic()),
        packageName + ".HttpCodecFilterAdvice");
  }
}

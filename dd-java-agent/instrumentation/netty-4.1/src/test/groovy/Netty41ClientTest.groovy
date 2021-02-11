import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.netty41.client.HttpClientTracingHandler
import datadog.trace.instrumentation.netty41.client.NettyHttpClientDecorator
import io.netty.channel.AbstractChannel
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import org.asynchttpclient.AsyncCompletionHandler
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.asynchttpclient.Response
import spock.lang.AutoCleanup
import spock.lang.Retry
import spock.lang.Timeout

import java.util.concurrent.ExecutionException

import static datadog.trace.agent.test.utils.PortUtils.UNUSABLE_PORT
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static org.asynchttpclient.Dsl.asyncHttpClient

@Retry
@Timeout(5)
class Netty41ClientTest extends HttpClientTest {

  def clientConfig = DefaultAsyncHttpClientConfig.Builder.newInstance()
    .setConnectTimeout(CONNECT_TIMEOUT_MS as int)
    .setRequestTimeout(READ_TIMEOUT_MS as int)
    .setReadTimeout(READ_TIMEOUT_MS as int)
    .setSslContext(SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build())
    .setMaxRequestRetry(0)

  // Can't be @Shared otherwise field-injected classes get loaded too early.
  @AutoCleanup
  AsyncHttpClient asyncHttpClient = asyncHttpClient(clientConfig)

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers = [:], String body = "", Closure callback = null) {
    def methodName = "prepare" + method.toLowerCase().capitalize()
    def requestBuilder = asyncHttpClient."$methodName"(uri.toString())
    headers.each { requestBuilder.setHeader(it.key, it.value) }
    def response = requestBuilder.execute(new AsyncCompletionHandler() {
      @Override
      Object onCompleted(Response response) throws Exception {
        callback?.call()
        return response
      }
    }).get()
    blockUntilChildSpansFinished(1)
    return response.statusCode
  }

  @Override
  CharSequence component() {
    return NettyHttpClientDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return "netty.client.request"
  }

  @Override
  boolean testRedirects() {
    // with followRedirect=true, the client generates an extra request span
    false
  }

  @Override
  boolean testConnectionFailure() {
    false
  }

  @Override
  boolean testSecure() {
    true
  }

  @Override
  boolean testRemoteConnection() {
    return false
  }

  def "connection error (unopened port)"() {
    given:
    def uri = new URI("http://127.0.0.1:$UNUSABLE_PORT/")

    when:
    runUnderTrace("parent") {
      doRequest(method, uri)
    }

    then:
    def ex = thrown(Exception)
    ex.cause instanceof ConnectException
    def thrownException = ex instanceof ExecutionException ? ex.cause : ex

    and:
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent", null, thrownException)

        span {
          operationName "netty.connect"
          resourceName "netty.connect"
          childOf span(0)
          errored true
          tags {
            "$Tags.COMPONENT" "netty"
            errorTags AbstractChannel.AnnotatedConnectException, "Connection refused: /127.0.0.1:$UNUSABLE_PORT"
            defaultTags()
          }
        }
      }
    }

    where:
    method = "GET"
  }

  def "when a handler is added to the netty pipeline we add our tracing handler"() {
    setup:
    def channel = new EmbeddedChannel()
    def pipeline = channel.pipeline()

    when:
    pipeline.addLast("name", new HttpClientCodec())

    then:
    // The first one returns the removed tracing handler
    pipeline.remove(HttpClientTracingHandler) != null
  }

  def "when a handler is added to the netty pipeline we add ONLY ONE tracing handler"() {
    setup:
    def channel = new EmbeddedChannel()
    def pipeline = channel.pipeline()

    when:
    pipeline.addLast("name", new HttpClientCodec())
    // The first one returns the removed tracing handler
    pipeline.remove(HttpClientTracingHandler)
    // There is only one
    pipeline.remove(HttpClientTracingHandler) == null

    then:
    thrown NoSuchElementException
  }

  def "handlers of different types can be added"() {
    setup:
    def channel = new EmbeddedChannel()
    def pipeline = channel.pipeline()

    when:
    pipeline.addLast("some_handler", new SimpleHandler())
    pipeline.addLast("a_traced_handler", new HttpClientCodec())

    then:
    // The first one returns the removed tracing handler
    null != pipeline.remove(HttpClientTracingHandler)
    null != pipeline.remove("some_handler")
    null != pipeline.remove("a_traced_handler")
  }

  def "calling pipeline.addLast methods that use overloaded methods does not cause infinite loop"() {
    setup:
    def channel = new EmbeddedChannel()

    when:
    channel.pipeline().addLast(new SimpleHandler(), new OtherSimpleHandler())

    then:
    null != channel.pipeline().remove('Netty41ClientTest$SimpleHandler#0')
    null != channel.pipeline().remove('Netty41ClientTest$OtherSimpleHandler#0')
  }

  def "when a traced handler is added from an initializer we still detect it and add our channel handlers"() {
    // This test method replicates a scenario similar to how reactor 0.8.x register the `HttpClientCodec` handler
    // into the pipeline.

    setup:
    def channel = new EmbeddedChannel()

    when:
    channel.pipeline().addLast(new TracedHandlerFromInitializerHandler())

    then:
    null != channel.pipeline().remove("added_in_initializer")
    null != channel.pipeline().remove(HttpClientTracingHandler)
  }

  class SimpleHandler implements ChannelHandler {
    @Override
    void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    }

    @Override
    void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    }

    @Override
    void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    }
  }

  class OtherSimpleHandler implements ChannelHandler {
    @Override
    void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    }

    @Override
    void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    }

    @Override
    void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    }
  }

  class TracedHandlerFromInitializerHandler extends ChannelInitializer<Channel> implements ChannelHandler {
    @Override
    protected void initChannel(Channel ch) throws Exception {
      // This replicates how reactor 0.8.x add the HttpClientCodec
      ch.pipeline().addLast("added_in_initializer", new HttpClientCodec())
    }
  }
}

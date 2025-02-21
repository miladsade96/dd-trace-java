package datadog.trace.core.tagprocessor

import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink
import datadog.trace.core.DDSpanContext
import datadog.trace.test.util.DDSpecification

class PostProcessorChainTest extends DDSpecification {
  def "chain works"() {
    setup:
    def processor1 = new TagsPostProcessor() {
        @Override
        Map<String, Object> processTags(Map<String, Object> unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
          unsafeTags.put("key1", "processor1")
          unsafeTags.put("key2", "processor1")
          return unsafeTags
        }
      }
    def processor2 = new TagsPostProcessor() {
        @Override
        Map<String, Object> processTags(Map<String, Object> unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
          unsafeTags.put("key1", "processor2")
          return unsafeTags
        }
      }

    def chain = new PostProcessorChain(processor1, processor2)
    def tags = ["key1": "root", "key3": "root"]

    when:
    def out = chain.processTags(tags, null, [])

    then:
    assert out == ["key1": "processor2", "key2": "processor1", "key3": "root"]
  }

  def "processor can hide tags to next one()"() {
    setup:
    def processor1 = new TagsPostProcessor() {
        @Override
        Map<String, Object> processTags(Map<String, Object> unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
          return ["my": "tag"]
        }
      }
    def processor2 = new TagsPostProcessor() {
        @Override
        Map<String, Object> processTags(Map<String, Object> unsafeTags, DDSpanContext spanContext, List<AgentSpanLink> spanLinks) {
          if (unsafeTags.containsKey("test")) {
            unsafeTags.put("found", "true")
          }
          return unsafeTags
        }
      }

    def chain = new PostProcessorChain(processor1, processor2)
    def tags = ["test": "test"]

    when:
    def out = chain.processTags(tags, null, [])

    then:
    assert out == ["my": "tag"]
  }
}

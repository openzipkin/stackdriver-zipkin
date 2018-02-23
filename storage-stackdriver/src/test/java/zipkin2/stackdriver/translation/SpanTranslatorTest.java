/**
 * Copyright 2016-2018 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.stackdriver.translation;

import com.google.devtools.cloudtrace.v1.TraceSpan;
import org.junit.Test;
import zipkin2.Span;

import static org.junit.Assert.assertEquals;

public class SpanTranslatorTest {
  SpanTranslator translator = new SpanTranslator();

  @Test public void translate() {
    Span zipkinSpan =
        Span.newBuilder()
            .id("2")
            .name("/foo")
            .traceId("3")
            .parentId("5")
            .timestamp(3000001L) // 3.000001 seconds after the unix epoch.
            .duration(8000001L) // 8.000001 seconds;
            .build();
    TraceSpan traceSpan = translator.translate(zipkinSpan);

    assertEquals(2, traceSpan.getSpanId());
    assertEquals("/foo", traceSpan.getName());
    assertEquals(5, traceSpan.getParentSpanId());

    assertEquals(3, traceSpan.getStartTime().getSeconds());
    assertEquals(1000, traceSpan.getStartTime().getNanos());

    assertEquals(3 + 8, traceSpan.getEndTime().getSeconds());
    assertEquals(2000, traceSpan.getEndTime().getNanos());
  }

  @Test public void translate_missingName() {
    Span zipkinSpan = Span.newBuilder().traceId("3").id("2").build();

    TraceSpan traceSpan = translator.translate(zipkinSpan);

    assertEquals("", traceSpan.getName());
  }
}

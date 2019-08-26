/*
 * Copyright 2016-2019 The OpenZipkin Authors
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
package zipkin2.reporter.stackdriver;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.devtools.cloudtrace.v1.GetTraceRequest;
import com.google.devtools.cloudtrace.v1.Trace;
import com.google.devtools.cloudtrace.v1.TraceServiceGrpc;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.testing.GrpcServerRule;
import org.awaitility.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import zipkin2.Span;
import zipkin2.reporter.AsyncReporter;

import java.io.IOException;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static zipkin2.TestObjects.FRONTEND;
import static zipkin2.TestObjects.BACKEND;
import static zipkin2.TestObjects.TODAY;

/** Same as ITStackdriverSpanConsumer: tests everything wired together */
public class ITStackdriverSender {
  @Rule public final GrpcServerRule server = new GrpcServerRule().directExecutor();

  String projectId = "zipkin-gcp-ci";
  GoogleCredentials credentials;
  StackdriverSender sender;
  StackdriverSender senderNoPermission;
  AsyncReporter<Span> reporter;
  AsyncReporter<Span> reporterNoPermission;
  TraceServiceGrpc.TraceServiceBlockingStub traceServiceGrpcV1;
  Channel v1Channel;

  @Before
  public void setUp() throws IOException {
    credentials = GoogleCredentials.getApplicationDefault()
            .createScoped(Collections.singletonList("https://www.googleapis.com/auth/trace.append"));

    // Setup the sender to authenticate the Google Stackdriver service
    sender = StackdriverSender.newBuilder()
            .projectId(projectId)
            .callOptions(CallOptions.DEFAULT.withCallCredentials(MoreCallCredentials.from(credentials)))
            .build();

    reporter =
        AsyncReporter.builder(sender)
            .messageTimeout(0, TimeUnit.MILLISECONDS) // don't spawn a thread
            .build(StackdriverEncoder.V2);

    traceServiceGrpcV1 = TraceServiceGrpc.newBlockingStub(sender.channel)
            .withCallCredentials(MoreCallCredentials.from(credentials.createScoped("https://www.googleapis.com/auth/cloud-platform")));

    senderNoPermission = StackdriverSender.newBuilder()
            .projectId(projectId)
            .build();

    reporterNoPermission =
            AsyncReporter.builder(senderNoPermission)
                    .messageTimeout(0, TimeUnit.MILLISECONDS)
                    .build(StackdriverEncoder.V2);
  }

  @Test
  public void healthcheck() {
    assertThat(reporter.check().ok()).isTrue();
  }

  @Test
  public void sendSpans() {
    Random random = new Random();
    Span span = Span.newBuilder()
            .traceId(random.nextLong(), random.nextLong())
            .parentId("1")
            .id("2")
            .name("get")
            .kind(Span.Kind.CLIENT)
            .localEndpoint(FRONTEND)
            .remoteEndpoint(BACKEND)
            .timestamp((TODAY + 50L) * 1000L)
            .duration(200000L)
            .addAnnotation((TODAY + 100L) * 1000L, "foo")
            .putTag("http.path", "/api")
            .putTag("clnt/finagle.version", "6.45.0")
            .build();

    reporter.report(span);
    reporter.flush();

    Trace trace = await()
            .atLeast(Duration.ONE_SECOND)
            .atMost(Duration.TEN_SECONDS)
            .ignoreException(StatusRuntimeException.class)
            .ignoreExceptionsMatching(e ->
                    e instanceof StatusRuntimeException &&
                            ((StatusRuntimeException) e).getStatus().getCode() == Status.Code.NOT_FOUND
            )
            .until(() -> traceServiceGrpcV1.getTrace(GetTraceRequest.newBuilder()
                    .setProjectId(projectId)
                    .setTraceId(span.traceId())
                    .build()), t -> t.getSpansCount() == 1);

    assertThat("0000000000000002").isEqualTo(span.id());
    assertThat("0000000000000001").isEqualTo(span.parentId());
  }

  @Test
  public void healthCheckFail() {
    assertThat(reporterNoPermission.check().ok()).isFalse();
  }
}

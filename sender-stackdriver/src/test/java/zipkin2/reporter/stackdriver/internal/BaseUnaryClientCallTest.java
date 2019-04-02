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
package zipkin2.reporter.stackdriver.internal;

import com.google.devtools.cloudtrace.v2.BatchWriteSpansRequest;
import com.google.devtools.cloudtrace.v2.TraceServiceGrpc;
import com.google.protobuf.Empty;
import io.grpc.Channel;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcServerRule;
import org.junit.Rule;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import zipkin2.Callback;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.grpc.CallOptions.DEFAULT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

public class BaseUnaryClientCallTest {
  @Rule public final GrpcServerRule server = new GrpcServerRule().directExecutor();
  final TestTraceService traceService = spy(new TestTraceService());

    static class BatchWriteSpansCall extends UnaryClientCall<BatchWriteSpansRequest, Empty> {
    final Channel channel;

    BatchWriteSpansCall(Channel channel, BatchWriteSpansRequest request) {
      super(channel, TraceServiceGrpc.getBatchWriteSpansMethod(), DEFAULT, request);
      this.channel = channel;
    }

    BatchWriteSpansCall(Channel channel, BatchWriteSpansRequest request, long serverResponseTimeout) {
      super(channel, TraceServiceGrpc.getBatchWriteSpansMethod(), DEFAULT, request, serverResponseTimeout);
      this.channel = channel;
    }

    @Override
    public BatchWriteSpansCall clone() {
      return new BatchWriteSpansCall(channel, request());
    }
  }

  BatchWriteSpansCall call;

  void verifyPatchRequestSent() {
    ArgumentCaptor<BatchWriteSpansRequest> requestCaptor =
        ArgumentCaptor.forClass(BatchWriteSpansRequest.class);

    verify(traceService).batchWriteSpans(requestCaptor.capture(), any());

    BatchWriteSpansRequest request = requestCaptor.getValue();
    assertThat(request).isEqualTo(BatchWriteSpansRequest.getDefaultInstance());
  }

  static class TestTraceService extends TraceServiceGrpc.TraceServiceImplBase {}

  void awaitCallbackResult() throws Throwable {
    AtomicReference<Throwable> ref = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    call.enqueue(
        new Callback<Empty>() {
          @Override
          public void onSuccess(Empty empty) {
            latch.countDown();
          }

          @Override
          public void onError(Throwable throwable) {
            ref.set(throwable);
            latch.countDown();
          }
        });
    latch.await(10, TimeUnit.MILLISECONDS);
    if (ref.get() != null) throw ref.get();
  }

  void onClientCall(Consumer<StreamObserver<Empty>> onClientCall) {
    doAnswer(
            (Answer<Void>)
                invocationOnMock -> {
                  StreamObserver<Empty> observer =
                      ((StreamObserver) invocationOnMock.getArguments()[1]);
                  onClientCall.accept(observer);
                  return null;
                })
        .when(traceService)
        .batchWriteSpans(any(BatchWriteSpansRequest.class), any(StreamObserver.class));
  }
}
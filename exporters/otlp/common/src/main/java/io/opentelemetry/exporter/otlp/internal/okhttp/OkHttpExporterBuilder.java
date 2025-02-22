/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.otlp.internal.okhttp;

import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.exporter.otlp.internal.ExporterBuilderUtil;
import io.opentelemetry.exporter.otlp.internal.Marshaler;
import io.opentelemetry.exporter.otlp.internal.TlsUtil;
import io.opentelemetry.exporter.otlp.internal.retry.RetryInterceptor;
import io.opentelemetry.exporter.otlp.internal.retry.RetryPolicy;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;
import javax.net.ssl.X509TrustManager;
import okhttp3.Headers;
import okhttp3.OkHttpClient;

/**
 * A builder for {@link OkHttpExporter}.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
@SuppressWarnings("checkstyle:JavadocMethod")
public final class OkHttpExporterBuilder<T extends Marshaler> {
  public static final long DEFAULT_TIMEOUT_SECS = 10;

  private final String type;

  private String endpoint;

  private long timeoutNanos = TimeUnit.SECONDS.toNanos(DEFAULT_TIMEOUT_SECS);
  private boolean compressionEnabled = false;
  @Nullable private Headers.Builder headersBuilder;
  @Nullable private byte[] trustedCertificatesPem;
  @Nullable private RetryPolicy retryPolicy;
  private MeterProvider meterProvider = MeterProvider.noop();

  public OkHttpExporterBuilder(String type, String defaultEndpoint) {
    this.type = type;

    endpoint = defaultEndpoint;
  }

  public OkHttpExporterBuilder<T> setTimeout(long timeout, TimeUnit unit) {
    timeoutNanos = unit.toNanos(timeout);
    return this;
  }

  public OkHttpExporterBuilder<T> setTimeout(Duration timeout) {
    return setTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS);
  }

  public OkHttpExporterBuilder<T> setEndpoint(String endpoint) {
    URI uri = ExporterBuilderUtil.validateEndpoint(endpoint);
    this.endpoint = uri.toString();
    return this;
  }

  public OkHttpExporterBuilder<T> setCompression(String compressionMethod) {
    if (compressionMethod.equals("gzip")) {
      this.compressionEnabled = true;
    }
    return this;
  }

  public OkHttpExporterBuilder<T> addHeader(String key, String value) {
    if (headersBuilder == null) {
      headersBuilder = new Headers.Builder();
    }
    headersBuilder.add(key, value);
    return this;
  }

  public OkHttpExporterBuilder<T> setTrustedCertificates(byte[] trustedCertificatesPem) {
    this.trustedCertificatesPem = trustedCertificatesPem;
    return this;
  }

  public OkHttpExporterBuilder<T> setMeterProvider(MeterProvider meterProvider) {
    this.meterProvider = meterProvider;
    return this;
  }

  public OkHttpExporterBuilder<T> setRetryPolicy(RetryPolicy retryPolicy) {
    this.retryPolicy = retryPolicy;
    return this;
  }

  public OkHttpExporter<T> build() {
    OkHttpClient.Builder clientBuilder =
        new OkHttpClient.Builder()
            .dispatcher(OkHttpUtil.newDispatcher())
            .callTimeout(Duration.ofNanos(timeoutNanos));

    if (trustedCertificatesPem != null) {
      try {
        X509TrustManager trustManager = TlsUtil.trustManager(trustedCertificatesPem);
        clientBuilder.sslSocketFactory(TlsUtil.sslSocketFactory(trustManager), trustManager);
      } catch (SSLException e) {
        throw new IllegalStateException(
            "Could not set trusted certificate for OTLP HTTP connection, are they valid X.509 in PEM format?",
            e);
      }
    }

    Headers headers = headersBuilder == null ? null : headersBuilder.build();

    if (retryPolicy != null) {
      clientBuilder.addInterceptor(new RetryInterceptor(retryPolicy, OkHttpExporter::isRetryable));
    }

    return new OkHttpExporter<>(
        type, clientBuilder.build(), meterProvider, endpoint, headers, compressionEnabled);
  }

  /**
   * Reflectively access a {@link OkHttpExporterBuilder} instance in field called "delegate" of the
   * instance.
   *
   * @throws IllegalArgumentException if the instance does not contain a field called "delegate" of
   *     type {@link OkHttpExporterBuilder}
   */
  public static <T> OkHttpExporterBuilder<?> getDelegateBuilder(Class<T> type, T instance) {
    try {
      Field field = type.getDeclaredField("delegate");
      field.setAccessible(true);
      Object value = field.get(instance);
      if (!(value instanceof OkHttpExporterBuilder)) {
        throw new IllegalArgumentException("delegate field is not type OkHttpExporterBuilder");
      }
      return (OkHttpExporterBuilder<?>) value;
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new IllegalArgumentException("Unable to access delegate reflectively.", e);
    }
  }
}

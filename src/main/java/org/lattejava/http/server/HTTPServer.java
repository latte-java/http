/*
 * Copyright (c) 2022-2025, FusionAuth, All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package org.lattejava.http.server;

import module java.base;
import module org.lattejava.http;

import java.lang.System.Logger.Level;

import org.lattejava.http.io.MultipartConfiguration;
import org.lattejava.http.io.MultipartFileUploadPolicy;
import org.lattejava.http.server.internal.*;

/**
 * The server bro!
 *
 * @author Brian Pontarelli
 */
@SuppressWarnings("unused")
public class HTTPServer implements Closeable, Configurable<HTTPServer> {
  private static final System.Logger logger = System.getLogger(HTTPServer.class.getName());

  private final List<HTTPServerAcceptorThread> servers = new ArrayList<>();

  private HTTPServerConfiguration configuration = new HTTPServerConfiguration();

  private volatile HTTPContext context;

  @Override
  public void close() {
    long start = System.currentTimeMillis();
    long shutdownDuration = configuration.getShutdownDuration().toMillis();
    logger.log(Level.INFO, "HTTP server shutdown requested. Attempting to close each listener. Wait up to [{0}] ms.", shutdownDuration);

    // First, shutdown all the threads
    for (HTTPServerAcceptorThread thread : servers) {
      thread.shutdown();
    }

    // Next, try joining on them
    for (Thread thread : servers) {
      try {
        thread.join(shutdownDuration);
      } catch (InterruptedException e) {
        // Ignore so we join on all the threads
      }

      // Just bail
      if (System.currentTimeMillis() - start > shutdownDuration) {
        break;
      }
    }

    logger.log(Level.INFO, "HTTP server shutdown successfully.");
  }

  @Override
  public HTTPServerConfiguration configuration() {
    return configuration;
  }

  /**
   * @return The actual port the first listener is bound to. Useful when the listener was configured with port 0
   *     (OS-assigned). Returns -1 if the server has not been started.
   */
  public int getActualPort() {
    if (servers.isEmpty()) {
      return -1;
    }
    return servers.get(0).getActualPort();
  }

  /**
   * @return The HTTP Context or null if the server hasn't been started yet.
   */
  public HTTPContext getContext() {
    return context;
  }

  public HTTPServer start() {
    if (context != null) {
      return this;
    }

    validateConfiguration();

    logger.log(Level.INFO, "Starting the HTTP server. Buckle up!");

    context = new HTTPContext(configuration.getBaseDir());

    try {
      for (HTTPListenerConfiguration listener : configuration.getListeners()) {
        HTTPServerAcceptorThread server = new HTTPServerAcceptorThread(configuration, context, listener);
        servers.add(server);
        server.start();
        logger.log(Level.INFO, "HTTP server listening on port [{0,number,#}]", listener.getPort());
      }

      logger.log(Level.INFO, "HTTP server started successfully");
    } catch (Exception e) {
      logger.log(Level.ERROR, "Unable to start the HTTP server because one of the listeners threw an exception.", e);

      // Clean up the threads that did start
      close();

      throw new IllegalStateException("Unable to start the HTTP server because one of the listeners threw an exception.", e);
    }

    return this;
  }

  /**
   * Specify the full configuration object for the server rather than using the {@code with} builder methods.
   *
   * @param configuration The configuration for the server.
   * @return This.
   */
  public HTTPServer withConfiguration(HTTPServerConfiguration configuration) {
    this.configuration = configuration;
    return this;
  }

  private void validateConfiguration() {
    MultipartConfiguration multipart = configuration.getMultipartConfiguration();

    // No file uploads → maxFileSize is irrelevant.
    if (multipart.getFileUploadPolicy() != MultipartFileUploadPolicy.Allow) {
      return;
    }

    long maxFileSize = multipart.getMaxFileSize();
    // getMaxRequestBodySize never returns null because HTTPServerConfiguration.withMaxRequestBodySize always seeds the "*" fallback key.
    long effectiveCap = HTTPTools.getMaxRequestBodySize("multipart/form-data", configuration.getMaxRequestBodySize());

    // -1 means unlimited.
    if (effectiveCap == -1) {
      return;
    }

    if (maxFileSize > effectiveCap) {
      throw new IllegalStateException("The MultipartConfiguration maxFileSize [" + maxFileSize + "] must not exceed the maxRequestBodySize for [multipart/form-data], which resolves to [" + effectiveCap + "]. Either lower maxFileSize or raise maxRequestBodySize for [multipart/form-data] (or its wildcard parent).");
    }
  }
}

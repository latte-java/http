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
package org.lattejava.http.server.internal;

import module java.base;
import module org.lattejava.http;

import java.lang.System.Logger.Level;

/**
 * A thread that manages the accept process for a single server socket. Once a connection is accepted, the socket is
 * passed to a virtual thread for processing.
 *
 * @author Brian Pontarelli
 */
public class HTTPServerAcceptorThread extends Thread {
  private static final System.Logger logger = System.getLogger(HTTPServerAcceptorThread.class.getName());

  private final ConnectionReaperThread reaperThread;

  private final Deque<ClientConnection> clients = new ConcurrentLinkedDeque<>();

  private final HTTPServerConfiguration configuration;

  private final HTTPContext context;

  private final Instrumenter instrumenter;

  private final HTTPListenerConfiguration listener;

  private final ServerSocket socket;

  private volatile boolean running;

  public HTTPServerAcceptorThread(HTTPServerConfiguration configuration, HTTPContext context, HTTPListenerConfiguration listener)
      throws IOException, GeneralSecurityException {
    super("HTTP server [" + listener.getBindAddress().toString() + ":" + listener.getPort() + "]");

    this.configuration = configuration;
    this.context = context;
    this.listener = listener;
    this.instrumenter = configuration.getInstrumenter();
    this.reaperThread = new ConnectionReaperThread();

    if (listener.isTLS()) {
      SSLContext sslContext = SecurityTools.serverContext(listener.getCertificateChain(), listener.getPrivateKey());
      this.socket = sslContext.getServerSocketFactory().createServerSocket();
    } else {
      this.socket = new ServerSocket();
    }

    socket.setSoTimeout(0); // Always block
    socket.bind(new InetSocketAddress(listener.getBindAddress(), listener.getPort()), configuration.getMaxPendingSocketConnections());

    if (instrumenter != null) {
      instrumenter.serverStarted();
    }
  }

  @Override
  public void run() {
    running = true;
    reaperThread.start();

    while (running) {
      try {
        // Note that the socket is using the configured backlog from the configured value for getMaxPendingSocketConnections.
        // - This should be adequate, but in theory we could also just accept these sockets and queue them for another thread to work FIFO
        //   and construct the virtual threads. I don't think it is necessary, but if we think this is too slow to pull connections off of
        //   the server socket and fire up an HTTP worker, then we could consider seeing if we can improve performance here.
        Socket clientSocket = socket.accept();
        clientSocket.setSoTimeout((int) configuration.getInitialReadTimeoutDuration().toMillis());
        if (logger.isLoggable(Level.TRACE)) {
          String listenerAddress = listener.getBindAddress().toString() + ":" + listener.getPort();
          logger.log(Level.TRACE, "[{0}] Accepted inbound connection. [{1}] existing connections.", listenerAddress, clients.size());
        }

        if (instrumenter != null) {
          instrumenter.acceptedConnection();
        }

        Throughput throughput = new Throughput(configuration.getReadThroughputCalculationDelay().toMillis(), configuration.getWriteThroughputCalculationDelay().toMillis());

        // Protocol selection (TLS-ALPN handshake / h2c preface peek) is BLOCKING, so it runs inside ConnectionDispatcher
        // on the per-connection virtual thread — never on this accept thread. The dispatcher is created unstarted and
        // registered with the reaper before it starts, so the connection is tracked from the moment it can run.
        ConnectionDispatcher dispatcher = new ConnectionDispatcher(clientSocket, configuration, context, instrumenter, listener, throughput);
        Thread client = Thread.ofVirtual()
                              .name("HTTP client [" + clientSocket.getRemoteSocketAddress() + "]")
                              .unstarted(dispatcher);
        clients.add(new ClientConnection(client, dispatcher, throughput));
        client.start();
      } catch (SocketTimeoutException ignore) {
        // Completely smother since this is expected with the SO_TIMEOUT setting in the constructor
        logger.log(Level.DEBUG, "Nothing accepted. Cleaning up existing connections.");
      } catch (SocketException e) {
        // This should only happen when the server is shutdown
        if (socket.isClosed()) {
          running = false;
          logger.log(Level.DEBUG, "The server socket was closed. Shutting down the server.");
        } else {
          logger.log(Level.ERROR, "An exception was thrown while accepting incoming connections.", e);
        }
      } catch (IOException ignore) {
        // Completely smother since most IO exceptions are common during the connection phase
        logger.log(Level.DEBUG, "IO exception. Likely a fuzzer or a bad client or a TLS issue, all of which are common and can mostly be ignored.");
      } catch (Throwable t) {
        logger.log(Level.ERROR, "An exception was thrown during server processing. This is a fatal issue and we need to shutdown the server.", t);
        break;
      }
    }

    // Close all the client connections as cleanly as possible.
    // HTTP/2 connections get a GOAWAY(NO_ERROR) so the peer knows the server is shutting down gracefully.
    for (ClientConnection client : clients) {
      client.connection().shutdown();
    }
    for (ClientConnection client : clients) {
      client.thread().interrupt();
    }
  }

  /**
   * @return The actual port the server socket is bound to. Useful when the listener was configured with port 0
   *     (OS-assigned).
   */
  public int getActualPort() {
    return socket.getLocalPort();
  }

  public void shutdown() {
    running = false;
    try {
      reaperThread.interrupt();
      socket.close();
    } catch (IOException ignore) {
      // Ignorable since we are shutting down regardless
    }
  }

  // - In theory we could hold onto some meta-data here that keeps track of how many requests we have processed on this thread and then exit.
  record ClientConnection(Thread thread, HTTPConnection connection, Throughput throughput) {
    public long getAge() {
      return System.currentTimeMillis() - connection().getStartInstant();
    }

    public long getHandledRequests() {
      return connection().getHandledRequests();
    }

    public long getStartInstant() {
      return connection().getStartInstant();
    }
  }

  private class ConnectionReaperThread extends Thread {
    public ConnectionReaperThread() {
      super("Cleaner for HTTP server [" + listener.getBindAddress().toString() + ":" + listener.getPort() + "]");
    }

    public void run() {
      while (running) {

        int currentClientCount = clients.size();
        int removedClientCount = 0;
        logger.log(Level.TRACE, "Wake up. Review [{0}] client worker threads for cleanup.", currentClientCount);

        Iterator<ClientConnection> iterator = clients.iterator();
        while (iterator.hasNext()) {
          ClientConnection client = iterator.next();
          Thread thread = client.thread();
          long threadId = thread.threadId();
          if (!thread.isAlive()) {
            logger.log(Level.TRACE, "[{0}] Remove dead client worker. Born [{1}]. Died at age [{2}] ms. Requests handled [{3}].", threadId, client.getStartInstant(), client.getAge(), client.getHandledRequests());
            iterator.remove();
            removedClientCount++;
            continue;
          }

          long now = System.currentTimeMillis();
          HTTPConnection worker = client.connection();
          EvictionReason reason = worker.check(now);
          if (reason == null) {
            logger.log(Level.TRACE, "[{0}] Worker is healthy", threadId);
            continue;
          }

          logger.log(Level.DEBUG, "[{0}] Evicting connection [{1}]. Reason [{2}]. Requests handled [{3}].",
              threadId, worker.getSocket().getRemoteSocketAddress(), reason, worker.getHandledRequests());
          iterator.remove();
          removedClientCount++;
          worker.evict(reason);

          if (instrumenter != null) {
            instrumenter.connectionClosed();
          }
        }

        // Only bother tracing this if we started with greater than 0
        if (currentClientCount > 0) {
          logger.log(Level.TRACE, "Cleanup removed [{0}] clients", removedClientCount);
        }

        // Take a break
        try {
          //noinspection BusyWait
          sleep(2_000);
        } catch (InterruptedException ignore) {
          // Ignore
        }
      }
    }
  }
}

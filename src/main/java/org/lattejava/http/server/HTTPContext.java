/*
 * Copyright (c) 2021-2022, FusionAuth, All Rights Reserved
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

/**
 * An object that is the context of the server. It can store global attributes and also a base directory from which
 * files can be loaded.
 * <p>
 * This might be useful for MVCs and applications is they need a way to locate files based on a location such as an
 * application directory.
 *
 * @author Brian Pontarelli
 */
public class HTTPContext {
  public Map<String, Object> attributes = Collections.synchronizedMap(new HashMap<>());

  public Path baseDir;

  public HTTPContext(Path baseDir) {
    this.baseDir = baseDir;
  }

  /**
   * Retrieves a global attribute.
   *
   * @param name The name of the attribute.
   * @return The attribute or null if it doesn't exist.
   */
  public Object getAttribute(String name) {
    return attributes.get(name);
  }

  /**
   * Retrieves all the global attributes. This returns the direct Map so changes to the Map will affect all attributes.
   *
   * @return The attribute Map.
   */
  public Map<String, Object> getAttributes() {
    return attributes;
  }

  /**
   * Attempts to retrieve a file or classpath resource at the given path. If the path is invalid, this will return null.
   * If the classpath is borked or the path somehow cannot be converted to a URL, then this throws an exception.
   * <p>
   * This method protects against path traversal attacks by normalizing the resolved path and ensuring it stays within
   * the baseDir. Attempts to escape the baseDir using sequences like {@code ../} will cause this method to return
   * null.
   *
   * @param path The path.
   * @return The URL to the resource or null.
   * @throws IllegalStateException If the classpath is borked or the file system is jacked.
   */
  public URL getResource(String path) throws IllegalStateException {
    // Protect against absolute paths that break out of the context of the web app
    String filePath = path;
    if (path.startsWith("/")) {
      filePath = path.substring(1);
    }

    try {
      Path resolved = baseDir.resolve(filePath).normalize();

      // Security: Verify the resolved path stays within baseDir to prevent path traversal attacks
      if (!resolved.startsWith(baseDir.normalize())) {
        return null;
      }

      if (Files.exists(resolved)) {
        return resolved.toUri().toURL();
      }

      return HTTPContext.class.getResource(path);
    } catch (MalformedURLException e) {
      // This is quite likely impossible but we don't really care since the resource was not obtainable. Therefore, we are
      // just rethrow an exception
      throw new IllegalStateException(e);
    }
  }

  /**
   * Removes a global attribute.
   *
   * @param name The name of the attribute.
   * @return The attribute if it exists.
   */
  public Object removeAttribute(String name) {
    return attributes.remove(name);
  }

  /**
   * Locates the path given the webapps baseDir (passed into the constructor).
   * <p>
   * This method protects against path traversal attacks by normalizing the resolved path and ensuring it stays within
   * the baseDir. Attempts to escape the baseDir using sequences like {@code ../} will return null.
   *
   * @param appPath The app path to a resource (like an FTL file).
   * @return The resolved path, or null if the path attempts to escape the baseDir.
   */
  public Path resolve(String appPath) {
    if (appPath.startsWith("/")) {
      appPath = appPath.substring(1);
    }

    Path resolved = baseDir.resolve(appPath).normalize();

    // Security: Verify the resolved path stays within baseDir to prevent path traversal attacks
    if (!resolved.startsWith(baseDir.normalize())) {
      return null;
    }

    return resolved;
  }

  /**
   * Sets a global attribute.
   *
   * @param name  The name to store the attribute under.
   * @param value The attribute value.
   */
  public void setAttribute(String name, Object value) {
    attributes.put(name, value);
  }
}
